#include "audio_engine.h"
#include <sys/resource.h>
#include <sys/mman.h>
#include <unistd.h>
#include <cmath>
#include <cerrno>
#include <cstring>

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
    // 如果源文件是 16-bit，优先匹配 16-bit Alt 1；如果是 24-bit，优先匹配 24-bit Alt 2。
    // 不改变源文件格式以保持最佳 Bit-Perfect 音质。
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
        // bmRequestType: 0x21 (Host to Interface, Class-specific)
        // bRequest: 0x01 (CS_CUR / SET_CUR)
        // wValue: (CS_SAM_FREQ_CONTROL << 8) -> 0x0100
        // wIndex: (clock_id << 8) | (control_interface_number & 0xFF)
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
        } else {
            LOGI("UAC2 时钟设置成功: %d Hz (clock_id=%u, iface=%d)",
                 sample_rate, endpoint_info_.clock_id, endpoint_info_.control_interface_number);
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

int AudioEngine::lock_hardware_volume(uint8_t fu_id) {
    if (!dev_handle_ || fu_id == 0) return 0;

    LOGI("开始锁定 UAC Feature Unit (id=%u) 硬件音量为 0.0dB (Unity Gain)...", fu_id);

    const uint8_t req_type = static_cast<uint8_t>(LIBUSB_REQUEST_TYPE_CLASS) | static_cast<uint8_t>(LIBUSB_RECIPIENT_INTERFACE);
    const uint8_t bRequest = endpoint_info_.is_uac2 ? uac::uac2::CS_CUR : uac::uac1::SET_CUR;
    const uint16_t wIndex = (static_cast<uint16_t>(fu_id) << 8) | (endpoint_info_.control_interface_number & 0xFF);

    // 💡 0x0000 对应 0.0 dB (Unity Gain 100% 满幅输出，每 LSB 对应 1/256 dB)
    uint8_t vol_data[2] = {0x00, 0x00};
    bool any_success = false;

    // 💡 遍历 Channel 0 (Master), Channel 1 (Left), Channel 2 (Right)
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
            // 部分声卡仅支持 Master(0) 或仅支持分声道(1/2)，返回 STALL/PIPE 属于标准内的预期表现
            LOGD("UAC Feature Unit (id=%u, ch=%u) 音量设置响应: %d", fu_id, ch, r);
        }

        // 💡 尝试同时发送 FU_MUTE_CONTROL 设为 0 (解静音) 确保声音畅通
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

int AudioEngine::open_device(int fd, int sample_rate, int bit_depth, int channels) {
    std::lock_guard<std::recursive_mutex> lock(op_mutex_);
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
    if (!parse_audio_descriptors(dev, bit_depth)) {
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

    if (current_bit_depth_ == 24) {
        LOGI("24-bit 输入格式化就绪, 采样率: %d Hz, 声道数: %d, 目标物理子帧: %s",
             sample_rate, channels,
             (current_output_format_ == PcmFormatter::OutputFormat::S32_LE ? "S32_LE" :
              (current_output_format_ == PcmFormatter::OutputFormat::S24_3LE ? "S24_3LE" : "S16_LE")));
    } else {
        LOGI("16-bit 输入格式化就绪, 采样率: %d Hz, 声道数: %d, 目标物理子帧: %s",
             sample_rate, channels,
             (current_output_format_ == PcmFormatter::OutputFormat::S32_LE ? "S32_LE" :
              (current_output_format_ == PcmFormatter::OutputFormat::S24_3LE ? "S24_3LE" : "S16_LE")));
    }

    // 💡 步骤 1: 卸载 Linux 内核驱动 snd-usb-audio，实现全链路接口独占
    if (endpoint_info_.control_interface_number >= 0 &&
        endpoint_info_.control_interface_number != endpoint_info_.interface_number) {
        if (libusb_kernel_driver_active(dev_handle_, endpoint_info_.control_interface_number) == 1) {
            libusb_detach_kernel_driver(dev_handle_, endpoint_info_.control_interface_number);
        }
        libusb_claim_interface(dev_handle_, endpoint_info_.control_interface_number);
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
        // 💡 失败退出前尝试重新挂载 Linux 内核驱动，避免电视声卡永久哑音
        libusb_attach_kernel_driver(dev_handle_, endpoint_info_.interface_number);
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
        // 💡 失败退出前重新绑定 Linux 内核驱动
        libusb_attach_kernel_driver(dev_handle_, endpoint_info_.interface_number);
        libusb_close(dev_handle_);
        dev_handle_ = nullptr;
        is_claimed_.store(false);
        return r;
    }

    // 💡 步骤 4: 锁定硬件时钟采样率与 UAC Feature Unit 音量 (0.0dB Unity Gain)
    configure_clock(sample_rate);
    if (endpoint_info_.feature_unit_id != 0) {
        lock_hardware_volume(endpoint_info_.feature_unit_id);
    } else {
        LOGI("未发现 UAC Feature Unit 实体，跳过硬件音量锁定");
    }

    // 💡 步骤 5: 构建无锁环形缓冲区 (默认 1MB，支持 ~5 秒无损缓冲)
    ring_buffer_ = std::make_unique<SpscRingBuffer>(1024 * 1024);

    // 💡 步骤 5.2: 配置 50ms DAC Warmup 静音预充 (保护继电器免受切频瞬间物理爆音冲击)
    // High-Speed (125us 微帧): 每秒 8000 microframes，50ms 对应 400 个微帧
    packet_rate_ = 8000;
    const int warmup_packets = static_cast<int>((packet_rate_ * 50) / 1000);
    warmup_packets_remaining_.store(warmup_packets, std::memory_order_release);
    LOGI("配置 DAC Warmup 静音预充: %d 个微帧数据包 (约 50ms)", warmup_packets);

    // 💡 步骤 6: 配置 Bresenham 时钟步进 (解决 44.1kHz 分数包采样率与 USB High-Speed 8000微帧/秒同步)
    base_samples_per_packet_ = static_cast<uint32_t>(sample_rate / packet_rate_);
    remainder_samples_ = static_cast<uint32_t>(sample_rate % packet_rate_);
    bresenham_accum_ = 0;
    phase_accumulator_ = 0;
    // 💡 预设初始反馈定点数为标称值，开局立即进入精准微帧同步，杜绝初始浪涌
    const uint32_t nominal_fb = static_cast<uint32_t>(((uint64_t)sample_rate << 16) / packet_rate_);
    feedback_rate_q16_.store(nominal_fb, std::memory_order_release);

    // 分配单个 URB packet 的最大缓冲区 (base_samples + 2)
    const size_t max_samples_per_pkt = base_samples_per_packet_ + 2;
    packet_size_ = max_samples_per_pkt * current_channels_ * endpoint_info_.subslot_size;
    if (packet_size_ > endpoint_info_.max_packet_size && endpoint_info_.max_packet_size > 0) {
        packet_size_ = endpoint_info_.max_packet_size;
    }
    if (packet_size_ == 0) packet_size_ = 512;

    LOGI("配置 Bresenham 时钟步进: rate=%d, base_samples=%u, rem=%u, packet_rate=%u, max_pkt_size=%zu",
         sample_rate, base_samples_per_packet_, remainder_samples_, packet_rate_, packet_size_);

    // 💡 步骤 6: 预分配等时 URB 池与传输线程
    is_running_.store(true);
    is_paused_.store(false);
    has_pre_rolled_.store(false);
    feedback_locked_.store(false);

    transfers_.resize(NUM_TRANSFERS, nullptr);
    transfer_buffers_.resize(NUM_TRANSFERS);

    const size_t transfer_buf_size = packet_size_ * PACKETS_PER_TRANSFER;
    for (int i = 0; i < NUM_TRANSFERS; ++i) {
        transfer_buffers_[i].resize(transfer_buf_size, 0);
        // 💡 锁定等时传输 URB 物理内存页，杜绝换页抖动 (Page Fault)
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
            1000 // 超时 1000ms
        );

        transfers_[i] = xfer;
        // 💡 预初始化填充静音与紧凑连续数据包长度
        fill_isoc_transfer(xfer);
    }

    // 💡 步骤 6.2: 预分配 UAC 显式时钟反馈 (Explicit Feedback) IN URBs
    if (endpoint_info_.feedback_ep != 0) {
        feedback_transfers_.resize(NUM_FEEDBACK_TRANSFERS, nullptr);
        feedback_buffers_.resize(NUM_FEEDBACK_TRANSFERS);
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

    LOGI("RingBuffer 与 URB 物理内存页已执行 mlock 锁定 (RingBuffer: %zu bytes, OUT URB: %zu bytes, FB URB: %zu bytes)",
         ring_buffer_->capacity(),
         transfer_buf_size * NUM_TRANSFERS,
         feedback_buffers_.empty() ? 0 : (feedback_buffers_[0].size() * NUM_FEEDBACK_TRANSFERS));

    // 启动 libusb 事件循环线程
    event_thread_ = std::thread(&AudioEngine::event_thread_func, this);

    // 💡 提交首批反馈 URBs 开启时钟闭环监听
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

    // 💡 提交首批 OUT URBs (预充静音 Warmup 防爆音)
    for (int i = 0; i < NUM_TRANSFERS; ++i) {
        if (transfers_[i]) {
            int r = libusb_submit_transfer(transfers_[i]);
            if (r == LIBUSB_SUCCESS) {
                active_transfers_.fetch_add(1, std::memory_order_acq_rel);
            } else {
                LOGE("提交初始等时 OUT URB[%d] 失败: %d", i, r);
            }
        }
    }

    LOGI("音频引擎启动成功，等时传输流水线正常运转 (Explicit Feedback: %s)",
         endpoint_info_.feedback_ep != 0 ? "ENABLED" : "OPEN-LOOP");
    return 0;
}

void AudioEngine::event_thread_func() {
    LOGI("启动 Native URB 调度线程，提升至实时调度等级...");
    // 💡 提升当前线程优先级至最高 (-19) 规避丢包
    errno = 0;
    int prio_res = setpriority(PRIO_PROCESS, 0, -19);
    if (prio_res == 0) {
        LOGI("Native URB 调度线程已成功提升至 Linux 实时音频优先级 (nice -19)");
    } else {
        LOGW("提升线程优先级失败 (errno=%d: %s)，以默认系统优先级降级运行", errno, strerror(errno));
    }

    struct timeval tv { 0, 10000 }; // 10ms 超时
    // 💡 保持事件泵运行直到主动停止且所有在途 URB 的取消/完成回调均已被内核投递完成
    while ((is_running_.load(std::memory_order_relaxed) || active_transfers_.load(std::memory_order_relaxed) > 0) && ctx_) {
        int r = libusb_handle_events_timeout_completed(ctx_, &tv, nullptr);
        if (r < 0 && r != LIBUSB_ERROR_INTERRUPTED) {
            LOGW("libusb_handle_events 警告: %d", r);
        }
    }
    LOGI("Native URB 调度线程退出 (剩余在途 URB: %d)", active_transfers_.load(std::memory_order_relaxed));
}

void LIBUSB_CALL AudioEngine::isoc_transfer_cb(struct libusb_transfer* transfer) {
    if (!transfer) return;
    auto* engine = static_cast<AudioEngine*>(transfer->user_data);
    if (engine) {
        engine->process_isoc_transfer(transfer);
    }
}

void AudioEngine::fill_isoc_transfer(struct libusb_transfer* transfer) {
    const bool paused = is_paused_.load(std::memory_order_relaxed);
    uint8_t* pkt_buf = transfer->buffer;
    size_t total_transfer_len = 0;

    // 💡 动态根据输入位深确定单采样字节数 (16-bit: 2 bytes, 24-bit: 3 bytes)
    const size_t input_bytes_per_sample = (current_bit_depth_ > 0) ? static_cast<size_t>(current_bit_depth_ / 8) : 2;

    // 💡 预留预充缓冲区门限 (100ms) 彻底避免网络抖动微下溢
    const size_t pre_roll_threshold = (current_sample_rate_ * current_channels_ * input_bytes_per_sample) / 10;
    if (!has_pre_rolled_.load(std::memory_order_relaxed)) {
        if (ring_buffer_ && ring_buffer_->available_read() >= pre_roll_threshold) {
            has_pre_rolled_.store(true, std::memory_order_release);
            LOGI("环形缓冲区预充就绪 (已缓冲 >= 100ms PCM: %zu 字节)，开启平滑输出", ring_buffer_->available_read());
        }
    }
    const bool ready_to_play = has_pre_rolled_.load(std::memory_order_relaxed);

    // 临时输入缓冲区 (存放从 RingBuffer 提取的原始输入 PCM)
    static thread_local std::vector<uint8_t> temp_pcm_in;

    for (int i = 0; i < transfer->num_iso_packets; ++i) {
        struct libusb_iso_packet_descriptor* pkt = &transfer->iso_packet_desc[i];

        // 💡 步进计算：优先采用 UAC2 显式时钟反馈闭环伺服，后备开环 Bresenham
        size_t samples_this_pkt = 0;
        const uint32_t fb_rate = feedback_rate_q16_.load(std::memory_order_relaxed);

        if (fb_rate > 0) {
            // 💡 闭环模式：Q16.16 瞬时微帧采样率相位累加
            // fb_rate 为 DAC 硬件晶振汇报的 Q16.16 定点数 (例如 44.1kHz 下标称 0x00058333 = 5.5125 采样/微帧)
            phase_accumulator_ += fb_rate;
            samples_this_pkt = phase_accumulator_ >> 16;
            phase_accumulator_ &= 0xFFFF; // 保留小数余量
        } else {
            // 💡 开环模式：Bresenham 时钟步进计算当前微帧所需采样点数 (如 44.1kHz 时交替 5 与 6 采样点)
            samples_this_pkt = base_samples_per_packet_;
            bresenham_accum_ += remainder_samples_;
            if (bresenham_accum_ >= packet_rate_) {
                bresenham_accum_ -= packet_rate_;
                samples_this_pkt += 1;
            }
        }

        // 边界防护：限制在单微帧最大安全容量内
        const size_t max_allowed_samples = (endpoint_info_.subslot_size > 0 && current_channels_ > 0) ?
            (packet_size_ / (current_channels_ * endpoint_info_.subslot_size)) : 8;
        if (samples_this_pkt > max_allowed_samples && max_allowed_samples > 0) {
            samples_this_pkt = max_allowed_samples;
        }

        const size_t target_pkt_bytes = samples_this_pkt * current_channels_ * endpoint_info_.subslot_size;
        const size_t raw_input_bytes = samples_this_pkt * current_channels_ * input_bytes_per_sample;
        pkt->length = static_cast<unsigned int>(target_pkt_bytes);

        // 💡 优先判断 DAC Warmup 静音预充：开流或切频首 50ms 注入全零，保护继电器免受爆音冲击
        const int remaining_warmup = warmup_packets_remaining_.load(std::memory_order_relaxed);
        if (remaining_warmup > 0) {
            if (warmup_packets_remaining_.fetch_sub(1, std::memory_order_relaxed) == 1) {
                LOGI("DAC Warmup 50ms 静音预充完成，开始正常音频调度");
            }
            std::memset(pkt_buf, 0, target_pkt_bytes);
        } else if (paused || !ready_to_play || !ring_buffer_ || ring_buffer_->available_read() < raw_input_bytes) {
            // 💡 暂停、预充中或下溢时填充静音帧，保持等时时钟锁相不丢步
            std::memset(pkt_buf, 0, target_pkt_bytes);
            static int consecutive_underruns = 0;
            if (ready_to_play && ring_buffer_ && ring_buffer_->available_read() < raw_input_bytes) {
                consecutive_underruns++;
                // 💡 软抗抖策略：偶发微下溢仅填补单微帧静音垫步保活，避免人为制造 100ms 停顿跳跃；
                // 仅当连续 50 个微帧 (~50ms) 持续读空时，才判定为网络断流并重新触发 100ms 蓄水保护
                if (consecutive_underruns >= 50) {
                    has_pre_rolled_.store(false, std::memory_order_release);
                    consecutive_underruns = 0;
                    LOGW("环形缓冲区持续下溢超 50ms，重新进入 100ms 预充保护");
                }
            } else {
                consecutive_underruns = 0;
            }
        } else {
            temp_pcm_in.resize(raw_input_bytes);
            // 从无锁环形缓冲区读取纯 PCM 数据
            size_t bytes_read = ring_buffer_->read(temp_pcm_in.data(), raw_input_bytes);
            if (bytes_read == raw_input_bytes) {
                // 💡 根据当前输入位深动态分流 (16-bit vs 24-bit)，并执行高位对齐与低位补零 (Bit-Perfect LSB Zero-Padding)
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

        // 💡 严格遵循 Linux usbfs / libusb 规范：数据包在 transfer->buffer 内紧凑无缝连续排布
        pkt_buf += target_pkt_bytes;
        total_transfer_len += target_pkt_bytes;
    }

    transfer->length = static_cast<int>(total_transfer_len);
}

void AudioEngine::process_isoc_transfer(struct libusb_transfer* transfer) {
    if (!is_running_.load(std::memory_order_relaxed) ||
        transfer->status == LIBUSB_TRANSFER_CANCELLED ||
        transfer->status == LIBUSB_TRANSFER_NO_DEVICE) {
        active_transfers_.fetch_sub(1, std::memory_order_acq_rel);
        return;
    }

    if (transfer->status != LIBUSB_TRANSFER_COMPLETED) {
        static int transfer_err_count = 0;
        if (transfer_err_count++ % 100 == 0) {
            LOGW("等时传输 URB 状态异常: status=%d", transfer->status);
        }
    }

    fill_isoc_transfer(transfer);

    // 重新提交该 URB
    int r = libusb_submit_transfer(transfer);
    if (r != LIBUSB_SUCCESS) {
        if (is_running_.load(std::memory_order_relaxed)) {
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
    if (!is_running_.load(std::memory_order_relaxed) ||
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
                    // UAC1 10.14 格式 (Full Speed, 1ms 帧)
                    const uint32_t val10_14 = static_cast<uint32_t>(buf[0]) |
                                              (static_cast<uint32_t>(buf[1]) << 8) |
                                              (static_cast<uint32_t>(buf[2]) << 16);
                    raw_val = val10_14 << 2;
                } else {
                    // UAC2 16.16 格式 (High Speed Q16.16 125us 微帧)
                    raw_val = static_cast<uint32_t>(buf[0]) |
                              (static_cast<uint32_t>(buf[1]) << 8) |
                              (static_cast<uint32_t>(buf[2]) << 16) |
                              (static_cast<uint32_t>(buf[3]) << 24);
                }

                // 💡 验证合法范围 (允许标称频率 ±10% 物理晶振范围，过滤非法干扰包)
                const uint64_t nominal_q16 = (static_cast<uint64_t>(current_sample_rate_) << 16) / packet_rate_;
                const uint64_t min_q16 = nominal_q16 * 90 / 100;
                const uint64_t max_q16 = nominal_q16 * 110 / 100;

                if (raw_val >= min_q16 && raw_val <= max_q16) {
                    if (!feedback_locked_.load(std::memory_order_relaxed)) {
                        feedback_locked_.store(true, std::memory_order_release);
                        LOGI("🎉 成功锁定 UAC2 Explicit Feedback 物理时钟: raw=0x%08X (%.4f 采样/微帧, 目标基准: 0x%08llX)",
                             raw_val, static_cast<double>(raw_val) / 65536.0, static_cast<unsigned long long>(nominal_q16));
                    }
                    // 💡 实时精准跟随外部 DAC 晶振反馈伺服步进，杜绝相位滞后引发的 Overrun 溢出
                    feedback_rate_q16_.store(raw_val, std::memory_order_release);
                } else {
                    static int err_count = 0;
                    if (err_count++ % 1000 == 0) {
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

    // 重置接收包长并重新提交
    const size_t fb_pkt_size = std::max<size_t>(
        endpoint_info_.feedback_max_packet_size > 0 ? endpoint_info_.feedback_max_packet_size : 4,
        8
    );
    transfer->iso_packet_desc[0].length = static_cast<unsigned int>(fb_pkt_size);
    transfer->length = static_cast<int>(fb_pkt_size);

    int r = libusb_submit_transfer(transfer);
    if (r != LIBUSB_SUCCESS) {
        if (is_running_.load(std::memory_order_relaxed)) {
            LOGW("重新提交反馈 URB 失败: %d", r);
        }
        active_transfers_.fetch_sub(1, std::memory_order_acq_rel);
    }
}

int AudioEngine::write_pcm(const uint8_t* data, size_t size) {
    if (!ring_buffer_ || !is_running_.load(std::memory_order_relaxed) || !data || size == 0) {
        return -1;
    }
    const size_t available = ring_buffer_->available_write();
    // 💡 保护原则：剩余空间不足以写入整包时执行整包丢弃 (Drop Whole Packet)，绝不截断半帧拼接破坏连续声波
    if (available < size) {
        static int overrun_log = 0;
        if (overrun_log++ % 100 == 0) {
            LOGW("环形缓冲区高水位饱和 (可用: %zu < 需求: %zu)，整包丢弃以消除波形断裂爆音", available, size);
        }
        return 0;
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
    has_pre_rolled_.store(false, std::memory_order_release);
    feedback_locked_.store(false, std::memory_order_release);
    warmup_packets_remaining_.store(0, std::memory_order_release);
    if (ring_buffer_) {
        ring_buffer_->clear();
    }
    is_paused_.store(true, std::memory_order_release);
    return 0;
}

void AudioEngine::flush_buffer() {
    std::lock_guard<std::recursive_mutex> lock(op_mutex_);
    LOGI("AudioEngine 刷新环形缓冲区并重置预充门限 (FLUSH/Seek)");
    if (ring_buffer_) {
        ring_buffer_->clear();
    }
    has_pre_rolled_.store(false, std::memory_order_release);
    phase_accumulator_ = 0;
    bresenham_accum_ = 0;
}

int AudioEngine::release_device() {
    std::lock_guard<std::recursive_mutex> lock(op_mutex_);
    LOGI("开始执行声卡归还与内核驱动重绑时序...");

    // 💡 严格遵循时序规范：
    // (1) 终止 URB 传输并回收 URB
    is_running_.store(false, std::memory_order_release);
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
    LOGI("所有在途等时传输 URB 已完成注销与内核回收 (active=%d)，执行物理内存安全释放...",
         active_transfers_.load(std::memory_order_relaxed));
    active_transfers_.store(0, std::memory_order_release);

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

    // 💡 解锁并清空 URB 物理内存
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

    if (dev_handle_) {
        // (2) 释放接口独占
        if (is_claimed_.load()) {
            libusb_release_interface(dev_handle_, endpoint_info_.interface_number);
            if (endpoint_info_.control_interface_number >= 0 &&
                endpoint_info_.control_interface_number != endpoint_info_.interface_number) {
                libusb_release_interface(dev_handle_, endpoint_info_.control_interface_number);
            }
            is_claimed_.store(false);
        }

        // (3) 💡 重新绑定 Linux 内核驱动 snd-usb-audio (触发底层 USBDEVFS_CONNECT ioctl)
        LOGI("重新向 Linux 内核挂载 snd-usb-audio 驱动 (USBDEVFS_CONNECT)...");
        if (endpoint_info_.control_interface_number >= 0 &&
            endpoint_info_.control_interface_number != endpoint_info_.interface_number) {
            libusb_attach_kernel_driver(dev_handle_, endpoint_info_.control_interface_number);
        }
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

size_t AudioEngine::get_available_read() const {
    return ring_buffer_ ? ring_buffer_->available_read() : 0;
}

} // namespace purecast
