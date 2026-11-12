#include "audio_engine.h"
#include "flac_decoder.h"
#include "libusb.h"
#include "raop/raop_server.h"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

using namespace purecast;
using namespace purecast::raop;
using namespace purecast::test;

namespace {

#define PROTO_ASSERT(cond, msg) \
    do { \
        if (!(cond)) { \
            std::fprintf(stderr, "[FAIL] %s:%d: %s\n", __FILE__, __LINE__, (msg)); \
            std::abort(); \
        } \
    } while (0)

template <typename Pred>
bool wait_until(Pred pred, std::chrono::milliseconds timeout = std::chrono::milliseconds(2000)) {
    const auto deadline = std::chrono::steady_clock::now() + timeout;
    while (std::chrono::steady_clock::now() < deadline) {
        if (pred()) return true;
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
    return pred();
}

int find_free_tcp_port() {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    PROTO_ASSERT(fd >= 0, "socket");
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = 0;
    PROTO_ASSERT(bind(fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) == 0, "bind ephemeral");
    socklen_t len = sizeof(addr);
    PROTO_ASSERT(getsockname(fd, reinterpret_cast<sockaddr*>(&addr), &len) == 0, "getsockname");
    int port = ntohs(addr.sin_port);
    close(fd);
    return port;
}

std::string rtsp_exchange(int sock, const std::string& req) {
    ssize_t sent = send(sock, req.data(), req.size(), 0);
    PROTO_ASSERT(sent == static_cast<ssize_t>(req.size()), "rtsp send");
    std::string resp;
    char buf[4096];
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(1500);
    while (std::chrono::steady_clock::now() < deadline) {
        ssize_t n = recv(sock, buf, sizeof(buf), 0);
        if (n > 0) {
            resp.append(buf, static_cast<size_t>(n));
            if (resp.find("\r\n\r\n") != std::string::npos) {
                return resp;
            }
        } else {
            break;
        }
    }
    return resp;
}

int parse_server_port(const std::string& setup_resp) {
    auto pos = setup_resp.find("server_port=");
    PROTO_ASSERT(pos != std::string::npos, "server_port present in SETUP response");
    return std::atoi(setup_resp.c_str() + pos + 12);
}

// 用例 1 (#1, #2, #6):
// 验证:
// 1. TV/Focus 暂停状态下 AirPlay 收到有效非静音 RTP 音频包绝不会越权调用 resume_stream 冲掉暂停态 (#6)
// 2. resume_after_reopen(newEpoch) 同步刷新 rtp active_epoch (#2)
// 3. preempt_active_client() 立即停止 RaopRtp 并关闭阻塞中的 client_fd 同时保留 RTSP listening server (#1)
void test_raop_pause_guard_reopen_and_preempt() {
    std::printf("Running test_raop_pause_guard_reopen_and_preempt...\n");
    auto& mock = MockLibusbController::instance();
    mock.reset();

    auto& engine = AudioEngine::instance();
    PROTO_ASSERT(engine.open_device(42, 44100, 16, 2) == 0, "open AudioEngine");
    PROTO_ASSERT(wait_until([&] { return engine.get_active_transfers() > 0; }), "engine URBs active");

    const int rtsp_port = find_free_tcp_port();
    RaopServer server;

    std::atomic<int64_t> active_ep{0};
    std::atomic<int> resume_events{0};
    std::atomic<int> first_pkt_events{0};

    server.set_stream_request_callback([&](int64_t, int64_t, const RaopStreamParams&) -> int64_t {
        uint64_t ep = engine.emergency_mute_and_flush(FlushReason::PREEMPT);
        active_ep.store(static_cast<int64_t>(ep), std::memory_order_release);
        return static_cast<int64_t>(ep);
    });
    server.set_stream_resume_callback([&](int64_t, int64_t, int64_t) {
        resume_events.fetch_add(1, std::memory_order_acq_rel);
    });
    server.set_first_data_packet_callback([&](int64_t, int64_t, int64_t, int, int, int, const std::string&) {
        first_pkt_events.fetch_add(1, std::memory_order_acq_rel);
    });

    PROTO_ASSERT(server.start(rtsp_port), "RaopServer start");

    int client_sock = socket(AF_INET, SOCK_STREAM, 0);
    PROTO_ASSERT(client_sock >= 0, "client socket");
    sockaddr_in srv_addr{};
    srv_addr.sin_family = AF_INET;
    srv_addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    srv_addr.sin_port = htons(static_cast<uint16_t>(rtsp_port));
    PROTO_ASSERT(connect(client_sock, reinterpret_cast<sockaddr*>(&srv_addr), sizeof(srv_addr)) == 0,
                 "connect RTSP");

    const std::string sdp =
        "v=0\r\n"
        "m=audio 0 RTP/AVP 10\r\n"
        "a=rtpmap:10 L16/44100/2\r\n";
    std::string announce_req =
        "ANNOUNCE rtsp://127.0.0.1/test RTSP/1.0\r\n"
        "CSeq: 1\r\n"
        "Content-Type: application/sdp\r\n"
        "Content-Length: " + std::to_string(sdp.size()) + "\r\n\r\n" + sdp;
    PROTO_ASSERT(rtsp_exchange(client_sock, announce_req).find("200 OK") != std::string::npos, "ANNOUNCE 200");

    std::string setup_req =
        "SETUP rtsp://127.0.0.1/test RTSP/1.0\r\n"
        "CSeq: 2\r\n"
        "Transport: RTP/AVP/UDP;unicast;mode=record;control_port=6001;timing_port=6002\r\n\r\n";
    std::string setup_resp = rtsp_exchange(client_sock, setup_req);
    PROTO_ASSERT(setup_resp.find("200 OK") != std::string::npos, "SETUP 200");
    const int udp_data_port = parse_server_port(setup_resp);

    std::string record_req =
        "RECORD rtsp://127.0.0.1/test RTSP/1.0\r\n"
        "CSeq: 3\r\n\r\n";
    PROTO_ASSERT(rtsp_exchange(client_sock, record_req).find("200 OK") != std::string::npos, "RECORD 200");
    PROTO_ASSERT(server.rtp().is_running(), "RaopRtp must be running after RECORD");

    // 模拟 TV/音频焦点显式暂停 (#6)
    engine.emergency_mute_and_flush(FlushReason::EXPLICIT_PAUSE);
    PROTO_ASSERT(engine.is_paused(), "AudioEngine is paused");

    // 通过 UDP 发送有效非静音 L16 PCM RTP 包 (PT=10)
    int udp_sock = socket(AF_INET, SOCK_DGRAM, 0);
    PROTO_ASSERT(udp_sock >= 0, "udp_sock");
    sockaddr_in udp_dest{};
    udp_dest.sin_family = AF_INET;
    udp_dest.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    udp_dest.sin_port = htons(static_cast<uint16_t>(udp_data_port));

    std::vector<uint8_t> rtp_pkt(12 + 1408, 0);
    rtp_pkt[0] = 0x80;
    rtp_pkt[1] = 10; // L16 static PT
    for (size_t i = 12; i < rtp_pkt.size(); ++i) {
        rtp_pkt[i] = static_cast<uint8_t>((i % 127) + 1);
    }
    const int resume_before = resume_events.load();
    for (int seq = 1; seq <= 8; ++seq) {
        rtp_pkt[2] = static_cast<uint8_t>((seq >> 8) & 0xFF);
        rtp_pkt[3] = static_cast<uint8_t>(seq & 0xFF);
        sendto(udp_sock, rtp_pkt.data(), rtp_pkt.size(), 0,
               reinterpret_cast<sockaddr*>(&udp_dest), sizeof(udp_dest));
        std::this_thread::sleep_for(std::chrono::milliseconds(3));
    }
    std::this_thread::sleep_for(std::chrono::milliseconds(20));

    // #6 核心断言: 尽管有真实非静音音频包抵达，AudioEngine::is_paused() 必须纹丝不动，且不发恢复回调、不写 RingBuffer
    PROTO_ASSERT(engine.is_paused(), "AudioEngine explicit pause must NOT be overridden by RTP audio");
    PROTO_ASSERT(resume_events.load() == resume_before, "No implicit resume callback allowed while TV paused");
    PROTO_ASSERT(first_pkt_events.load() == 0, "Paused data_loop must not push frames or emit FirstDataPacket");

    // #2 核心断言: resume_after_reopen(new_epoch) 原地同步更新 active_epoch 并恢复写入
    const uint64_t reopened_ep = engine.emergency_mute_and_flush(FlushReason::PREEMPT);
    PROTO_ASSERT(server.resume_after_reopen(reopened_ep), "resume_after_reopen succeeds while RTP live");
    PROTO_ASSERT(server.rtp().get_active_epoch() == reopened_ep, "active_epoch updated to reopened epoch");

    for (int seq = 9; seq <= 40; ++seq) {
        rtp_pkt[2] = static_cast<uint8_t>((seq >> 8) & 0xFF);
        rtp_pkt[3] = static_cast<uint8_t>(seq & 0xFF);
        sendto(udp_sock, rtp_pkt.data(), rtp_pkt.size(), 0,
               reinterpret_cast<sockaddr*>(&udp_dest), sizeof(udp_dest));
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
    PROTO_ASSERT(wait_until([&] { return first_pkt_events.load() >= 1; }),
                 "After resume_after_reopen, valid RTP frames flow into AudioEngine");

    // #1 核心断言: 跨协议抢占立即切断 UDP 接收与活动 RTSP 连接，同时保持 server 监听存活以迎接下次投屏
    server.preempt_active_client();
    PROTO_ASSERT(!server.rtp().is_running(), "RaopRtp must stop immediately after preempt_active_client");
    PROTO_ASSERT(server.is_running(), "RaopServer listener must stay alive after preempt_active_client");

    char eof_buf[16];
    struct timeval tv{0, 200000};
    setsockopt(client_sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    ssize_t peer_n = recv(client_sock, eof_buf, sizeof(eof_buf), 0);
    PROTO_ASSERT(peer_n == 0, "Preempted client RTSP socket must receive immediate EOF");

    close(udp_sock);
    close(client_sock);
    server.stop();
    engine.release_device();
    std::printf("PASS test_raop_pause_guard_reopen_and_preempt\n");
}

// 构造合法最小 1-frame (CONSTANT 静音/固定振幅) 44.1kHz 16-bit 2ch FLAC 流供 dr_flac 解析
uint8_t flac_crc8(const uint8_t* data, size_t len) {
    uint8_t crc = 0;
    for (size_t i = 0; i < len; ++i) {
        crc ^= data[i];
        for (int b = 0; b < 8; ++b) {
            crc = (crc & 0x80) ? static_cast<uint8_t>((crc << 1) ^ 0x07) : static_cast<uint8_t>(crc << 1);
        }
    }
    return crc;
}

uint16_t flac_crc16(const uint8_t* data, size_t len) {
    uint16_t crc = 0;
    for (size_t i = 0; i < len; ++i) {
        crc ^= static_cast<uint16_t>(data[i]) << 8;
        for (int b = 0; b < 8; ++b) {
            crc = (crc & 0x8000) ? static_cast<uint16_t>((crc << 1) ^ 0x8005) : static_cast<uint16_t>(crc << 1);
        }
    }
    return crc;
}

std::vector<uint8_t> make_minimal_valid_flac(int num_frames) {
    std::vector<uint8_t> out;
    // "fLaC"
    out.insert(out.end(), {'f', 'L', 'a', 'C'});
    // Last metadata block: STREAMINFO (0x80), length = 34
    out.push_back(0x80);
    out.push_back(0x00);
    out.push_back(0x00);
    out.push_back(0x22);

    const uint64_t total_samples = static_cast<uint64_t>(num_frames) * 4096ULL;
    uint8_t si[34]{};
    // min_blocksize=4096 (0x1000), max_blocksize=4096 (0x1000)
    si[0] = 0x10; si[1] = 0x00;
    si[2] = 0x10; si[3] = 0x00;
    // min_framesize=0, max_framesize=0 (bytes 4..9 = 0)
    // sample_rate=44100 (20 bits = 0x0AC44), channels-1=1 (3 bits), bps-1=15 (5 bits), total_samples (36 bits)
    // Byte 10..13: sr(19..0)=0x0AC44
    si[10] = 0x0A;
    si[11] = 0xC4;
    si[12] = static_cast<uint8_t>((0x4 << 4) | (1 << 1) | 0); // lower 4 bits of sr=4, ch-1=1 (001), top bit of bps-1 (01111 -> 0)
    si[13] = static_cast<uint8_t>((0x0F << 4) | ((total_samples >> 32) & 0x0F));
    si[14] = static_cast<uint8_t>((total_samples >> 24) & 0xFF);
    si[15] = static_cast<uint8_t>((total_samples >> 16) & 0xFF);
    si[16] = static_cast<uint8_t>((total_samples >> 8) & 0xFF);
    si[17] = static_cast<uint8_t>(total_samples & 0xFF);
    out.insert(out.end(), si, si + 34);

    // 帧构造: fixed-blocksize sync=0xFFF8, bs_code=1100 (4096 -> 0xC), sr=1001 (44.1kHz -> 0x9)
    // ch_assign=0001 (2ch L/R -> 0x1), bps=100 (16-bit -> 0x4), reserved=0 => byte3 = 0x18
    for (uint8_t fn = 0; fn < static_cast<uint8_t>(num_frames); ++fn) {
        std::vector<uint8_t> frame;
        frame.push_back(0xFF);
        frame.push_back(0xF8);
        frame.push_back(0xC9); // bs=1100 (4096), sr=1001 (44.1kHz)
        frame.push_back(0x18); // ch=0001 (stereo independent), bps=100 (16-bit), reserved=0
        frame.push_back(fn);   // UTF-8 coded frame index (<128)
        frame.push_back(flac_crc8(frame.data(), frame.size()));
        // Channel 0 & Channel 1: CONSTANT subframe (type=000000, wasted=0 => 0x00) + 16-bit sample (0x0123)
        for (int ch = 0; ch < 2; ++ch) {
            frame.push_back(0x00);
            frame.push_back(0x01);
            frame.push_back(0x23);
        }
        uint16_t c16 = flac_crc16(frame.data(), frame.size());
        frame.push_back(static_cast<uint8_t>((c16 >> 8) & 0xFF));
        frame.push_back(static_cast<uint8_t>(c16 & 0xFF));
        out.insert(out.end(), frame.begin(), frame.end());
    }
    return out;
}

struct FlacCbContext {
    AudioEngine* engine{nullptr};
    std::atomic<bool> format_called{false};
};

int64_t on_flac_format(int sample_rate, int bit_depth, int channels, int64_t, void* user_data) {
    auto* ctx = static_cast<FlacCbContext*>(user_data);
    ctx->format_called.store(true, std::memory_order_release);
    PROTO_ASSERT(sample_rate == 44100 && bit_depth == 16 && channels == 2, "FLAC stream info parsed");
    return static_cast<int64_t>(ctx->engine->get_current_buffer_epoch());
}

void on_flac_first_pcm(void*) {}

// 用例 2 (#5): 校验 FlacDecoder 在暂停休眠期间遇到 epoch 切歌/STOP 变更时立即中止，绝不无限死睡
void test_flac_decoder_exits_on_epoch_invalidation_while_paused() {
    std::printf("Running test_flac_decoder_exits_on_epoch_invalidation_while_paused...\n");
    auto& mock = MockLibusbController::instance();
    mock.reset();

    auto& engine = AudioEngine::instance();
    PROTO_ASSERT(engine.open_device(42, 44100, 16, 2) == 0, "open AudioEngine");
    PROTO_ASSERT(wait_until([&] { return engine.get_active_transfers() > 0; }), "engine URBs active");

    // 进入显式暂停态
    engine.emergency_mute_and_flush(FlushReason::EXPLICIT_PAUSE);
    PROTO_ASSERT(engine.is_paused(), "engine must be paused");

    JNIEnv env;
    MockJniInputStream stream;
    stream.bytes = make_minimal_valid_flac(3);

    FlacCbContext cb_ctx{&engine};
    std::atomic<bool> finished{false};
    std::atomic<bool> decode_ok{true};

    std::thread decoder_thread([&]() {
        bool ok = FlacDecoder::decode_stream(&env, &stream, on_flac_format, on_flac_first_pcm, &cb_ctx);
        decode_ok.store(ok, std::memory_order_release);
        finished.store(true, std::memory_order_release);
    });

    PROTO_ASSERT(wait_until([&] { return cb_ctx.format_called.load(std::memory_order_acquire); }),
                 "FlacDecoder parses header and enters pause wait");
    std::this_thread::sleep_for(std::chrono::milliseconds(30));
    PROTO_ASSERT(!finished.load(std::memory_order_acquire), "decoder stays paused before epoch change");

    // 触发切歌 FLUSH_SWITCH 推送 epoch -> FlacDecoder 必须立即唤醒并返回 false (aborted_by_epoch)
    engine.emergency_mute_and_flush(FlushReason::FLUSH_SWITCH);
    PROTO_ASSERT(wait_until([&] { return finished.load(std::memory_order_acquire); }, std::chrono::milliseconds(1000)),
                 "FlacDecoder must abort promptly instead of sleeping forever when epoch changes while paused");
    PROTO_ASSERT(!decode_ok.load(std::memory_order_acquire), "aborted_by_epoch returns false cleanly");

    decoder_thread.join();
    engine.release_device();
    std::printf("PASS test_flac_decoder_exits_on_epoch_invalidation_while_paused\n");
}

} // namespace

int main() {
    test_raop_pause_guard_reopen_and_preempt();
    test_flac_decoder_exits_on_epoch_invalidation_while_paused();
    std::printf("All purecast native protocol tests PASSED!\n");
    return 0;
}
