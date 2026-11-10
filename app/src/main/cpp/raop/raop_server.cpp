#include "raop_server.h"
#include "dmap_parser.h"
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <fcntl.h>
#include <sstream>
#include <android/log.h>
#include <cctype>
#include <algorithm>

#define RAOP_TAG "PureCastRAOP"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, RAOP_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, RAOP_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, RAOP_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, RAOP_TAG, __VA_ARGS__)

namespace purecast::raop {

static size_t safe_parse_content_length(const std::string& str) {
    if (str.empty()) return 0;
    try {
        size_t idx = 0;
        while (idx < str.size() && std::isspace(static_cast<unsigned char>(str[idx]))) idx++;
        if (idx >= str.size() || !std::isdigit(static_cast<unsigned char>(str[idx]))) return 0;
        unsigned long val = std::stoul(str.substr(idx));
        if (val > 16 * 1024 * 1024) return 0; // 限制最大 16MB，防止恶意畸形超大报文引发 OOM
        return static_cast<size_t>(val);
    } catch (...) {
        LOGW("Content-Length 格式非法，安全回退为 0: %s", str.c_str());
        return 0;
    }
}

RaopServer::RaopServer() {
    rtp_.set_stream_state_callback([this](bool is_paused) {
        if (is_paused) {
            if (stream_pause_cb_) stream_pause_cb_();
        } else {
            if (stream_resume_cb_) stream_resume_cb_();
        }
    });
}

RaopServer::~RaopServer() {
    stop();
}

bool RaopServer::start(int port) {
    if (is_running_.load()) return true;

    server_port_ = port;
    server_fd_ = socket(AF_INET, SOCK_STREAM, 0);
    if (server_fd_ < 0) {
        LOGE("创建 RTSP TCP socket 失败: %d", errno);
        return false;
    }

    int opt = 1;
    setsockopt(server_fd_, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(server_port_);

    if (bind(server_fd_, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        LOGE("绑定 RTSP 端口 %d 失败: %d", server_port_, errno);
        close(server_fd_);
        server_fd_ = -1;
        return false;
    }

    if (listen(server_fd_, 5) < 0) {
        LOGE("监听 RTSP 端口失败: %d", errno);
        close(server_fd_);
        server_fd_ = -1;
        return false;
    }

    is_running_.store(true);
    server_thread_ = std::thread(&RaopServer::server_loop, this);
    LOGI("RaopServer 成功启动，监听 RTSP 端口: %d", server_port_);
    return true;
}

void RaopServer::stop() {
    if (!is_running_.exchange(false)) return;

    LOGI("正在停止 RaopServer...");
    rtp_.stop();

    if (server_fd_ >= 0) {
        shutdown(server_fd_, SHUT_RDWR);
        close(server_fd_);
        server_fd_ = -1;
    }

    if (server_thread_.joinable()) {
        server_thread_.join();
    }
    LOGI("RaopServer 已安全停止");
}

void RaopServer::server_loop() {
    while (is_running_.load()) {
        sockaddr_in client_addr{};
        socklen_t client_len = sizeof(client_addr);
        int client_fd = accept(server_fd_, (struct sockaddr*)&client_addr, &client_len);
        if (client_fd < 0) {
            if (is_running_.load()) {
                LOGW("accept RTSP client 失败: %d", errno);
            }
            break;
        }

        char ip_str[INET_ADDRSTRLEN];
        inet_ntop(AF_INET, &client_addr.sin_addr, ip_str, INET_ADDRSTRLEN);
        current_params_.client_ip = ip_str;
        LOGI("收到来自 %s 的 AirPlay RTSP 连接请求", ip_str);

        // 处理该客户端连接
        handle_client(client_fd);
        close(client_fd);
    }
}

void RaopServer::handle_client(int client_fd) {
    std::vector<char> buffer(8192);
    std::string accumulated_data;

    try {
        while (is_running_.load()) {
            ssize_t bytes_read = recv(client_fd, buffer.data(), buffer.size() - 1, 0);
            if (bytes_read <= 0) {
                LOGI("RTSP 客户端连接已关闭或断开");
                break;
            }
            buffer[bytes_read] = '\0';
            accumulated_data.append(buffer.data(), bytes_read);

            // 检查是否包含完整 RTSP 请求头 \r\n\r\n
            size_t header_end = accumulated_data.find("\r\n\r\n");
            if (header_end != std::string::npos) {
                std::string content_length_str = parse_header(accumulated_data, "Content-Length");
                size_t content_length = safe_parse_content_length(content_length_str);

                size_t total_expected = header_end + 4 + content_length;
                if (accumulated_data.size() >= total_expected) {
                    std::string full_request = accumulated_data.substr(0, total_expected);
                    accumulated_data.erase(0, total_expected);
                    handle_rtsp_request(client_fd, full_request);
                }
            }
        }
    } catch (const std::exception& e) {
        LOGE("处理 RTSP 客户端数据时捕获异常: %s，安全关闭客户端连接", e.what());
    } catch (...) {
        LOGE("处理 RTSP 客户端数据时捕获未知异常，安全关闭客户端连接");
    }
}

void RaopServer::handle_rtsp_request(int client_fd, const std::string& request) {
    std::istringstream stream(request);
    std::string method, uri, version;
    stream >> method >> uri >> version;

    std::string cseq = parse_header(request, "CSeq");
    LOGI("处理 RTSP 方法: %s, URI: %s, CSeq: %s", method.c_str(), uri.c_str(), cseq.c_str());

    // 💡 只要请求中包含 DACP 凭据与端口信息，立即提取更新 (兼顾 ANNOUNCE, SETUP, SET_PARAMETER)
    std::string token = parse_header(request, "Active-Remote");
    if (!token.empty()) current_params_.active_remote_token = token;
    std::string dacp_id = parse_header(request, "DACP-ID");
    if (!dacp_id.empty()) current_params_.dacp_id = dacp_id;
    std::string dacp_port = parse_header(request, "DACP-Port");
    if (!dacp_port.empty()) {
        try {
            current_params_.dacp_port = std::stoi(dacp_port);
        } catch (const std::exception& e) {
            LOGW("解析 DACP-Port 异常: %s (%s)", dacp_port.c_str(), e.what());
            current_params_.dacp_port = 0;
        }
    }

    std::string challenge = parse_header(request, "Apple-Challenge");
    std::string apple_response_header;
    if (!challenge.empty()) {
        LOGI("收到 Apple-Challenge: %s", challenge.c_str());
        if (challenge_cb_) {
            std::string sig = challenge_cb_(challenge);
            if (!sig.empty()) {
                apple_response_header = "Apple-Response: " + sig + "\r\nAudio-Jack-Status: connected; type=digital\r\n";
                LOGI("生成 Apple-Response 响应头成功: len=%zu", sig.size());
            } else {
                LOGE("求解 Apple-Challenge 失败");
            }
        }
    }

    std::ostringstream response;

    if (method == "GET") {
        if (uri.find("/info") != std::string::npos) {
            // 💡 针对 iOS 发起的 /info 探针，声明纯音频 AirPort Express (features=7)，避免进入 AirPlay 2 配对通道
            std::string plist =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"
                "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\r\n"
                "<plist version=\"1.0\">\r\n"
                "<dict>\r\n"
                "    <key>model</key>\r\n"
                "    <string>AirPort4,107</string>\r\n"
                "    <key>features</key>\r\n"
                "    <integer>7</integer>\r\n"
                "    <key>sourceVersion</key>\r\n"
                "    <string>220.68</string>\r\n"
                "    <key>statusFlags</key>\r\n"
                "    <integer>4</integer>\r\n"
                "</dict>\r\n"
                "</plist>\r\n";

            response << "RTSP/1.0 200 OK\r\n"
                     << "CSeq: " << cseq << "\r\n"
                     << "Content-Type: text/x-apple-plist+xml\r\n"
                     << "Content-Length: " << plist.size() << "\r\n"
                     << "Server: AirTunes/105.1\r\n\r\n"
                     << plist;
        } else {
            response << "RTSP/1.0 200 OK\r\n"
                     << "CSeq: " << cseq << "\r\n"
                     << "Server: AirTunes/105.1\r\n\r\n";
        }
    } else if (method == "POST") {
        if (uri.find("/pair-setup") != std::string::npos || uri.find("/pair-verify") != std::string::npos) {
            // 明确拒绝 AirPlay 2 配对请求，迫使 iOS 客户端降级至免认证的标准 AirPlay 1 RAOP 协议
            LOGW("收到 AirPlay 2 配对探测请求 (%s)，返回 405 Method Not Allowed 触发客户端 AirPlay 1 回退", uri.c_str());
            response << "RTSP/1.0 405 Method Not Allowed\r\n"
                     << "CSeq: " << cseq << "\r\n"
                     << "Server: AirTunes/105.1\r\n\r\n";
        } else {
            response << "RTSP/1.0 200 OK\r\n"
                     << "CSeq: " << cseq << "\r\n"
                     << "Server: AirTunes/105.1\r\n\r\n";
        }
    } else if (method == "FLUSH") {
        rtp_.reset_buffer(); // 💡 收到 FLUSH 立即重置 JitterBuffer 与 AudioEngine 环形缓冲，杜绝残音爆音与断层
        if (flush_cb_) flush_cb_();
        response << "RTSP/1.0 200 OK\r\n"
                 << "CSeq: " << cseq << "\r\n"
                 << "RTP-Info: rtptime=0\r\n"
                 << "Server: AirTunes/105.1\r\n\r\n";
    } else if (method == "GET_PARAMETER") {
        size_t header_end = request.find("\r\n\r\n");
        std::string body;
        if (header_end != std::string::npos && header_end + 4 < request.size()) {
            body = request.substr(header_end + 4);
        }
        LOGI("收到 RTSP GET_PARAMETER 请求: CSeq=%s, Body='%s'", cseq.c_str(), body.c_str());

        if (body.find("volume") != std::string::npos) {
            float vol = current_volume_db_.load(std::memory_order_relaxed);
            char vol_buf[64] = {0};
            snprintf(vol_buf, sizeof(vol_buf), "volume: %.6f\r\n", vol);
            std::string vol_str(vol_buf);

            response << "RTSP/1.0 200 OK\r\n"
                     << "CSeq: " << cseq << "\r\n"
                     << "Content-Type: text/parameters\r\n"
                     << "Content-Length: " << vol_str.size() << "\r\n"
                     << "Server: AirTunes/105.1\r\n\r\n"
                     << vol_str;
        } else {
            response << "RTSP/1.0 200 OK\r\n"
                     << "CSeq: " << cseq << "\r\n"
                     << "Server: AirTunes/105.1\r\n\r\n";
        }
    } else if (method == "OPTIONS") {
        response << "RTSP/1.0 200 OK\r\n"
                 << "CSeq: " << cseq << "\r\n"
                 << apple_response_header
                 << "Public: ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER\r\n"
                 << "Server: AirTunes/105.1\r\n\r\n";
    } else if (method == "ANNOUNCE") {
        LOGI("=== 收到客户端 RTSP ANNOUNCE 请求 ===\n%s\n========================================", request.c_str());

        // 解析 SDP 中的 rsaaeskey 与 aesiv
        current_params_.aes_key_base64 = parse_sdp_attribute(request, "rsaaeskey");
        current_params_.aes_iv_base64 = parse_sdp_attribute(request, "aesiv");

        // 💡 1. 提取 m=audio 行中的音频 Payload Type (如 m=audio 0 RTP/AVP 96 或 m=audio 0 RTP/AVP 10)
        int audio_pt = 96;
        size_t m_pos = request.find("m=audio ");
        if (m_pos != std::string::npos) {
            size_t line_end = request.find("\n", m_pos);
            std::string m_line = request.substr(m_pos, (line_end != std::string::npos) ? (line_end - m_pos) : std::string::npos);
            size_t avp_pos = m_line.find("RTP/AVP");
            if (avp_pos != std::string::npos) {
                std::istringstream miss(m_line.substr(avp_pos + 7));
                int pt_val;
                if (miss >> pt_val) {
                    audio_pt = pt_val;
                }
            }
        }

        // 💡 2. 提取 SDP a=rtpmap 格式映射行 (例如 a=rtpmap:96 AppleLossless 或 a=rtpmap:96 L16/44100/2)
        std::string rtpmap_str = parse_sdp_attribute(request, "rtpmap");
        std::string rtpmap_lower = rtpmap_str;
        std::transform(rtpmap_lower.begin(), rtpmap_lower.end(), rtpmap_lower.begin(), ::tolower);

        if (!rtpmap_str.empty()) {
            std::istringstream riss(rtpmap_str);
            int rtp_pt_val;
            if (riss >> rtp_pt_val) {
                audio_pt = rtp_pt_val;
            }
        }
        current_params_.payload_type = audio_pt;
        rtp_.set_audio_payload_type(static_cast<uint8_t>(audio_pt));

        // 💡 3. 提取 SDP a=fmtp 参数行 (例如 a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100)
        std::string fmtp_str = parse_sdp_attribute(request, "fmtp");
        current_params_.fmtp.clear();
        if (!fmtp_str.empty()) {
            std::istringstream iss(fmtp_str);
            int32_t val;
            while (iss >> val) {
                current_params_.fmtp.push_back(val);
            }
        }

        LOGI("ANNOUNCE SDP 解析: pt=%d, rtpmap='%s', fmtp_count=%zu, key_len=%zu, iv_len=%zu",
             audio_pt, rtpmap_str.c_str(), current_params_.fmtp.size(),
             current_params_.aes_key_base64.size(), current_params_.aes_iv_base64.size());

        // 判断音频编码格式与参数 (L16 未压缩线性 PCM vs AppleLossless ALAC)
        if (rtpmap_lower.find("l16") != std::string::npos ||
            rtpmap_lower.find("pcm") != std::string::npos ||
            audio_pt == 10 || audio_pt == 11) {
            current_params_.codec = "PCM";
            current_params_.bit_depth = 16;
            current_params_.sample_rate = 44100;
            current_params_.channels = (audio_pt == 11) ? 1 : 2;

            // 尝试从 rtpmap (形如 96 L16/48000/2 或 96 L16/44100) 提取采样率和通道
            size_t slash1 = rtpmap_str.find('/');
            if (slash1 != std::string::npos) {
                size_t slash2 = rtpmap_str.find('/', slash1 + 1);
                std::string rate_str = (slash2 != std::string::npos)
                    ? rtpmap_str.substr(slash1 + 1, slash2 - slash1 - 1)
                    : rtpmap_str.substr(slash1 + 1);
                try {
                    int rate = std::stoi(rate_str);
                    if (rate > 0) current_params_.sample_rate = rate;
                } catch (...) {}

                if (slash2 != std::string::npos) {
                    try {
                        int ch = std::stoi(rtpmap_str.substr(slash2 + 1));
                        if (ch > 0) current_params_.channels = ch;
                    } catch (...) {}
                }
            }

            rtp_.set_audio_codec(AudioCodec::PCM);
            LOGI("🎉 ANNOUNCE 成功匹配未压缩格式: PCM (L16), pt=%d, rate=%d, depth=%d, channels=%d",
                 audio_pt, current_params_.sample_rate, current_params_.bit_depth, current_params_.channels);
        } else if (rtpmap_lower.find("l24") != std::string::npos) {
            current_params_.codec = "PCM";
            current_params_.bit_depth = 24;
            current_params_.sample_rate = 44100;
            current_params_.channels = 2;
            rtp_.set_audio_codec(AudioCodec::PCM);
            LOGI("🎉 ANNOUNCE 成功匹配未压缩格式: PCM (L24), pt=%d, rate=%d, depth=%d, channels=%d",
                 audio_pt, current_params_.sample_rate, current_params_.bit_depth, current_params_.channels);
        } else {
            // 默认为 AppleLossless (ALAC)
            current_params_.codec = "ALAC";
            rtp_.set_audio_codec(AudioCodec::ALAC);

            if (current_params_.fmtp.size() >= 12) {
                LOGI("ANNOUNCE 提取 fmtp 参数 (12项): frame_size=%d, sample_size=%d, sample_rate=%d",
                     current_params_.fmtp[1], current_params_.fmtp[3], current_params_.fmtp[11]);
                current_params_.sample_rate = current_params_.fmtp[11];
                current_params_.bit_depth = current_params_.fmtp[3];
                current_params_.channels = current_params_.fmtp[7] > 0 ? current_params_.fmtp[7] : 2;
                rtp_.init_alac(current_params_.fmtp.data());
            } else {
                LOGI("ANNOUNCE 未检测到标准 12 项 fmtp，采用标准 44.1kHz/16-bit 默认配置初始化 ALAC");
                current_params_.sample_rate = 44100;
                current_params_.bit_depth = 16;
                current_params_.channels = 2;
                rtp_.init_alac(nullptr);
            }
        }

        LOGI("ANNOUNCE 协商完成: codec=%s, pt=%d, rate=%d, key_len=%zu",
             current_params_.codec.c_str(), audio_pt, current_params_.sample_rate, current_params_.aes_key_base64.size());

        response << "RTSP/1.0 200 OK\r\n"
                 << "CSeq: " << cseq << "\r\n"
                 << apple_response_header
                 << "Server: AirTunes/105.1\r\n\r\n";
    } else if (method == "SETUP") {
        // 解析 Active-Remote 与 DACP-ID (仅非空时覆盖，避免清空已保留的凭据)
        std::string setup_token = parse_header(request, "Active-Remote");
        if (!setup_token.empty()) current_params_.active_remote_token = setup_token;
        std::string setup_dacp_id = parse_header(request, "DACP-ID");
        if (!setup_dacp_id.empty()) current_params_.dacp_id = setup_dacp_id;
        std::string dacp_port_str = parse_header(request, "DACP-Port");
        if (!dacp_port_str.empty()) {
            try {
                current_params_.dacp_port = std::stoi(dacp_port_str);
            } catch (const std::exception& e) {
                LOGW("SETUP 解析 DACP-Port 异常: %s (%s)", dacp_port_str.c_str(), e.what());
                current_params_.dacp_port = 0;
            }
        }

        // 解析控制端口
        std::string transport = parse_header(request, "Transport");
        LOGI("=== 收到 RTSP SETUP Transport ===: %s", transport.c_str());
        size_t cport_pos = transport.find("control_port=");
        if (cport_pos != std::string::npos) {
            current_params_.client_control_port = std::stoi(transport.substr(cport_pos + 13));
            if (!current_params_.client_ip.empty() && current_params_.client_control_port > 0) {
                rtp_.set_client_control_endpoint(current_params_.client_ip, current_params_.client_control_port);
            }
        }

        bool accepted = true;
        if (stream_request_cb_) {
            accepted = stream_request_cb_(current_params_);
        }

        if (accepted) {
            int data_p = 0, ctrl_p = 0, timing_p = 0;
            if (rtp_.start(data_p, ctrl_p, timing_p)) {
                response << "RTSP/1.0 200 OK\r\n"
                         << "CSeq: " << cseq << "\r\n"
                         << "Transport: RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=" << ctrl_p
                         << ";timing_port=" << timing_p
                         << ";server_port=" << data_p << "\r\n"
                         << "Session: 1\r\n"
                         << "Audio-Jack-Status: connected; type=digital\r\n"
                         << "Server: AirTunes/105.1\r\n\r\n";
            } else {
                response << "RTSP/1.0 500 Internal Server Error\r\n"
                         << "CSeq: " << cseq << "\r\n\r\n";
            }
        } else {
            response << "RTSP/1.0 503 Service Unavailable\r\n"
                     << "CSeq: " << cseq << "\r\n\r\n";
        }
    } else if (method == "RECORD") {
        rtp_.reset_buffer();
        rtp_.mark_paused_manually(false);
        if (stream_resume_cb_) stream_resume_cb_();
        if (flush_cb_) flush_cb_();
        response << "RTSP/1.0 200 OK\r\n"
                 << "CSeq: " << cseq << "\r\n"
                 << "Audio-Latency: 11025\r\n"
                 << "Server: AirTunes/105.1\r\n\r\n";
    } else if (method == "PAUSE") {
        LOGI("收到 RTSP PAUSE 请求，执行推流暂停");
        rtp_.mark_paused_manually(true);
        if (stream_pause_cb_) stream_pause_cb_();
        response << "RTSP/1.0 200 OK\r\n"
                 << "CSeq: " << cseq << "\r\n\r\n";
    } else if (method == "SET_PARAMETER") {
        std::string content_type = parse_header(request, "Content-Type");
        size_t header_end = request.find("\r\n\r\n");

        if (header_end != std::string::npos) {
            const uint8_t* payload = reinterpret_cast<const uint8_t*>(request.data() + header_end + 4);
            size_t payload_len = request.size() - (header_end + 4);

            if (content_type.find("application/x-dmap-tagged") != std::string::npos) {
                // 💡 1. 结构化 DMAP 元数据解析 (歌名、艺术家、专辑)
                DmapMetadata dmap;
                if (DmapParser::parse(payload, payload_len, dmap)) {
                    if (metadata_cb_) {
                        metadata_cb_(dmap.title, dmap.artist, dmap.album, dmap.duration_ms, dmap.cover_art);
                    }
                }
            } else if (content_type.find("image/") != std::string::npos) {
                // 💡 2. 封面图片推送 (JPEG/PNG 原始二进制，杜绝送入 DMAP 解析器被误丢弃)
                if (content_type.find("image/none") == std::string::npos && payload_len > 0) {
                    std::vector<uint8_t> cover_art(payload, payload + payload_len);
                    LOGI("SET_PARAMETER 收到原生封面图片推送: %s, 字节数=%zu", content_type.c_str(), payload_len);
                    if (metadata_cb_) {
                        metadata_cb_("", "", "", 0, cover_art);
                    }
                } else {
                    LOGI("SET_PARAMETER 收到切歌过渡/空封面提示 (image/none)，保留当前封面杜绝界面黑屏跳动");
                }
            } else if (content_type.find("text/parameters") != std::string::npos) {
                // 💡 3. 文本参数推送 (progress: start/curr/end 播放时间进度或 volume: 音量调节)
                std::string body(reinterpret_cast<const char*>(payload), payload_len);
                LOGI("SET_PARAMETER 收到文本参数推送:\n%s", body.c_str());

                // 3.1 音量调节: volume: -15.000000 (单位 dB, -144.0 为静音 MUTE, 0.0 为最大)
                size_t vol_pos = body.find("volume:");
                if (vol_pos != std::string::npos) {
                    size_t vstart = vol_pos + 7;
                    while (vstart < body.size() && (body[vstart] == ' ' || body[vstart] == '\t')) vstart++;
                    size_t vend = body.find("\r\n", vstart);
                    if (vend == std::string::npos) vend = body.size();
                    try {
                        float vol_db = std::stof(body.substr(vstart, vend - vstart));
                        current_volume_db_.store(vol_db, std::memory_order_relaxed);
                        rtp_.set_volume(vol_db);
                        LOGI("AirPlay 客户端设置音量: %.2f dB %s", vol_db, (vol_db <= -143.0f ? "(MUTE 静音)" : ""));
                    } catch (const std::exception& e) {
                        LOGW("解析 volume 参数异常: %s", e.what());
                    }
                }

                // 3.2 播放时间进度: progress: start/curr/end
                size_t prog_pos = body.find("progress:");
                if (prog_pos != std::string::npos) {
                    size_t start_pos = prog_pos + 9;
                    while (start_pos < body.size() && body[start_pos] == ' ') start_pos++;
                    size_t line_end = body.find("\r\n", start_pos);
                    if (line_end == std::string::npos) line_end = body.size();
                    std::string prog_val = body.substr(start_pos, line_end - start_pos);

                    // 格式: start/curr/end (RTP 时间戳)
                    uint64_t start_ts = 0, curr_ts = 0, end_ts = 0;
                    char s1 = 0, s2 = 0;
                    std::istringstream pstream(prog_val);
                    if (pstream >> start_ts >> s1 >> curr_ts >> s2 >> end_ts && s1 == '/' && s2 == '/') {
                        if (start_ts == 0 && curr_ts == 0 && end_ts == 0) {
                            LOGI("AirPlay 收到暂停/未就绪时间戳 (progress: 0/0/0)，忽略以防进度冲刷归零");
                        } else if (end_ts > start_ts) {
                            uint32_t rate = current_params_.sample_rate > 0 ? current_params_.sample_rate : 44100;
                            long pos_ms = (curr_ts >= start_ts) ? static_cast<long>((curr_ts - start_ts) * 1000ULL / rate) : 0;
                            long dur_ms = static_cast<long>((end_ts - start_ts) * 1000ULL / rate);
                            LOGD("AirPlay 进度实时校准: pos=%ld ms, duration=%ld ms (RTP: %s)",
                                 pos_ms, dur_ms, prog_val.c_str());
                            if (progress_cb_) {
                                progress_cb_(pos_ms, dur_ms);
                            }
                        } else if (end_ts == 0 && curr_ts >= start_ts && start_ts > 0) {
                            // 💡 直播/电台流 (end_ts == 0)，上报当前已播放时长，总时长为 0
                            uint32_t rate = current_params_.sample_rate > 0 ? current_params_.sample_rate : 44100;
                            long pos_ms = static_cast<long>((curr_ts - start_ts) * 1000ULL / rate);
                            LOGD("AirPlay 直播流进度校准: pos=%ld ms (RTP: %s)", pos_ms, prog_val.c_str());
                            if (progress_cb_) {
                                progress_cb_(pos_ms, 0);
                            }
                        }
                    }
                }
            }
        }

        response << "RTSP/1.0 200 OK\r\n"
                 << "CSeq: " << cseq << "\r\n\r\n";
    } else if (method == "TEARDOWN") {
        rtp_.stop();
        if (flush_cb_) flush_cb_();
        if (stream_stop_cb_) {
            stream_stop_cb_();
        }
        response << "RTSP/1.0 200 OK\r\n"
                 << "CSeq: " << cseq << "\r\n"
                 << "Connection: close\r\n\r\n";
    } else {
        response << "RTSP/1.0 200 OK\r\n"
                 << "CSeq: " << cseq << "\r\n\r\n";
    }

    std::string resp_str = response.str();
    send(client_fd, resp_str.data(), resp_str.size(), 0);
}

std::string RaopServer::parse_header(const std::string& request, const std::string& header_name) {
    size_t header_end = request.find("\r\n\r\n");
    size_t scan_len = (header_end != std::string::npos) ? (header_end + 2) : request.size();

    // 💡 仅拷贝并转换头部区域，严禁对多兆字节的二进制 Body (如封面图片) 进行全量复制与 tolower
    std::string req_lower = request.substr(0, scan_len);
    std::string hdr_lower = header_name;
    std::transform(req_lower.begin(), req_lower.end(), req_lower.begin(), ::tolower);
    std::transform(hdr_lower.begin(), hdr_lower.end(), hdr_lower.begin(), ::tolower);

    std::string target = "\n" + hdr_lower + ":";
    size_t pos = req_lower.find(target);
    size_t target_len = target.size();
    if (pos == std::string::npos) {
        if (req_lower.rfind(hdr_lower + ":", 0) == 0) {
            pos = 0;
            target_len = hdr_lower.size() + 1;
        } else {
            return "";
        }
    } else {
        pos += 1; // 跳过前导换行符
        target_len -= 1;
    }

    size_t start = pos + target_len;
    while (start < scan_len && (request[start] == ' ' || request[start] == '\t')) start++;
    size_t end = request.find("\r", start);
    if (end == std::string::npos || end > scan_len) {
        end = request.find("\n", start);
        if (end == std::string::npos || end > scan_len) end = scan_len;
    }
    return request.substr(start, end - start);
}

std::string RaopServer::parse_sdp_attribute(const std::string& sdp, const std::string& attr_name) {
    std::string target = "a=" + attr_name + ":";
    size_t pos = sdp.find(target);
    if (pos == std::string::npos) {
        // 尝试大小写不敏感匹配
        std::string sdp_lower = sdp;
        std::string target_lower = target;
        std::transform(sdp_lower.begin(), sdp_lower.end(), sdp_lower.begin(), ::tolower);
        std::transform(target_lower.begin(), target_lower.end(), target_lower.begin(), ::tolower);
        pos = sdp_lower.find(target_lower);
        if (pos == std::string::npos) return "";
    }
    size_t start = pos + target.size();
    while (start < sdp.size() && (sdp[start] == ' ' || sdp[start] == '\t')) start++;
    size_t end = sdp.find("\n", start);
    if (end == std::string::npos) {
        end = sdp.size();
    } else if (end > start && sdp[end - 1] == '\r') {
        end--;
    }
    return sdp.substr(start, end - start);
}

} // namespace purecast::raop
