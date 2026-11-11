cmake_minimum_required(VERSION 3.16)

project(purecast_native_tests LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

option(ENABLE_SANITIZERS "Build host tests with ASan+UBSan and TSan variants" ON)

set(CPP_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/..")

set(TEST_SOURCES
    "${CMAKE_CURRENT_SOURCE_DIR}/audio_engine_test.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/mocks/libusb_mock.cpp"
    "${CPP_ROOT}/audio_engine.cpp"
)

function(add_purecast_test_target target_name)
    add_executable(${target_name} ${TEST_SOURCES})
    target_include_directories(${target_name} PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/mocks"
        "${CPP_ROOT}"
    )
    target_compile_options(${target_name} PRIVATE
        -Wall
        -Wextra
        -Wno-unused-parameter
        -O1
        -g
        -fno-omit-frame-pointer
    )
    find_package(Threads REQUIRED)
    target_link_libraries(${target_name} PRIVATE Threads::Threads)
endfunction()

enable_testing()

if(ENABLE_SANITIZERS)
    # 1. AddressSanitizer + UndefinedBehaviorSanitizer
    add_purecast_test_target(audio_engine_test_asan)
    target_compile_options(audio_engine_test_asan PRIVATE -fsanitize=address,undefined)
    target_link_options(audio_engine_test_asan PRIVATE -fsanitize=address,undefined)
    add_test(NAME audio_engine_test_asan COMMAND audio_engine_test_asan)

    # 2. ThreadSanitizer (SPSC 与 Epoch 无锁竞态强校验)
    add_purecast_test_target(audio_engine_test_tsan)
    target_compile_options(audio_engine_test_tsan PRIVATE -fsanitize=thread)
    target_link_options(audio_engine_test_tsan PRIVATE -fsanitize=thread)
    add_test(NAME audio_engine_test_tsan COMMAND audio_engine_test_tsan)
else()
    add_purecast_test_target(audio_engine_test)
    add_test(NAME audio_engine_test COMMAND audio_engine_test)
endif()
