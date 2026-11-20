#include "audio_engine.h"
#include "libusb.h"

#include <atomic>
#include <cassert>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <random>
#include <thread>
#include <vector>

using namespace purecast;
using namespace purecast::test;

namespace {

#define TEST_ASSERT(cond, msg) \
    do { \
        if (!(cond)) { \
            std::fprintf(stderr, "[FAIL] %s:%d: %s\n", __FILE__, __LINE__, (msg)); \
            std::abort(); \
        } \
    } while (0)

template <typename Pred>
bool wait_until(Pred pred, std::chrono::milliseconds timeout = std::chrono::milliseconds(1500)) {
    const auto deadline = std::chrono::steady_clock::now() + timeout;
    while (std::chrono::steady_clock::now() < deadline) {
        if (pred()) return true;
        std::this_thread::sleep_for(std::chrono::microseconds(250));
    }
    return pred();
}

std::vector<uint8_t> make_pattern_pcm(size_t bytes, uint8_t tag) {
    std::vector<uint8_t> buf(bytes);
    for (size_t i = 0; i < bytes; ++i) {
        // 保证全帧非零以区别静音包
        buf[i] = static_cast<uint8_t>(tag ^ ((i % 251) + 1));
        if (buf[i] == 0) buf[i] = 0x5A;
    }
    return buf;
}

// 用例 1: Write-Before-Clear 竞态门闩测试
// 验证在消费端尚未回调清空 E1 时，生产线程写入 E2 首帧会等待追平，绝不吞掉新歌首包任意一字节
void test_write_before_clear_latch() {
    std::printf("Running test_write_before_clear_latch...\n");
    auto& mock = MockLibusbController::instance();
    mock.reset();

    AudioEngine engine;
    TEST_ASSERT(engine.open_device(42, 44100, 16, 2) == 0, "open_device should succeed");
    TEST_ASSERT(wait_until([&] { return engine.get_active_transfers() > 0; }), "URBs should become active");

    const uint64_t e1 = engine.get_current_buffer_epoch();
    TEST_ASSERT(e1 >= 1, "initial epoch must be >= 1");

    // 先写入 E1 残留数据 (~40ms = 7056 bytes)
    auto e1_data = make_pattern_pcm(7056, 0x11);
    TEST_ASSERT(engine.write_pcm(e1_data.data(), e1_data.size(), e1) == static_cast<int>(e1_data.size()),
                "E1 write should succeed");

    // 阻塞消费端回调，模拟消费线程尚未醒来清空 E1 的竞态窗口
    mock.set_isoc_completion_paused(true);

    // 控制线程执行快路径切歌 flush -> 推进至 E2
    const uint64_t e2 = engine.emergency_mute_and_flush(FlushReason::FLUSH_SWITCH);
    TEST_ASSERT(e2 == e1 + 1, "FLUSH_SWITCH must increment epoch");
    TEST_ASSERT(engine.is_flushing_until_reroll(), "FLUSH_SWITCH must set flushing flag");
    TEST_ASSERT(!engine.is_paused(), "FLUSH_SWITCH must NOT set explicit pause flag");
    TEST_ASSERT(engine.get_consumed_buffer_epoch() == e1, "consumed_epoch must still be E1 while paused");

    // 用旧 epoch E1 写入必须被立刻拒绝
    TEST_ASSERT(engine.write_pcm(e1_data.data(), e1_data.size(), e1) < 0, "stale E1 write must be rejected");

    // 生产线程立即带着新 epoch E2 写入首帧新歌 PCM
    auto e2_header = make_pattern_pcm(4096, 0xA5);
    std::atomic<bool> write_started{false};
    std::atomic<bool> write_finished{false};
    std::atomic<int> written_bytes{-999};

    std::thread producer([&]() {
        write_started.store(true, std::memory_order_release);
        int n = engine.write_pcm(e2_header.data(), e2_header.size(), e2);
        written_bytes.store(n, std::memory_order_release);
        write_finished.store(true, std::memory_order_release);
    });

    TEST_ASSERT(wait_until([&] { return write_started.load(std::memory_order_acquire); }), "producer started");
    std::this_thread::sleep_for(std::chrono::milliseconds(5));
    // 门闩生效期间生产者必须被阻塞等待，绝不能先写后清
    TEST_ASSERT(!write_finished.load(std::memory_order_acquire),
                "producer must wait on latch until consumer catches up consumed_epoch_ == E2");

    // 开启输出捕获并放行消费线程，验证先清 E1 再写 E2，首包字节 100% 保留
    mock.clear_captured_out_bytes();
    mock.set_capture_enabled(true);
    mock.set_isoc_completion_paused(false);

    producer.join();
    TEST_ASSERT(write_finished.load(), "producer should unblock after consumer catches up");
    TEST_ASSERT(written_bytes.load() == static_cast<int>(e2_header.size()),
                "producer must write full E2 header frame");
    TEST_ASSERT(engine.get_consumed_buffer_epoch() == e2, "consumed_epoch must equal E2");

    // 补写剩余 >= 100ms 预充数据使 E2 自动解除 flushing 静音
    auto e2_tail = make_pattern_pcm(16384, 0xA5);
    TEST_ASSERT(engine.write_pcm(e2_tail.data(), e2_tail.size(), e2) == static_cast<int>(e2_tail.size()),
                "E2 tail write should succeed");
    TEST_ASSERT(!engine.is_flushing_until_reroll(), "100ms reroll must clear flushing flag in-place");

    // 等待消费线程输出 E2 数据
    TEST_ASSERT(wait_until([&] { return engine.get_available_read() == 0; }), "ring buffer should drain");
    std::this_thread::sleep_for(std::chrono::milliseconds(15));
    mock.set_capture_enabled(false);
    auto captured = mock.take_captured_out_bytes();

    // 在输出流中找到第一个非零字节，核对从该位置开始的序列正是 e2_header 完整首帧 (无 E1 残音、无首包被抹除)
    size_t first_nz = 0;
    while (first_nz < captured.size() && captured[first_nz] == 0) {
        ++first_nz;
    }
    TEST_ASSERT(first_nz < captured.size(), "captured audio must contain non-zero E2 PCM");
    // 跳过可能对齐到第一个采样起始（非零包头部字节必然就是 e2_header 的开头，因为 e2_header 全部非零）
    TEST_ASSERT(first_nz + e2_header.size() <= captured.size(), "captured length must cover full header");
    TEST_ASSERT(std::memcmp(captured.data() + first_nz, e2_header.data(), e2_header.size()) == 0,
                "first bytes of E2 must match e2_header 100% intact with zero lost bytes");

    engine.release_device();
    TEST_ASSERT(mock.is_kernel_driver_attached(1), "kernel driver must reattach on release");
    std::printf("PASS test_write_before_clear_latch\n");
}

// 用例 2: EXPLICIT_PAUSE 不丢缓冲、不改 epoch、快速切全 0 静音且恢复后原 epoch 可续写
void test_explicit_pause_preserves_buffer_and_epoch() {
    std::printf("Running test_explicit_pause_preserves_buffer_and_epoch...\n");
    auto& mock = MockLibusbController::instance();
    mock.reset();

    AudioEngine engine;
    TEST_ASSERT(engine.open_device(42, 44100, 16, 2) == 0, "open_device should succeed");
    TEST_ASSERT(wait_until([&] { return engine.get_active_transfers() > 0; }), "URBs active");

    const uint64_t epoch = engine.get_current_buffer_epoch();

    // 写入 150ms 数据 (~26460 bytes) 完成预充并开始播放
    auto pcm = make_pattern_pcm(26460, 0x33);
    TEST_ASSERT(engine.write_pcm(pcm.data(), pcm.size(), epoch) == static_cast<int>(pcm.size()), "write pcm");

    // 暂停 URB 回调前的状态：等待至少几帧非零音频输出（绕过 50ms warmup）
    TEST_ASSERT(wait_until([&] { return !mock.last_out_urb_was_all_zero(); }, std::chrono::milliseconds(500)),
                "should observe non-zero PCM output after warmup");

    const size_t avail_before_pause = engine.get_available_read();
    TEST_ASSERT(avail_before_pause > 0, "buffer should still hold unplayed audio before pause");

    // 触发 EXPLICIT_PAUSE
    const auto t_pause = std::chrono::steady_clock::now();
    const uint64_t pause_epoch = engine.emergency_mute_and_flush(FlushReason::EXPLICIT_PAUSE);
    TEST_ASSERT(pause_epoch == epoch, "EXPLICIT_PAUSE must NOT increment buffer_epoch_");
    TEST_ASSERT(engine.is_paused(), "is_paused must be true");
    TEST_ASSERT(!engine.is_flushing_until_reroll(), "EXPLICIT_PAUSE must not set flushing flag");

    // 必须在 <= 20ms 内 URB 输出切换为全 0 静音包，且 URB 持续保活运转
    TEST_ASSERT(wait_until([&] { return mock.last_out_urb_was_all_zero(); }, std::chrono::milliseconds(20)),
                "T3 mute SLA <= 20ms must hold");
    const auto t3_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - t_pause).count();
    std::printf("  Measured T3 (EXPLICIT_PAUSE -> all-zero URB): %lld ms\n", static_cast<long long>(t3_ms));

    // 💡 pause 前瞬间的在途 URB 可能在 mute 生效前多消费 1 个微帧，因此从全0静音稳态点采样并在后续 20ms 内验证 0 消耗且缓冲未清零
    const size_t avail_at_mute = engine.get_available_read();
    TEST_ASSERT(avail_at_mute > 0 && avail_at_mute <= avail_before_pause,
                "EXPLICIT_PAUSE must preserve remaining ring buffer bytes");

    const uint64_t urbs_at_pause = mock.get_completed_out_urbs();
    std::this_thread::sleep_for(std::chrono::milliseconds(20));
    TEST_ASSERT(mock.get_completed_out_urbs() > urbs_at_pause, "URBs must stay alive during EXPLICIT_PAUSE");
    TEST_ASSERT(engine.get_available_read() == avail_at_mute,
                "EXPLICIT_PAUSE must not drain ring buffer while URBs stay alive");

    // 恢复播放并验证仍可用原 epoch 无缝续写与继续输出
    TEST_ASSERT(engine.resume_stream() == 0, "resume_stream succeeds");
    TEST_ASSERT(!engine.is_paused(), "is_paused must be false after resume");
    auto more = make_pattern_pcm(8192, 0x44);
    TEST_ASSERT(engine.write_pcm(more.data(), more.size(), epoch) == static_cast<int>(more.size()),
                "same epoch write must succeed after resume");

    TEST_ASSERT(wait_until([&] { return !mock.last_out_urb_was_all_zero(); }, std::chrono::milliseconds(100)),
                "non-zero audio must resume");

    engine.release_device();
    std::printf("PASS test_explicit_pause_preserves_buffer_and_epoch\n");
}

// 用例 3: 多线程压力测试 (Producer + Control + URB Consumer) 校验 SPSC 安全性与 warmup 不被 FLUSH 重置
void test_multithread_stress_and_warmup_isolation() {
    std::printf("Running test_multithread_stress_and_warmup_isolation...\n");
    auto& mock = MockLibusbController::instance();
    mock.reset();

    AudioEngine engine;
    TEST_ASSERT(engine.open_device(42, 44100, 16, 2) == 0, "open_device");
    TEST_ASSERT(wait_until([&] { return engine.get_active_transfers() > 0; }), "active URBs");

    // 等待初始 50ms warmup 消费完毕
    std::this_thread::sleep_for(std::chrono::milliseconds(120));
    // 验证一次 FLUSH_SWITCH 不会把 warmup_packets_remaining_ 重新置回 400
    engine.emergency_mute_and_flush(FlushReason::FLUSH_SWITCH);
    TEST_ASSERT(engine.get_warmup_packets_remaining() == 0,
                "FLUSH_SWITCH must NEVER reset warmup_packets_remaining_");

    std::atomic<bool> stop{false};
    std::atomic<uint64_t> active_epoch{engine.get_current_buffer_epoch()};

    std::thread producer([&]() {
        auto frame = make_pattern_pcm(1408, 0x7E);
        while (!stop.load(std::memory_order_acquire)) {
            uint64_t ep = active_epoch.load(std::memory_order_acquire);
            engine.write_pcm(frame.data(), frame.size(), ep);
            std::this_thread::sleep_for(std::chrono::microseconds(400));
        }
    });

    std::thread controller([&]() {
        std::mt19937 rng(20261111);
        for (int i = 0; i < 40; ++i) {
            int op = static_cast<int>(rng() % 4);
            if (op == 0) {
                uint64_t next = engine.emergency_mute_and_flush(FlushReason::FLUSH_SWITCH);
                active_epoch.store(next, std::memory_order_release);
            } else if (op == 1) {
                engine.emergency_mute_and_flush(FlushReason::EXPLICIT_PAUSE);
            } else if (op == 2) {
                engine.resume_stream();
            } else {
                uint64_t next = engine.emergency_mute_and_flush(FlushReason::PREEMPT);
                active_epoch.store(next, std::memory_order_release);
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
        stop.store(true, std::memory_order_release);
    });

    controller.join();
    producer.join();

    // 验证缓冲区元数据一致、消费未卡死、available_read <= capacity
    TEST_ASSERT(engine.get_available_read() <= engine.get_buffer_capacity(),
                "ring buffer indices must remain consistent");
    engine.release_device();
    TEST_ASSERT(engine.get_active_transfers() == 0, "all URBs reclaimed cleanly");
    std::printf("PASS test_multithread_stress_and_warmup_isolation\n");
}

// 用例 4: 三元组原位切频 (44.1k/16 -> 96k/24 -> 192k/24) 与失败安全回滚恢复测试
void test_triplet_reconfigure_and_safe_rollback() {
    std::printf("Running test_triplet_reconfigure_and_safe_rollback...\n");
    auto& mock = MockLibusbController::instance();
    mock.reset();

    AudioEngine engine;
    TEST_ASSERT(engine.open_device(42, 44100, 16, 2) == 0, "open 44.1k/16");
    TEST_ASSERT(mock.get_alt_setting(1) == 1, "16-bit selects alt 1");
    TEST_ASSERT(mock.get_configured_sample_rate() == 44100, "clock configured to 44100");
    const uint64_t ep1 = engine.get_current_buffer_epoch();

    // 同规格幂等切频应保持不重开且返回有效 epoch
    const int64_t same_ep = engine.reconfigure_audio_format(44100, 16, 2);
    TEST_ASSERT(same_ep == static_cast<int64_t>(ep1), "same format returns active epoch");

    // 44.1k/16 -> 96k/24 原位切频 (alt 1 -> alt 2, clock -> 96000)
    const int64_t ep2 = engine.reconfigure_audio_format(96000, 24, 2);
    TEST_ASSERT(ep2 > static_cast<int64_t>(ep1), "96k/24 reconfigure must succeed and advance epoch");
    TEST_ASSERT(mock.get_alt_setting(1) == 2, "24-bit selects alt 2");
    TEST_ASSERT(mock.get_configured_sample_rate() == 96000, "clock configured to 96000");
    TEST_ASSERT(!mock.is_kernel_driver_attached(1), "kernel driver stays detached during in-place reconfigure");

    auto pcm96 = make_pattern_pcm(60000, 0x22);
    TEST_ASSERT(engine.write_pcm(pcm96.data(), pcm96.size(), static_cast<uint64_t>(ep2)) > 0,
                "96k/24 write succeeds after reconfigure");

    // 96k/24 -> 192k/24 原位切频
    const int64_t ep3 = engine.reconfigure_audio_format(192000, 24, 2);
    TEST_ASSERT(ep3 > ep2, "192k/24 reconfigure succeeds");
    TEST_ASSERT(mock.get_configured_sample_rate() == 192000, "clock configured to 192000");

    // 非法格式必须返回负错误码
    TEST_ASSERT(engine.reconfigure_audio_format(-44100, 16, 2) < 0, "invalid sample rate returns negative error");
    TEST_ASSERT(engine.reconfigure_audio_format(48000, 7, 2) < 0, "invalid bit depth returns negative error");

    // 注入底层硬件切钟失败 (LIBUSB_ERROR_PIPE)，验证安全回滚并释放干净且可重开恢复
    mock.set_fail_next_clock_control(LIBUSB_ERROR_PIPE);
    const int64_t fail_ep = engine.reconfigure_audio_format(384000, 24, 2);
    TEST_ASSERT(fail_ep < 0, "hardware clock reject must return negative error");
    TEST_ASSERT(!engine.is_running(), "rollback must stop running stream cleanly");
    TEST_ASSERT(engine.get_active_transfers() == 0, "rollback must reclaim all URBs");
    TEST_ASSERT(mock.is_kernel_driver_attached(1), "rollback must reattach kernel driver");

    // 引擎回滚后必须可以重新 open_device 恢复正常工作
    TEST_ASSERT(engine.open_device(43, 44100, 16, 2) == 0, "engine must recover via open_device after rollback");
    const uint64_t recovered_ep = engine.get_current_buffer_epoch();
    TEST_ASSERT(static_cast<int64_t>(recovered_ep) > ep3, "recovered epoch must remain strictly monotonic");

    engine.release_device();
    std::printf("PASS test_triplet_reconfigure_and_safe_rollback\n");
}

// 用例 5: active_transfers_ == 0 (无在途 URB 线程) 时 write_pcm 安全清零追平分支测试
void test_write_pcm_sync_when_no_active_transfers() {
    std::printf("Running test_write_pcm_sync_when_no_active_transfers...\n");
    auto& mock = MockLibusbController::instance();
    mock.reset();
    AudioEngine engine;
    TEST_ASSERT(engine.open_device(42, 44100, 16, 2) == 0, "open_device");
    TEST_ASSERT(wait_until([&] { return engine.get_active_transfers() > 0; }), "active_transfers > 0");

    // 注入持续提交失败，使所有在途 URB 完成后退出但 engine 仍保持 is_running_ == true 且 active_transfers_ == 0
    mock.set_fail_all_submits(LIBUSB_ERROR_IO);
    TEST_ASSERT(wait_until([&] { return engine.get_active_transfers() == 0; }, std::chrono::milliseconds(3000)),
                "active_transfers must drain to 0 when all resubmits fail");
    TEST_ASSERT(engine.is_running(), "engine remains logically running while URBs stopped");

    // 触发 flush 推进 epoch，此时没有消费线程在泵出，write_pcm 必须通过安全降级路径完成同步追平并写入
    const uint64_t next_ep = engine.emergency_mute_and_flush(FlushReason::FLUSH_SWITCH);
    auto frame = make_pattern_pcm(1024, 0x55);
    const int written = engine.write_pcm(frame.data(), frame.size(), next_ep);
    TEST_ASSERT(written == static_cast<int>(frame.size()),
                "write_pcm fallback must sync consumed_epoch and accept new epoch PCM when active_transfers == 0");
    TEST_ASSERT(engine.get_consumed_buffer_epoch() == next_ep,
                "fallback must update consumed_epoch to expected_epoch");

    // release_device 后 write_pcm 必须返回负错误码
    mock.set_fail_all_submits(0);
    engine.release_device();
    TEST_ASSERT(engine.write_pcm(frame.data(), frame.size(), engine.get_current_buffer_epoch()) < 0,
                "write_pcm when stopped must return negative error");
    std::printf("PASS test_write_pcm_sync_when_no_active_transfers\n");
}

// 用例 6 (#24): STOP_OR_TEARDOWN 必须递增 epoch、强制保持静音/暂停态并阻止旧帧泄漏到 OUT URB
void test_stop_or_teardown_flushes_and_stays_muted() {
    std::printf("Running test_stop_or_teardown_flushes_and_stays_muted...\n");
    auto& mock = MockLibusbController::instance();
    mock.reset();

    AudioEngine engine;
    TEST_ASSERT(engine.open_device(42, 44100, 16, 2) == 0, "open_device");
    TEST_ASSERT(wait_until([&] { return engine.get_active_transfers() > 0; }), "active transfers started");

    const uint64_t ep1 = engine.get_current_buffer_epoch();
    auto audio = make_pattern_pcm(24000, 0x33); // > 100ms 触发解锁出声
    TEST_ASSERT(engine.write_pcm(audio.data(), audio.size(), ep1) == static_cast<int>(audio.size()), "write E1");
    TEST_ASSERT(wait_until([&] { return !engine.is_muted(); }), "E1 unmutes after preroll");

    const uint64_t stop_ep = engine.emergency_mute_and_flush(FlushReason::STOP_OR_TEARDOWN);
    TEST_ASSERT(stop_ep > ep1, "STOP_OR_TEARDOWN must advance epoch");
    TEST_ASSERT(engine.is_paused(), "STOP_OR_TEARDOWN must mark explicitly paused");
    TEST_ASSERT(engine.is_muted(), "STOP_OR_TEARDOWN must force muted state");
    TEST_ASSERT(engine.write_pcm(audio.data(), audio.size(), ep1) == -2, "old epoch write must be rejected");

    mock.clear_captured_out_bytes();
    mock.set_capture_enabled(true);
    const uint64_t base_urbs = mock.get_completed_out_urbs();
    TEST_ASSERT(wait_until([&] { return mock.get_completed_out_urbs() >= base_urbs + 4; }),
                "urbs keep circulating in keep-alive silence");
    mock.set_capture_enabled(false);
    auto captured = mock.take_captured_out_bytes();
    bool any_non_zero = false;
    for (uint8_t b : captured) {
        if (b != 0) {
            any_non_zero = true;
            break;
        }
    }
    TEST_ASSERT(!any_non_zero, "STOP_OR_TEARDOWN keep-alive URBs must emit pure silence zeros");

    engine.release_device();
    std::printf("PASS test_stop_or_teardown_flushes_and_stays_muted\n");
}

// 用例 7 (#3): write_pcm + 只读查询并发重入 release_device / open_device / reconfigure 零 UAF/零死锁压力测试
void test_concurrent_write_vs_release_and_reconfigure_uaf() {
    std::printf("Running test_concurrent_write_vs_release_and_reconfigure_uaf...\n");
    auto& mock = MockLibusbController::instance();
    mock.reset();

    AudioEngine engine;
    TEST_ASSERT(engine.open_device(42, 44100, 16, 2) == 0, "initial open");

    std::atomic<bool> stop_flag{false};
    std::vector<std::thread> workers;
    for (int t = 0; t < 2; ++t) {
        workers.emplace_back([&, t]() {
            auto chunk = make_pattern_pcm(1764, static_cast<uint8_t>(0x41 + t));
            while (!stop_flag.load(std::memory_order_acquire)) {
                if (t == 0) {
                    // 💡 SPSC 契约保持单生产者写入，第二线程执行并发状态读取以验证零死锁/零 UAF
                    const uint64_t ep = engine.get_current_buffer_epoch();
                    (void)engine.write_pcm(chunk.data(), chunk.size(), ep);
                }
                (void)engine.get_buffer_available();
                (void)engine.get_buffer_capacity();
                (void)engine.get_available_read();
                std::this_thread::sleep_for(std::chrono::microseconds(150));
            }
        });
    }

    for (int round = 0; round < 6; ++round) {
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
        if (round % 2 == 0) {
            TEST_ASSERT(engine.reconfigure_audio_format(96000, 24, 2) > 0, "concurrent reconfigure 96k");
        } else {
            engine.release_device();
            TEST_ASSERT(engine.open_device(42 + round, 44100, 16, 2) == 0, "concurrent reopen 44.1k");
        }
    }

    stop_flag.store(true, std::memory_order_release);
    for (auto& w : workers) {
        w.join();
    }
    engine.release_device();
    TEST_ASSERT(engine.get_active_transfers() == 0, "all URBs reclaimed after UAF stress");
    std::printf("PASS test_concurrent_write_vs_release_and_reconfigure_uaf\n");
}

// 用例 8 (#11): 并发 FLUSH_SWITCH 与 try_unlock_preroll CAS 竞态防穿越测试
void test_preroll_atomic_cas_unlock_under_epoch_switch() {
    std::printf("Running test_preroll_atomic_cas_unlock_under_epoch_switch...\n");
    auto& mock = MockLibusbController::instance();
    mock.reset();

    AudioEngine engine;
    TEST_ASSERT(engine.open_device(42, 44100, 16, 2) == 0, "open_device");
    TEST_ASSERT(wait_until([&] { return engine.get_active_transfers() > 0; }), "urbs active");

    std::atomic<bool> stop_flag{false};
    std::thread writer([&]() {
        auto frame = make_pattern_pcm(4096, 0x7E);
        while (!stop_flag.load(std::memory_order_acquire)) {
            const uint64_t ep = engine.get_current_buffer_epoch();
            (void)engine.write_pcm(frame.data(), frame.size(), ep);
        }
    });

    for (int i = 0; i < 40; ++i) {
        const uint64_t next_ep = engine.emergency_mute_and_flush(FlushReason::FLUSH_SWITCH);
        // 💡 刚完成 FLUSH_SWITCH 的瞬时若消费者尚未推进或未预充，绝不可因旧世代 CAS 穿透而残留 unmuted
        TEST_ASSERT(engine.get_current_buffer_epoch() >= next_ep, "epoch strictly non-decreasing");
        std::this_thread::sleep_for(std::chrono::microseconds(800));
    }

    stop_flag.store(true, std::memory_order_release);
    writer.join();
    engine.release_device();
    std::printf("PASS test_preroll_atomic_cas_unlock_under_epoch_switch\n");
}

// 用例 9 (#7): UAC1 3-byte 时钟、Feature Unit Volume/Mute 以及畸形控制包拒绝校验
void test_uac1_and_feature_unit_control_validation() {
    std::printf("Running test_uac1_and_feature_unit_control_validation...\n");
    auto& mock = MockLibusbController::instance();
    mock.reset();
    mock.set_uac1_mode(true);

    AudioEngine engine;
    TEST_ASSERT(engine.open_device(77, 96000, 24, 2) == 0, "open UAC1 device 96k/24");
    TEST_ASSERT(mock.get_uac1_sample_rate() == 96000, "UAC1 3-byte SAMPLING_FREQ_CONTROL set to 96000");
    TEST_ASSERT(mock.get_alt_setting(1) == 2, "UAC1 24-bit selects alt 2");
    TEST_ASSERT(mock.get_fu_volume_count() >= 3, "Feature Unit volume control dispatched for ch 0..2");
    TEST_ASSERT(mock.get_fu_mute_count() >= 3, "Feature Unit mute control dispatched for ch 0..2");
    TEST_ASSERT(mock.get_invalid_control_count() == 0, "no invalid control transfers sent by AudioEngine");

    // 显式注入错误长度/未知请求控制传输，验证 mock 拒绝而不是静默放行
    unsigned char bad_buf[2] = {0, 0};
    int bad_ret = libusb_control_transfer(nullptr, 0x21, 0x01, 0x0100, 0x0300, bad_buf, 2, 100);
    TEST_ASSERT(bad_ret == LIBUSB_ERROR_PIPE, "malformed clock request length must return LIBUSB_ERROR_PIPE");
    TEST_ASSERT(mock.get_invalid_control_count() == 1, "invalid control count incremented");

    engine.release_device();
    mock.set_uac1_mode(false);
    std::printf("PASS test_uac1_and_feature_unit_control_validation\n");
}

} // namespace

int main() {
    test_write_before_clear_latch();
    test_explicit_pause_preserves_buffer_and_epoch();
    test_multithread_stress_and_warmup_isolation();
    test_triplet_reconfigure_and_safe_rollback();
    test_write_pcm_sync_when_no_active_transfers();
    test_stop_or_teardown_flushes_and_stays_muted();
    test_concurrent_write_vs_release_and_reconfigure_uaf();
    test_preroll_atomic_cas_unlock_under_epoch_switch();
    test_uac1_and_feature_unit_control_validation();
    std::printf("All purecast native audio_engine tests PASSED!\n");
    return 0;
}
