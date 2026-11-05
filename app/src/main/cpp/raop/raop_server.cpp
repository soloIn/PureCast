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
        response << "RTSP/1.0 200 OK\r\n"
                 << "CSeq: " << cseq << "\r\n"
                 << "Server: AirTunes/105.1\r\n\r\n";
    } else if (method == "OPTIONS") {
        response << "RTSP/1.0 200 OK\r\n"
                 << "CSeq: " << cseq << "\r\n"
                 << apple_response_header
                 << "Public: ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER\r\n"
                 << "Server: AirTunes/105.1\r\n\r\n";
    } else if (method == "ANNOUNCE") {
        // 解析 SDP 中的 rsaaeskey 与 aesiv
        current_params_.aes_key_base64 = parse_sdp_attribute(request, "rsaaeskey");
        current_params_.aes_iv_base64 = parse_sdp_attribute(request, "aesiv");

        // 💡 提取 SDP a=fmtp 参数行 (例如 a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100)
        std::string fmtp_str = parse_sdp_attribute(request, "fmtp");
        current_params_.fmtp.clear();
        if (!fmtp_str.empty()) {
            std::istringstream iss(fmtp_str);
            int32_t val;
            while (iss >> val) {
                current_params_.fmtp.push_back(val);
            }
        }

        if (current_params_.fmtp.size() >= 12) {
            LOGI("ANNOUNCE 提取 fmtp 参数 (12项): frame_size=%d, sample_size=%d, sample_rate=%d",
                 current_params_.fmtp[1], current_params_.fmtp[3], current_params_.fmtp[11]);
            current_params_.sample_rate = current_params_.fmtp[11];
            current_params_.bit_depth = current_params_.fmtp[3];
            current_params_.channels = current_params_.fmtp[7] > 0 ? current_params_.fmtp[7] : 2;
            rtp_.init_alac(current_params_.fmtp.data());
        } else {
            LOGI("ANNOUNCE 未检测到标准 12 项 fmtp，采用标准 44.1kHz/16-bit 默认配置初始化 ALAC");
            rtp_.init_alac(nullptr);
        }

        LOGI("ANNOUNCE 提取密钥: key_len=%zu, iv_len=%zu",
             current_params_.aes_key_base64.size(), current_params_.aes_iv_base64.size());

        response << "RTSP/1.0 200 OK\r\n"
                 << "CSeq: " << cseq << "\r\n"
                 << apple_response_header
                 << "Server: AirTunes/105.1\r\n\r\n";
    } else if (method == "SETUP") {
        // 解析 Active-Remote 与 DACP-ID
        current_params_.active_remote_token = parse_header(request, "Active-Remote");
        current_params_.dacp_id = parse_header(request, "DACP-ID");
        std::string dacp_port_str = parse_header(request, "DACP-Port");
        if (!dacp_port_str.empty()) {
            current_params_.dacp_port = std::stoi(dacp_port_str);
        }

        // 解析控制端口
        std::string transport = parse_header(request, "Transport");
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
                std::vector<uint8_t> cover_art;
                if (content_type.find("image/none") == std::string::npos && payload_len > 0) {
                    cover_art.assign(payload, payload + payload_len);
                    LOGI("SET_PARAMETER 收到原生封面图片推送: %s, 字节数=%zu", content_type.c_str(), payload_len);
                } else {
                    LOGI("SET_PARAMETER 收到清空封面请求 (image/none)");
                }
                if (metadata_cb_) {
                    metadata_cb_("", "", "", 0, cover_art);
                }
            } else if (content_type.find("text/parameters") != std::string::npos) {
                // 💡 3. 文本参数推送 (progress: start/curr/end 播放时间进度或音量)
                std::string body(reinterpret_cast<const char*>(payload), payload_len);
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
                        uint32_t rate = current_params_.sample_rate > 0 ? current_params_.sample_rate : 44100;
                        long pos_ms = (curr_ts >= start_ts) ? static_cast<long>((curr_ts - start_ts) * 1000ULL / rate) : 0;
                        long dur_ms = (end_ts >= start_ts) ? static_cast<long>((end_ts - start_ts) * 1000ULL / rate) : 0;
                        LOGD("AirPlay 进度实时校准: pos=%ld ms, duration=%ld ms (RTP: %s)",
                             pos_ms, dur_ms, prog_val.c_str());
                        if (progress_cb_) {
                            progress_cb_(pos_ms, dur_ms);
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
    std::string target = header_name + ":";
    size_t pos = request.find(target);
    if (pos == std::string::npos) {
        target = header_name + ": ";
        pos = request.find(target);
        if (pos == std::string::npos) return "";
    }
    size_t start = pos + target.size();
    while (start < request.size() && request[start] == ' ') start++;
    size_t end = request.find("\r\n", start);
    if (end == std::string::npos) end = request.size();
    return request.substr(start, end - start);
}

std::string RaopServer::parse_sdp_attribute(const std::string& sdp, const std::string& attr_name) {
    std::string target = "a=" + attr_name + ":";
    size_t pos = sdp.find(target);
    if (pos == std::string::npos) return "";
    size_t start = pos + target.size();
    size_t end = sdp.find("\r\n", start);
    if (end == std::string::npos) end = sdp.size();
    return sdp.substr(start, end - start);
}

} // namespace purecast::raop
