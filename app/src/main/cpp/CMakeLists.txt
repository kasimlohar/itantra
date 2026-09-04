cmake_minimum_required(VERSION 3.22)
project(itantra-native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

add_library(itantra-native SHARED
    native-lib.cpp
    audio/audio_capture.cpp
    audio/ring_buffer.h
    vad/vad_fsm.cpp
    asr/asr_engine.cpp
    tts/tts_engine.cpp
)

target_include_directories(itantra-native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# PRD 4.1.2 compiler constraints
target_compile_options(itantra-native PRIVATE
    -Wall -Wextra -Wpedantic -Werror
    -O2
    -fvisibility=hidden
)

if(SANITIZE)
    target_compile_options(itantra-native PRIVATE
        -g -O1 -fsanitize=address,undefined -fno-omit-frame-pointer
    )
    target_link_options(itantra-native PRIVATE
        -fsanitize=address,undefined
    )
endif()

find_library(log-lib log)
target_link_libraries(itantra-native PRIVATE ${log-lib})
