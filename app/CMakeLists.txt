cmake_minimum_required(VERSION 3.22.1)

project("purecast_usb")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

# 💡 针对 armeabi-v7a 32 位架构开启 NEON 向量化加速与 O3 优化
if(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv7-a -mfpu=neon -mfloat-abi=softfp -O3 -fvisibility=hidden")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv7-a -mfpu=neon -mfloat-abi=softfp -O3 -fvisibility=hidden")
else()
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -fvisibility=hidden")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -fvisibility=hidden")
endif()

# -------------------------------------------------------------
# libusb-1.0 静态库定义 (Rootless usbfs 方案)
# -------------------------------------------------------------
set(LIBUSB_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/libusb)

set(LIBUSB_SRC
    ${LIBUSB_ROOT}/core.c
    ${LIBUSB_ROOT}/descriptor.c
    ${LIBUSB_ROOT}/hotplug.c
    ${LIBUSB_ROOT}/io.c
    ${LIBUSB_ROOT}/strerror.c
    ${LIBUSB_ROOT}/sync.c
    ${LIBUSB_ROOT}/os/linux_usbfs.c
    ${LIBUSB_ROOT}/os/linux_netlink.c
    ${LIBUSB_ROOT}/os/events_posix.c
    ${LIBUSB_ROOT}/os/threads_posix.c
)

add_library(libusb_static STATIC ${LIBUSB_SRC})

target_include_directories(libusb_static PUBLIC
    ${LIBUSB_ROOT}
    ${LIBUSB_ROOT}/os
)

target_compile_definitions(libusb_static PRIVATE
    _GNU_SOURCE=1
    HAVE_CONFIG_H=1
)

# -------------------------------------------------------------
# PureCast USB Native 核心库 (libpurecast_usb.so)
# -------------------------------------------------------------
set(PURECAST_NATIVE_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp)

set(PURECAST_SRC
    ${PURECAST_NATIVE_ROOT}/jni_bridge.cpp
    ${PURECAST_NATIVE_ROOT}/audio_engine.cpp
    ${PURECAST_NATIVE_ROOT}/dr_flac.c
    ${PURECAST_NATIVE_ROOT}/flac_decoder.cpp
    ${PURECAST_NATIVE_ROOT}/crypto/aes.c
    ${PURECAST_NATIVE_ROOT}/alac/alac_decoder.cpp
    ${PURECAST_NATIVE_ROOT}/raop/raop_server.cpp
)

add_library(purecast_usb SHARED ${PURECAST_SRC})

target_include_directories(purecast_usb PRIVATE
    ${PURECAST_NATIVE_ROOT}
    ${PURECAST_NATIVE_ROOT}/crypto
    ${PURECAST_NATIVE_ROOT}/alac
    ${PURECAST_NATIVE_ROOT}/raop
    ${LIBUSB_ROOT}
)

find_library(log-lib log)

target_link_libraries(purecast_usb
    PRIVATE
    libusb_static
    ${log-lib}
)
