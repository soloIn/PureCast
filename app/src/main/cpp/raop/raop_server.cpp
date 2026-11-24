#include "raop_server.h"
#include "dmap_parser.h"
#include "../audio_engine.h"
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <fcntl.h>
#include <poll.h>
#include <sstream>
#include <android/log.h>
#include <cctype>
#include <cerrno>
#include <algorithm>

#define RAOP_TAG "PureCastRAOP"
#undef LOGD
#undef LOGI
#undef LOGW
#undef LOGE
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

static inline std::string to_lower_copy(std::string_view s) {
    std::string res(s);
    std::transform(res.begin(), res.end(), res.begin(), ::tolower);
    return res;
}

RaopServer::RaopServer() {
    rtp_.set_stream_state_callback([this](bool is_paused, uint64_t epoch) {
        const int64_t sid = active_session_id_.load(std::memory_order_acquire);
        const int64_t seq = next_seq();
        if (is_paused) {
            if (stream_pause_cb_) stream_pause_cb_(sid, seq, static_cast<int64_t>(epoch));
        } else {
            if (stream_resume_cb_) stream_resume_cb_(sid, seq, static_cast<int64_t>(epoch));
        }
    });
    rtp_.set_first_data_packet_callback([this](uint64_t epoch, int sr, int bits, int ch, const std::string& codec) {
        const int64_t sid = active_session_id_.load(std::memory_order_acquire);
        const int64_t seq = next_seq();
        if (first_data_packet_cb_) {
            first_data_packet_cb_(sid, seq, static_cast<int64_t>(epoch), sr, bits, ch, codec);
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

    {
        std::lock_guard<std::mutex> lk(client_fd_mutex_);
        const int cfd = active_client_fd_.load(std::memory_order_acquire);
        if (cfd >= 0) {
            shutdown(cfd, SHUT_RDWR);
        }
    }

    if (server_fd_ >= 0) {
        shutdown(server_fd_, SHUT_RDWR);
    }

    // 💡 TSan 安全时序：必须先 join() 终止 server_loop 线程，再 close/清零 server_fd_，避免跨线程数据竞争与 fd 复用竞态
    if (server_thread_.joinable()) {
        server_thread_.join();
    }

    if (server_fd_ >= 0) {
        close(server_fd_);
        server_fd_ = -1;
    }
    {
        std::lock_guard<std::mutex> lock(crypto_cache_mutex_);
        cached_aes_key_.clear();
        cached_aes_iv_.clear();
    }
    LOGI("RaopServer 已安全停止");
}

void RaopServer::preempt_active_client() {
    // 💡 P0 #1 跨协议抢占安全闭环：保留 RTSP server_fd_ 监听，立即停撤当前 UDP 推流线程并打断活动 client_fd，
    // 防止旧 AirPlay 发射端继续向环形缓冲区 write_pcm 污染新协议会话。
    rtp_.stop();

    std::lock_guard<std::mutex> lk(client_fd_mutex_);
    const int cfd = active_client_fd_.load(std::memory_order_acquire);
    if (cfd >= 0) {
        shutdown(cfd, SHUT_RDWR);
        LOGI("已向当前活动 RTSP 客户端 (fd=%d) 发送 shutdown 以加速退出", cfd);
    }
}

bool RaopServer::resume_after_reopen(uint64_t new_epoch) {
    // 💡 P0 #2 重开声卡闭查校验：仅当 RAOP 服务器与 UDP 接收管线以及活跃客户端均存活时允许原地接续，
    // 并重置 JitterBuffer / 更新 active_epoch / 解除暂停标记；若管线已退化则返回 false 由上层保持 PAUSED。
    if (!is_running_.load(std::memory_order_acquire) ||
        !rtp_.is_running() ||
        active_client_fd_.load(std::memory_order_acquire) < 0) {
        LOGW("RaopServer resume_after_reopen 拒绝恢复：UDP/RTSP 管线未存活 (epoch=%llu)",
             static_cast<unsigned long long>(new_epoch));
        return false;
    }
    rtp_.set_active_epoch(new_epoch);
    rtp_.reset_buffer();
    rtp_.mark_paused_manually(false, false);
    LOGI("RaopServer 已同步新世代并恢复消费管线 (newEpoch=%llu)",
         static_cast<unsigned long long>(new_epoch));
    return true;
}

void RaopServer::server_loop() {
    while (is_running_.load(std::memory_order_acquire)) {
        struct pollfd pfd{};
        pfd.fd = server_fd_;
        pfd.events = POLLIN;
        int pr = poll(&pfd, 1, 50);
        if (pr == 0) {
            continue;
        }
        if (pr < 0) {
            if (errno == EINTR && is_running_.load(std::memory_order_acquire)) {
                continue;
            }
            break;
        }
        if (!is_running_.load(std::memory_order_acquire)) {
            break;
        }

        sockaddr_in client_addr{};
        socklen_t client_len = sizeof(client_addr);
        int client_fd = accept(server_fd_, (struct sockaddr*)&client_addr, &client_len);
        if (client_fd < 0) {
            if (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK || errno == ECONNABORTED) {
                continue;
            }
            if (is_running_.load(std::memory_order_acquire)) {
                LOGW("accept RTSP client 异常 (errno=%d)，继续保持监听循环", errno);
                std::this_thread::sleep_for(std::chrono::milliseconds(50));
                continue;
            }
            break;
        }

        // 💡 配置 2 秒 RTSP recv 超时，确保 stop()/preempt 时即便内核未立刻唤醒也不会无限挂起
        struct timeval rcv_tv{};
        rcv_tv.tv_sec = 2;
        rcv_tv.tv_usec = 0;
        setsockopt(client_fd, SOL_SOCKET, SO_RCVTIMEO, &rcv_tv, sizeof(rcv_tv));

        char ip_str[INET_ADDRSTRLEN];
        inet_ntop(AF_INET, &client_addr.sin_addr, ip_str, INET_ADDRSTRLEN);
        current_params_.client_ip = ip_str;
        LOGI("收到来自 %s 的 AirPlay RTSP 连接请求", ip_str);

        {
            std::lock_guard<std::mutex> lk(client_fd_mutex_);
            active_client_fd_.store(client_fd, std::memory_order_release);
        }

        // 处理该客户端连接
        handle_client(client_fd);

        {
            std::lock_guard<std::mutex> lk(client_fd_mutex_);
            active_client_fd_.store(-1, std::memory_order_release);
            close(client_fd);
        }
        // 💡 修复 Finding 2 (CWE-404): 客户端断开连接后，若推流仍活跃，停止 RTP 接收引擎、排空音频并通知协调器，清空密钥上下文杜绝资源泄漏
        if (rtp_.is_running()) {
            LOGI("RTSP 客户端断开且推流仍处于运行态，停止 RTP 管道并归还资源");
            rtp_.stop();
            const uint64_t teardown_ep = AudioEngine::instance().emergency_mute_and_flush(FlushReason::STOP_OR_TEARDOWN);
            rtp_.set_active_epoch(teardown_ep);
            if (stream_stop_cb_) {
                stream_stop_cb_(active_session_id_.load(std::memory_order_acquire), next_seq(), static_cast<int64_t>(teardown_ep));
            }
        }
        current_params_ = RaopStreamParams{};
        rtp_.set_crypto_key({}, {});
        {
            std::lock_guard<std::mutex> lock(crypto_cache_mutex_);
            cached_aes_key_.clear();
            cached_aes_iv_.clear();
        }
    }
}

void RaopServer::handle_client(int client_fd) {
    std::vector<char> buffer(8192);
    std::string accumulated_data;
    int idle_timeout_count = 0;

    try {
        while (is_running_.load()) {
            ssize_t bytes_read = recv(client_fd, buffer.data(), buffer.size() - 1, 0);
            if (bytes_read < 0) {
                if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) {
                    // 💡 修复 Finding 1: 若推流仍在运行 (rtp_.is_running())，RTSP 控制通道间歇无数据属于正常播放现象，严禁断开！
                    // 仅当推流未建立且连续空闲超时超过 30 秒 (15 次) 时，方判定为空闲探测连接并主动关闭让出 accept 循环
                    if (!rtp_.is_running() && accumulated_data.empty() && ++idle_timeout_count >= 15) {
                        LOGI("RTSP 客户端无数据空闲超时 (%d 秒) 且未启动推流，主动关闭空闲连接让出 accept 循环", idle_timeout_count * 2);
                        break;
                    }
                    continue;
                }
                LOGI("RTSP 客户端读取异常断开 (errno=%d)", errno);
                break;
            }
            idle_timeout_count = 0;
            if (bytes_read == 0) {
                LOGI("RTSP 客户端连接已关闭或断开");
                break;
            }
            buffer[bytes_read] = '\0';
            accumulated_data.append(buffer.data(), bytes_read);

            // 💡 修复 Finding 2: 循环消费所有已完整到达的 RTSP 请求 (Pipelining 粘包处理)，避免截留后续请求导致死锁
            while (true) {
                size_t header_end = accumulated_data.find("\r\n\r\n");
                if (header_end == std::string::npos) {
                    break;
                }
                std::string content_length_str = parse_header(accumulated_data, "Content-Length");
                size_t content_length = safe_parse_content_length(content_length_str);

                size_t total_expected = header_end + 4 + content_length;
                if (accumulated_data.size() < total_expected) {
                    break;
                }
                std::string full_request = accumulated_data.substr(0, total_expected);
                accumulated_data.erase(0, total_expected);
                handle_rtsp_request(client_fd, full_request);
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
        // 💡 在 RaopRtp::audio_packet_mutex_ 锁内原子执行 AudioEngine 排空递增 epoch、同步 active_epoch、
        // 重置 JitterBuffer 与开启 FLUSH 后静音拦截门控，彻底消除 sync_drain_thread 抢跑垫步与首包旧 epoch 竞态；
        // 严禁在 FLUSH 中越权调用 AudioEngine::resume_stream()，避免 TV 端暂停后 iPhone 响应发 FLUSH 提前解除本机静音漏出尾音。
        const uint64_t new_epoch = rtp_.on_rtsp_flush();
        if (flush_cb_) {
            flush_cb_(active_session_id_.load(std::memory_order_acquire), next_seq(), static_cast<int64_t>(new_epoch));
        }
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
        std::string rtpmap_lower = to_lower_copy(rtpmap_str);

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
        // 💡 修复 Finding 13: 增加 fmtp 字符串长度与数值项上限校验，防止恶意构造异常大包耗尽内存
        std::string fmtp_str = parse_sdp_attribute(request, "fmtp");
        current_params_.fmtp.clear();
        if (!fmtp_str.empty() && fmtp_str.size() <= 256) {
            std::istringstream iss(fmtp_str);
            int32_t val;
            while ((iss >> val) && current_params_.fmtp.size() < 32) {
                current_params_.fmtp.push_back(val);
            }
        }

        LOGI("ANNOUNCE SDP 解析: pt=%d, rtpmap='%s', fmtp_count=%zu, key_len=%zu, iv_len=%zu",
             audio_pt, rtpmap_str.c_str(), current_params_.fmtp.size(),
             current_params_.aes_key_base64.size(), current_params_.aes_iv_base64.size());

        // 判断音频编码格式与参数 (L16 / L24 未压缩线性 PCM vs AppleLossless ALAC)
        bool is_l24 = (rtpmap_lower.find("l24") != std::string::npos);
        bool is_l16 = (rtpmap_lower.find("l16") != std::string::npos ||
                       rtpmap_lower.find("pcm") != std::string::npos ||
                       audio_pt == 10 || audio_pt == 11);

        if (is_l16 || is_l24) {
            current_params_.codec = "PCM";
            current_params_.bit_depth = is_l24 ? 24 : 16;
            current_params_.sample_rate = 44100;
            current_params_.channels = (audio_pt == 11) ? 1 : 2;

            // 💡 修复 Finding 3: 尝试从 rtpmap (形如 96 L24/96000/2 或 96 L16/48000/2 或 96 L16/44100) 提取真实采样率和声道数
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
            LOGI("🎉 ANNOUNCE 成功匹配未压缩格式: PCM (L%d), pt=%d, rate=%d, depth=%d, channels=%d",
                 current_params_.bit_depth, audio_pt, current_params_.sample_rate, current_params_.bit_depth, current_params_.channels);
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
        // 解析控制端口与授时端口 (Active-Remote / DACP-ID / DACP-Port 已在公共请求头入口统一解析)
        std::string transport = parse_header(request, "Transport");
        LOGI("=== 收到 RTSP SETUP Transport ===: %s", transport.c_str());
        size_t cport_pos = transport.find("control_port=");
        if (cport_pos != std::string::npos) {
            try {
                current_params_.client_control_port = std::stoi(transport.substr(cport_pos + 13));
                if (!current_params_.client_ip.empty() && current_params_.client_control_port > 0) {
                    rtp_.set_client_control_endpoint(current_params_.client_ip, current_params_.client_control_port);
                }
            } catch (const std::exception& e) {
                LOGW("解析 control_port 异常: %s", e.what());
            }
        }
        size_t tport_pos = transport.find("timing_port=");
        if (tport_pos != std::string::npos) {
            try {
                current_params_.client_timing_port = std::stoi(transport.substr(tport_pos + 12));
                if (!current_params_.client_ip.empty() && current_params_.client_timing_port > 0) {
                    rtp_.set_client_timing_endpoint(current_params_.client_ip, current_params_.client_timing_port);
                }
            } catch (const std::exception& e) {
                LOGW("解析 timing_port 异常: %s", e.what());
            }
        }

        const int64_t sid = next_session_id_.fetch_add(1, std::memory_order_acq_rel) + 1;
        active_session_id_.store(sid, std::memory_order_release);

        int64_t acquired_epoch = 1;
        if (stream_request_cb_) {
            acquired_epoch = stream_request_cb_(sid, next_seq(), current_params_);
        }

        if (acquired_epoch > 0) {
            rtp_.set_format_info(current_params_.sample_rate, current_params_.bit_depth, current_params_.channels, current_params_.codec);
            rtp_.reset_buffer();
            rtp_.set_active_epoch(static_cast<uint64_t>(acquired_epoch));
            // 💡 修复 Finding 6 (CWE-321): 若当前会话协商有 AES 加密密钥，确保重新注入给 rtp_，杜绝 TEARDOWN 停止后重启解密失败产生白噪声
            {
                std::lock_guard<std::mutex> lock(crypto_cache_mutex_);
                if (!cached_aes_key_.empty() && !cached_aes_iv_.empty()) {
                    rtp_.set_crypto_key(cached_aes_key_, cached_aes_iv_);
                }
            }
            int data_p = 0, ctrl_p = 0, timing_p = 0;
            if (rtp_.start(data_p, ctrl_p, timing_p)) {
                response << "RTSP/1.0 200 OK\r\n"
                         << "CSeq: " << cseq << "\r\n"
                         << "Transport: RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=" << ctrl_p
                         << ";timing_port=" << timing_p
                         << ";server_port=" << data_p << "\r\n"
                         << "Session: 1\r\n"
                         << "Audio-Jack-Status: connected; type=analog\r\n"
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
        const bool was_manually_paused = rtp_.is_manually_paused();
        AudioEngine::instance().resume_stream();
        // 💡 修复 Finding 15 & 4: 无论初次推流还是显式暂停恢复，RECORD 都代表新周期的音频推流启动，必须重置 JitterBuffer 以重锚定序列号，杜绝长暂停 >4.3m 序号溢出丢包
        rtp_.reset_buffer();
        if (was_manually_paused) {
            // 💡 从显式暂停态恢复推流：取消显式静音标记并向协调器发射恢复状态
            rtp_.mark_paused_manually(false, true);
        } else {
            // 💡 初次建流 RECORD：就绪，绝不发射恢复回调，严格等待首帧 25 包预充门禁 (R-2 防御)
            rtp_.mark_paused_manually(false, false);
        }
        response << "RTSP/1.0 200 OK\r\n"
                 << "CSeq: " << cseq << "\r\n"
                 << "Audio-Latency: " << RaopRtp::REPORTED_AUDIO_LATENCY_SAMPLES << "\r\n"
                 << "Server: AirTunes/105.1\r\n\r\n";
    } else if (method == "PAUSE") {
        LOGI("收到 RTSP PAUSE 请求，执行无锁快路径静音 (EXPLICIT_PAUSE) 并保留环形缓冲");
        AudioEngine::instance().emergency_mute_and_flush(FlushReason::EXPLICIT_PAUSE);
        rtp_.mark_paused_manually(true, true);
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
                        metadata_cb_(
                            active_session_id_.load(std::memory_order_acquire),
                            next_seq(),
                            dmap.title,
                            dmap.artist,
                            dmap.album,
                            dmap.duration_ms,
                            dmap.cover_art
                        );
                    }
                }
            } else if (content_type.find("image/") != std::string::npos) {
                // 💡 2. 封面图片推送 (JPEG/PNG 原始二进制，杜绝送入 DMAP 解析器被误丢弃)
                if (content_type.find("image/none") == std::string::npos && payload_len > 0) {
                    std::vector<uint8_t> cover_art(payload, payload + payload_len);
                    LOGI("SET_PARAMETER 收到原生封面图片推送: %s, 字节数=%zu", content_type.c_str(), payload_len);
                    if (metadata_cb_) {
                        metadata_cb_(
                            active_session_id_.load(std::memory_order_acquire),
                            next_seq(),
                            "",
                            "",
                            "",
                            0,
                            cover_art
                        );
                    }
                } else {
                    LOGI("SET_PARAMETER 收到切歌过渡/空封面提示 (image/none)，保留当前封面杜绝界面黑屏跳动");
                }
            } else if (content_type.find("text/parameters") != std::string::npos) {
                // 💡 3. 文本参数推送 (progress: start/curr/end 播放时间进度或 volume: 音量调节)
                std::string body(reinterpret_cast<const char*>(payload), payload_len);
                LOGI("SET_PARAMETER 收到文本参数推送:\n%s", body.c_str());

                auto extract_param_line = [&](const char* key, size_t key_len) -> std::string {
                    size_t search_from = 0;
                    while (search_from < body.size()) {
                        size_t pos = body.find(key, search_from);
                        if (pos == std::string::npos) return {};
                        if (pos == 0 || body[pos - 1] == '\n') {
                            size_t vstart = pos + key_len;
                            while (vstart < body.size() && (body[vstart] == ' ' || body[vstart] == '\t')) vstart++;
                            size_t vend = body.find_first_of("\r\n", vstart);
                            if (vend == std::string::npos) vend = body.size();
                            while (vend > vstart && (body[vend - 1] == ' ' || body[vend - 1] == '\t')) vend--;
                            return body.substr(vstart, vend - vstart);
                        }
                        search_from = pos + 1;
                    }
                    return {};
                };

                // 3.1 音量调节: volume: -15.000000 (单位 dB, -144.0 为静音 MUTE, 0.0 为最大)
                std::string vol_val = extract_param_line("volume:", 7);
                if (!vol_val.empty()) {
                    try {
                        float vol_db = std::stof(vol_val);
                        current_volume_db_.store(vol_db, std::memory_order_relaxed);
                        rtp_.set_volume(vol_db);
                        LOGI("AirPlay 客户端设置音量: %.2f dB %s", vol_db, (vol_db <= -143.0f ? "(MUTE 静音)" : ""));
                    } catch (const std::exception& e) {
                        LOGW("解析 volume 参数异常: %s", e.what());
                    }
                }

                // 3.2 播放时间进度: progress: start/curr/end
                std::string prog_val = extract_param_line("progress:", 9);
                if (!prog_val.empty()) {
                    // 格式: start/curr/end (RTP 时间戳)
                    uint64_t start_ts = 0, curr_ts = 0, end_ts = 0;
                    char s1 = 0, s2 = 0;
                    std::istringstream pstream(prog_val);
                    if (pstream >> start_ts >> s1 >> curr_ts >> s2 >> end_ts && s1 == '/' && s2 == '/') {
                        if (start_ts == 0 && curr_ts == 0 && end_ts == 0) {
                            LOGI("AirPlay 收到暂停/未就绪时间戳 (progress: 0/0/0)，忽略以防进度冲刷归零");
                        } else {
                            const uint32_t rate = current_params_.sample_rate > 0 ? static_cast<uint32_t>(current_params_.sample_rate) : 44100U;
                            // 💡 使用有符号 32-bit 模差，彻底兼容 RFC 3550 32-bit RTP 时间戳回绕
                            const uint32_t start32 = static_cast<uint32_t>(start_ts);
                            const uint32_t curr32 = static_cast<uint32_t>(curr_ts);
                            const uint32_t end32 = static_cast<uint32_t>(end_ts);
                            const int32_t dur_samples = static_cast<int32_t>(end32 - start32);
                            const int32_t curr_diff_samples = static_cast<int32_t>(curr32 - start32);
                            const bool has_valid_duration = (end_ts != 0 && dur_samples > 0);
                            const bool is_live_progress = (end_ts == 0 && start_ts > 0 && curr_diff_samples >= 0);

                            if (has_valid_duration || is_live_progress) {
                                const long dur_ms = has_valid_duration
                                    ? static_cast<long>(static_cast<int64_t>(dur_samples) * 1000LL / rate)
                                    : 0L;

                                // 💡 扬声器真实出声位置统一校准：
                                // 优先使用已写入环形缓冲扣除未消费采样后的 live speaker_ts；
                                // 在 FLUSH/重连后 Sync 门控等待期（尚未写入首帧）自动回退到 NTP+Sync 推导的 target_speaker_ts，
                                // 杜绝裸用发端超前 1.75s 的 curr_ts 虚报进度引发重连断崖回跳。
                                uint32_t speaker_ts = 0;
                                bool is_live_audio = false;
                                uint32_t ref_ts = curr32;
                                bool used_speaker_ts = false;
                                if (rtp_.resolve_speaker_rtp_ts(speaker_ts, is_live_audio)) {
                                    const int32_t speaker_ahead_samples = static_cast<int32_t>(speaker_ts - curr32);
                                    const int32_t min_valid = -static_cast<int32_t>(rate * 5U / 2U); // -2500ms
                                    const int32_t max_valid = static_cast<int32_t>(rate * 7U / 2U);  // +3500ms
                                    if (speaker_ahead_samples >= min_valid && speaker_ahead_samples <= max_valid) {
                                        ref_ts = speaker_ts;
                                        used_speaker_ts = true;
                                    }
                                }

                                // 💡 AirPlay 排队延迟语义契约：
                                // 1) 当 ref_ts 早于 start32（新曲目仍在发端/本地缓冲排队）时，上报精确负偏移 (-queue_delay_ms, 范围 -5000..-2ms)，
                                //    供 Kotlin 协调器将新曲目时钟锚点精准延后至扬声器出声时刻，彻底消除无 FLUSH 切歌 ~2.2s 抢跑回跳；
                                // 2) 当尚未起播且 Sync 锚点不可用 (used_speaker_ts=0 && !is_live_audio) 时上报 -1L 仅同步时长，不发射虚高进度。
                                const int32_t ref_diff_samples = static_cast<int32_t>(ref_ts - start32);
                                long pos_ms = -1L;
                                if (!used_speaker_ts && !is_live_audio && rtp_.is_running()) {
                                    pos_ms = -1L;
                                } else if (ref_diff_samples < 0) {
                                    const long queue_delay_ms = static_cast<long>(
                                        (static_cast<int64_t>(-ref_diff_samples) * 1000LL) / rate
                                    );
                                    pos_ms = -std::clamp(queue_delay_ms, 2L, 5000L);
                                } else {
                                    const long raw_pos_ms = static_cast<long>(
                                        (static_cast<int64_t>(ref_diff_samples) * 1000LL) / rate
                                    );
                                    pos_ms = std::max(0L, raw_pos_ms);
                                    if (has_valid_duration && dur_ms > 0L && pos_ms > dur_ms) {
                                        pos_ms = dur_ms;
                                    }
                                }

                                LOGD("AirPlay %s进度实时校准: pos=%ld ms, dur=%ld ms (used_speaker_ts=%d, live=%d, ref_ts=%u, curr_ts=%u, RTP: %s)",
                                     has_valid_duration ? "" : "直播流", pos_ms, dur_ms,
                                     used_speaker_ts ? 1 : 0, is_live_audio ? 1 : 0,
                                     ref_ts, curr32, prog_val.c_str());
                                if (has_valid_duration && dur_ms > 0L) {
                                    rtp_.set_track_rtp_anchor(start32, dur_ms);
                                } else {
                                    rtp_.invalidate_track_rtp_anchor();
                                }
                                rtp_.update_track_progress(pos_ms, dur_ms);
                                if (progress_cb_) {
                                    progress_cb_(active_session_id_.load(std::memory_order_acquire), next_seq(), pos_ms, dur_ms);
                                }
                            }
                        }
                    }
                }
            }
        }

        response << "RTSP/1.0 200 OK\r\n"
                 << "CSeq: " << cseq << "\r\n\r\n";
    } else if (method == "TEARDOWN") {
        // 💡 会话保活修复：TEARDOWN 属于通道临时拆除（如 iOS 暂停），绝不能在此擦除 AES 密钥与 IV，
        // 否则后续在同一连接或新连接续播时会导致密钥丢失而出现白噪声爆音；密钥生命周期交由 ANNOUNCE 覆盖或客户端断开连接。
        // 同时严禁在此重置 track_progress 为 -1 抹除已播放进度。
        rtp_.stop();
        const uint64_t teardown_ep = AudioEngine::instance().emergency_mute_and_flush(FlushReason::STOP_OR_TEARDOWN);
        rtp_.set_active_epoch(teardown_ep);
        if (stream_stop_cb_) {
            stream_stop_cb_(active_session_id_.load(std::memory_order_acquire), next_seq(), static_cast<int64_t>(teardown_ep));
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
        std::string sdp_lower = to_lower_copy(sdp);
        std::string target_lower = to_lower_copy(target);
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
