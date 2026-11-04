#pragma once

#include <string>
#include <vector>
#include <cstdint>
#include <cstddef>

namespace purecast::raop {

struct DmapMetadata {
    std::string title;
    std::string artist;
    std::string album;
    long duration_ms{0};
    std::vector<uint8_t> cover_art;
};

/**
 * 💡 DmapParser: Apple DMAP (Digital Media Access Protocol) 二进制标记解析器
 * 解析 RTSP SET_PARAMETER 投递的 application/x-dmap-tagged 标签集
 * 提取歌曲名 (minm)、艺术家 (asar)、专辑名 (asal)、封面图像 (PICT)
 */
class DmapParser {
public:
    static bool parse(const uint8_t* data, size_t size, DmapMetadata& out_metadata);

private:
    static void parse_recursive(const uint8_t* data, size_t size, DmapMetadata& out_metadata);
    static uint32_t read_uint32_be(const uint8_t* p);
};

} // namespace purecast::raop
