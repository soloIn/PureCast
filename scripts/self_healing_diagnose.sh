#!/usr/bin/env bash
set -euo pipefail

TARGET_IP="${1:-192.168.2.179}"
TARGET_PORT="${2:-5555}"
ADB_BIN="${ADB_BIN:-$(which adb || echo "/opt/homebrew/share/android-commandlinetools/platform-tools/adb")}"

echo "=========================================================="
echo "PureCast 声卡独占接管与自愈让渡时序诊断脚本"
echo "目标设备: ${TARGET_IP}:${TARGET_PORT}"
echo "=========================================================="

"${ADB_BIN}" connect "${TARGET_IP}:${TARGET_PORT}" || true

echo ">> 1. 检查 Linux 内核 ALSA 物理声卡注册状态 (/proc/asound/cards):"
"${ADB_BIN}" -s "${TARGET_IP}:${TARGET_PORT}" shell cat /proc/asound/cards || true

echo ""
echo ">> 2. 检查 USB 硬件总线挂载信息 (Sony ZX300A 0x054c):"
"${ADB_BIN}" -s "${TARGET_IP}:${TARGET_PORT}" shell lsusb || true

echo ""
echo ">> 3. 抓取 PureCast 核心日志链路 (前台服务、USB 接管、Bit-Perfect 状态与内核重绑):"
"${ADB_BIN}" -s "${TARGET_IP}:${TARGET_PORT}" logcat -d -v time -s "PureCast:USB:*" "PureCast:Audio:*" "PureCast:UPnP:*" "PureCast:Service:*" "PureCastNative:*" | tail -n 50 || true

echo ""
echo "=========================================================="
echo "自愈与时序断言指引:"
echo "✔ 独占时: 日志出现 'libusb_detach_kernel_driver' 且 ALSA 列表中声卡暂时卸载"
echo "✔ 播放时: 日志出现 'S24_3LE / S32_LE' 高位对齐与 LSB 低位补零"
echo "✔ 空闲时: 暂停 10 秒后触发 'libusb_attach_kernel_driver (USBDEVFS_CONNECT)'"
echo "✔ 自愈完成: /proc/asound/cards 中重新识别出 Sony USB DAC"
echo "=========================================================="
