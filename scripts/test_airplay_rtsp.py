#!/usr/bin/env python3
import socket
import sys
import time

def send_rtsp_req(sock, req_str):
    print(f"\n--- [C -> S] ---\n{req_str.strip()}")
    sock.sendall(req_str.encode("utf-8"))
    
    # 读取响应
    sock.settimeout(3.0)
    response = b""
    while True:
        try:
            chunk = sock.recv(4096)
            if not chunk:
                break
            response += chunk
            if b"\r\n\r\n" in response:
                break
        except socket.timeout:
            break
    print(f"--- [S -> C] ---\n{response.decode('utf-8', errors='ignore').strip()}")
    return response.decode('utf-8', errors='ignore')

def main():
    target_ip = sys.argv[1] if len(sys.argv) > 1 else "192.168.2.179"
    target_port = int(sys.argv[2]) if len(sys.argv) > 2 else 5000

    print(f"Connecting to PureCast AirPlay RTSP on {target_ip}:{target_port}...")
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.connect((target_ip, target_port))

    # 1. OPTIONS
    req_options = (
        f"OPTIONS rtsp://{target_ip}/123456 RTSP/1.0\r\n"
        "CSeq: 1\r\n"
        "User-Agent: AirPlay/220.68\r\n"
        "Client-Instance: 0123456789ABCDEF\r\n\r\n"
    )
    resp = send_rtsp_req(s, req_options)
    assert "200 OK" in resp, "OPTIONS failed"

    # 2. ANNOUNCE
    sdp_body = (
        "v=0\r\n"
        "o=AirTunes 123456 0 IN IP4 192.168.2.1\r\n"
        "s=AirTunes\r\n"
        "t=0 0\r\n"
        "m=audio 0 RTP/AVP 96\r\n"
        "a=rtpmap:96 AppleLossless\r\n"
        "a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100\r\n"
        "a=rsaaeskey:dGVzdGtleQ==\r\n"
        "a=aesiv:dGVzdGl2MTIzNDU2\r\n"
    )
    req_announce = (
        f"ANNOUNCE rtsp://{target_ip}/123456 RTSP/1.0\r\n"
        "CSeq: 2\r\n"
        "Content-Type: application/sdp\r\n"
        f"Content-Length: {len(sdp_body)}\r\n\r\n"
        f"{sdp_body}"
    )
    resp = send_rtsp_req(s, req_announce)
    assert "200 OK" in resp, "ANNOUNCE failed"

    # 3. SETUP
    req_setup = (
        f"SETUP rtsp://{target_ip}/123456 RTSP/1.0\r\n"
        "CSeq: 3\r\n"
        "Transport: RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=6001;timing_port=6002\r\n"
        "Active-Remote: 1234567890\r\n"
        "DACP-ID: 0123456789ABCDEF\r\n\r\n"
    )
    resp = send_rtsp_req(s, req_setup)
    assert "200 OK" in resp, "SETUP failed"

    # 4. RECORD
    req_record = (
        f"RECORD rtsp://{target_ip}/123456 RTSP/1.0\r\n"
        "CSeq: 4\r\n"
        "Range: npt=0-\r\n"
        "RTP-Info: seq=1;rtptime=0\r\n\r\n"
    )
    resp = send_rtsp_req(s, req_record)
    assert "200 OK" in resp, "RECORD failed"

    time.sleep(2)

    # 5. TEARDOWN
    req_teardown = (
        f"TEARDOWN rtsp://{target_ip}/123456 RTSP/1.0\r\n"
        "CSeq: 5\r\n\r\n"
    )
    resp = send_rtsp_req(s, req_teardown)
    assert "200 OK" in resp, "TEARDOWN failed"

    s.close()
    print("\n🎉 AirPlay 1 RTSP 状态机真机交互测试全部成功通过！")

if __name__ == "__main__":
    main()
