#pragma once

#include <cstdint>
#include <cstddef>
#include <cstring>

#ifdef __cplusplus
extern "C" {
#endif

#define LIBUSB_CALL

#define LIBUSB_SUCCESS 0
#define LIBUSB_ERROR_IO -1
#define LIBUSB_ERROR_INVALID_PARAM -2
#define LIBUSB_ERROR_ACCESS -3
#define LIBUSB_ERROR_NO_DEVICE -4
#define LIBUSB_ERROR_NOT_FOUND -5
#define LIBUSB_ERROR_BUSY -6
#define LIBUSB_ERROR_TIMEOUT -7
#define LIBUSB_ERROR_PIPE -9
#define LIBUSB_ERROR_INTERRUPTED -10
#define LIBUSB_ERROR_NO_MEM -11
#define LIBUSB_ERROR_OTHER -99

#define LIBUSB_OPTION_NO_DEVICE_DISCOVERY 2

#define LIBUSB_TRANSFER_TYPE_ISOCHRONOUS 1

#define LIBUSB_REQUEST_TYPE_CLASS (0x01 << 5)
#define LIBUSB_RECIPIENT_INTERFACE 0x01
#define LIBUSB_RECIPIENT_ENDPOINT 0x02

#define LIBUSB_TRANSFER_COMPLETED 0
#define LIBUSB_TRANSFER_ERROR 1
#define LIBUSB_TRANSFER_TIMED_OUT 2
#define LIBUSB_TRANSFER_CANCELLED 3
#define LIBUSB_TRANSFER_STALL 4
#define LIBUSB_TRANSFER_NO_DEVICE 5

struct libusb_context;
struct libusb_device;
struct libusb_device_handle;
struct libusb_transfer;

struct libusb_version {
    uint16_t major;
    uint16_t minor;
    uint16_t micro;
    uint16_t nano;
    const char* rc;
    const char* describe;
};

struct libusb_iso_packet_descriptor {
    unsigned int length;
    unsigned int actual_length;
    int status;
};

typedef void (LIBUSB_CALL *libusb_transfer_cb_fn)(struct libusb_transfer* transfer);

struct libusb_transfer {
    libusb_device_handle* dev_handle;
    uint8_t flags;
    unsigned char endpoint;
    unsigned char type;
    unsigned int timeout;
    int status;
    int length;
    int actual_length;
    libusb_transfer_cb_fn callback;
    void* user_data;
    unsigned char* buffer;
    int num_iso_packets;
    struct libusb_iso_packet_descriptor iso_packet_desc[1];
};

struct libusb_endpoint_descriptor {
    uint8_t bLength;
    uint8_t bDescriptorType;
    uint8_t bEndpointAddress;
    uint8_t bmAttributes;
    uint16_t wMaxPacketSize;
    uint8_t bInterval;
    uint8_t bRefresh;
    uint8_t bSynchAddress;
    const unsigned char* extra;
    int extra_length;
};

struct libusb_interface_descriptor {
    uint8_t bLength;
    uint8_t bDescriptorType;
    uint8_t bInterfaceNumber;
    uint8_t bAlternateSetting;
    uint8_t bNumEndpoints;
    uint8_t bInterfaceClass;
    uint8_t bInterfaceSubClass;
    uint8_t bInterfaceProtocol;
    uint8_t iInterface;
    const struct libusb_endpoint_descriptor* endpoint;
    const unsigned char* extra;
    int extra_length;
};

struct libusb_interface {
    const struct libusb_interface_descriptor* altsetting;
    int num_altsetting;
};

struct libusb_config_descriptor {
    uint8_t bLength;
    uint8_t bDescriptorType;
    uint16_t wTotalLength;
    uint8_t bNumInterfaces;
    uint8_t bConfigurationValue;
    uint8_t iConfiguration;
    uint8_t bmAttributes;
    uint8_t MaxPower;
    const struct libusb_interface* interface;
    const unsigned char* extra;
    int extra_length;
};

struct timeval;

const struct libusb_version* libusb_get_version(void);
int libusb_set_option(libusb_context* ctx, int option, ...);
int libusb_init(libusb_context** ctx);
void libusb_exit(libusb_context* ctx);

int libusb_wrap_sys_device(libusb_context* ctx, intptr_t sys_dev, libusb_device_handle** dev_handle);
libusb_device* libusb_get_device(libusb_device_handle* dev_handle);
void libusb_close(libusb_device_handle* dev_handle);

int libusb_get_active_config_descriptor(libusb_device* dev, struct libusb_config_descriptor** config);
int libusb_get_config_descriptor(libusb_device* dev, uint8_t config_index, struct libusb_config_descriptor** config);
void libusb_free_config_descriptor(struct libusb_config_descriptor* config);

int libusb_kernel_driver_active(libusb_device_handle* dev_handle, int interface_number);
int libusb_detach_kernel_driver(libusb_device_handle* dev_handle, int interface_number);
int libusb_attach_kernel_driver(libusb_device_handle* dev_handle, int interface_number);
int libusb_claim_interface(libusb_device_handle* dev_handle, int interface_number);
int libusb_release_interface(libusb_device_handle* dev_handle, int interface_number);
int libusb_set_interface_alt_setting(libusb_device_handle* dev_handle, int interface_number, int alternate_setting);

int libusb_control_transfer(
    libusb_device_handle* dev_handle,
    uint8_t request_type,
    uint8_t bRequest,
    uint16_t wValue,
    uint16_t wIndex,
    unsigned char* data,
    uint16_t wLength,
    unsigned int timeout
);

struct libusb_transfer* libusb_alloc_transfer(int iso_packets);
void libusb_free_transfer(struct libusb_transfer* transfer);

static inline void libusb_fill_iso_transfer(
    struct libusb_transfer* transfer,
    libusb_device_handle* dev_handle,
    unsigned char endpoint,
    unsigned char* buffer,
    int length,
    int num_iso_packets,
    libusb_transfer_cb_fn callback,
    void* user_data,
    unsigned int timeout
) {
    transfer->dev_handle = dev_handle;
    transfer->endpoint = endpoint;
    transfer->type = LIBUSB_TRANSFER_TYPE_ISOCHRONOUS;
    transfer->timeout = timeout;
    transfer->buffer = buffer;
    transfer->length = length;
    transfer->num_iso_packets = num_iso_packets;
    transfer->user_data = user_data;
    transfer->callback = callback;
}

static inline unsigned char* libusb_get_iso_packet_buffer(
    struct libusb_transfer* transfer,
    unsigned int packet
) {
    if (!transfer || static_cast<int>(packet) >= transfer->num_iso_packets) {
        return nullptr;
    }
    size_t offset = 0;
    for (unsigned int i = 0; i < packet; ++i) {
        offset += transfer->iso_packet_desc[i].length;
    }
    return transfer->buffer + offset;
}

int libusb_submit_transfer(struct libusb_transfer* transfer);
int libusb_cancel_transfer(struct libusb_transfer* transfer);
int libusb_handle_events_timeout_completed(libusb_context* ctx, struct timeval* tv, int* completed);

#ifdef __cplusplus
}
#endif

// 💡 宿主机 CTest 专用桩控制器 (模拟 UAC2 USB DAC 与实时 URB 调度)
#ifdef __cplusplus
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <mutex>
#include <vector>

namespace purecast::test {

class MockLibusbController {
public:
    static MockLibusbController& instance();

    void reset();

    // 故障注入与门控
    void set_isoc_completion_paused(bool paused);
    void set_fail_next_alt_setting(int error_code);
    void set_fail_next_clock_control(int error_code);
    void set_fail_next_submit(int error_code);
    void set_fail_all_submits(int error_code);
    void set_feedback_rate_override(uint32_t q16_rate);
    void set_uac1_mode(bool enabled);

    // 运行态观测指标
    bool is_kernel_driver_attached(int iface) const;
    bool is_interface_claimed(int iface) const;
    int get_alt_setting(int iface) const;
    int get_configured_sample_rate() const;
    int get_uac1_sample_rate() const;
    int get_fu_volume_count() const;
    int get_fu_mute_count() const;
    int get_invalid_control_count() const;
    bool is_uac1_mode() const { return uac1_mode_.load(std::memory_order_acquire); }
    int get_in_flight_count() const;
    uint64_t get_completed_out_urbs() const;

    // 输出音频采样捕获 (记录每次完成 OUT URB 时写入硬件缓冲的字节流)
    void set_capture_enabled(bool enabled);
    void clear_captured_out_bytes();
    std::vector<uint8_t> take_captured_out_bytes();
    bool last_out_urb_was_all_zero() const;
    uint64_t get_last_non_zero_out_urb_time_ns() const;
    uint64_t get_first_zero_out_urb_after_ns(uint64_t since_ns) const;

    // 内部由 libusb_mock.cpp 驱动的钩子
    void on_submit(struct libusb_transfer* xfer);
    void on_cancel(struct libusb_transfer* xfer);
    void pump_events();

    mutable std::mutex mutex_;
    std::condition_variable cv_;

    bool kernel_driver_attached_[4]{true, true, true, true};
    bool interface_claimed_[4]{false, false, false, false};
    int alt_setting_[4]{0, 0, 0, 0};
    int configured_sample_rate_{0};
    int configured_uac1_sample_rate_{0};
    int fu_volume_count_{0};
    int fu_mute_count_{0};
    int invalid_control_count_{0};

    std::atomic<bool> uac1_mode_{false};
    std::atomic<bool> isoc_completion_paused_{false};
    std::atomic<int> fail_next_alt_setting_{0};
    std::atomic<int> fail_next_clock_control_{0};
    std::atomic<int> fail_next_submit_{0};
    std::atomic<int> fail_all_submits_{0};
    std::atomic<uint32_t> feedback_rate_override_{0};
    bool in_pump_callback_{false};

    std::atomic<bool> capture_enabled_{false};
    std::vector<uint8_t> captured_out_bytes_;
    std::atomic<bool> last_out_urb_all_zero_{true};
    std::atomic<uint64_t> last_non_zero_out_urb_ns_{0};
    std::atomic<uint64_t> first_zero_out_urb_ns_{0};
    std::atomic<uint64_t> zero_capture_since_ns_{0};
    std::atomic<uint64_t> completed_out_urbs_{0};

    struct PendingTransfer {
        struct libusb_transfer* xfer{nullptr};
        bool cancelled{false};
    };
    std::vector<PendingTransfer> pending_;
};

} // namespace purecast::test
#endif
