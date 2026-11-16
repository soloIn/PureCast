#!/usr/bin/env python3
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

TEST_PORT = 8998
LOCAL_IP = "192.168.2.124"
TARGET_HOST = "192.168.2.179"
TARGET_PORT = 7952

def get_local_ip():
    try:
        out = subprocess.check_output(["ipconfig", "getifaddr", "en0"]).decode().strip()
        if out:
            return out
    except Exception:
        pass
    return LOCAL_IP

def generate_sine_wav(filename, duration_sec=268, sample_rate=44100, bit_depth=16, freq=440.0):
    num_samples = int(duration_sec * sample_rate)
    num_channels = 2
    bytes_per_sample = bit_depth // 8
    block_align = num_channels * bytes_per_sample
    byte_rate = sample_rate * block_align
    data_size = num_samples * block_align
    total_file_size = 36 + data_size

    with open(filename, "wb") as f:
        f.write(b"RIFF")
        f.write(struct.pack("<I", total_file_size))
        f.write(b"WAVE")
        f.write(b"fmt ")
        f.write(struct.pack("<I", 16))
        f.write(struct.pack("<H", 1)) # PCM
        f.write(struct.pack("<H", num_channels))
        f.write(struct.pack("<I", sample_rate))
        f.write(struct.pack("<I", byte_rate))
        f.write(struct.pack("<H", block_align))
        f.write(struct.pack("<H", bit_depth))
        f.write(b"data")
        f.write(struct.pack("<I", data_size))

        # 写入前 1 秒的音频数据，剩余部分通过 seek 快速生成稀疏文件
        first_sec_samples = min(sample_rate, num_samples)
        amplitude = 0.3 * (2**(bit_depth - 1) - 1)
        for i in range(first_sec_samples):
            val = int(amplitude * math.sin(2.0 * math.pi * freq * (i / sample_rate)))
            packed = struct.pack("<h", val)
            f.write(packed)
            f.write(packed)
        f.seek(total_file_size - 1)
        f.write(b"\x00")

def start_http_server(directory, port):
    class QuietHandler(SimpleHTTPRequestHandler):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, directory=directory, **kwargs)
        def log_message(self, format, *args):
            pass

    server = HTTPServer(("0.0.0.0", port), QuietHandler)
    t = threading.Thread(target=server.serve_forever, daemon=True)
    t.start()
    return server

def send_soap_action_via_curl(action, body_xml):
    soap_body = f"""<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:{action} xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      {body_xml}
    </u:{action}>
  </s:Body>
</s:Envelope>"""
    cmd = [
        "curl",
        "-s", "--connect-timeout", "2", "-X", "POST", f"http://{TARGET_HOST}:{TARGET_PORT}/AVTransport/control",
        "-H", "Content-Type: text/xml; charset=\"utf-8\"",
        "-H", f"SOAPAction: \"urn:schemas-upnp-org:service:AVTransport:1#{action}\"",
        "-d", soap_body
    ]
    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if not res.stdout:
        # Fallback to socks proxy if needed
        cmd_proxy = ["curl", "-x", "socks5h://127.0.0.1:6153"] + cmd[1:]
        res = subprocess.run(cmd_proxy, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    return res.stdout

def main():
    local_ip = get_local_ip()
    temp_dir = "/tmp/purecast_lyrics_test"
    os.makedirs(temp_dir, exist_ok=True)
    wav_path = os.path.join(temp_dir, "test_yellow_268.wav")
    duration_sec = 268
    generate_sine_wav(wav_path, duration_sec=duration_sec)

    server = start_http_server(temp_dir, TEST_PORT)
    stream_url = f"http://{local_ip}:{TEST_PORT}/test_yellow_268.wav"
    print(f">> 本地音频服务: {stream_url}")

    # 元数据: Yellow - Coldplay (267秒)
    didl = f"""&lt;DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"&gt;&lt;item id="1" parentID="0" restricted="1"&gt;&lt;dc:title&gt;Yellow&lt;/dc:title&gt;&lt;upnp:artist&gt;Coldplay&lt;/upnp:artist&gt;&lt;upnp:album&gt;Parachutes&lt;/upnp:album&gt;&lt;res duration="00:04:27" protocolInfo="http-get:*:audio/wav:*"&gt;{stream_url}&lt;/res&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;"""

    set_uri_xml = f"""<InstanceID>0</InstanceID><CurrentURI>{stream_url}</CurrentURI><CurrentURIMetaData>{didl}</CurrentURIMetaData>"""
    print(">> 发送 SetAVTransportURI...")
    send_soap_action_via_curl("SetAVTransportURI", set_uri_xml)

    time.sleep(1)

    play_xml = "<InstanceID>0</InstanceID><Speed>1</Speed>"
    print(">> 发送 Play...")
    send_soap_action_via_curl("Play", play_xml)

    print(f">> 保持播放中，等待 8 秒观察歌词拉取...")
    time.sleep(8)

    print(">> 发送 Stop...")
    stop_xml = "<InstanceID>0</InstanceID>"
    send_soap_action_via_curl("Stop", stop_xml)

if __name__ == "__main__":
    main()
