#include "raop_jitter_buffer.h"
#include <android/log.h>
#include <cstring>

#define RAOP_TAG "PureCastRaopJitter"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, RAOP_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, RAOP_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, RAOP_TAG, __VA_ARGS__)

namespace purecast::raop {

RaopJitterBuffer::RaopJitterBuffer() {
    slots_.resize(BUFFER_SIZE);
    reset();
}

void RaopJitterBuffer::reset() {
    std::lock_guard<std::mutex> lock(mutex_);
    for (auto& slot : slots_) {
        slot.valid = false;
        slot.pcm.clear();
    }
    head_seq_ = 0;
    max_seq_seen_ = 0;
    first_packet_ = true;
    is_buffering_.store(true, std::memory_order_release);
    lost_packets_.store(0, std::memory_order_relaxed);
    LOGI("RaopJitterBuffer 重置完成");
}

void RaopJitterBuffer::push(uint16_t seq, uint32_t timestamp, std::vector<uint8_t> pcm) {
    std::lock_guard<std::mutex> lock(mutex_);

    if (first_packet_) {
        head_seq_ = seq;
        max_seq_seen_ = seq;
        first_packet_ = false;
        LOGI("JitterBuffer 首包捕获: seq=%u, ts=%u", seq, timestamp);
    } else {
        // 如果序列号过旧（落后于 head_seq_），丢弃
        if (seq_less(seq, head_seq_)) {
            LOGD("丢弃过期乱序包: seq=%u (当前期望 head=%u)", seq, head_seq_);
            return;
        }

        // 如果序列号超前过多（跳变超过缓冲区大小），执行强制时钟重同步
        if (static_cast<int16_t>(seq - head_seq_) >= static_cast<int16_t>(BUFFER_SIZE)) {
            LOGW("检测到 RTP 序列号跳变异常 (seq=%u, head=%u)，重置 JitterBuffer 游标", seq, head_seq_);
            for (auto& slot : slots_) {
                slot.valid = false;
                slot.pcm.clear();
            }
            head_seq_ = seq;
            max_seq_seen_ = seq;
            is_buffering_.store(true, std::memory_order_release);
        } else if (seq_less(max_seq_seen_, seq)) {
            max_seq_seen_ = seq;
        }
    }

    // 存入对应槽位
    size_t index = seq % BUFFER_SIZE;
    slots_[index].seq_num = seq;
    slots_[index].timestamp = timestamp;
    slots_[index].pcm = std::move(pcm);
    slots_[index].valid = true;

    // 预充判断：计算从 head_seq_ 到 max_seq_seen_ 之间的已缓冲包数
    if (is_buffering_.load(std::memory_order_relaxed)) {
        size_t count = 0;
        int16_t distance = static_cast<int16_t>(max_seq_seen_ - head_seq_) + 1;
        if (distance > 0) {
            for (int16_t i = 0; i < distance; ++i) {
                uint16_t s = static_cast<uint16_t>(head_seq_ + i);
                if (slots_[s % BUFFER_SIZE].valid && slots_[s % BUFFER_SIZE].seq_num == s) {
                    count++;
                }
            }
        }

        if (count >= PRE_ROLL_PACKETS) {
            is_buffering_.store(false, std::memory_order_release);
            LOGI("JitterBuffer 预充完成 (已缓存 %zu 帧音频，约 %zu ms)，开启有序播放",
                 count, (count * 352 * 1000) / 44100);
        }
    }
}

bool RaopJitterBuffer::pop(std::vector<uint8_t>& out_pcm) {
    std::lock_guard<std::mutex> lock(mutex_);

    if (is_buffering_.load(std::memory_order_relaxed) || first_packet_) {
        return false;
    }

    size_t index = head_seq_ % BUFFER_SIZE;
    if (slots_[index].valid && slots_[index].seq_num == head_seq_) {
        // 顺序匹配命中
        out_pcm = std::move(slots_[index].pcm);
        slots_[index].valid = false;
        slots_[index].pcm.clear();
        head_seq_++;
        return true;
    }

    // 当前槽位空缺，判断后续包是否超差判定丢包
    int16_t diff = static_cast<int16_t>(max_seq_seen_ - head_seq_);
    if (diff > MAX_REORDER_TOLERANCE) {
        // 丢包确认，生成全 0 静音补偿帧保持物理晶振时钟对齐
        out_pcm.assign(FRAME_BYTES, 0);
        lost_packets_.fetch_add(1, std::memory_order_relaxed);
        LOGW("RTP 丢包补偿: 丢失帧 seq=%u (最新已收 seq=%u, 超差 %d 帧)，插入 8ms 静音保真帧",
             head_seq_, max_seq_seen_, diff);
        slots_[index].valid = false;
        slots_[index].pcm.clear();
        head_seq_++;
        return true;
    }

    // 仍在乱序容忍等待窗口期内，暂不出队
    return false;
}

} // namespace purecast::raop
