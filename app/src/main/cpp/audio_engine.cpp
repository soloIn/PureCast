#include "audio_engine.h"
#include <sys/resource.h>
#include <sys/mman.h>
#include <unistd.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cerrno>
#include <cstring>

namespace purecast {

namespace {
struct ScopedActiveWriter {
    std::atomic<int>& counter;
    explicit ScopedActiveWriter(std::atomic<int>& c) : counter(c) {
        counter.fetch_add(1, std::memory_order_seq_cst);
    }
    ~ScopedActiveWriter() {
        counter.fetch_sub(1, std::memory_order_seq_cst);
    }
};
} // namespace

AudioEngine& AudioEngine::instance() {
    static AudioEngine inst;
    return inst;
}

AudioEngine::AudioEngine() {
    LOGI("AudioEngine 实例创建");
}

AudioEngine::~AudioEngine() {
    release_device();
    if (ctx_) {
        libusb_exit(ctx_);
        ctx_ = nullptr;
    }
}

std::string AudioEngine::get_libusb_version() const {
    const struct libusb_version* ver = libusb_get_version();
    if (!ver) return "unknown";
    char buf[64];
    snprintf(buf, sizeof(buf), "%u.%u.%u.%u%s",
             ver->major, ver->minor, ver->micro, ver->nano,
             ver->rc ? ver->rc : "");
    return std::string(buf);
}

int AudioEngine::init_context() {
    std::lock_guard<std::recursive_mutex> lock(op_mutex_);
    if (ctx_) {
        return 0; // 已初始化
    }

    // 💡 开启 LIBUSB_OPTION_NO_DEVICE_DISCOVERY，杜绝在普通权限下扫描 /dev/bus/usb 触发权限拒绝
    int r = libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, nullptr);
    if (r != LIBUSB_SUCCESS) {
        LOGE("libusb_set_option(LIBUSB_OPTION_NO_DEVICE_DISCOVERY) 失败: %d", r);
        return r;
    }

    r = libusb_init(&ctx_);
    if (r != LIBUSB_SUCCESS) {
        LOGE("libusb_init 失败: %d", r);
        return r;
    }

    LOGI("libusb 上下文初始化完成 (无 Root usbfs 模式), 版本: %s", get_libusb_version().c_str());
    return 0;
}

uint64_t AudioEngine::advance_epoch_and_set_flushing(bool flushing) {
    uint64_t cur = epoch_and_flush_.load(std::memory_order_relaxed);
    uint64_t next = 0;
    do {
        const uint64_t next_epoch = (cur >> EPOCH_SHIFT) + 1ULL;
        next = (next_epoch << EPOCH_SHIFT) | (flushing ? FLAG_FLUSHING : 0ULL);
    } while (!epoch_and_flush_.compare_exchange_weak(
        cur, next, std::memory_order_acq_rel, std::memory_order_relaxed));
    return next >> EPOCH_SHIFT;
}

bool AudioEngine::parse_audio_descriptors(libusb_device* dev, int target_bit_depth) {
    struct libusb_config_descriptor* config = nullptr;
    int r = libusb_get_active_config_descriptor(dev, &config);
    if (r != LIBUSB_SUCCESS) {
        r = libusb_get_config_descriptor(dev, 0, &config);
    }
    if (r != LIBUSB_SUCCESS || !config) {
        LOGE("获取 USB 配置描述符失败: %d", r);
        return false;
    }

    endpoint_info_ = uac::AudioEndpointInfo{};
    uint8_t found_clock_id = 1; // 默认时钟源ID为1

    struct AltCandidate {
        uac::AudioEndpointInfo info{};
    };
    std::vector<AltCandidate> candidates;

    // 遍历所有接口寻找 AudioStreaming 接口与 Isochronous OUT 端点
    for (uint8_t i = 0; i < config->bNumInterfaces; ++i) {
        const struct libusb_interface* iface = &config->interface[i];
        for (int a = 0; a < iface->num_altsetting; ++a) {
            const struct libusb_interface_descriptor* alt = &iface->altsetting[a];

            // 检查 AudioControl 接口中的 Clock Source 实体 (UAC2)
            if (alt->bInterfaceClass == uac::CLASS_AUDIO && alt->bInterfaceSubClass == uac::SUBCLASS_AUDIOCONTROL) {
                endpoint_info_.control_interface_number = alt->bInterfaceNumber;
                const uint8_t* extra = alt->extra;
                int extra_len = alt->extra_length;
                while (extra_len >= 3) {
                    uint8_t len = extra[0];
                    uint8_t type = extra[1];
                    uint8_t subtype = extra[2];
                    if (len == 0 || len > extra_len) break;

                    if (type == uac::CS_INTERFACE) {
                        if (subtype == uac::uac2::CLOCK_SOURCE) {
                            if (len >= 4) {
                                found_clock_id = extra[3];
                                endpoint_info_.clock_id = found_clock_id;
                                endpoint_info_.is_uac2 = true;
                                LOGI("发现 UAC2 Clock Source 实体 ID: %u, Control Iface: %d",
                                     found_clock_id, endpoint_info_.control_interface_number);
                            }
                        } else if (subtype == uac::FEATURE_UNIT) {
                            // 💡 解析 Feature Unit 实体 ID (UAC1 与 UAC2 结构中 bUnitID 均为 offset 3)
                            if (len >= 4) {
                                uint8_t fu_id = extra[3];
                                endpoint_info_.feature_unit_ids.push_back(fu_id);
                                if (endpoint_info_.feature_unit_id == 0) {
                                    endpoint_info_.feature_unit_id = fu_id;
                                }
                                LOGI("发现 UAC Feature Unit 实体 ID: %u, Control Iface: %d",
                                     fu_id, endpoint_info_.control_interface_number);
                            }
                        }
                    }
                    extra += len;
                    extra_len -= len;
                }
            }

            // 检查 AudioStreaming 接口
            if (alt->bInterfaceClass == uac::CLASS_AUDIO && alt->bInterfaceSubClass == uac::SUBCLASS_AUDIOSTREAMING) {
                bool is_uac2 = (alt->bInterfaceProtocol == uac::PROTOCOL_UAC2);

                // 寻找等时 OUT 端点与配套的显式时钟反馈 (Feedback) 端点
                for (uint8_t e = 0; e < alt->bNumEndpoints; ++e) {
                    const struct libusb_endpoint_descriptor* ep = &alt->endpoint[e];
                    const bool is_out = (ep->bEndpointAddress & 0x80) == 0;
                    const uint8_t transfer_type = ep->bmAttributes & 0x03;

                    if (is_out && transfer_type == LIBUSB_TRANSFER_TYPE_ISOCHRONOUS) {
                        AltCandidate cand;
                        cand.info.interface_number = alt->bInterfaceNumber;
                        cand.info.alt_setting = alt->bAlternateSetting;
                        cand.info.endpoint_address = ep->bEndpointAddress;
                        cand.info.max_packet_size = ep->wMaxPacketSize;
                        cand.info.interval = ep->bInterval;
                        cand.info.clock_id = found_clock_id;
                        cand.info.control_interface_number = endpoint_info_.control_interface_number;
                        cand.info.feature_unit_id = endpoint_info_.feature_unit_id;
                        cand.info.feature_unit_ids = endpoint_info_.feature_unit_ids;
                        cand.info.is_uac2 = is_uac2;

                        if (ep->bSynchAddress != 0) {
                            cand.info.feedback_ep = ep->bSynchAddress;
                        }

                        // 寻找同 Altsetting 下的显式反馈 IN 端点 (Explicit Feedback Endpoint)
                        for (uint8_t fe = 0; fe < alt->bNumEndpoints; ++fe) {
                            const struct libusb_endpoint_descriptor* fep = &alt->endpoint[fe];
                            const bool is_in = (fep->bEndpointAddress & 0x80) != 0;
                            const uint8_t f_type = fep->bmAttributes & 0x03;
                            const uint8_t f_usage = (fep->bmAttributes >> 4) & 0x03;

                            if (is_in && f_type == LIBUSB_TRANSFER_TYPE_ISOCHRONOUS) {
                                // 💡 严格遵循 UAC2 规范：仅在端点 Usage 为 FEEDBACK (0x01) 或与明确指定的同步端点匹配时识别为反馈端点
                                if (f_usage == 0x01 /* FEEDBACK */ || (cand.info.feedback_ep != 0 && cand.info.feedback_ep == fep->bEndpointAddress)) {
                                    cand.info.feedback_ep = fep->bEndpointAddress;
                                    cand.info.feedback_interval = fep->bInterval;
                                    cand.info.feedback_max_packet_size = fep->wMaxPacketSize;
                                    break;
                                }
                            }
                        }

                        // 解析格式描述符 (Type I)
                        const uint8_t* extra = alt->extra;
                        int extra_len = alt->extra_length;
                        cand.info.subslot_size = 2;
                        cand.info.bit_resolution = 16;

                        while (extra_len >= 3) {
                            uint8_t len = extra[0];
                            uint8_t type = extra[1];
                            uint8_t subtype = extra[2];
                            if (len == 0 || len > extra_len) break;

                            if (type == uac::CS_INTERFACE && subtype == 0x02 /* FORMAT_TYPE */) {
                                if (is_uac2 && len >= 6) {
                                    cand.info.subslot_size = extra[4];
                                    cand.info.bit_resolution = extra[5];
                                } else if (!is_uac2 && len >= 7) {
                                    cand.info.subslot_size = extra[5];
                                    cand.info.bit_resolution = extra[6];
                                }
                                break;
                            }
                            extra += len;
                            extra_len -= len;
                        }

                        LOGI("发现候选等时音频端点: iface=%d, alt=%d, ep=0x%02X, subslot=%u, bitRes=%u, feedbackEp=0x%02X",
                             cand.info.interface_number, cand.info.alt_setting, cand.info.endpoint_address,
                             cand.info.subslot_size, cand.info.bit_resolution, cand.info.feedback_ep);
                        candidates.push_back(cand);
                        break;
                    }
                }
            }
        }
    }

    libusb_free_config_descriptor(config);

    if (candidates.empty()) {
        LOGE("未在设备描述符中找到兼容的等时音频输出端点");
        return false;
    }

    // 💡 匹配原则：精准匹配源位深！
    const AltCandidate* selected = nullptr;

    // 1. 完全精确匹配 bit_resolution == target_bit_depth
    for (const auto& c : candidates) {
        if (c.info.bit_resolution == target_bit_depth) {
            selected = &c;
            break;
        }
    }

    // 2. 若无完全精确匹配，匹配能够容纳该位深的最小容器 (如 16-bit 尝试 subslot_size >= 2)
    if (!selected) {
        const int target_bytes = (target_bit_depth + 7) / 8;
        for (const auto& c : candidates) {
            if (c.info.subslot_size >= target_bytes) {
                if (!selected || c.info.subslot_size < selected->info.subslot_size) {
                    selected = &c;
                }
            }
        }
    }

    // 3. 兜底选第一条候选
    if (!selected) {
        selected = &candidates.front();
    }

    endpoint_info_ = selected->info;
    LOGI("🎯 精准匹配源位深 (%d bit)，选定等时音频端点: iface=%d, alt=%d, ep=0x%02x, subslotSize=%u, bitResolution=%u, feedbackEp=0x%02x, isUAC2=%d",
         target_bit_depth,
         endpoint_info_.interface_number, endpoint_info_.alt_setting,
         endpoint_info_.endpoint_address, endpoint_info_.subslot_size,
         endpoint_info_.bit_resolution, endpoint_info_.feedback_ep,
         endpoint_info_.is_uac2);

    return true;
}

int AudioEngine::configure_clock(int sample_rate) {
    if (!dev_handle_) return -1;

    LOGI("配置 DAC 硬件时钟: %d Hz (UAC%d)", sample_rate, endpoint_info_.is_uac2 ? 2 : 1);

    if (endpoint_info_.is_uac2) {
        // 💡 UAC2 规范：发送 CS_SAM_FREQ_CONTROL 至 Clock Source Entity
        const uint8_t req_type = static_cast<uint8_t>(LIBUSB_REQUEST_TYPE_CLASS) | static_cast<uint8_t>(LIBUSB_RECIPIENT_INTERFACE);
        const uint8_t bRequest = uac::uac2::CS_CUR;
        const uint16_t wValue = (uac::uac2::CS_SAM_FREQ_CONTROL << 8);
        const uint16_t wIndex = (endpoint_info_.clock_id << 8) | (endpoint_info_.control_interface_number & 0xFF);

        uint32_t freq_le = static_cast<uint32_t>(sample_rate);
        uint8_t data[4];
        data[0] = static_cast<uint8_t>(freq_le & 0xFF);
        data[1] = static_cast<uint8_t>((freq_le >> 8) & 0xFF);
        data[2] = static_cast<uint8_t>((freq_le >> 16) & 0xFF);
        data[3] = static_cast<uint8_t>((freq_le >> 24) & 0xFF);

        int r = libusb_control_transfer(dev_handle_, req_type, bRequest, wValue, wIndex, data, sizeof(data), 1000);
        if (r < 0) {
            LOGW("UAC2 CS_SAM_FREQ_CONTROL 发送返回 %d (clock_id=%u, iface=%d)",
                 r, endpoint_info_.clock_id, endpoint_info_.control_interface_number);
            return r;
        }
        LOGI("UAC2 时钟设置成功: %d Hz (clock_id=%u, iface=%d)",
             sample_rate, endpoint_info_.clock_id, endpoint_info_.control_interface_number);
        return 0;
    } else {
        // 💡 UAC1 规范：发送 SAMPLING_FREQ_CONTROL 至端点
        const uint8_t req_type = static_cast<uint8_t>(LIBUSB_REQUEST_TYPE_CLASS) | static_cast<uint8_t>(LIBUSB_RECIPIENT_ENDPOINT);
        const uint8_t bRequest = uac::uac1::SET_CUR;
        const uint16_t wValue = (uac::uac1::SAMPLING_FREQ_CONTROL << 8);
        const uint16_t wIndex = endpoint_info_.endpoint_address;

        uint8_t data[3];
        data[0] = static_cast<uint8_t>(sample_rate & 0xFF);
        data[1] = static_cast<uint8_t>((sample_rate >> 8) & 0xFF);
        data[2] = static_cast<uint8_t>((sample_rate >> 16) & 0xFF);

        int r = libusb_control_transfer(dev_handle_, req_type, bRequest, wValue, wIndex, data, sizeof(data), 1000);
        if (r < 0) {
            LOGW("UAC1 SAMPLING_FREQ_CONTROL 发送返回 %d", r);
            return r;
        }
        LOGI("UAC1 端点采样率设置成功: %d Hz", sample_rate);
        return 0;
    }
}

int AudioEngine::lock_hardware_volume(uint8_t fu_id) {
    if (!dev_handle_ || fu_id == 0) return 0;

    LOGI("开始锁定 UAC Feature Unit (id=%u) 硬件音量为 0.0dB (Unity Gain)...", fu_id);

    const uint8_t req_type = static_cast<uint8_t>(LIBUSB_REQUEST_TYPE_CLASS) | static_cast<uint8_t>(LIBUSB_RECIPIENT_INTERFACE);
    const uint8_t bRequest = endpoint_info_.is_uac2 ? uac::uac2::CS_CUR : uac::uac1::SET_CUR;
    const uint16_t wIndex = (static_cast<uint16_t>(fu_id) << 8) | (endpoint_info_.control_interface_number & 0xFF);

    uint8_t vol_data[2] = {0x00, 0x00};
    bool any_success = false;

    for (uint8_t ch = 0; ch <= 2; ++ch) {
        const uint16_t wValue = (static_cast<uint16_t>(uac::FU_VOLUME_CONTROL) << 8) | ch;
        int r = libusb_control_transfer(
            dev_handle_,
            req_type,
            bRequest,
            wValue,
            wIndex,
            vol_data,
            sizeof(vol_data),
            1000
        );
        if (r >= 0) {
            any_success = true;
            LOGI("UAC Feature Unit (id=%u, ch=%u) 音量已成功设置为 0.0dB", fu_id, ch);
        } else {
            LOGD("UAC Feature Unit (id=%u, ch=%u) 音量设置响应: %d", fu_id, ch, r);
        }

        uint8_t mute_data[1] = {0x00};
        const uint16_t wValueMute = (static_cast<uint16_t>(uac::FU_MUTE_CONTROL) << 8) | ch;
        libusb_control_transfer(
            dev_handle_,
            req_type,
            bRequest,
            wValueMute,
            wIndex,
            mute_data,
            sizeof(mute_data),
            500
        );
    }

    if (any_success) {
        LOGI("成功锁定 UAC Feature Unit (id=%u) 硬件音量为 0.0dB", fu_id);
    } else {
        LOGW("锁定 UAC Feature Unit (id=%u) 硬件音量未生效 (声卡可能未实现可调音量)", fu_id);
    }

    return any_success ? 0 : -1;
}

int AudioEngine::start_transfer_pipeline_locked(int sample_rate, int bit_depth, int channels) {
    current_sample_rate_ = sample_rate;
    current_bit_depth_ = bit_depth;
    current_channels_ = channels;
    consecutive_underruns_.store(0, std::memory_order_relaxed);

    // 💡 确定物理输出格式
    if (endpoint_info_.subslot_size == 3) {
        current_output_format_ = PcmFormatter::OutputFormat::S24_3LE;
        LOGI("确定声卡输出物理子帧格式: S24_3LE (3 bytes per sample)");
    } else if (endpoint_info_.subslot_size >= 4) {
        current_output_format_ = PcmFormatter::OutputFormat::S32_LE;
        LOGI("确定声卡输出物理子帧格式: S32_LE (4 bytes per sample)");
    } else {
        current_output_format_ = PcmFormatter::OutputFormat::S16_LE;
        LOGI("确定声卡输出物理子帧格式: S16_LE (2 bytes per sample)");
    }

    // 💡 构建无锁环形缓冲区：容量 = max(65536, byte_rate * 2.5s)（对齐至 2^N，约 2.97 秒缓冲，完全容纳 AirPlay 2 秒突发且绝不丢包）
    const size_t bytes_per_sample = (bit_depth > 0) ? static_cast<size_t>(bit_depth / 8) : 2;
    const size_t byte_rate = static_cast<size_t>(sample_rate) * static_cast<size_t>(channels) * bytes_per_sample;
    const size_t min_capacity = std::max(static_cast<size_t>(65536), static_cast<size_t>(byte_rate * 2.5));
    ring_buffer_ = std::make_unique<SpscRingBuffer>(min_capacity);
    pre_roll_threshold_bytes_.store(byte_rate / 10, std::memory_order_release);

    LOGI("动态构建无锁环形缓冲区: min_capacity=%zu, actual_capacity=%zu (byte_rate=%zu bytes/s, 约 %.2f 秒缓冲)",
         min_capacity, ring_buffer_->capacity(), byte_rate,
         byte_rate > 0 ? (static_cast<double>(ring_buffer_->capacity()) / byte_rate) : 0.0);

    // 💡 修复 Finding 10: 动态检测全速 (Full-Speed 1000 pkt/s) vs 高速 (High-Speed 8000 pkt/s) 帧率
    // UAC1 全速设备以 1ms 帧为调度基准 (1000 fps)；UAC2 高速设备以 125us 微帧为调度基准 (8000 fps)
    int dev_speed = LIBUSB_SPEED_UNKNOWN;
    if (dev_handle_) {
        libusb_device* dev = libusb_get_device(dev_handle_);
        if (dev) {
            dev_speed = libusb_get_device_speed(dev);
        }
    }
    if (dev_speed == LIBUSB_SPEED_FULL || dev_speed == LIBUSB_SPEED_LOW ||
        (!endpoint_info_.is_uac2 && dev_speed != LIBUSB_SPEED_HIGH && dev_speed != LIBUSB_SPEED_SUPER)) {
        packet_rate_ = 1000;
        LOGI("检测到 USB 全速 (Full-Speed / UAC1) 设备，基准帧率设为 1000 帧/秒");
    } else {
        packet_rate_ = 8000;
        LOGI("检测到 USB 高速 (High-Speed / UAC2) 设备，基准帧率设为 8000 微帧/秒");
    }

    const int warmup_packets = static_cast<int>((packet_rate_ * 50) / 1000);
    warmup_packets_remaining_.store(warmup_packets, std::memory_order_release);
    LOGI("配置 DAC Warmup 静音预充: %d 个微帧数据包 (约 50ms)", warmup_packets);

    // 💡 配置 Bresenham 时钟步进
    base_samples_per_packet_ = static_cast<uint32_t>(sample_rate / static_cast<int>(packet_rate_));
    remainder_samples_ = static_cast<uint32_t>(sample_rate % static_cast<int>(packet_rate_));
    bresenham_accum_ = 0;
    phase_accumulator_ = 0;
    const uint32_t nominal_fb = static_cast<uint32_t>((static_cast<uint64_t>(sample_rate) << 16) / packet_rate_);
    feedback_rate_q16_.store(nominal_fb, std::memory_order_release);

    const size_t max_samples_per_pkt = base_samples_per_packet_ + 2;
    packet_size_ = max_samples_per_pkt * static_cast<size_t>(current_channels_) * endpoint_info_.subslot_size;
    if (packet_size_ > endpoint_info_.max_packet_size && endpoint_info_.max_packet_size > 0) {
        packet_size_ = endpoint_info_.max_packet_size;
    }
    if (packet_size_ == 0) packet_size_ = 512;

    LOGI("配置 Bresenham 时钟步进: rate=%d, base_samples=%u, rem=%u, packet_rate=%u, max_pkt_size=%zu",
         sample_rate, base_samples_per_packet_, remainder_samples_, packet_rate_, packet_size_);

    // 💡 重置会话状态并单调推进世代
    is_explicitly_paused_.store(false, std::memory_order_release);
    feedback_locked_.store(false, std::memory_order_release);
    const uint64_t new_epoch = advance_epoch_and_set_flushing(false);
    consumed_epoch_.store(new_epoch, std::memory_order_release);
    is_running_.store(true, std::memory_order_seq_cst);

    transfers_.assign(NUM_TRANSFERS, nullptr);
    transfer_buffers_.assign(NUM_TRANSFERS, {});

    const size_t transfer_buf_size = packet_size_ * PACKETS_PER_TRANSFER;
    for (int i = 0; i < NUM_TRANSFERS; ++i) {
        transfer_buffers_[i].resize(transfer_buf_size, 0);
        if (mlock(transfer_buffers_[i].data(), transfer_buf_size) != 0) {
            LOGW("mlock transfer_buffer[%d] 警告: errno=%d (%s)", i, errno, strerror(errno));
        }

        struct libusb_transfer* xfer = libusb_alloc_transfer(PACKETS_PER_TRANSFER);
        if (!xfer) {
            LOGE("libusb_alloc_transfer 失败");
            continue;
        }

        libusb_fill_iso_transfer(
            xfer,
            dev_handle_,
            endpoint_info_.endpoint_address,
            transfer_buffers_[i].data(),
            static_cast<int>(transfer_buf_size),
            PACKETS_PER_TRANSFER,
            isoc_transfer_cb,
            this,
            1000
        );

        transfers_[i] = xfer;
        fill_isoc_transfer(xfer);
    }

    if (endpoint_info_.feedback_ep != 0) {
        feedback_transfers_.assign(NUM_FEEDBACK_TRANSFERS, nullptr);
        feedback_buffers_.assign(NUM_FEEDBACK_TRANSFERS, {});
        const size_t fb_pkt_size = std::max<size_t>(
            endpoint_info_.feedback_max_packet_size > 0 ? endpoint_info_.feedback_max_packet_size : 4,
            8
        );

        for (int i = 0; i < NUM_FEEDBACK_TRANSFERS; ++i) {
            feedback_buffers_[i].resize(fb_pkt_size, 0);
            if (mlock(feedback_buffers_[i].data(), fb_pkt_size) != 0) {
                LOGW("mlock feedback_buffer[%d] 警告: errno=%d (%s)", i, errno, strerror(errno));
            }
            struct libusb_transfer* fb_xfer = libusb_alloc_transfer(1);
            if (!fb_xfer) {
                LOGE("libusb_alloc_transfer 反馈 URB 失败");
                continue;
            }

            libusb_fill_iso_transfer(
                fb_xfer,
                dev_handle_,
                endpoint_info_.feedback_ep,
                feedback_buffers_[i].data(),
                static_cast<int>(fb_pkt_size),
                1,
                feedback_transfer_cb,
                this,
                1000
            );

            fb_xfer->iso_packet_desc[0].length = static_cast<unsigned int>(fb_pkt_size);
            feedback_transfers_[i] = fb_xfer;
        }
        LOGI("初始化 UAC2 Explicit Feedback 接收管道: ep=0x%02X, URBs=%d, pktSize=%zu",
             endpoint_info_.feedback_ep, NUM_FEEDBACK_TRANSFERS, fb_pkt_size);
    }

    // 启动 libusb 事件循环线程
    event_thread_ = std::thread(&AudioEngine::event_thread_func, this);

    // 💡 提交反馈 URBs
    if (endpoint_info_.feedback_ep != 0) {
        for (int i = 0; i < NUM_FEEDBACK_TRANSFERS; ++i) {
            if (feedback_transfers_[i]) {
                int r = libusb_submit_transfer(feedback_transfers_[i]);
                if (r == LIBUSB_SUCCESS) {
                    active_transfers_.fetch_add(1, std::memory_order_acq_rel);
                } else {
                    LOGW("提交初始反馈 URB[%d] 失败: %d", i, r);
                }
            }
        }
    }

    // 💡 提交等时 OUT URBs
    int submitted_out = 0;
    for (int i = 0; i < NUM_TRANSFERS; ++i) {
        if (transfers_[i]) {
            int r = libusb_submit_transfer(transfers_[i]);
            if (r == LIBUSB_SUCCESS) {
                active_transfers_.fetch_add(1, std::memory_order_acq_rel);
                ++submitted_out;
            } else {
                LOGE("提交初始等时 OUT URB[%d] 失败: %d", i, r);
            }
        }
    }

    if (submitted_out == 0) {
        LOGE("所有初始等时 OUT URB 提交均失败，中止传输管线启动");
        stop_transfer_pipeline_locked();
        return -5;
    }

    LOGI("音频引擎启动成功，等时传输流水线正常运转 (epoch=%llu, Explicit Feedback: %s)",
         static_cast<unsigned long long>(new_epoch),
         endpoint_info_.feedback_ep != 0 ? "ENABLED" : "OPEN-LOOP");
    return 0;
}

int AudioEngine::stop_transfer_pipeline_locked() {
    is_running_.store(false, std::memory_order_seq_cst);
    warmup_packets_remaining_.store(0, std::memory_order_release);

    for (auto* xfer : transfers_) {
        if (xfer) {
            libusb_cancel_transfer(xfer);
        }
    }
    for (auto* xfer : feedback_transfers_) {
        if (xfer) {
            libusb_cancel_transfer(xfer);
        }
    }

    if (event_thread_.joinable()) {
        event_thread_.join();
    }
    LOGI("所有在途等时传输 URB 已完成注销与内核回收 (active=%d)",
         active_transfers_.load(std::memory_order_relaxed));
    active_transfers_.store(0, std::memory_order_release);

    // 💡 排空仍在 write_pcm / 只读查询中的外部生产线程，确保后续 ring_buffer_ 销毁或重配绝不产生 UAF
    while (active_writers_.load(std::memory_order_seq_cst) > 0) {
        std::this_thread::sleep_for(std::chrono::microseconds(100));
    }

    for (auto* xfer : transfers_) {
        if (xfer) {
            libusb_free_transfer(xfer);
        }
    }
    for (auto* xfer : feedback_transfers_) {
        if (xfer) {
            libusb_free_transfer(xfer);
        }
    }
    transfers_.clear();

    for (auto& buf : transfer_buffers_) {
        if (!buf.empty()) {
            munlock(buf.data(), buf.size());
        }
    }
    transfer_buffers_.clear();

    for (auto& buf : feedback_buffers_) {
        if (!buf.empty()) {
            munlock(buf.data(), buf.size());
        }
    }
    feedback_transfers_.clear();
    feedback_buffers_.clear();
    feedback_rate_q16_.store(0, std::memory_order_release);
    phase_accumulator_ = 0;
    bresenham_accum_ = 0;
    return 0;
}

void AudioEngine::release_claimed_interfaces_locked() {
    if (!dev_handle_) return;

    if (is_claimed_.load(std::memory_order_acquire)) {
        libusb_release_interface(dev_handle_, endpoint_info_.interface_number);
        is_claimed_.store(false, std::memory_order_release);
    }
    if (is_control_claimed_.load(std::memory_order_acquire)) {
        if (endpoint_info_.control_interface_number >= 0 &&
            endpoint_info_.control_interface_number != endpoint_info_.interface_number) {
            libusb_release_interface(dev_handle_, endpoint_info_.control_interface_number);
        }
        is_control_claimed_.store(false, std::memory_order_release);
    }

    LOGI("重新向 Linux 内核挂载 snd-usb-audio 驱动 (USBDEVFS_CONNECT)...");
    if (endpoint_info_.control_interface_number >= 0 &&
        endpoint_info_.control_interface_number != endpoint_info_.interface_number) {
        libusb_attach_kernel_driver(dev_handle_, endpoint_info_.control_interface_number);
    }
    int r = libusb_attach_kernel_driver(dev_handle_, endpoint_info_.interface_number);
    if (r != LIBUSB_SUCCESS) {
        LOGW("libusb_attach_kernel_driver 结果: %d (声卡可能已处于内核接管态)", r);
    }
}

bool AudioEngine::is_valid_audio_format(int sample_rate, int bit_depth, int channels) {
    return sample_rate > 0 && sample_rate <= 768000 &&
        (bit_depth == 16 || bit_depth == 24 || bit_depth == 32) &&
        channels > 0 && channels <= 8;
}

void AudioEngine::reset_ring_state_for_epoch(uint64_t target_epoch) {
    if (ring_buffer_) {
        ring_buffer_->clear();
    }
    consecutive_underruns_.store(0, std::memory_order_relaxed);
    phase_accumulator_ = 0;
    bresenham_accum_ = 0;
    // 💡 绝不重置 warmup_packets_remaining_：URB 保活下继电器未断开，切歌无需多静音 50ms
    consumed_epoch_.store(target_epoch, std::memory_order_release);
}

bool AudioEngine::try_unlock_preroll(uint64_t target_epoch, bool* cas_unmuted) {
    if (cas_unmuted) {
        *cas_unmuted = false;
    }
    uint64_t cur = epoch_and_flush_.load(std::memory_order_acquire);
    if ((cur >> EPOCH_SHIFT) != target_epoch || (cur & FLAG_PREROLLED) != 0ULL) {
        return false;
    }
    const size_t threshold = pre_roll_threshold_bytes_.load(std::memory_order_relaxed);
    if (!ring_buffer_ || threshold == 0 || ring_buffer_->available_read() < threshold) {
        return false;
    }
    // 💡 仅当 epoch 与未预充位仍匹配时，用单条 CAS 原子置位 FLAG_PREROLLED 并清除 FLAG_FLUSHING，严防过期线程污染新世代
    const uint64_t desired = (target_epoch << EPOCH_SHIFT) | FLAG_PREROLLED;
    while (!epoch_and_flush_.compare_exchange_weak(
        cur, desired, std::memory_order_acq_rel, std::memory_order_acquire
    )) {
        if ((cur >> EPOCH_SHIFT) != target_epoch || (cur & FLAG_PREROLLED) != 0ULL) {
            return false;
        }
    }
    if (cas_unmuted) {
        *cas_unmuted = ((cur & FLAG_FLUSHING) != 0ULL);
    }
    return true;
}

int AudioEngine::open_device(int fd, int sample_rate, int bit_depth, int channels) {
    if (!is_valid_audio_format(sample_rate, bit_depth, channels)) {
        LOGE("open_device 非法音频参数: rate=%d, bit=%d, ch=%d", sample_rate, bit_depth, channels);
        return -22;
    }

    std::lock_guard<std::recursive_mutex> lock(op_mutex_);
    LOGI("接管 USB 硬件设备: fd=%d, rate=%d, bit=%d, ch=%d", fd, sample_rate, bit_depth, channels);

    if (init_context() != 0) {
        return -1;
    }

    if (dev_handle_) {
        release_device();
    }

    current_fd_ = fd;

    int r = libusb_wrap_sys_device(ctx_, static_cast<intptr_t>(fd), &dev_handle_);
    if (r != LIBUSB_SUCCESS || !dev_handle_) {
        LOGE("libusb_wrap_sys_device 失败: %d", r);
        return r != 0 ? r : -1;
    }

    libusb_device* dev = libusb_get_device(dev_handle_);
    if (!parse_audio_descriptors(dev, bit_depth)) {
        libusb_close(dev_handle_);
        dev_handle_ = nullptr;
        return -2;
    }

    // 💡 步骤 1: 卸载 Linux 内核驱动 snd-usb-audio，实现全链路接口独占
    if (endpoint_info_.control_interface_number >= 0 &&
        endpoint_info_.control_interface_number != endpoint_info_.interface_number) {
        if (libusb_kernel_driver_active(dev_handle_, endpoint_info_.control_interface_number) == 1) {
            libusb_detach_kernel_driver(dev_handle_, endpoint_info_.control_interface_number);
        }
        if (libusb_claim_interface(dev_handle_, endpoint_info_.control_interface_number) == LIBUSB_SUCCESS) {
            is_control_claimed_.store(true, std::memory_order_release);
        }
    }

    if (libusb_kernel_driver_active(dev_handle_, endpoint_info_.interface_number) == 1) {
        LOGI("检测到 Linux 内核 snd-usb-audio 驱动活跃，执行 detach...");
        r = libusb_detach_kernel_driver(dev_handle_, endpoint_info_.interface_number);
        if (r != LIBUSB_SUCCESS) {
            LOGW("libusb_detach_kernel_driver 返回 %d (可能已被系统自动卸载)", r);
        }
    }

    // 💡 步骤 2: 声明占用接口 (Claim Interface)
    r = libusb_claim_interface(dev_handle_, endpoint_info_.interface_number);
    if (r != LIBUSB_SUCCESS) {
        LOGE("libusb_claim_interface 失败: %d", r);
        release_claimed_interfaces_locked();
        libusb_close(dev_handle_);
        dev_handle_ = nullptr;
        return r;
    }
    is_claimed_.store(true, std::memory_order_release);

    // 💡 步骤 3: 激活等时传输 Alternate Setting
    r = libusb_set_interface_alt_setting(dev_handle_, endpoint_info_.interface_number, endpoint_info_.alt_setting);
    if (r != LIBUSB_SUCCESS) {
        LOGE("libusb_set_interface_alt_setting 失败: %d", r);
        release_claimed_interfaces_locked();
        libusb_close(dev_handle_);
        dev_handle_ = nullptr;
        return r;
    }

    // 💡 步骤 4: 锁定硬件时钟采样率与 UAC Feature Unit 音量
    configure_clock(sample_rate);
    if (endpoint_info_.feature_unit_id != 0) {
        lock_hardware_volume(endpoint_info_.feature_unit_id);
    } else {
        LOGI("未发现 UAC Feature Unit 实体，跳过硬件音量锁定");
    }

    // 💡 步骤 5: 启动 SPSC 缓冲与等时传输流水线
    r = start_transfer_pipeline_locked(sample_rate, bit_depth, channels);
    if (r != 0) {
        release_device();
        return r;
    }

    return 0;
}

int64_t AudioEngine::reconfigure_audio_format(int sample_rate, int bit_depth, int channels) {
    if (!is_valid_audio_format(sample_rate, bit_depth, channels)) {
        LOGE("reconfigure_audio_format 拒绝非法参数: rate=%d, bit=%d, ch=%d",
             sample_rate, bit_depth, channels);
        return -22;
    }

    std::lock_guard<std::recursive_mutex> lock(op_mutex_);
    if (!dev_handle_ || !is_claimed_.load(std::memory_order_acquire)) {
        LOGW("reconfigure_audio_format 失败: 硬件设备尚未打开或独占未就绪");
        return -1;
    }

    // 💡 同规格快速路径：规格未变化且流水线正常运转时保持 URB 不中断
    if (is_running_.load(std::memory_order_acquire) &&
        sample_rate == current_sample_rate_ &&
        bit_depth == current_bit_depth_ &&
        channels == current_channels_) {
        is_explicitly_paused_.store(false, std::memory_order_release);
        const uint64_t epoch = get_current_buffer_epoch();
        LOGI("音频规格未变更 (%dHz/%dbit/%dch)，复用现役管线并保留 epoch=%llu",
             sample_rate, bit_depth, channels, static_cast<unsigned long long>(epoch));
        return static_cast<int64_t>(epoch);
    }

    LOGI("开始 UAC 原位三元组切频: %dHz/%dbit/%dch -> %dHz/%dbit/%dch",
         current_sample_rate_, current_bit_depth_, current_channels_,
         sample_rate, bit_depth, channels);

    const uac::AudioEndpointInfo prev_endpoint = endpoint_info_;
    const int prev_sample_rate = current_sample_rate_;

    // 先推进 epoch 并静音，安全收回全部在途 URB
    advance_epoch_and_set_flushing(true);
    is_explicitly_paused_.store(false, std::memory_order_release);
    stop_transfer_pipeline_locked();

    libusb_device* dev = libusb_get_device(dev_handle_);
    if (!dev || !parse_audio_descriptors(dev, bit_depth)) {
        LOGE("原位切频解析描述符失败，触发安全回滚释放设备");
        endpoint_info_ = prev_endpoint;
        release_device();
        return -2;
    }

    // 若新候选位于不同的 AudioStreaming 接口，先释放旧接口再独占新接口
    if (endpoint_info_.interface_number != prev_endpoint.interface_number) {
        libusb_release_interface(dev_handle_, prev_endpoint.interface_number);
        is_claimed_.store(false, std::memory_order_release);
        if (libusb_kernel_driver_active(dev_handle_, endpoint_info_.interface_number) == 1) {
            libusb_detach_kernel_driver(dev_handle_, endpoint_info_.interface_number);
        }
        int claim_ret = libusb_claim_interface(dev_handle_, endpoint_info_.interface_number);
        if (claim_ret != LIBUSB_SUCCESS) {
            LOGE("原位切频切换流接口失败 (%d)，触发安全回滚", claim_ret);
            endpoint_info_ = prev_endpoint;
            release_device();
            return claim_ret < 0 ? claim_ret : -3;
        }
        is_claimed_.store(true, std::memory_order_release);
    }

    int r = libusb_set_interface_alt_setting(
        dev_handle_,
        endpoint_info_.interface_number,
        endpoint_info_.alt_setting
    );
    if (r != LIBUSB_SUCCESS) {
        LOGE("原位切频设置 alt_setting=%d 失败 (%d)，触发安全回滚", endpoint_info_.alt_setting, r);
        release_device();
        return r < 0 ? r : -4;
    }

    int clk_ret = configure_clock(sample_rate);
    if (clk_ret < 0 && (endpoint_info_.is_uac2 || sample_rate != prev_sample_rate)) {
        LOGE("原位切频硬件时钟配置失败 (%d)，触发安全回滚", clk_ret);
        release_device();
        return clk_ret;
    }

    if (endpoint_info_.feature_unit_id != 0) {
        lock_hardware_volume(endpoint_info_.feature_unit_id);
    }

    r = start_transfer_pipeline_locked(sample_rate, bit_depth, channels);
    if (r != 0) {
        LOGE("原位切频重建传输管线失败 (%d)，触发安全回滚", r);
        release_device();
        return r < 0 ? r : -5;
    }

    const uint64_t new_epoch = get_current_buffer_epoch();
    LOGI("🎉 原位切频成功完成: %dHz/%dbit/%dch (alt=%d, epoch=%llu)",
         sample_rate, bit_depth, channels, endpoint_info_.alt_setting,
         static_cast<unsigned long long>(new_epoch));
    return static_cast<int64_t>(new_epoch);
}

uint64_t AudioEngine::emergency_mute_and_flush(FlushReason reason) {
    // 💡 R1/KTD1 无锁硬件快路径铁律：
    // 1. 严禁获取 op_mutex_
    // 2. 严禁取消或停止 URB (保持 8 个等时 URB + 4 个反馈 URB 泵送全 0 静音锁相)
    // 3. 严禁跨线程调用 ring_buffer_->clear() 修改 tail_
    switch (reason) {
        case FlushReason::EXPLICIT_PAUSE: {
            is_explicitly_paused_.store(true, std::memory_order_release);
            const uint64_t epoch = get_current_buffer_epoch();
            LOGI("快路径 EXPLICIT_PAUSE: 切入全0保活，保留缓冲与 epoch=%llu",
                 static_cast<unsigned long long>(epoch));
            return epoch;
        }
        case FlushReason::FLUSH_SWITCH: {
            const uint64_t next_epoch = advance_epoch_and_set_flushing(true);
            LOGI("快路径 FLUSH_SWITCH: 推进至 epoch=%llu 并挂起预充自动解锁",
                 static_cast<unsigned long long>(next_epoch));
            return next_epoch;
        }
        case FlushReason::STOP_OR_TEARDOWN: {
            is_explicitly_paused_.store(true, std::memory_order_release);
            const uint64_t next_epoch = advance_epoch_and_set_flushing(false);
            LOGI("快路径 STOP_OR_TEARDOWN: 置显式静音并推进 epoch=%llu 排空旧流",
                 static_cast<unsigned long long>(next_epoch));
            return next_epoch;
        }
        case FlushReason::PREEMPT: {
            is_explicitly_paused_.store(false, std::memory_order_release);
            const uint64_t next_epoch = advance_epoch_and_set_flushing(true);
            LOGI("快路径 PREEMPT: 解除旧暂停，推进 epoch=%llu 等待新流预充",
                 static_cast<unsigned long long>(next_epoch));
            return next_epoch;
        }
    }
    return get_current_buffer_epoch();
}

void AudioEngine::event_thread_func() {
    LOGI("启动 Native URB 调度线程，提升至实时调度等级...");
    errno = 0;
    int prio_res = setpriority(PRIO_PROCESS, 0, -19);
    if (prio_res == 0) {
        LOGI("Native URB 调度线程已成功提升至 Linux 实时音频优先级 (nice -19)");
    } else {
        LOGW("提升线程优先级失败 (errno=%d: %s)，以默认系统优先级降级运行", errno, strerror(errno));
    }

    struct timeval tv { 0, 10000 }; // 10ms 超时
    while ((is_running_.load(std::memory_order_acquire) || active_transfers_.load(std::memory_order_acquire) > 0) && ctx_) {
        int r = libusb_handle_events_timeout_completed(ctx_, &tv, nullptr);
        if (r < 0 && r != LIBUSB_ERROR_INTERRUPTED) {
            LOGW("libusb_handle_events 警告: %d", r);
        }
    }
    LOGI("Native URB 调度线程退出 (剩余在途 URB: %d)", active_transfers_.load(std::memory_order_acquire));
}

void LIBUSB_CALL AudioEngine::isoc_transfer_cb(struct libusb_transfer* transfer) {
    if (!transfer) return;
    auto* engine = static_cast<AudioEngine*>(transfer->user_data);
    if (engine) {
        engine->process_isoc_transfer(transfer);
    }
}

void AudioEngine::fill_isoc_transfer(struct libusb_transfer* transfer) {
    // 💡 R2/KTD2：仅由消费线程检查世代并安全排空 SPSC tail_
    const uint64_t packed_entry = epoch_and_flush_.load(std::memory_order_acquire);
    const uint64_t target_epoch = packed_entry >> EPOCH_SHIFT;
    if (consumed_epoch_.load(std::memory_order_relaxed) != target_epoch) {
        reset_ring_state_for_epoch(target_epoch);
        LOGI("消费线程已安全清空旧代残余并追平 epoch=%llu", static_cast<unsigned long long>(target_epoch));
    }

    uint8_t* pkt_buf = transfer->buffer;
    size_t total_transfer_len = 0;

    const size_t input_bytes_per_sample = (current_bit_depth_ > 0) ? static_cast<size_t>(current_bit_depth_ / 8) : 2;

    if (try_unlock_preroll(target_epoch)) {
        LOGI("环形缓冲区预充就绪 (已缓冲 >= 100ms PCM: %zu 字节)，开启平滑输出",
             ring_buffer_->available_read());
    }
    const uint64_t packed_after_unlock = epoch_and_flush_.load(std::memory_order_acquire);
    const bool ready_to_play =
        ((packed_after_unlock >> EPOCH_SHIFT) == target_epoch) &&
        ((packed_after_unlock & FLAG_PREROLLED) != 0ULL);

    static thread_local std::vector<uint8_t> temp_pcm_in;

    for (int i = 0; i < transfer->num_iso_packets; ++i) {
        struct libusb_iso_packet_descriptor* pkt = &transfer->iso_packet_desc[i];

        size_t samples_this_pkt = 0;
        const uint32_t fb_rate = feedback_rate_q16_.load(std::memory_order_acquire);

        if (fb_rate > 0) {
            phase_accumulator_ += fb_rate;
            samples_this_pkt = phase_accumulator_ >> 16;
            phase_accumulator_ &= 0xFFFF;
        } else {
            samples_this_pkt = base_samples_per_packet_;
            bresenham_accum_ += remainder_samples_;
            if (bresenham_accum_ >= packet_rate_) {
                bresenham_accum_ -= packet_rate_;
                samples_this_pkt += 1;
            }
        }

        const size_t max_allowed_samples = (endpoint_info_.subslot_size > 0 && current_channels_ > 0) ?
            (packet_size_ / (static_cast<size_t>(current_channels_) * endpoint_info_.subslot_size)) : 8;
        if (samples_this_pkt > max_allowed_samples && max_allowed_samples > 0) {
            samples_this_pkt = max_allowed_samples;
        }

        const size_t target_pkt_bytes = samples_this_pkt * static_cast<size_t>(current_channels_) * endpoint_info_.subslot_size;
        const size_t raw_input_bytes = samples_this_pkt * static_cast<size_t>(current_channels_) * input_bytes_per_sample;
        pkt->length = static_cast<unsigned int>(target_pkt_bytes);

        // 💡 每微帧实时采样原子静音标记，确保快路径哪怕在 URB 填充中途触发也能立刻切全 0
        const uint64_t packed_now = epoch_and_flush_.load(std::memory_order_acquire);
        const bool epoch_changed = (packed_now >> EPOCH_SHIFT) != target_epoch;
        const bool flushing_now = (packed_now & FLAG_FLUSHING) != 0ULL;
        const bool paused_now = is_explicitly_paused_.load(std::memory_order_acquire);
        const bool muted = paused_now || flushing_now || epoch_changed;

        const int remaining_warmup = warmup_packets_remaining_.load(std::memory_order_relaxed);
        if (remaining_warmup > 0) {
            if (warmup_packets_remaining_.fetch_sub(1, std::memory_order_relaxed) == 1) {
                LOGI("DAC Warmup 50ms 静音预充完成，开始正常音频调度");
            }
            std::memset(pkt_buf, 0, target_pkt_bytes);
        } else if (muted || !ready_to_play || !ring_buffer_ || ring_buffer_->available_read() < raw_input_bytes) {
            std::memset(pkt_buf, 0, target_pkt_bytes);
            if (!muted && ready_to_play && ring_buffer_ && ring_buffer_->available_read() < raw_input_bytes) {
                int underruns = consecutive_underruns_.fetch_add(1, std::memory_order_relaxed) + 1;
                // 💡 修复 Finding 3: 曲目尾部残留不足单微帧的零散字节排空 (Drain Tail Residual Samples)
                // 当连续下溢 >= 5 (约 5ms 无新写入，说明生产者已读到 EOF) 且缓冲区中仍有残留字节时，
                // 主动读取剩余的完整声道采样并以 0 填充微帧剩余部分，彻底排空 ring_buffer_ 避免 Java 端 6 秒阻塞！
                size_t leftover = ring_buffer_->available_read();
                const size_t bytes_per_sample_frame = static_cast<size_t>(current_channels_) * input_bytes_per_sample;
                if (underruns >= 5 && leftover > 0 && bytes_per_sample_frame > 0) {
                    size_t samples_bytes_to_read = (leftover / bytes_per_sample_frame) * bytes_per_sample_frame;
                    if (samples_bytes_to_read > 0) {
                        temp_pcm_in.resize(raw_input_bytes);
                        std::memset(temp_pcm_in.data(), 0, raw_input_bytes);
                        ring_buffer_->read(temp_pcm_in.data(), samples_bytes_to_read);
                        if (current_bit_depth_ == 24) {
                            PcmFormatter::format_24bit_to_target(temp_pcm_in.data(), raw_input_bytes, pkt_buf, current_output_format_);
                        } else {
                            PcmFormatter::format_16bit_to_target(temp_pcm_in.data(), raw_input_bytes, pkt_buf, current_output_format_);
                        }
                    } else {
                        std::vector<uint8_t> discard(leftover);
                        ring_buffer_->read(discard.data(), leftover);
                    }
                }
                if (underruns >= 50) {
                    uint64_t cur_state = epoch_and_flush_.load(std::memory_order_acquire);
                    while ((cur_state >> EPOCH_SHIFT) == target_epoch &&
                           (cur_state & FLAG_PREROLLED) != 0ULL) {
                        if (epoch_and_flush_.compare_exchange_weak(
                                cur_state,
                                cur_state & ~FLAG_PREROLLED,
                                std::memory_order_acq_rel,
                                std::memory_order_acquire
                            )) {
                            LOGW("环形缓冲区持续下溢超 50ms，重新进入 100ms 预充保护");
                            break;
                        }
                    }
                    consecutive_underruns_.store(0, std::memory_order_relaxed);
                }
            } else {
                consecutive_underruns_.store(0, std::memory_order_relaxed);
            }
        } else {
            temp_pcm_in.resize(raw_input_bytes);
            size_t bytes_read = ring_buffer_->read(temp_pcm_in.data(), raw_input_bytes);
            if (bytes_read == raw_input_bytes) {
                if (current_bit_depth_ == 24) {
                    PcmFormatter::format_24bit_to_target(
                        temp_pcm_in.data(),
                        bytes_read,
                        pkt_buf,
                        current_output_format_
                    );
                } else {
                    PcmFormatter::format_16bit_to_target(
                        temp_pcm_in.data(),
                        bytes_read,
                        pkt_buf,
                        current_output_format_
                    );
                }
            } else {
                std::memset(pkt_buf, 0, target_pkt_bytes);
            }
        }

        pkt_buf += target_pkt_bytes;
        total_transfer_len += target_pkt_bytes;
    }

    transfer->length = static_cast<int>(total_transfer_len);
}

void AudioEngine::process_isoc_transfer(struct libusb_transfer* transfer) {
    if (!is_running_.load(std::memory_order_acquire) ||
        transfer->status == LIBUSB_TRANSFER_CANCELLED ||
        transfer->status == LIBUSB_TRANSFER_NO_DEVICE) {
        active_transfers_.fetch_sub(1, std::memory_order_acq_rel);
        return;
    }

    if (transfer->status != LIBUSB_TRANSFER_COMPLETED) {
        if (transfer_err_count_.fetch_add(1, std::memory_order_relaxed) % 100 == 0) {
            LOGW("等时传输 URB 状态异常: status=%d", transfer->status);
        }
    }

    fill_isoc_transfer(transfer);

    int r = libusb_submit_transfer(transfer);
    if (r != LIBUSB_SUCCESS) {
        if (is_running_.load(std::memory_order_acquire)) {
            LOGW("重新提交等时 URB 失败: %d", r);
        }
        active_transfers_.fetch_sub(1, std::memory_order_acq_rel);
    }
}

void LIBUSB_CALL AudioEngine::feedback_transfer_cb(struct libusb_transfer* transfer) {
    if (!transfer) return;
    auto* engine = static_cast<AudioEngine*>(transfer->user_data);
    if (engine) {
        engine->process_feedback_transfer(transfer);
    }
}

void AudioEngine::process_feedback_transfer(struct libusb_transfer* transfer) {
    if (!is_running_.load(std::memory_order_acquire) ||
        transfer->status == LIBUSB_TRANSFER_CANCELLED ||
        transfer->status == LIBUSB_TRANSFER_NO_DEVICE) {
        active_transfers_.fetch_sub(1, std::memory_order_acq_rel);
        return;
    }

    if (transfer->status == LIBUSB_TRANSFER_COMPLETED) {
        for (int i = 0; i < transfer->num_iso_packets; ++i) {
            struct libusb_iso_packet_descriptor* pkt = &transfer->iso_packet_desc[i];
            if (pkt->status == LIBUSB_TRANSFER_COMPLETED && pkt->actual_length >= 3) {
                const uint8_t* buf = libusb_get_iso_packet_buffer(transfer, i);
                if (!buf) {
                    LOGW("libusb_get_iso_packet_buffer 返回空指针 (packet=%d)", i);
                    continue;
                }
                uint32_t raw_val = 0;
                if (pkt->actual_length == 3) {
                    const uint32_t val10_14 = static_cast<uint32_t>(buf[0]) |
                                              (static_cast<uint32_t>(buf[1]) << 8) |
                                              (static_cast<uint32_t>(buf[2]) << 16);
                    raw_val = val10_14 << 2;
                } else {
                    raw_val = static_cast<uint32_t>(buf[0]) |
                              (static_cast<uint32_t>(buf[1]) << 8) |
                              (static_cast<uint32_t>(buf[2]) << 16) |
                              (static_cast<uint32_t>(buf[3]) << 24);
                }

                const uint64_t nominal_q16 = (static_cast<uint64_t>(current_sample_rate_) << 16) / packet_rate_;
                const uint64_t min_q16 = nominal_q16 * 90 / 100;
                const uint64_t max_q16 = nominal_q16 * 110 / 100;

                if (raw_val >= min_q16 && raw_val <= max_q16) {
                    if (!feedback_locked_.load(std::memory_order_relaxed)) {
                        feedback_locked_.store(true, std::memory_order_release);
                        LOGI("🎉 成功锁定 UAC2 Explicit Feedback 物理时钟: raw=0x%08X (%.4f 采样/微帧, 目标基准: 0x%08llX)",
                             raw_val, static_cast<double>(raw_val) / 65536.0, static_cast<unsigned long long>(nominal_q16));
                    }
                    feedback_rate_q16_.store(raw_val, std::memory_order_release);
                } else {
                    if (feedback_err_count_.fetch_add(1, std::memory_order_relaxed) % 1000 == 0) {
                        LOGW("FB 越界异常: raw=0x%08X, nominal=0x%08llX, range=[0x%08llX, 0x%08llX], act_len=%u",
                             raw_val, static_cast<unsigned long long>(nominal_q16),
                             static_cast<unsigned long long>(min_q16),
                             static_cast<unsigned long long>(max_q16),
                             pkt->actual_length);
                    }
                }
            }
        }
    }

    const size_t fb_pkt_size = std::max<size_t>(
        endpoint_info_.feedback_max_packet_size > 0 ? endpoint_info_.feedback_max_packet_size : 4,
        8
    );
    transfer->iso_packet_desc[0].length = static_cast<unsigned int>(fb_pkt_size);
    transfer->length = static_cast<int>(fb_pkt_size);

    int r = libusb_submit_transfer(transfer);
    if (r != LIBUSB_SUCCESS) {
        if (is_running_.load(std::memory_order_acquire)) {
            LOGW("重新提交反馈 URB 失败: %d", r);
        }
        active_transfers_.fetch_sub(1, std::memory_order_acq_rel);
    }
}

int AudioEngine::write_pcm(const uint8_t* data, size_t size, uint64_t expected_epoch) {
    // 💡 P0 #3 并发防御：先登记 active_writers_，再以 seq_cst 检查 is_running_，
    // 配合 stop_transfer_pipeline_locked() 先设 is_running_=false (seq_cst) 再排空 active_writers_，
    // 确保 ring_buffer_ 在整个写入期间存活且不被 release_device()/reconfigure() 并发销毁。
    ScopedActiveWriter writer_guard(active_writers_);
    if (!is_running_.load(std::memory_order_seq_cst) || !ring_buffer_) {
        return -1;
    }
    if (!data || size == 0) {
        return 0;
    }
    if (expected_epoch == 0 || expected_epoch != get_current_buffer_epoch()) {
        return -2;
    }

    // 💡 R2/KTD2 “写先于清 (Write-Before-Clear)” 同步门闩：
    // 若快路径刚把 buffer_epoch_ 推到 expected_epoch，但消费线程尚在 <=1ms 回调窗口内未追平，
    // 生产线程必须让出等待消费线程先清完旧代残余再写首包，严禁跨线程清缓冲或提前写导致吞首音。
    if (consumed_epoch_.load(std::memory_order_acquire) != expected_epoch) {
        const auto wait_deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(50);
        while (consumed_epoch_.load(std::memory_order_acquire) != expected_epoch) {
            if (!is_running_.load(std::memory_order_seq_cst)) {
                return -1;
            }
            if (get_current_buffer_epoch() != expected_epoch) {
                return -2;
            }
            // 💡 严禁误用 is_streaming() (静音期间恒 false)！仅以 is_running_ && active_transfers_ > 0 判定消费线程存活
            if (active_transfers_.load(std::memory_order_acquire) == 0) {
                // 💡 必须使用 try_to_lock：若控制线程正持 op_mutex_ 执行 stop_transfer_pipeline_locked() 等待 active_writers_==0，
                // 阻塞等待 op_mutex_ 会引发互相等待的锁序死锁！
                std::unique_lock<std::recursive_mutex> lock(op_mutex_, std::try_to_lock);
                if (!lock.owns_lock()) {
                    if (!is_running_.load(std::memory_order_seq_cst)) {
                        return -1;
                    }
                    if (std::chrono::steady_clock::now() >= wait_deadline) {
                        return -3;
                    }
                    std::this_thread::sleep_for(std::chrono::microseconds(100));
                    continue;
                }
                if (!is_running_.load(std::memory_order_seq_cst)) {
                    return -1;
                }
                if (get_current_buffer_epoch() != expected_epoch) {
                    return -2;
                }
                if (active_transfers_.load(std::memory_order_acquire) == 0 &&
                    consumed_epoch_.load(std::memory_order_acquire) != expected_epoch) {
                    reset_ring_state_for_epoch(expected_epoch);
                    break;
                }
            } else {
                if (std::chrono::steady_clock::now() >= wait_deadline) {
                    LOGW("write_pcm 等待消费线程追平 epoch=%llu 超时 (consumed=%llu)",
                         static_cast<unsigned long long>(expected_epoch),
                         static_cast<unsigned long long>(consumed_epoch_.load(std::memory_order_acquire)));
                    return -3;
                }
                std::this_thread::sleep_for(std::chrono::microseconds(100));
            }
        }
    }

    // 追平唤醒后再次校验世代，防止等待期间已发生新的切歌 Flush
    if (!is_running_.load(std::memory_order_seq_cst)) {
        return -1;
    }
    if (get_current_buffer_epoch() != expected_epoch) {
        return -2;
    }

    const size_t available = ring_buffer_->available_write();
    // 💡 整包丢弃原则 (Drop Whole Packet)：严禁半包截断破坏采样声道对齐
    if (available < size) {
        if (overrun_log_count_.fetch_add(1, std::memory_order_relaxed) % 100 == 0) {
            LOGW("环形缓冲区高水位饱和 (可用: %zu < 需求: %zu)，整包丢弃以消除波形断裂爆音", available, size);
        }
        return 0;
    }

    const size_t written = ring_buffer_->write(data, size);

    // 💡 写入后达到 100ms 预充阈值且仍在同一 epoch 时，原位原子解除 FLUSH 静音
    if (written > 0) {
        bool cas_unmuted = false;
        if (try_unlock_preroll(expected_epoch, &cas_unmuted) && cas_unmuted) {
            LOGI("新流达到 100ms 预充水位，Native 原位自动解除 FLUSH 静音 (epoch=%llu)",
                 static_cast<unsigned long long>(expected_epoch));
        }
    }

    return static_cast<int>(written);
}

int AudioEngine::resume_stream() {
    is_explicitly_paused_.store(false, std::memory_order_release);
    LOGI("音频流显式暂停标记已清除，恢复等时输出");
    return 0;
}

int AudioEngine::release_device() {
    std::lock_guard<std::recursive_mutex> lock(op_mutex_);
    LOGI("开始执行声卡归还与内核驱动重绑时序...");

    stop_transfer_pipeline_locked();

    if (dev_handle_) {
        release_claimed_interfaces_locked();
        libusb_close(dev_handle_);
        dev_handle_ = nullptr;
    }

    ring_buffer_.reset();
    current_fd_ = -1;
    LOGI("声卡硬件独占已完全解除，成功归还给电视系统");
    return 0;
}

bool AudioEngine::is_streaming() const {
    return is_running_.load(std::memory_order_acquire) && !is_muted();
}

size_t AudioEngine::get_buffer_available() const {
    ScopedActiveWriter writer_guard(active_writers_);
    return (is_running_.load(std::memory_order_seq_cst) && ring_buffer_) ? ring_buffer_->available_write() : 0;
}

size_t AudioEngine::get_buffer_capacity() const {
    ScopedActiveWriter writer_guard(active_writers_);
    return (is_running_.load(std::memory_order_seq_cst) && ring_buffer_) ? ring_buffer_->capacity() : 0;
}

size_t AudioEngine::get_available_read() const {
    ScopedActiveWriter writer_guard(active_writers_);
    return (is_running_.load(std::memory_order_seq_cst) && ring_buffer_) ? ring_buffer_->available_read() : 0;
}

int64_t AudioEngine::get_ring_buffer_delay_ms() const {
    ScopedActiveWriter writer_guard(active_writers_);
    if (!is_running_.load(std::memory_order_seq_cst) || !ring_buffer_) {
        return 0;
    }
    const size_t unconsumed_bytes = ring_buffer_->available_read();
    const int sr = current_sample_rate_;
    const int ch = current_channels_;
    const int bd = current_bit_depth_;
    if (sr <= 0 || ch <= 0 || bd <= 0) {
        return 0;
    }
    const size_t bytes_per_sample = static_cast<size_t>(bd / 8);
    const size_t bytes_per_sec = static_cast<size_t>(sr) * static_cast<size_t>(ch) * bytes_per_sample;
    if (bytes_per_sec == 0) {
        return 0;
    }
    return static_cast<int64_t>((unconsumed_bytes * 1000ULL) / bytes_per_sec);
}

} // namespace purecast
