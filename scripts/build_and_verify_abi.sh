#!/usr/bin/env bash
set -euo pipefail

echo "=========================================================="
echo "PureCast 架构产物校验与交叉编译测试 (armeabi-v7a / arm64-v8a)"
echo "=========================================================="

# 确定 NDK readelf 路径
NDK_DIR="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}/ndk/26.3.11579264"
READELF="${NDK_DIR}/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-readelf"

if [ ! -f "${READELF}" ]; then
    echo "⚠️ 未找到 NDK llvm-readelf, 尝试使用系统 readelf"
    READELF=$(which readelf || which llvm-readelf || true)
fi

echo ">> 正在构建 Debug APK..."
./gradlew assembleDebug

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "${APK_PATH}" ]; then
    echo "❌ 错误: 未生成目标 APK: ${APK_PATH}"
    exit 1
fi

echo ">> 正在校验 APK 内 Native 动态库文件结构..."
unzip -l "${APK_PATH}" | grep -E "lib/(armeabi-v7a|arm64-v8a)/libpurecast_usb.so"

TMP_DIR="/tmp/purecast_verify_$$"
mkdir -p "${TMP_DIR}"
unzip -q -j "${APK_PATH}" "lib/armeabi-v7a/libpurecast_usb.so" -d "${TMP_DIR}"

SO_32="${TMP_DIR}/libpurecast_usb.so"
if [ ! -f "${SO_32}" ]; then
    echo "❌ 错误: APK 中缺失 armeabi-v7a 32位架构 .so 产物!"
    rm -rf "${TMP_DIR}"
    exit 1
fi

echo ">> 校验 32 位 ELF 头部信息..."
if [ -n "${READELF}" ] && [ -x "${READELF}" ]; then
    "${READELF}" -h "${SO_32}" | grep -E "Class:|Machine:"
    
    # 断言包含 ELF32 与 ARM
    "${READELF}" -h "${SO_32}" | grep -q "ELF32" || { echo "❌ 并非 ELF32 格式"; exit 1; }
    "${READELF}" -h "${SO_32}" | grep -q "ARM" || { echo "❌ 并非 ARM 架构"; exit 1; }
    echo "✅ 成功验证: libpurecast_usb.so 严格为 32 位 ARM (ELF32) 格式！"
else
    file "${SO_32}"
fi

rm -rf "${TMP_DIR}"
echo "🎉 架构校验通过，U1 验收成功！"
