#pragma once

#include <atomic>
#include <cstdint>
#include <cstddef>
#include <vector>
#include <cstring>
#include <algorithm>
#include <sys/mman.h>

namespace purecast {

/**
 * 💡 C++20 单生产者单消费者 (SPSC) 无锁环形缓冲区
 * 专为 USB 等时音频传输设计，满足以下核心严苛要求：
 * 1. 零锁调度：读写索引使用 std::atomic<size_t> 搭配 acquire/release 内存屏障，彻底杜绝优先级反转引起的音频爆音 (Pops/Glitches)。
 * 2. 内存锁定：构造时调用 mlock() 锁定物理内存页，防止操作系统内存回收器将其换出到 Swap 或产生缺页异常。
 * 3. 2的幂次容量：容量自动对齐至 2^N，用与操作 (index & mask) 替代取模操作，消除 CPU 除法指令开销。
 */
class SpscRingBuffer {
public:
    explicit SpscRingBuffer(size_t min_capacity_bytes = 1024 * 512) // 默认 512KB (~3秒 16bit/44.1k/2ch)
        : head_(0), tail_(0), buffer_(nullptr), capacity_(0), mask_(0) {
        
        // 计算大于等于 min_capacity_bytes 的最小 2^N
        capacity_ = 1;
        while (capacity_ < min_capacity_bytes) {
            capacity_ <<= 1;
        }
        mask_ = capacity_ - 1;

        // 分配 64 字节对齐的连续物理内存
        buffer_ = static_cast<uint8_t*>(aligned_alloc(64, capacity_));
        if (buffer_) {
            std::memset(buffer_, 0, capacity_);
            // 💡 锁定物理内存页，杜绝缺页中断 (Page Fault)
            mlock(buffer_, capacity_);
        }
    }

    ~SpscRingBuffer() {
        if (buffer_) {
            munlock(buffer_, capacity_);
            free(buffer_);
            buffer_ = nullptr;
        }
    }

    // 禁止拷贝与赋值
    SpscRingBuffer(const SpscRingBuffer&) = delete;
    SpscRingBuffer& operator=(const SpscRingBuffer&) = delete;

    /**
     * 生产者写入（网络流拉取线程调用）
     * @return 实际写入的字节数
     */
    size_t write(const uint8_t* data, size_t size) {
        if (!buffer_ || size == 0) return 0;

        const size_t head = head_.load(std::memory_order_relaxed);
        const size_t tail = tail_.load(std::memory_order_acquire);

        const size_t occupied = head - tail;
        const size_t available = capacity_ - occupied;
        const size_t to_write = std::min(size, available);

        if (to_write == 0) {
            return 0; // 缓冲区已满
        }

        const size_t head_offset = head & mask_;
        const size_t first_chunk = std::min(to_write, capacity_ - head_offset);
        
        std::memcpy(buffer_ + head_offset, data, first_chunk);
        if (to_write > first_chunk) {
            std::memcpy(buffer_, data + first_chunk, to_write - first_chunk);
        }

        head_.store(head + to_write, std::memory_order_release);
        return to_write;
    }

    /**
     * 消费者读取（USB 等时 URB 调度线程调用）
     * @return 实际读取的字节数
     */
    size_t read(uint8_t* out_data, size_t size) {
        if (!buffer_ || size == 0) return 0;

        const size_t tail = tail_.load(std::memory_order_relaxed);
        const size_t head = head_.load(std::memory_order_acquire);

        const size_t available = head - tail;
        const size_t to_read = std::min(size, available);

        if (to_read == 0) {
            return 0; // 缓冲区为空（下溢）
        }

        const size_t tail_offset = tail & mask_;
        const size_t first_chunk = std::min(to_read, capacity_ - tail_offset);

        std::memcpy(out_data, buffer_ + tail_offset, first_chunk);
        if (to_read > first_chunk) {
            std::memcpy(out_data + first_chunk, buffer_, to_read - first_chunk);
        }

        tail_.store(tail + to_read, std::memory_order_release);
        return to_read;
    }

    /**
     * 清空缓冲区（用于暂停、切歌或 Seek 重置）
     */
    void clear() {
        const size_t head = head_.load(std::memory_order_relaxed);
        tail_.store(head, std::memory_order_release);
    }

    /**
     * 获取当前已填充的数据字节数
     */
    [[nodiscard]] size_t available_read() const {
        const size_t head = head_.load(std::memory_order_acquire);
        const size_t tail = tail_.load(std::memory_order_relaxed);
        return head - tail;
    }

    /**
     * 获取当前剩余可写空间字节数
     */
    [[nodiscard]] size_t available_write() const {
        return capacity_ - available_read();
    }

    [[nodiscard]] size_t capacity() const {
        return capacity_;
    }

private:
    // 💡 读写索引分别位于不同的 Cache Line (64字节)，杜绝伪共享 (False Sharing)
    alignas(64) std::atomic<size_t> head_;
    alignas(64) std::atomic<size_t> tail_;

    uint8_t* buffer_;
    size_t capacity_;
    size_t mask_;
};

} // namespace purecast
