#include "libusb.h"
#include <algorithm>
#include <cstdarg>
#include <cstdlib>
#include <thread>

namespace purecast::test {

namespace {
uint64_t now_ns() {
    return static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch()
        ).count()
    );
}
} // namespace

MockLibusbController& MockLibusbController::instance() {
    static MockLibusbController inst;
    return inst;
}

void MockLibusbController::reset() {
    std::lock_guard<std::mutex> lock(mutex_);
    for (int i = 0; i < 4; ++i) {
        kernel_driver_attached_[i] = true;
        interface_claimed_[i] = false;
        alt_setting_[i] = 0;
    }
    configured_sample_rate_ = 0;
    isoc_completion_paused_.store(false);
    fail_next_alt_setting_.store(0);
    fail_next_clock_control_.store(0);
    fail_next_submit_.store(0);
    fail_all_submits_.store(0);
    feedback_rate_override_.store(0);
    in_pump_callback_ = false;
    capture_enabled_.store(false);
    captured_out_bytes_.clear();
    last_out_urb_all_zero_.store(true);
    last_non_zero_out_urb_ns_.store(0);
    first_zero_out_urb_ns_.store(0);
    zero_capture_since_ns_.store(0);
    completed_out_urbs_.store(0);
    pending_.clear();
}

void MockLibusbController::set_isoc_completion_paused(bool paused) {
    {
        std::unique_lock<std::mutex> lock(mutex_);
        isoc_completion_paused_.store(paused, std::memory_order_release);
        if (paused) {
            // 💡 确定性屏障：等当前正在分发 URB 回调的 pump_events 批次完全收尾后再返回
            cv_.wait(lock, [this]() { return !in_pump_callback_; });
        }
    }
    cv_.notify_all();
}

void MockLibusbController::set_fail_next_alt_setting(int error_code) {
    fail_next_alt_setting_.store(error_code);
}

void MockLibusbController::set_fail_next_clock_control(int error_code) {
    fail_next_clock_control_.store(error_code);
}

void MockLibusbController::set_fail_next_submit(int error_code) {
    fail_next_submit_.store(error_code);
}

void MockLibusbController::set_fail_all_submits(int error_code) {
    fail_all_submits_.store(error_code, std::memory_order_release);
}

void MockLibusbController::set_feedback_rate_override(uint32_t q16_rate) {
    feedback_rate_override_.store(q16_rate);
}

bool MockLibusbController::is_kernel_driver_attached(int iface) const {
    std::lock_guard<std::mutex> lock(mutex_);
    return (iface >= 0 && iface < 4) ? kernel_driver_attached_[iface] : false;
}

bool MockLibusbController::is_interface_claimed(int iface) const {
    std::lock_guard<std::mutex> lock(mutex_);
    return (iface >= 0 && iface < 4) ? interface_claimed_[iface] : false;
}

int MockLibusbController::get_alt_setting(int iface) const {
    std::lock_guard<std::mutex> lock(mutex_);
    return (iface >= 0 && iface < 4) ? alt_setting_[iface] : -1;
}

int MockLibusbController::get_configured_sample_rate() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return configured_sample_rate_;
}

int MockLibusbController::get_in_flight_count() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return static_cast<int>(pending_.size());
}

uint64_t MockLibusbController::get_completed_out_urbs() const {
    return completed_out_urbs_.load(std::memory_order_acquire);
}

void MockLibusbController::set_capture_enabled(bool enabled) {
    std::lock_guard<std::mutex> lock(mutex_);
    capture_enabled_.store(enabled, std::memory_order_release);
}

void MockLibusbController::clear_captured_out_bytes() {
    std::lock_guard<std::mutex> lock(mutex_);
    captured_out_bytes_.clear();
}

std::vector<uint8_t> MockLibusbController::take_captured_out_bytes() {
    std::lock_guard<std::mutex> lock(mutex_);
    std::vector<uint8_t> out;
    out.swap(captured_out_bytes_);
    return out;
}

bool MockLibusbController::last_out_urb_was_all_zero() const {
    return last_out_urb_all_zero_.load(std::memory_order_acquire);
}

uint64_t MockLibusbController::get_last_non_zero_out_urb_time_ns() const {
    return last_non_zero_out_urb_ns_.load(std::memory_order_acquire);
}

uint64_t MockLibusbController::get_first_zero_out_urb_after_ns(uint64_t since_ns) const {
    uint64_t val = first_zero_out_urb_ns_.load(std::memory_order_acquire);
    return (val >= since_ns) ? val : 0;
}

void MockLibusbController::on_submit(struct libusb_transfer* xfer) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        pending_.push_back({xfer, false});
    }
    cv_.notify_all();
}

void MockLibusbController::on_cancel(struct libusb_transfer* xfer) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        for (auto& p : pending_) {
            if (p.xfer == xfer) {
                p.cancelled = true;
            }
        }
    }
    cv_.notify_all();
}

void MockLibusbController::pump_events() {
    std::vector<PendingTransfer> ready;
    {
        std::unique_lock<std::mutex> lock(mutex_);
        if (pending_.empty()) {
            cv_.wait_for(lock, std::chrono::milliseconds(1));
        }

        const bool paused = isoc_completion_paused_.load(std::memory_order_acquire);
        auto it = pending_.begin();
        while (it != pending_.end()) {
            // 取消的 URB 无论是否暂停回调都要立刻返回 CANCELLED 以避免 release_device 阻塞
            if (it->cancelled || !paused) {
                ready.push_back(*it);
                it = pending_.erase(it);
            } else {
                ++it;
            }
        }
        if (!ready.empty()) {
            in_pump_callback_ = true;
        }
    }

    if (ready.empty()) {
        std::this_thread::sleep_for(std::chrono::microseconds(250));
        return;
    }

    // 模拟真实等时微帧 ~1ms 节奏（仅当存在正常完成 URB 时短暂睡眠，避免自旋跑空 CPU）
    bool has_normal = false;
    for (const auto& item : ready) {
        if (!item.cancelled) {
            has_normal = true;
            break;
        }
    }
    if (has_normal) {
        std::this_thread::sleep_for(std::chrono::microseconds(500));
        // 睡眠期间若外部刚确立了 pause 屏障，把非取消 URB 放回 pending_ 避免泄漏回调
        if (isoc_completion_paused_.load(std::memory_order_acquire)) {
            std::vector<PendingTransfer> cancelled_only;
            std::lock_guard<std::mutex> lock(mutex_);
            for (const auto& item : ready) {
                if (item.cancelled) {
                    cancelled_only.push_back(item);
                } else {
                    pending_.push_back(item);
                }
            }
            ready.swap(cancelled_only);
        }
    }

    for (auto& item : ready) {
        struct libusb_transfer* xfer = item.xfer;
        if (!xfer) continue;

        if (item.cancelled) {
            xfer->status = LIBUSB_TRANSFER_CANCELLED;
            if (xfer->callback) {
                xfer->callback(xfer);
            }
            continue;
        }

        const bool is_in = (xfer->endpoint & 0x80) != 0;
        xfer->status = LIBUSB_TRANSFER_COMPLETED;

        if (is_in) {
            // 显式 Feedback IN 端点：填入 Q16.16 采样率
            uint32_t rate_q16 = feedback_rate_override_.load(std::memory_order_acquire);
            if (rate_q16 == 0) {
                int sr = get_configured_sample_rate();
                if (sr <= 0) sr = 44100;
                rate_q16 = static_cast<uint32_t>((static_cast<uint64_t>(sr) << 16) / 8000ULL);
            }
            if (xfer->num_iso_packets > 0 && xfer->buffer && xfer->length >= 4) {
                xfer->buffer[0] = static_cast<uint8_t>(rate_q16 & 0xFF);
                xfer->buffer[1] = static_cast<uint8_t>((rate_q16 >> 8) & 0xFF);
                xfer->buffer[2] = static_cast<uint8_t>((rate_q16 >> 16) & 0xFF);
                xfer->buffer[3] = static_cast<uint8_t>((rate_q16 >> 24) & 0xFF);
                xfer->iso_packet_desc[0].status = LIBUSB_TRANSFER_COMPLETED;
                xfer->iso_packet_desc[0].actual_length = 4;
            }
        } else {
            // OUT 端点：检验刚发送给 DAC 的缓冲数据是否全 0，并在开启采样捕获时记录
            const size_t len = static_cast<size_t>(std::max(0, xfer->length));
            bool all_zero = true;
            for (size_t i = 0; i < len; ++i) {
                if (xfer->buffer[i] != 0) {
                    all_zero = false;
                    break;
                }
            }
            const uint64_t ts = now_ns();
            last_out_urb_all_zero_.store(all_zero, std::memory_order_release);
            if (!all_zero) {
                last_non_zero_out_urb_ns_.store(ts, std::memory_order_release);
                first_zero_out_urb_ns_.store(0, std::memory_order_release);
            } else {
                uint64_t expected = 0;
                first_zero_out_urb_ns_.compare_exchange_strong(expected, ts, std::memory_order_acq_rel);
            }
            if (capture_enabled_.load(std::memory_order_acquire) && xfer->buffer && len > 0) {
                std::lock_guard<std::mutex> lock(mutex_);
                captured_out_bytes_.insert(captured_out_bytes_.end(), xfer->buffer, xfer->buffer + len);
            }
            completed_out_urbs_.fetch_add(1, std::memory_order_release);
        }

        if (xfer->callback) {
            xfer->callback(xfer);
        }
    }

    {
        std::lock_guard<std::mutex> lock(mutex_);
        in_pump_callback_ = false;
    }
    cv_.notify_all();
}

} // namespace purecast::test

namespace {

struct MockContext { int dummy{1}; };
struct MockDevice { int dummy{1}; };
struct MockDeviceHandle { MockDevice dev; int fd{-1}; };

// 构造接近 Sony NW-ZX300A 的 UAC2 描述符树：
// - Interface 0 (AudioControl): Clock Source ID=3, Feature Unit ID=5
// - Interface 1 (AudioStreaming):
//   - Alt 0: 零带宽
//   - Alt 1: 16-bit (subslot=2, bitRes=16), OUT EP 0x01, Feedback IN EP 0x81
//   - Alt 2: 24-bit (subslot=4, bitRes=24), OUT EP 0x01, Feedback IN EP 0x81
const unsigned char kAcExtra[] = {
    // UAC2 Clock Source (type=0x24, subtype=0x0A, clock_id=3)
    8, 0x24, 0x0A, 3, 0x03, 0x07, 0x00, 0x00,
    // UAC Feature Unit (type=0x24, subtype=0x06, unit_id=5, source_id=1)
    6, 0x24, 0x06, 5, 1, 0x00
};

const unsigned char kAsAlt1Extra[] = {
    // UAC2 Format Type I: subslot=2, bitRes=16
    6, 0x24, 0x02, 0x01, 2, 16
};

const unsigned char kAsAlt2Extra[] = {
    // UAC2 Format Type I: subslot=4, bitRes=24
    6, 0x24, 0x02, 0x01, 4, 24
};

const libusb_endpoint_descriptor kAltEndpoints[] = {
    // OUT Isochronous EP 0x01
    { 7, 0x05, 0x01, 0x05, 1024, 1, 0, 0x81, nullptr, 0 },
    // Feedback IN Isochronous EP 0x81 (usage = 0x01 -> bmAttributes = 0x11)
    { 7, 0x05, 0x81, 0x11, 4, 4, 0, 0, nullptr, 0 }
};

const libusb_interface_descriptor kAcAlts[] = {
    { 9, 0x04, 0, 0, 0, 0x01, 0x01, 0x20, 0, nullptr, kAcExtra, sizeof(kAcExtra) }
};

const libusb_interface_descriptor kAsAlts[] = {
    { 9, 0x04, 1, 0, 0, 0x01, 0x02, 0x20, 0, nullptr, nullptr, 0 },
    { 9, 0x04, 1, 1, 2, 0x01, 0x02, 0x20, 0, kAltEndpoints, kAsAlt1Extra, sizeof(kAsAlt1Extra) },
    { 9, 0x04, 1, 2, 2, 0x01, 0x02, 0x20, 0, kAltEndpoints, kAsAlt2Extra, sizeof(kAsAlt2Extra) }
};

const libusb_interface kInterfaces[] = {
    { kAcAlts, 1 },
    { kAsAlts, 3 }
};

const libusb_config_descriptor kConfigDesc = {
    9, 0x02, 128, 2, 1, 0, 0x80, 50, kInterfaces, nullptr, 0
};

const libusb_version kVersion = { 1, 0, 26, 0, "", "mock-libusb" };

} // namespace

extern "C" {

const struct libusb_version* libusb_get_version(void) {
    return &kVersion;
}

int libusb_set_option(libusb_context* /*ctx*/, int /*option*/, ...) {
    return LIBUSB_SUCCESS;
}

int libusb_init(libusb_context** ctx) {
    if (!ctx) return LIBUSB_ERROR_INVALID_PARAM;
    *ctx = reinterpret_cast<libusb_context*>(new MockContext());
    return LIBUSB_SUCCESS;
}

void libusb_exit(libusb_context* ctx) {
    delete reinterpret_cast<MockContext*>(ctx);
}

int libusb_wrap_sys_device(libusb_context* /*ctx*/, intptr_t sys_dev, libusb_device_handle** dev_handle) {
    if (!dev_handle || sys_dev < 0) return LIBUSB_ERROR_INVALID_PARAM;
    auto* handle = new MockDeviceHandle();
    handle->fd = static_cast<int>(sys_dev);
    *dev_handle = reinterpret_cast<libusb_device_handle*>(handle);
    return LIBUSB_SUCCESS;
}

libusb_device* libusb_get_device(libusb_device_handle* dev_handle) {
    auto* h = reinterpret_cast<MockDeviceHandle*>(dev_handle);
    return h ? reinterpret_cast<libusb_device*>(&h->dev) : nullptr;
}

void libusb_close(libusb_device_handle* dev_handle) {
    delete reinterpret_cast<MockDeviceHandle*>(dev_handle);
}

int libusb_get_active_config_descriptor(libusb_device* /*dev*/, struct libusb_config_descriptor** config) {
    if (!config) return LIBUSB_ERROR_INVALID_PARAM;
    *config = const_cast<libusb_config_descriptor*>(&kConfigDesc);
    return LIBUSB_SUCCESS;
}

int libusb_get_config_descriptor(libusb_device* dev, uint8_t /*config_index*/, struct libusb_config_descriptor** config) {
    return libusb_get_active_config_descriptor(dev, config);
}

void libusb_free_config_descriptor(struct libusb_config_descriptor* /*config*/) {
    // 静态常量描述符无需释放
}

int libusb_kernel_driver_active(libusb_device_handle* /*dev_handle*/, int interface_number) {
    return purecast::test::MockLibusbController::instance().is_kernel_driver_attached(interface_number) ? 1 : 0;
}

int libusb_detach_kernel_driver(libusb_device_handle* /*dev_handle*/, int interface_number) {
    auto& ctrl = purecast::test::MockLibusbController::instance();
    std::lock_guard<std::mutex> lock(ctrl.mutex_);
    if (interface_number >= 0 && interface_number < 4) {
        ctrl.kernel_driver_attached_[interface_number] = false;
    }
    return LIBUSB_SUCCESS;
}

int libusb_attach_kernel_driver(libusb_device_handle* /*dev_handle*/, int interface_number) {
    auto& ctrl = purecast::test::MockLibusbController::instance();
    std::lock_guard<std::mutex> lock(ctrl.mutex_);
    if (interface_number >= 0 && interface_number < 4) {
        ctrl.kernel_driver_attached_[interface_number] = true;
    }
    return LIBUSB_SUCCESS;
}

int libusb_claim_interface(libusb_device_handle* /*dev_handle*/, int interface_number) {
    auto& ctrl = purecast::test::MockLibusbController::instance();
    std::lock_guard<std::mutex> lock(ctrl.mutex_);
    if (interface_number >= 0 && interface_number < 4) {
        ctrl.interface_claimed_[interface_number] = true;
    }
    return LIBUSB_SUCCESS;
}

int libusb_release_interface(libusb_device_handle* /*dev_handle*/, int interface_number) {
    auto& ctrl = purecast::test::MockLibusbController::instance();
    std::lock_guard<std::mutex> lock(ctrl.mutex_);
    if (interface_number >= 0 && interface_number < 4) {
        ctrl.interface_claimed_[interface_number] = false;
    }
    return LIBUSB_SUCCESS;
}

int libusb_set_interface_alt_setting(libusb_device_handle* /*dev_handle*/, int interface_number, int alternate_setting) {
    auto& ctrl = purecast::test::MockLibusbController::instance();
    int fail = ctrl.fail_next_alt_setting_.exchange(0);
    if (fail != 0) return fail;
    std::lock_guard<std::mutex> lock(ctrl.mutex_);
    if (interface_number >= 0 && interface_number < 4) {
        ctrl.alt_setting_[interface_number] = alternate_setting;
    }
    return LIBUSB_SUCCESS;
}

int libusb_control_transfer(
    libusb_device_handle* /*dev_handle*/,
    uint8_t /*request_type*/,
    uint8_t bRequest,
    uint16_t wValue,
    uint16_t /*wIndex*/,
    unsigned char* data,
    uint16_t wLength,
    unsigned int /*timeout*/
) {
    auto& ctrl = purecast::test::MockLibusbController::instance();
    // 识别 UAC2 CS_SAM_FREQ_CONTROL (bRequest=0x01, wValue=0x0100)
    if (bRequest == 0x01 && wValue == 0x0100 && data && wLength >= 4) {
        int fail = ctrl.fail_next_clock_control_.exchange(0);
        if (fail != 0) return fail;
        int rate = static_cast<int>(data[0]) |
                   (static_cast<int>(data[1]) << 8) |
                   (static_cast<int>(data[2]) << 16) |
                   (static_cast<int>(data[3]) << 24);
        std::lock_guard<std::mutex> lock(ctrl.mutex_);
        ctrl.configured_sample_rate_ = rate;
        return 4;
    }
    return static_cast<int>(wLength);
}

struct libusb_transfer* libusb_alloc_transfer(int iso_packets) {
    const int n = std::max(1, iso_packets);
    const size_t size = sizeof(struct libusb_transfer) +
                        static_cast<size_t>(n - 1) * sizeof(struct libusb_iso_packet_descriptor);
    auto* xfer = static_cast<struct libusb_transfer*>(std::calloc(1, size));
    return xfer;
}

void libusb_free_transfer(struct libusb_transfer* transfer) {
    std::free(transfer);
}

int libusb_submit_transfer(struct libusb_transfer* transfer) {
    if (!transfer) return LIBUSB_ERROR_INVALID_PARAM;
    auto& ctrl = purecast::test::MockLibusbController::instance();
    int fail_all = ctrl.fail_all_submits_.load(std::memory_order_acquire);
    if (fail_all != 0) return fail_all;
    int fail = ctrl.fail_next_submit_.exchange(0);
    if (fail != 0) return fail;
    ctrl.on_submit(transfer);
    return LIBUSB_SUCCESS;
}

int libusb_cancel_transfer(struct libusb_transfer* transfer) {
    if (!transfer) return LIBUSB_ERROR_INVALID_PARAM;
    purecast::test::MockLibusbController::instance().on_cancel(transfer);
    return LIBUSB_SUCCESS;
}

int libusb_handle_events_timeout_completed(libusb_context* /*ctx*/, struct timeval* /*tv*/, int* /*completed*/) {
    purecast::test::MockLibusbController::instance().pump_events();
    return LIBUSB_SUCCESS;
}

} // extern "C"
