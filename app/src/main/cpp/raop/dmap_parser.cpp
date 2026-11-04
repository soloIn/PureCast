#include "dmap_parser.h"
#include <cstring>
#include <android/log.h>

#define DMAP_TAG "PureCastDmap"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, DMAP_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, DMAP_TAG, __VA_ARGS__)

namespace purecast::raop {

uint32_t DmapParser::read_uint32_be(const uint8_t* p) {
    return (static_cast<uint32_t>(p[0]) << 24) |
           (static_cast<uint32_t>(p[1]) << 16) |
           (static_cast<uint32_t>(p[2]) << 8)  |
           static_cast<uint32_t>(p[3]);
}

bool DmapParser::parse(const uint8_t* data, size_t size, DmapMetadata& out_metadata) {
    if (!data || size < 8) return false;
    parse_recursive(data, size, out_metadata);
    return !out_metadata.title.empty() || !out_metadata.artist.empty() || !out_metadata.cover_art.empty();
}

void DmapParser::parse_recursive(const uint8_t* data, size_t size, DmapMetadata& out_metadata) {
    size_t offset = 0;
    while (offset + 8 <= size) {
        char tag[5] = {
            static_cast<char>(data[offset]),
            static_cast<char>(data[offset + 1]),
            static_cast<char>(data[offset + 2]),
            static_cast<char>(data[offset + 3]),
            '\0'
        };
        uint32_t len = read_uint32_be(data + offset + 4);
        offset += 8;

        if (offset + len > size) {
            // 防止残缺数据包溢出
            break;
        }

        const uint8_t* val_ptr = data + offset;

        // 判断已知 Tag
        if (std::strcmp(tag, "minm") == 0) { // 歌曲名称
            out_metadata.title.assign(reinterpret_cast<const char*>(val_ptr), len);
            LOGI("DMAP 提取歌曲名称: %s", out_metadata.title.c_str());
        } else if (std::strcmp(tag, "asar") == 0) { // 艺术家
            out_metadata.artist.assign(reinterpret_cast<const char*>(val_ptr), len);
            LOGI("DMAP 提取艺术家: %s", out_metadata.artist.c_str());
        } else if (std::strcmp(tag, "asal") == 0) { // 专辑名
            out_metadata.album.assign(reinterpret_cast<const char*>(val_ptr), len);
            LOGI("DMAP 提取专辑: %s", out_metadata.album.c_str());
        } else if (std::strcmp(tag, "PICT") == 0) { // 专辑封面图片数据
            out_metadata.cover_art.assign(val_ptr, val_ptr + len);
            LOGI("DMAP 提取封面图片: %u 字节", len);
        } else if (std::strcmp(tag, "mdcl") == 0 ||
                   std::strcmp(tag, "mlit") == 0 ||
                   std::strcmp(tag, "mbcl") == 0 ||
                   std::strcmp(tag, "mshl") == 0 ||
                   std::strcmp(tag, "mlcl") == 0) {
            // 复合容器标签，递归解析内部子元素
            parse_recursive(val_ptr, len, out_metadata);
        }

        offset += len;
    }
}

} // namespace purecast::raop
