#include "audio_engine.h"
#include <sys/resource.h>
#include <unistd.h>
#include <cmath>

namespace purecast {

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
    std::lock_guard<std::mutex> lock(op_mutex_);
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

bool AudioEngine::parse_audio_descriptors(libusb_device* dev) {
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

    // 遍历所有接口寻找 AudioStreaming 接口与 Isochronous OUT 端点
    for (uint8_t i = 0; i < config->bNumInterfaces; ++i) {
        const struct libusb_interface* iface = &config->interface[i];
        for (int a = 0; a < iface->num_altsetting; ++a) {
            const struct libusb_interface_descriptor* alt = &iface->altsetting[a];

            // 检查 AudioControl 接口中的 Clock Source 实体 (UAC2)
            if (alt->bInterfaceClass == uac::CLASS_AUDIO && alt->bInterfaceSubClass == uac::SUBCLASS_AUDIOCONTROL) {
                const uint8_t* extra = alt->extra;
                int extra_len = alt->extra_length;
                while (extra_len >= 3) {
                    uint8_t len = extra[0];
                    uint8_t type = extra[1];
                    uint8_t subtype = extra[2];
                    if (len == 0 || len > extra_len) break;

                    if (type == uac::CS_INTERFACE && subtype == uac::uac2::CLOCK_SOURCE) {
                        found_clock_id = extra[3];
                        endpoint_info_.is_uac2 = true;
                        LOGI("发现 UAC2 Clock Source 实体 ID: %u", found_clock_id);
                    }
                    extra += len;
                    extra_len -= len;
                }
            }

            // 检查 AudioStreaming 接口
            if (alt->bInterfaceClass == uac::CLASS_AUDIO && alt->bInterfaceSubClass == uac::SUBCLASS_AUDIOSTREAMING) {
                if (alt->bInterfaceProtocol == uac::PROTOCOL_UAC2) {
                    endpoint_info_.is_uac2 = true;
                }

                // 寻找等时 OUT 端点
                for (uint8_t e = 0; e < alt->bNumEndpoints; ++e) {
                    const struct libusb_endpoint_descriptor* ep = &alt->endpoint[e];
                    const bool is_out = (ep->bEndpointAddress & 0x80) == 0;
                    const uint8_t transfer_type = ep->bmAttributes & 0x03;

                    if (is_out && transfer_type == LIBUSB_TRANSFER_TYPE_ISOCHRONOUS) {
                        endpoint_info_.interface_number = alt->bInterfaceNumber;
                        endpoint_info_.alt_setting = alt->bAlternateSetting;
                        endpoint_info_.endpoint_address = ep->bEndpointAddress;
                        endpoint_info_.max_packet_size = ep->wMaxPacketSize;
                        endpoint_info_.interval = ep->bInterval;
                        endpoint_info_.clock_id = found_clock_id;

                        // 解析格式描述符 (Type I)
                        const uint8_t* extra = alt->extra;
                        int extra_len = alt->extra_length;
                        while (extra_len >= 3) {
                            uint8_t len = extra[0];
                            uint8_t type = extra[1];
                            uint8_t subtype = extra[2];
                            if (len == 0 || len > extra_len) break;

                            if (type == uac::CS_INTERFACE && subtype == 0x02 /* FORMAT_TYPE */) {
                                if (endpoint_info_.is_uac2 && len >= 6) {
                                    endpoint_info_.subslot_size = extra[4];
                                    endpoint_info_.bit_resolution = extra[5];
                                } else if (!endpoint_info_.is_uac2 && len >= 7) {
                                    endpoint_info_.subslot_size = extra[5];
                                    endpoint_info_.bit_resolution = extra[6];
                                }
                                LOGI("解析格式描述符: subslotSize=%u bytes, bitResolution=%u bits",
                                     endpoint_info_.subslot_size, endpoint_info_.bit_resolution);
                                break;
                            }
                            extra += len;
                            extra_len -= len;
                        }

                        LOGI("成功定位目标等时音频端点: iface=%d, alt=%d, ep=0x%02x, maxPacket=%u, isUAC2=%d",
                             endpoint_info_.interface_number, endpoint_info_.alt_setting,
                             endpoint_info_.endpoint_address, endpoint_info_.max_packet_size,
                             endpoint_info_.is_uac2);
                        libusb_free_config_descriptor(config);
                        return true;
                    }
                }
            }
        }
    }

    libusb_free_config_descriptor(config);
    LOGE("未在设备描述符中找到兼容的等时音频输出端点");
    return false;
}

int AudioEngine::configure_clock(int sample_rate) {
    if (!dev_handle_) return -1;

    LOGI("配置 DAC 硬件时钟: %d Hz (UAC%d)", sample_rate, endpoint_info_.is_uac2 ? 2 : 1);

    if (endpoint_info_.is_uac2) {
        // 💡 UAC2 规范：发送 CS_SAM_FREQ_CONTROL 至 Clock Source Entity
        // bmRequestType: 0x21 (Host to Interface, Class-specific)
        // bRequest: 0x01 (CS_CUR / SET_CUR)
        // wValue: (CS_SAM_FREQ_CONTROL << 8) -> 0x0100
        // wIndex: (clock_id << 8) | interface_number
        const uint8_t req_type = static_cast<uint8_t>(LIBUSB_REQUEST_TYPE_CLASS) | static_cast<uint8_t>(LIBUSB_RECIPIENT_INTERFACE);
        const uint8_t bRequest = uac::uac2::CS_CUR;
        const uint16_t wValue = (uac::uac2::CS_SAM_FREQ_CONTROL << 8);
        const uint16_t wIndex = (endpoint_info_.clock_id << 8) | (endpoint_info_.interface_number & 0xFF);

        uint32_t freq_le = static_cast<uint32_t>(sample_rate);
        uint8_t data[4];
        data[0] = static_cast<uint8_t>(freq_le & 0xFF);
        data[1] = static_cast<uint8_t>((freq_le >> 8) & 0xFF);
        data[2] = static_cast<uint8_t>((freq_le >> 16) & 0xFF);
        data[3] = static_cast<uint8_t>((freq_le >> 24) & 0xFF);

        int r = libusb_control_transfer(dev_handle_, req_type, bRequest, wValue, wIndex, data, sizeof(data), 1000);
        if (r < 0) {
            LOGW("UAC2 CS_SAM_FREQ_CONTROL 发送返回 %d, 尝试备用控制端点", r);
        } else {
            LOGI("UAC2 时钟设置成功: %d Hz", sample_rate);
        }
    } else {
        // 💡 UAC1 规范：发送 SAMPLING_FREQ_CONTROL 至端点
        // bmRequestType: 0x22 (Host to Endpoint, Class-specific)
        // bRequest: 0x01 (SET_CUR)
        // wValue: (SAMPLING_FREQ_CONTROL << 8) -> 0x0100
        // wIndex: endpoint_address
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
        } else {
            LOGI("UAC1 端点采样率设置成功: %d Hz", sample_rate);
        }
    }

    return 0;
}

int AudioEngine::open_device(int fd, int sample_rate, int bit_depth, int channels) {
    std::lock_guard<std::mutex> lock(op_mutex_);
    LOGI("接管 USB 硬件设备: fd=%d, rate=%d, bit=%d, ch=%d", fd, sample_rate, bit_depth, channels);

    if (init_context() != 0) {
        return -1;
    }

    // 若此前已打开，先优雅归还
    if (dev_handle_) {
        release_device();
    }

    current_fd_ = fd;
    current_sample_rate_ = sample_rate;
    current_bit_depth_ = bit_depth;
    current_channels_ = channels;

    int r = libusb_wrap_sys_device(ctx_, static_cast<intptr_t>(fd), &dev_handle_);
    if (r != LIBUSB_SUCCESS || !dev_handle_) {
        LOGE("libusb_wrap_sys_device 失败: %d", r);
        return r;
    }

    libusb_device* dev = libusb_get_device(dev_handle_);
    if (!parse_audio_descriptors(dev)) {
        libusb_close(dev_handle_);
        dev_handle_ = nullptr;
        return -2;
    }

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

    // 💡 步骤 1: 卸载 Linux 内核驱动 snd-usb-audio，实现独占
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
        libusb_close(dev_handle_);
        dev_handle_ = nullptr;
        return r;
    }
    is_claimed_.store(true);

    // 💡 步骤 3: 激活等时传输 Alternate Setting
    r = libusb_set_interface_alt_setting(dev_handle_, endpoint_info_.interface_number, endpoint_info_.alt_setting);
    if (r != LIBUSB_SUCCESS) {
        LOGE("libusb_set_interface_alt_setting 失败: %d", r);
        libusb_release_interface(dev_handle_, endpoint_info_.interface_number);
        libusb_close(dev_handle_);
        dev_handle_ = nullptr;
        is_claimed_.store(false);
        return r;
    }

    // 💡 步骤 4: 锁定硬件时钟采样率
    configure_clock(sample_rate);

    // 💡 步骤 5: 构建无锁环形缓冲区 (默认 1MB，支持 ~5 秒无损缓冲)
    ring_buffer_ = std::make_unique<SpscRingBuffer>(1024 * 1024);

    // 计算每微帧或每毫秒包大小
    // USB High-Speed 下每 125us 一个微帧 (8000 microframes/sec)
    // 包大小 = ceil(sample_rate / 8000) * channels * subslot_size
    const size_t samples_per_packet = static_cast<size_t>(std::ceil(static_cast<double>(sample_rate) / 8000.0)) + 1;
    packet_size_ = samples_per_packet * current_channels_ * endpoint_info_.subslot_size;
    if (packet_size_ > endpoint_info_.max_packet_size && endpoint_info_.max_packet_size > 0) {
        packet_size_ = endpoint_info_.max_packet_size;
    }
    if (packet_size_ == 0) packet_size_ = 512;

    LOGI("计算等时 URB 数据包尺寸: packet_size=%zu, subslot=%u", packet_size_, endpoint_info_.subslot_size);

    // 💡 步骤 6: 预分配等时 URB 池与传输线程
    is_running_.store(true);
    is_paused_.store(false);

    transfers_.resize(NUM_TRANSFERS, nullptr);
    transfer_buffers_.resize(NUM_TRANSFERS);

    const size_t transfer_buf_size = packet_size_ * PACKETS_PER_TRANSFER;
    for (int i = 0; i < NUM_TRANSFERS; ++i) {
        transfer_buffers_[i].resize(transfer_buf_size, 0);
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
            1000 // 超时 1000ms
        );

        libusb_set_iso_packet_lengths(xfer, static_cast<unsigned int>(packet_size_));
        transfers_[i] = xfer;
    }

    // 启动 libusb 事件循环线程
    event_thread_ = std::thread(&AudioEngine::event_thread_func, this);

    // 💡 提交首批 URBs (预充 50ms 静音 Warmup 防爆音)
    for (int i = 0; i < NUM_TRANSFERS; ++i) {
        if (transfers_[i]) {
            libusb_submit_transfer(transfers_[i]);
        }
    }

    LOGI("音频引擎启动成功，等时传输流水线正常运转");
    return 0;
}

void AudioEngine::event_thread_func() {
    LOGI("启动 Native URB 调度线程，提升至实时调度等级...");
    // 💡 提升当前线程优先级至最高 (-19) 规避丢包
    setpriority(PRIO_PROCESS, 0, -19);

    struct timeval tv { 0, 10000 }; // 10ms 超时
    while (is_running_.load(std::memory_order_relaxed) && ctx_) {
        int r = libusb_handle_events_timeout_completed(ctx_, &tv, nullptr);
        if (r < 0 && r != LIBUSB_ERROR_INTERRUPTED) {
            LOGW("libusb_handle_events 警告: %d", r);
        }
    }
    LOGI("Native URB 调度线程退出");
}

void LIBUSB_CALL AudioEngine::isoc_transfer_cb(struct libusb_transfer* transfer) {
    if (!transfer) return;
    auto* engine = static_cast<AudioEngine*>(transfer->user_data);
    if (engine) {
        engine->process_isoc_transfer(transfer);
    }
}

void AudioEngine::process_isoc_transfer(struct libusb_transfer* transfer) {
    if (!is_running_.load(std::memory_order_relaxed)) {
        return;
    }

    const bool paused = is_paused_.load(std::memory_order_relaxed);
    uint8_t* base_buf = transfer->buffer;

    // 临时输入缓冲区 (存放原始 16-bit PCM)
    static thread_local std::vector<uint8_t> temp_pcm16;
    const size_t samples_per_pkt = packet_size_ / (current_channels_ * endpoint_info_.subslot_size);
    const size_t raw16_pkt_bytes = samples_per_pkt * current_channels_ * sizeof(int16_t);
    temp_pcm16.resize(raw16_pkt_bytes);

    for (int i = 0; i < transfer->num_iso_packets; ++i) {
        struct libusb_iso_packet_descriptor* pkt = &transfer->iso_packet_desc[i];
        pkt->length = static_cast<unsigned int>(packet_size_);
        uint8_t* pkt_buf = base_buf + (i * packet_size_);

        if (paused || !ring_buffer_ || ring_buffer_->available_read() < raw16_pkt_bytes) {
            // 💡 暂停或下溢时填充静音帧，保持等时时钟锁相不丢步
            std::memset(pkt_buf, 0, packet_size_);
        } else {
            // 从无锁环形缓冲区读取纯 PCM 数据
            size_t bytes_read = ring_buffer_->read(temp_pcm16.data(), raw16_pkt_bytes);
            if (bytes_read == raw16_pkt_bytes) {
                // 💡 执行低位补零与高位对齐 (Bit-Perfect LSB Zero-Padding)
                PcmFormatter::format_16bit_to_target(
                    temp_pcm16.data(),
                    bytes_read,
                    pkt_buf,
                    current_output_format_
                );
            } else {
                std::memset(pkt_buf, 0, packet_size_);
            }
        }
    }

    // 重新提交该 URB
    int r = libusb_submit_transfer(transfer);
    if (r != LIBUSB_SUCCESS && is_running_.load(std::memory_order_relaxed)) {
        LOGW("重新提交等时 URB 失败: %d", r);
    }
}

int AudioEngine::write_pcm(const uint8_t* data, size_t size) {
    if (!ring_buffer_ || !is_running_.load(std::memory_order_relaxed)) {
        return -1;
    }
    return static_cast<int>(ring_buffer_->write(data, size));
}

int AudioEngine::pause_stream() {
    is_paused_.store(true, std::memory_order_release);
    LOGI("音频流等时传输已暂停 (持续推送静音保活帧)");
    return 0;
}

int AudioEngine::resume_stream() {
    is_paused_.store(false, std::memory_order_release);
    LOGI("音频流等时传输已恢复");
    return 0;
}

int AudioEngine::stop_stream() {
    LOGI("停止音频流...");
    if (ring_buffer_) {
        ring_buffer_->clear();
    }
    is_paused_.store(true, std::memory_order_release);
    return 0;
}

int AudioEngine::release_device() {
    std::lock_guard<std::mutex> lock(op_mutex_);
    LOGI("开始执行声卡归还与内核驱动重绑时序...");

    // 💡 严格遵循时序规范：
    // (1) 终止 URB 传输并回收 URB
    is_running_.store(false, std::memory_order_release);

    for (auto* xfer : transfers_) {
        if (xfer) {
            libusb_cancel_transfer(xfer);
        }
    }

    if (event_thread_.joinable()) {
        event_thread_.join();
    }

    for (auto* xfer : transfers_) {
        if (xfer) {
            libusb_free_transfer(xfer);
        }
    }
    transfers_.clear();
    transfer_buffers_.clear();

    if (dev_handle_) {
        // (2) 释放接口独占
        if (is_claimed_.load()) {
            libusb_release_interface(dev_handle_, endpoint_info_.interface_number);
            is_claimed_.store(false);
        }

        // (3) 💡 重新绑定 Linux 内核驱动 snd-usb-audio (触发底层 USBDEVFS_CONNECT ioctl)
        LOGI("重新向 Linux 内核挂载 snd-usb-audio 驱动 (USBDEVFS_CONNECT)...");
        int r = libusb_attach_kernel_driver(dev_handle_, endpoint_info_.interface_number);
        if (r != LIBUSB_SUCCESS) {
            LOGW("libusb_attach_kernel_driver 结果: %d (声卡可能已处于内核接管态)", r);
        }

        // (4) 关闭 libusb 句柄 (关闭后 Java 层的 UsbDeviceConnection 才能安全 close)
        libusb_close(dev_handle_);
        dev_handle_ = nullptr;
    }

    ring_buffer_.reset();
    current_fd_ = -1;
    LOGI("声卡硬件独占已完全解除，成功归还给电视系统");
    return 0;
}

bool AudioEngine::is_streaming() const {
    return is_running_.load(std::memory_order_relaxed) && !is_paused_.load(std::memory_order_relaxed);
}

size_t AudioEngine::get_buffer_available() const {
    return ring_buffer_ ? ring_buffer_->available_write() : 0;
}

size_t AudioEngine::get_buffer_capacity() const {
    return ring_buffer_ ? ring_buffer_->capacity() : 0;
}

} // namespace purecast
