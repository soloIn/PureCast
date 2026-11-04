#include "raop_server.h"
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <fcntl.h>
#include <sstream>
#include <android/log.h>

#define RAOP_TAG "PureCastRAOP"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, RAOP_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, RAOP_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, RAOP_TAG, __VA_ARGS__)

namespace purecast::raop {

RaopServer::RaopServer() = default;

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
            size_t content_length = content_length_str.empty() ? 0 : std::stoul(content_length_str);

            size_t total_expected = header_end + 4 + content_length;
            if (accumulated_data.size() >= total_expected) {
                std::string full_request = accumulated_data.substr(0, total_expected);
                accumulated_data.erase(0, total_expected);
                handle_rtsp_request(client_fd, full_request);
            }
        }
    }
}

void RaopServer::handle_rtsp_request(int client_fd, const std::string& request) {
    std::istringstream stream(request);
    std::string method, uri, version;
    stream >> method >> uri >> version;

    std::string cseq = parse_header(request, "CSeq");
    LOGI("处理 RTSP 方法: %s, CSeq: %s", method.c_str(), cseq.c_str());

    std::ostringstream response;

    if (method == "OPTIONS") {
        response << "RTSP/1.0 200 OK\r\n"
                 << "CSeq: " << cseq << "\r\n"
                 << "Public: ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER\r\n"
                 << "Server: AirTunes/220.68\r\n\r\n";
    } else if (method == "ANNOUNCE") {
        // 解析 SDP 中的 rsaaeskey 与 aesiv
        current_params_.aes_key_base64 = parse_sdp_attribute(request, "rsaaeskey");
        current_params_.aes_iv_base64 = parse_sdp_attribute(request, "aesiv");
        LOGI("ANNOUNCE 提取密钥: key_len=%zu, iv_len=%zu",
             current_params_.aes_key_base64.size(), current_params_.aes_iv_base64.size());

        response << "RTSP/1.0 200 OK\r\n"
                 << "CSeq: " << cseq << "\r\n"
                 << "Server: AirTunes/220.68\r\n\r\n";
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
        }

        bool accepted = true;
        if (stream_request_cb_) {
            accepted = stream_request_cb_(current_params_);
        }

        if (accepted) {
            response << "RTSP/1.0 200 OK\r\n"
                     << "CSeq: " << cseq << "\r\n"
                     << "Transport: RTP/AVP/UDP;unicast;mode=record;server_port=6000;control_port=6001;timing_port=6002\r\n"
                     << "Session: 1\r\n"
                     << "Audio-Jack-Status: connected; type=digital\r\n"
                     << "Server: AirTunes/220.68\r\n\r\n";
        } else {
            response << "RTSP/1.0 503 Service Unavailable\r\n"
                     << "CSeq: " << cseq << "\r\n\r\n";
        }
    } else if (method == "RECORD") {
        response << "RTSP/1.0 200 OK\r\n"
                 << "CSeq: " << cseq << "\r\n"
                 << "Audio-Latency: 11025\r\n"
                 << "Server: AirTunes/220.68\r\n\r\n";
    } else if (method == "SET_PARAMETER") {
        // DMAP 元数据推送
        response << "RTSP/1.0 200 OK\r\n"
                 << "CSeq: " << cseq << "\r\n\r\n";
    } else if (method == "TEARDOWN") {
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
