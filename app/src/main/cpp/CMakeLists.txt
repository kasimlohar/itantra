cmake_minimum_required(VERSION 3.22)
project(itantra-native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

option(BUILD_TESTING "Build tests" OFF)

# Oboe 1.8.1 — Fetch deferred to next phase where NDK prebuilt is linked.
# For this slice, AudioCapture uses stub (no Oboe headers) and is testable via onAudioReady direct call.
# if(ANDROID)
#   include(FetchContent)
#   FetchContent_Declare(oboe URL https://github.com/google/oboe/archive/refs/tags/1.8.1.zip)
#   FetchContent_MakeAvailable(oboe)
# endif()

add_library(itantra-native SHARED
    native-lib.cpp
    audio/audio_capture.cpp
    vad/vad_fsm.cpp
    vad/silero_vad.cpp
    vad/vad_pipeline.cpp
    asr/asr_engine.cpp
    tts/tts_engine.cpp
)

target_include_directories(itantra-native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

if(BUILD_TESTING)
  include(FetchContent)
  FetchContent_Declare(
    googletest
    URL https://github.com/google/googletest/archive/v1.14.0.zip
  )
  set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
  FetchContent_MakeAvailable(googletest)
  enable_testing()
  add_executable(ring_buffer_test tests/ring_buffer_test.cpp)
  target_link_libraries(ring_buffer_test PRIVATE GTest::gtest_main)
  target_include_directories(ring_buffer_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
  target_compile_options(ring_buffer_test PRIVATE -Wall -Wextra -Wpedantic -Werror -O2 -fvisibility=hidden)
  if(SANITIZE)
    target_compile_options(ring_buffer_test PRIVATE -g -O1 -fsanitize=address,undefined -fno-omit-frame-pointer)
    target_link_options(ring_buffer_test PRIVATE -g -O1 -fsanitize=address,undefined -fno-omit-frame-pointer)
  endif()
  add_executable(silero_vad_test tests/silero_vad_test.cpp vad/silero_vad.cpp vad/vad_fsm.cpp)
  target_link_libraries(silero_vad_test PRIVATE GTest::gtest_main)
  target_include_directories(silero_vad_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
  target_compile_options(silero_vad_test PRIVATE -Wall -Wextra -Wpedantic -Werror -O2 -fvisibility=hidden)
  if(SANITIZE)
    target_compile_options(silero_vad_test PRIVATE -g -O1 -fsanitize=address,undefined -fno-omit-frame-pointer)
    target_link_options(silero_vad_test PRIVATE -g -O1 -fsanitize=address,undefined -fno-omit-frame-pointer)
  endif()
  add_executable(audio_capture_test tests/audio_capture_test.cpp audio/audio_capture.cpp)
  target_link_libraries(audio_capture_test PRIVATE GTest::gtest_main)
  target_include_directories(audio_capture_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
  target_compile_options(audio_capture_test PRIVATE -Wall -Wextra -Wpedantic -Werror -O2 -fvisibility=hidden)
  if(SANITIZE)
    target_compile_options(audio_capture_test PRIVATE -g -O1 -fsanitize=address,undefined -fno-omit-frame-pointer)
    target_link_options(audio_capture_test PRIVATE -g -O1 -fsanitize=address,undefined -fno-omit-frame-pointer)
  endif()
  # Only link oboe for Android host if available; for host unit tests mock the callback directly
  if(TARGET oboe::oboe)
    target_link_libraries(audio_capture_test PRIVATE oboe::oboe)
    target_link_libraries(silero_vad_test PRIVATE oboe::oboe)
    target_link_libraries(ring_buffer_test PRIVATE oboe::oboe)
  endif()
  add_executable(vad_pipeline_test tests/vad_pipeline_test.cpp vad/vad_pipeline.cpp vad/silero_vad.cpp vad/vad_fsm.cpp)
  target_link_libraries(vad_pipeline_test PRIVATE GTest::gtest_main)
  target_include_directories(vad_pipeline_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
  target_compile_options(vad_pipeline_test PRIVATE -Wall -Wextra -Wpedantic -Werror -O2 -fvisibility=hidden)
  if(SANITIZE)
    target_compile_options(vad_pipeline_test PRIVATE -g -O1 -fsanitize=address,undefined -fno-omit-frame-pointer)
    target_link_options(vad_pipeline_test PRIVATE -g -O1 -fsanitize=address,undefined -fno-omit-frame-pointer)
  endif()
  include(GoogleTest)
  gtest_discover_tests(ring_buffer_test)
  gtest_discover_tests(silero_vad_test)
  gtest_discover_tests(audio_capture_test)
  gtest_discover_tests(vad_pipeline_test)
endif()

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
if(log-lib)
  target_link_libraries(itantra-native PRIVATE ${log-lib})
endif()
