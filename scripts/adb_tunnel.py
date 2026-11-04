#!/usr/bin/env python3
"""
PureCast 本地 ADB 透明中继代理
通过本地 SOCKS5 代理 (127.0.0.1:6153) 将 127.0.0.1:5555 转发到 192.168.2.179:5555
绕过 macOS Enhanced Mode 局域网直连 Errno 65 (No route to host) 限制。
"""

import socket
import select
import struct
import sys
import threading

LOCAL_PORT = 5555
REMOTE_HOST = "192.168.2.179"
REMOTE_PORT = 5555
SOCKS_HOST = "127.0.0.1"
SOCKS_PORT = 6153

def create_socks5_connection(target_host, target_port):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.connect((SOCKS_HOST, SOCKS_PORT))
    # SOCKS5 greeting: VER 0x05, 1 method, NO AUTH (0x00)
    s.sendall(b"\x05\x01\x00")
    resp = s.recv(2)
    if resp != b"\x05\x00":
        s.close()
        raise RuntimeError(f"SOCKS5 认证握手失败: {resp}")

    # CONNECT command
    ip_bytes = socket.inet_aton(target_host)
    cmd = b"\x05\x01\x00\x01" + ip_bytes + struct.pack(">H", target_port)
    s.sendall(cmd)
    reply = s.recv(10)
    if len(reply) < 2 or reply[1] != 0:
        s.close()
        raise RuntimeError(f"SOCKS5 连接目标失败, 响应码: {reply[1] if len(reply)>1 else 'unknown'}")
    return s

def forward(src, dst):
    try:
        while True:
            data = src.recv(32768)
            if not data:
                break
            dst.sendall(data)
    except Exception:
        pass
    finally:
        try:
            src.close()
        except Exception:
            pass
        try:
            dst.close()
        except Exception:
            pass

def handle_client(client_sock):
    try:
        remote_sock = create_socks5_connection(REMOTE_HOST, REMOTE_PORT)
    except Exception as e:
        print(f"中继建立远端连接失败: {e}", file=sys.stderr)
        client_sock.close()
        return

    t1 = threading.Thread(target=forward, args=(client_sock, remote_sock), daemon=True)
    t2 = threading.Thread(target=forward, args=(remote_sock, client_sock), daemon=True)
    t1.start()
    t2.start()

def main():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", LOCAL_PORT))
    server.listen(5)
    print(f"ADB Tunnel 运行中: 127.0.0.1:{LOCAL_PORT} -> {REMOTE_HOST}:{REMOTE_PORT} via SOCKS5 {SOCKS_PORT}", flush=True)

    while True:
        client, addr = server.accept()
        threading.Thread(target=handle_client, args=(client,), daemon=True).start()

if __name__ == "__main__":
    main()
