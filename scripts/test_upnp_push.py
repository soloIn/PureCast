#!/usr/bin/env python3
"""
PureCast 端到端 UPnP 音频推流测试脚本
基于标准 Python3 库，无需外部依赖。
生成标准 44.1kHz / 16-bit 双声道正弦波音频流，并通过 UPnP AVTransport 推送至 PureCast TV。
"""

import sys
import os
import math
import struct
import socket
import threading
import time
import subprocess
import urllib.request
import urllib.error
from http.server import HTTPServer, SimpleHTTPRequestHandler

TEST_PORT = 8999
AUDIO_FILE_NAME = "purecast_test_tone.wav"

def get_local_ip():
    import subprocess
    try:
        out = subprocess.check_output(["ipconfig", "getifaddr", "en0"]).decode().strip()
        if out:
            return out
    except Exception:
        pass
    return "192.168.2.124"

def generate_sine_wav(filename, duration_sec=5, sample_rate=44100, freq=440.0):
    """生成 44.1kHz / 16-bit 双声道标准正弦波 WAV 文件"""
    num_samples = int(duration_sec * sample_rate)
    num_channels = 2
    bits_per_sample = 16
    bytes_per_sample = bits_per_sample // 8
    block_align = num_channels * bytes_per_sample
    byte_rate = sample_rate * block_align
    data_size = num_samples * block_align
    chunk_size = 36 + data_size

    with open(filename, "wb") as f:
        # RIFF header
        f.write(b"RIFF")
        f.write(struct.pack("<I", chunk_size))
        f.write(b"WAVE")

        # fmt chunk
        f.write(b"fmt ")
        f.write(struct.pack("<I", 16)) # Subchunk1Size (16 for PCM)
        f.write(struct.pack("<H", 1))  # AudioFormat (1 for PCM)
        f.write(struct.pack("<H", num_channels))
        f.write(struct.pack("<I", sample_rate))
        f.write(struct.pack("<I", byte_rate))
        f.write(struct.pack("<H", block_align))
        f.write(struct.pack("<H", bits_per_sample))

        # data chunk
        f.write(b"data")
        f.write(struct.pack("<I", data_size))

        # PCM 载荷
        for i in range(num_samples):
            t = float(i) / sample_rate
            # 振幅 0.5 (-6dBFS)，防止削顶
            val = int(0.5 * 32767.0 * math.sin(2.0 * math.pi * freq * t))
            sample_bytes = struct.pack("<h", val)
            f.write(sample_bytes) # Left
            f.write(sample_bytes) # Right

    print(f"✅ 成功生成测试音频: {filename} ({duration_sec}s, {sample_rate}Hz, 16bit, 440Hz)")

def send_soap_action(target_host, target_port, action_name, action_xml):
    soap_body = f"""<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:{action_name} xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      {action_xml}
    </u:{action_name}>
  </s:Body>
</s:Envelope>"""

    body_bytes = soap_body.encode("utf-8")
    http_req = (
        f"POST /AVTransport/control HTTP/1.1\r\n"
        f"Host: {target_host}:{target_port}\r\n"
        f"Content-Type: text/xml; charset=\"utf-8\"\r\n"
        f"SOAPACTION: \"urn:schemas-upnp-org:service:AVTransport:1#{action_name}\"\r\n"
        f"Content-Length: {len(body_bytes)}\r\n"
        f"Connection: close\r\n\r\n"
    ).encode("utf-8") + body_bytes

    try:
        s = socket.socket()
        s.settimeout(2)
        s.connect((target_host, target_port))
    except Exception:
        # 💡 若局域网被本地路由拦截，优雅走本地 SOCKS5 代理 (Surge 6153)
        s = socket.socket()
        s.settimeout(4)
        s.connect(("127.0.0.1", 6153))
        s.sendall(b"\x05\x01\x00")
        s.recv(2)
        cmd = b"\x05\x01\x00\x01" + socket.inet_aton(target_host) + struct.pack(">H", target_port)
        s.sendall(cmd)
        rep = s.recv(10)
        if len(rep) < 2 or rep[1] != 0:
            print(f"⚠️ SOCKS5 连接失败: {rep}")
            return None

    try:
        s.sendall(http_req)
        resp = s.recv(4096).decode("utf-8", errors="ignore")
        print(f">> SOAP {action_name} 结果: {resp.splitlines()[0] if resp else 'empty'}")
        return resp
    except Exception as e:
        print(f"⚠️ 发送 SOAP 请求异常: {e}")
        return None
    finally:
        s.close()

def start_http_server(directory, port):
    class QuietHandler(SimpleHTTPRequestHandler):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, directory=directory, **kwargs)
        def log_message(self, format, *args):
            print(f">> HTTP 客户端拉取音频: {args[0]}")

    server = HTTPServer(("0.0.0.0", port), QuietHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server

def main():
    target_host = sys.argv[1] if len(sys.argv) > 1 else "192.168.2.179"
    target_port = int(sys.argv[2]) if len(sys.argv) > 2 else 7952
    duration = int(sys.argv[3]) if len(sys.argv) > 3 else 5
    fmt = sys.argv[4].lower() if len(sys.argv) > 4 else "wav"

    local_ip = get_local_ip()
    temp_dir = "/tmp/purecast_test"
    os.makedirs(temp_dir, exist_ok=True)
    wav_path = os.path.join(temp_dir, "purecast_test_tone.wav")

    generate_sine_wav(wav_path, duration_sec=duration, sample_rate=44100, freq=440.0)

    if fmt == "flac":
        file_name = "purecast_test_tone.flac"
        flac_path = os.path.join(temp_dir, file_name)
        subprocess.check_call(["ffmpeg", "-y", "-i", wav_path, flac_path], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        mime = "audio/flac"
    else:
        file_name = "purecast_test_tone.wav"
        mime = "audio/wav"

    server = start_http_server(temp_dir, TEST_PORT)
    print(f">> 本地 HTTP 音频服务已启动: http://{local_ip}:{TEST_PORT}/{file_name} ({mime})")

    stream_url = f"http://{local_ip}:{TEST_PORT}/{file_name}"
    didl_metadata = f"""&lt;DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"&gt;&lt;item id="1" parentID="0" restricted="1"&gt;&lt;dc:title&gt;PureCast 440Hz Test Tone ({fmt.upper()})&lt;/dc:title&gt;&lt;upnp:artist&gt;PureCast Test Suite&lt;/upnp:artist&gt;&lt;upnp:album&gt;Lossless Benchmark&lt;/upnp:album&gt;&lt;res protocolInfo="http-get:*:{mime}:*"&gt;""" + stream_url + """&lt;/res&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;"""

    print(f">> 向 PureCast MediaRenderer ({target_host}:{target_port}) 发起推流 ({fmt.upper()})...")
    
    # 1. SetAVTransportURI
    set_uri_xml = f"""<InstanceID>0</InstanceID><CurrentURI>{stream_url}</CurrentURI><CurrentURIMetaData>{didl_metadata}</CurrentURIMetaData>"""
    send_soap_action(target_host, target_port, "SetAVTransportURI", set_uri_xml)

    time.sleep(1)

    # 2. Play
    play_xml = "<InstanceID>0</InstanceID><Speed>1</Speed>"
    send_soap_action(target_host, target_port, "Play", play_xml)

    print(">> 正在推流中 (等待 4 秒监听音频)...")
    time.sleep(4)

    # 3. Stop
    stop_xml = "<InstanceID>0</InstanceID>"
    send_soap_action(target_host, target_port, "Stop", stop_xml)
    print(">> 测试推流完成，已发送 Stop 指令")

if __name__ == "__main__":
    main()
