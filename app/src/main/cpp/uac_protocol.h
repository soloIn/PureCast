#pragma once

#include <cstdint>
#include <cstddef>
#include <vector>

namespace purecast {

// 💡 USB Audio Class 规范定义 (依据 USB 官方 UAC1 / UAC2 Specification)
namespace uac {

// USB 设备与接口类代码
constexpr uint8_t CLASS_AUDIO = 0x01;
constexpr uint8_t SUBCLASS_AUDIOCONTROL = 0x01;
constexpr uint8_t SUBCLASS_AUDIOSTREAMING = 0x02;

// 协议版本
constexpr uint8_t PROTOCOL_UAC1 = 0x00;
constexpr uint8_t PROTOCOL_UAC2 = 0x20;

// 描述符类型
constexpr uint8_t CS_INTERFACE = 0x24;
constexpr uint8_t CS_ENDPOINT  = 0x25;

// UAC1 控制与端点子类型
namespace uac1 {
    constexpr uint8_t EP_GENERAL = 0x01;
    constexpr uint8_t SAMPLING_FREQ_CONTROL = 0x01;

    // UAC1 端点请求代码
    constexpr uint8_t SET_CUR = 0x01;
    constexpr uint8_t GET_CUR = 0x81;
}

// UAC2 实体与控制子类型
namespace uac2 {
    constexpr uint8_t CLOCK_SOURCE    = 0x0B;
    constexpr uint8_t CLOCK_SELECTOR  = 0x0C;
    constexpr uint8_t CLOCK_MULTIPLIER = 0x0D;

    // UAC2 Clock Source 控制选择器
    constexpr uint8_t CS_SAM_FREQ_CONTROL = 0x01;
    constexpr uint8_t CS_CLOCK_VALID_CONTROL = 0x02;

    // UAC2 接口请求代码
    constexpr uint8_t CS_CUR = 0x01;
    constexpr uint8_t CS_RANGE = 0x02;
}

#pragma pack(push, 1)

// UAC2 Clock Source 描述符结构
struct Uac2ClockSourceDesc {
    uint8_t bLength;
    uint8_t bDescriptorType;     // CS_INTERFACE (0x24)
    uint8_t bDescriptorSubtype;  // CLOCK_SOURCE (0x0B)
    uint8_t bClockID;
    uint8_t bmAttributes;
    uint8_t bmControls;
    uint8_t bAssocTerminal;
    uint8_t iClockSource;
};

// UAC2 Audio Streaming Interface 描述符结构
struct Uac2AsHeaderDesc {
    uint8_t bLength;
    uint8_t bDescriptorType;     // CS_INTERFACE (0x24)
    uint8_t bDescriptorSubtype;  // AS_GENERAL (0x01)
    uint8_t bTerminalLink;
    uint8_t bmControls;
    uint8_t bFormatType;
    uint32_t bmFormats;
    uint8_t bNrChannels;
    uint32_t bmChannelConfig;
    uint8_t iChannelNames;
};

// UAC Type I Format 描述符 (UAC2)
struct Uac2FormatTypeIDesc {
    uint8_t bLength;
    uint8_t bDescriptorType;     // CS_INTERFACE (0x24)
    uint8_t bDescriptorSubtype;  // FORMAT_TYPE (0x02)
    uint8_t bFormatType;         // FORMAT_TYPE_I (0x01)
    uint8_t bSubslotSize;        // 物理子槽大小: 2, 3 或 4 字节
    uint8_t bBitResolution;      // 有效采样位深: 16 或 24 位
};

#pragma pack(pop)

/**
 * 💡 UAC 音频流接口与端点配置信息
 */
struct AudioEndpointInfo {
    int interface_number = -1;
    int alt_setting = -1;
    uint8_t endpoint_address = 0;
    uint16_t max_packet_size = 0;
    uint8_t interval = 1;
    uint8_t subslot_size = 2;   // 2=S16, 3=S24_3LE, 4=S32_LE
    uint8_t bit_resolution = 16;
    uint8_t clock_id = 0;
    bool is_uac2 = false;
    uint8_t feedback_ep = 0;    // 同步反馈端点地址 (若有)
};

} // namespace uac
} // namespace purecast
