cmake_minimum_required(VERSION 3.16)

project(purecast_native_tests LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

option(ENABLE_SANITIZERS "Build host tests with ASan+UBSan and TSan variants" ON)

set(CPP_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/..")

set(ENGINE_SOURCES
    "${CMAKE_CURRENT_SOURCE_DIR}/mocks/libusb_mock.cpp"
    "${CPP_ROOT}/audio_engine.cpp"
)

set(PROTOCOL_SOURCES
    "${CMAKE_CURRENT_SOURCE_DIR}/protocol_native_test.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/mocks/libusb_mock.cpp"
    "${CPP_ROOT}/audio_engine.cpp"
    "${CPP_ROOT}/flac_decoder.cpp"
    "${CPP_ROOT}/dr_flac.c"
    "${CPP_ROOT}/crypto/aes.c"
    "${CPP_ROOT}/alac/alac.c"
    "${CPP_ROOT}/alac/alac_decoder.cpp"
    "${CPP_ROOT}/raop/dmap_parser.cpp"
    "${CPP_ROOT}/raop/raop_jitter_buffer.cpp"
    "${CPP_ROOT}/raop/raop_rtp.cpp"
    "${CPP_ROOT}/raop/raop_server.cpp"
)

function(add_purecast_test_target target_name sources_list)
    add_executable(${target_name} ${sources_list})
    target_include_directories(${target_name} PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/mocks"
        "${CPP_ROOT}"
    )
    target_compile_options(${target_name} PRIVATE
        $<$<COMPILE_LANGUAGE:CXX>:-Wall -Wextra -Wno-unused-parameter>
        -O1
        -g
        -fno-omit-frame-pointer
    )
    find_package(Threads REQUIRED)
    target_link_libraries(${target_name} PRIVATE Threads::Threads)
endfunction()

enable_testing()

if(ENABLE_SANITIZERS)
    # 1. AddressSanitizer + UndefinedBehaviorSanitizer (AudioEngine + 协议解码链)
    add_purecast_test_target(audio_engine_test_asan "${CMAKE_CURRENT_SOURCE_DIR}/audio_engine_test.cpp;${ENGINE_SOURCES}")
    target_compile_options(audio_engine_test_asan PRIVATE -fsanitize=address,undefined)
    target_link_options(audio_engine_test_asan PRIVATE -fsanitize=address,undefined)
    add_test(NAME audio_engine_test_asan COMMAND audio_engine_test_asan)

    add_purecast_test_target(protocol_native_test_asan "${PROTOCOL_SOURCES}")
    target_compile_options(protocol_native_test_asan PRIVATE -fsanitize=address,undefined)
    target_link_options(protocol_native_test_asan PRIVATE -fsanitize=address,undefined)
    add_test(NAME protocol_native_test_asan COMMAND protocol_native_test_asan)

    # 2. ThreadSanitizer (SPSC 与 Epoch/WriteBarrier/RAOP 线程竞态强校验)
    add_purecast_test_target(audio_engine_test_tsan "${CMAKE_CURRENT_SOURCE_DIR}/audio_engine_test.cpp;${ENGINE_SOURCES}")
    target_compile_options(audio_engine_test_tsan PRIVATE -fsanitize=thread)
    target_link_options(audio_engine_test_tsan PRIVATE -fsanitize=thread)
    add_test(NAME audio_engine_test_tsan COMMAND audio_engine_test_tsan)

    add_purecast_test_target(protocol_native_test_tsan "${PROTOCOL_SOURCES}")
    target_compile_options(protocol_native_test_tsan PRIVATE -fsanitize=thread)
    target_link_options(protocol_native_test_tsan PRIVATE -fsanitize=thread)
    add_test(NAME protocol_native_test_tsan COMMAND protocol_native_test_tsan)
else()
    add_purecast_test_target(audio_engine_test "${CMAKE_CURRENT_SOURCE_DIR}/audio_engine_test.cpp;${ENGINE_SOURCES}")
    add_test(NAME audio_engine_test COMMAND audio_engine_test)

    add_purecast_test_target(protocol_native_test "${PROTOCOL_SOURCES}")
    add_test(NAME protocol_native_test COMMAND protocol_native_test)
endif()
