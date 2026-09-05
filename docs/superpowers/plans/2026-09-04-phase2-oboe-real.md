# Phase 2 — Oboe 1.8.1 Real Capture (AAudio) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `AudioCapture` host stub with real Oboe 1.8.1 `AAudio` 16k mono LowLatency Exclusive 480 when `ANDROID`, keep host `onAudioReady` path for GTest.

**Architecture:** Un-comment `CMakeLists.txt:11` `if(ANDROID) FetchContent oboe 1.8.1` and link `oboe::oboe` to `itantra-native` + `audio_capture_test`/`silero_vad_test`/`ring_buffer_test` only when `TARGET oboe::oboe` exists. Guard `audio_capture.h` with `#ifdef __ANDROID__ #include <oboe/Oboe.h>` else keep stub `namespace oboe` (host test stays). In `audio_capture.cpp` `start()` uses `oboe::AudioStreamBuilder` `setDirection(Input)`, `setSampleRate(16000)`, `setChannelCount(1)`, `setFormat(I16)`, `setSharingMode(Exclusive)`, `setPerformanceMode(LowLatency)`, `setCallback(this)`, `setFramesPerCallback(480)` and `openStream`/`requestStart`; `stop()` `requestStop`/`close`; `onAudioReady` unchanged `ring_.push`. No alloc/log/JVM in callback.

**Tech Stack:** NDK 26.1.10909125, CMake 3.22, Oboe 1.8.1, C++17, GTest 1.14, `arm64-v8a` only, ASAN `address,undefined`.

**Spec:** `docs/PRD.md:4.1.1 audio-io` `AudioCapture::RingBuffer 16k mono 480`, `docs/PRD.md:4.1.2` `NDK r26, CMake 3.22`, `docs/Offline Multilingual Speech Transceiver Architecture.md:36` 16kHz Oboe AAudio

## Global Constraints

- `minSdk 24`, `targetSdk 34`, `NDK r26`, `CMake 3.22+`, `arm64-v8a` only, no `INTERNET` 11 perms, MIT/Apache-2.0/CC-BY-4.0 only, pure seams host-runnable, TDD RED first, single `hi` still, no UI/transport, ASAN active.

---

### Task 1: Inspect Oboe Stub Baseline

**Files:**
- Read: `app/src/main/cpp/audio/audio_capture.h:1` stub, `audio_capture.cpp:1`, `CMakeLists.txt:11` commented FetchContent, `app/src/main/cpp/tests/audio_capture_test.cpp:1`

**Interfaces:**
- Consumes: none
- Produces: report — stub present, no `oboe/Oboe.h` include, `CMake` not fetching, host test via `onAudioReady` direct, `start()` just `capturing_=true`.

- [ ] **Step 1: Inspect**

```powershell
Get-Content app/src/main/cpp/audio/audio_capture.h | Select-Object -First 30
Get-Content app/src/main/cpp/CMakeLists.txt | Select-String "oboe|Oboe|FetchContent" -Context 0,2
Get-Content app/src/main/cpp/tests/audio_capture_test.cpp | Select-Object -First 40
```

---

### Task 2: RED — Write Failing Test Expecting Oboe Guard

**Files:**
- Modify: `app/src/main/cpp/tests/audio_capture_test.cpp:1` add test that `AudioCapture` compiles with `__ANDROID__` guard and `start()` would attempt Oboe (but host still stub)
- Test: same file

**Interfaces:**
- Consumes: `AudioCapture`
- Produces: test that fails if `audio_capture.h` still defines stub `namespace oboe` when `__ANDROID__` is defined (i.e., guard missing):

```cpp
TEST(AudioCapture, OboeGuardExists) {
  // On host, oboe stub must exist; on Android, real oboe/Oboe.h must be includable.
  // We test that audio_capture.h has #ifdef __ANDROID__ guard by checking macro presence.
  // Simple fail: expect that AudioCapture::start() would not just set capturing_ without Oboe on Android.
  // For host, we assert that the header contains "#ifdef __ANDROID__"
  std::ifstream f("app/src/main/cpp/audio/audio_capture.h");
  std::string s((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
  EXPECT_NE(s.find("#ifdef __ANDROID__"), std::string::npos) << "guard missing";
  EXPECT_NE(s.find("#include <oboe/Oboe.h>"), std::string::npos) << "real include missing";
}
```

- [ ] **Step 1: Add test to `audio_capture_test.cpp`**
- [ ] **Step 2: Run `cmake --build build --target audio_capture_test && ./build/audio_capture_test` Expected FAIL `guard missing`**

---

### Task 3: GREEN — Enable Oboe 1.8.1 Real

**Files:**
- Modify: `app/src/main/cpp/audio/audio_capture.h:1` add guard
- Modify: `app/src/main/cpp/audio/audio_capture.cpp:1` real `AudioStreamBuilder`
- Modify: `app/src/main/cpp/CMakeLists.txt:11` un-comment FetchContent and link

**Interfaces:**
- Consumes: `AudioCapture` spec, Oboe API
- Produces: real `AudioCapture` that compiles both host and Android.

- [ ] **Step 1: Patch `audio_capture.h`**

```cpp
#pragma once
#include "audio/ring_buffer.h"
#include <cstdint>
#include <cstddef>
#ifdef __ANDROID__
#include <oboe/Oboe.h>
#else
namespace oboe { enum class DataCallbackResult{Continue,Stop}; enum class Result{OK,ErrorTimeout}; class AudioStream{}; class AudioStreamCallback{public: virtual ~AudioStreamCallback()=default; virtual DataCallbackResult onAudioReady(AudioStream*,void*,int32_t){return DataCallbackResult::Continue;}};}
#endif
...
```

- [ ] **Step 2: Patch `audio_capture.cpp`**

```cpp
#include "audio/audio_capture.h"
#ifdef __ANDROID__
#include <oboe/Oboe.h>
#endif
...
bool AudioCapture::start(){
  if(capturing_) return true;
#ifdef __ANDROID__
  oboe::AudioStreamBuilder builder;
  builder.setDirection(oboe::Direction::Input);
  builder.setSampleRate(16000);
  builder.setChannelCount(1);
  builder.setFormat(oboe::AudioFormat::I16);
  builder.setSharingMode(oboe::SharingMode::Exclusive);
  builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
  builder.setCallback(this);
  builder.setFramesPerCallback(480);
  oboe::AudioStream *stream=nullptr;
  auto res=builder.openStream(stream);
  if(res!=oboe::Result::OK || !stream) return false;
  // store stream if needed (add member oboe::AudioStream* stream_=nullptr;)
  stream_=stream;
  res=stream_->requestStart();
  if(res!=oboe::Result::OK) return false;
#endif
  capturing_=true; return true;
}
void AudioCapture::stop(){
#ifdef __ANDROID__
  if(stream_){ stream_->requestStop(); stream_->close(); stream_=nullptr; }
#endif
  capturing_=false;
}
```

Add member `oboe::AudioStream* stream_=nullptr` in header inside `#ifdef __ANDROID__`.

- [ ] **Step 3: Patch `CMakeLists.txt`**

```cmake
if(ANDROID)
  include(FetchContent)
  FetchContent_Declare(oboe URL https://github.com/google/oboe/archive/refs/tags/1.8.1.zip)
  FetchContent_MakeAvailable(oboe)
endif()
...
target_link_libraries(itantra-native PRIVATE ${log-lib})
if(TARGET oboe::oboe)
  target_link_libraries(itantra-native PRIVATE oboe::oboe)
endif()
...
if(TARGET oboe::oboe)
  target_link_libraries(audio_capture_test PRIVATE oboe::oboe)
  target_link_libraries(silero_vad_test PRIVATE oboe::oboe)
  target_link_libraries(ring_buffer_test PRIVATE oboe::oboe)
  target_link_libraries(vad_pipeline_test PRIVATE oboe::oboe)
  target_link_libraries(mel_features_test PRIVATE oboe::oboe)
endif()
```

- [ ] **Step 4: Run `cmake --build build --target audio_capture_test && ./build/audio_capture_test` Expected 6/6 PASS (guard now exists, host stub still works via `onAudioReady` push)**

- [ ] **Step 5: Run all GTest + Kotlin**

Run: `ctest --output-on-failure` => 35 native (prev 34 +1 guard) PASS, `./gradlew :app:testDebugUnitTest -q` => 161 Kotlin PASS

---

### Task 4: Verify + Commit

**Files:**
- Verify: `assembleDebug` still links `oboe::oboe` only on Android (host build still stub), `aapt` no INTERNET

- [ ] **Step 1: Full tests** `ctest && ./gradlew :app:testDebugUnitTest -q` => 196 total PASS
- [ ] **Step 2: Build** `./gradlew :app:assembleDebug -q` => BUILD SUCCESSFUL (Oboe fetched on Android, cached)
- [ ] **Step 3: No INTERNET** `scripts/check-no-internet.bat` => PASS
- [ ] **Step 4: Commit**

```bash
git add app/src/main/cpp/audio/audio_capture.h app/src/main/cpp/audio/audio_capture.cpp app/src/main/cpp/CMakeLists.txt app/src/main/cpp/tests/audio_capture_test.cpp docs/superpowers/plans/2026-09-04-phase2-oboe-real.md
git commit -m "feat(phase2): Oboe 1.8.1 real capture AAudio 16k mono 480"
```

```

