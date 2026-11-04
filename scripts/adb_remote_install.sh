#!/usr/bin/env bash
set -euo pipefail

TARGET_IP="${1:-192.168.2.179}"
TARGET_PORT="${2:-5555}"
ADB_BIN="${ADB_BIN:-$(which adb || echo "/opt/homebrew/share/android-commandlinetools/platform-tools/adb")}"

echo "=========================================================="
echo "PureCast 电视端无线静默部署与全自动权限授权脚本"
echo "目标设备: ${TARGET_IP}:${TARGET_PORT}"
echo "=========================================================="

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "${APK_PATH}" ]; then
    echo ">> APK 产物不存在，开始构建..."
    ./gradlew assembleDebug
fi

echo ">> 尝试无线连接目标设备..."
"${ADB_BIN}" connect "${TARGET_IP}:${TARGET_PORT}" || true

DEVICE_STATE=$("${ADB_BIN}" devices | grep "${TARGET_IP}" | awk '{print $2}' || true)
if [ "${DEVICE_STATE}" != "device" ]; then
    echo "⚠️ 警告: 设备 ${TARGET_IP}:${TARGET_PORT} 未就绪 (当前状态: ${DEVICE_STATE:-离线})。"
    echo "提示: 请确保电视盒子处于局域网中且已开启网络调试。"
    echo "如果本地调试，可连接本地模拟器测试。"
fi

echo ">> 静默安装 APK (${APK_PATH})..."
"${ADB_BIN}" -s "${TARGET_IP}:${TARGET_PORT}" install -r -d "${APK_PATH}" || {
    echo "⚠️ 安装尝试退出，如果在离线状态请先检查网络";
}

PACKAGE="com.solo.purecast"

echo ">> 自动授予运行所需各项系统特权..."
"${ADB_BIN}" -s "${TARGET_IP}:${TARGET_PORT}" shell pm grant "${PACKAGE}" android.permission.RECORD_AUDIO || true
"${ADB_BIN}" -s "${TARGET_IP}:${TARGET_PORT}" shell appops set "${PACKAGE}" SYSTEM_ALERT_WINDOW allow || true
"${ADB_BIN}" -s "${TARGET_IP}:${TARGET_PORT}" shell dumpsys deviceidle whitelist "+${PACKAGE}" || true
"${ADB_BIN}" -s "${TARGET_IP}:${TARGET_PORT}" shell cmd appops set "${PACKAGE}" AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore || true

echo ">> 启动 PureCast Leanback 交互界面..."
"${ADB_BIN}" -s "${TARGET_IP}:${TARGET_PORT}" shell am start -n "${PACKAGE}/.ui.MainActivity" || true

echo ">> 校验前台服务存活状态..."
"${ADB_BIN}" -s "${TARGET_IP}:${TARGET_PORT}" shell dumpsys activity services | grep -i "PlaybackService" || true

echo "🎉 部署与权限自动化授予流程执行完毕！"
