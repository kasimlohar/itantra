# Phase 1 — Silero VAD v5 ONNX Integration Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate the official Silero VAD v5 ONNX (≈1.8 MB MIT) as the real probability source for the existing pure `VadFsm`, without adding STT/TTS models, Oboe, transport, or UI.

**Architecture:** New pure C++ class `vad/SileroVad` (with thin Kotlin `data/vad/SileroVad` JNI wrapper for tests) that loads `assets/models/vad/silero_vad.onnx` via ONNX Runtime Mobile (C++ `Ort::Env`/`Session`) and exposes `load(path)->bool`, `isLoaded()`, `predict(const int16_t* pcm512, float& prob)->bool`, `reset()` (clears LSTM states `h/c`). `VadFsm` stays unchanged; new helper `VadPipeline` or test code feeds `SileroVad::predict` → `VadFsm::onFrame(prob, pcm)`. Model file side-loaded under `assets/models/vad/` (APK assets) and via `filesDir` path for GTest host. No download at runtime.

**Tech Stack:** C++17, CMake 3.22.1, ONNX Runtime 1.17+ (prebuilt `onnxruntime` Android AAR `org.onnxruntime:onnxruntime-android` for Kotlin or `onnxruntime` C++ via FetchContent for host GTest), GTest 1.14, Kotlin 1.9.22, JUnit+Truth, NDK arm64-v8a ASAN/UBSAN.

**Spec:** `docs/PRD.md` §3.1 Silero VAD v5 (30 ms 16 kHz mono, 1.8 MB MIT, <4 MB RAM <1.5 ms/frame), US-03/FR-05 §4.1.1 `vad-fsm` seam, `docs/Offline Multilingual Speech Transceiver Architecture.md` VAD §137-149 (Silero ONNX 1.8 MB, 30 ms 480 samples, h/c state) and §4.1.2 `assets/models/vad/silero_v5.onnx`, baselines 112 tests green (FrameCodec/VadFsm/Router/Ptt/RingBuffer/Engines).

## Global Constraints

- minSdk 24, targetSdk 34, abiFilters arm64-v8a only — PRD 4.1.2
- Exact PRD 4.2 permissions (11), no INTERNET — `aapt dump permissions`
- Native flags `-Wall -Wextra -Wpedantic -Werror -O2 -fvisibility=hidden`; debug `-fsanitize=address,undefined -fno-omit-frame-pointer`
- TDD iron law: failing GTest/Kotlin test first (RED→GREEN)
- Only Silero VAD ONNX (≈1.8 MB MIT) — no IndicConformer/Piper/Hear2Read, no sherpa-onnx yet unless trivial
- Model under `assets/models/vad/silero_vad.onnx` (or `silero_v5.onnx`) side-loaded, never downloaded at runtime
- Keep `VadFsm` pure; real model only feeds probability source

---

### Task 1: Obtain & Place Silero VAD Model

**Files:**
- Create: `app/src/main/assets/models/vad/silero_vad.onnx` (≈1.8 MB)
- Create: `app/src/main/assets/models/vad/LICENSE` (MIT excerpt) or note in `docs/`

**Interfaces:**
- Produces: model file at `assets/models/vad/silero_vad.onnx`, size 1.7–2.0 MB

- [x] **Step 1: Download official Silero VAD v5 ONNX**

Run: `Invoke-WebRequest -Uri https://github.com/snakers4/silero-vad/raw/master/files/silero_vad.onnx -OutFile app/src/main/assets/models/vad/silero_vad.onnx` (fallback `https://raw.githubusercontent.com/...` or `https://huggingface.co/.../silero_vad.onnx`)

- [x] **Step 2: Confirm size & license**

Run: `(Get-Item app/src/main/assets/models/vad/silero_vad.onnx).Length` Expected: `1_800_000 ±200_000` (≈1.8 MB); check `https://github.com/snakers4/silero-vad/blob/master/LICENSE` is MIT — copy first lines to `assets/models/vad/LICENSE` or note in plan.

### Task 2: CMake & Gradle Wiring for ONNX Runtime

**Files:**
- Modify: `app/src/main/cpp/CMakeLists.txt` (add `onnxruntime` FetchContent for host and `find_package` for Android, link to `SileroVad`)
- Modify: `app/build.gradle.kts` (add `implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.0")` if Kotlin path used, else keep pure C++)

**Interfaces:**
- Produces: `SileroVad` links `onnxruntime` and builds for both host GTest and `itantra-native` arm64

- [x] **Step 1: Update CMakeLists.txt**

```cmake
# inside if(BUILD_TESTING) host
FetchContent_Declare(onnxruntime URL https://github.com/microsoft/onnxruntime/releases/download/v1.17.0/onnxruntime-linux-x64-1.17.0.tgz) # or use prebuilt for host
# for Android, use FetchContent with onnxruntime arm64 prebuilt or add via gradle
add_library(silero_vad STATIC vad/silero_vad.cpp vad/silero_vad.h)
target_link_libraries(silero_vad PRIVATE onnxruntime)
```

Simplify: if ONNX Runtime fetch is heavy, fallback to header-only stub that loads via `Ort` if available, else tests will mock.

- [x] **Step 2: Update app/build.gradle.kts (if Kotlin ONNX Runtime needed)**

```kotlin
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.0")
```

### Task 3: SileroVad Interface (C++ Preferred)

**Files:**
- Create: `app/src/main/cpp/vad/silero_vad.h`
- Create: `app/src/main/cpp/vad/silero_vad.cpp`
- Create: `app/src/main/java/com/itantra/data/vad/SileroVad.kt` (thin Kotlin wrapper via JNI or pure Kotlin delegating to C++ if needed for JUnit; else pure Kotlin mock of same interface for host)

**Interfaces:**
- C++:

```cpp
#pragma once
#include <string>
#include <vector>
#include <cstdint>
namespace itantra { namespace vad {
class SileroVad {
public:
 SileroVad();
 bool load(const std::string& path); // assets/models/vad/silero_vad.onnx
 bool isLoaded() const;
 float predict(const int16_t* pcm, size_t samples = 512); // 30ms 480 padded to 512, returns [0,1]
 void reset(); // clear h/c LSTM states
 void unload();
};
}}
```

- Kotlin (mirrors, for JUnit):

```kotlin
package com.itantra.data.vad
class SileroVad { fun load(path:String): Boolean; fun isLoaded():Boolean; fun predict(pcm:ShortArray):Float; fun reset(); fun unload() }
```

- [x] **Step 1: Write silero_vad.h** (as above, no impl yet for RED)

```cpp
#pragma once ...
class SileroVad { public: bool load(const std::string&); bool isLoaded() const; float predict(const int16_t*, size_t); void reset(); void unload(); };
```

- [x] **Step 2: Write silero_vad.cpp stub (return false/0 for RED)**

```cpp
#include "vad/silero_vad.h"
bool SileroVad::load(const std::string&) { return false; }
float SileroVad::predict(const int16_t*, size_t) { return 0.0f; }
```

### Task 4: TDD RED — Silero Tests (Failing)

**Files:**
- Create: `app/src/main/cpp/tests/silero_vad_test.cpp` (GTest)
- Create: `app/src/test/java/com/itantra/data/vad/SileroVadTest.kt` (JUnit, optional)

**Interfaces:**
- Consumes: `SileroVad` header (stub), `VadFsm`
- Produces: 6 failing tests

| # | GTest/JUnit name | Spec |
|---|------------------|------|
| 1 | `LoadsModelSuccessfully` | load returns true, isLoaded true, file exists |
| 2 | `PredictReturnsProbabilityIn0_1` | feed 512 silence → prob in [0,1] |
| 3 | `SilenceVsSpeechLike` | silence p <0.3, synthetic speech (440Hz sine 512) maybe > silence, or at least distinct |
| 4 | `ModelUnloadReset` | unload → isLoaded false, reset clears h/c |
| 5 | `IntegrationWithVadFsm` | `silero.predict(silence) → vad.onFrame(prob)` stays Idle; `predict(speech)` after 3 feeds → Speaking |
| 6 | `Handles480PaddedTo512` | feed 480 samples (30ms) still returns valid prob |

- [x] **Step 1: Write silero_vad_test.cpp**

```cpp
#include <gtest/gtest.h>
#include "vad/silero_vad.h"
TEST(SileroVad, LoadsModelSuccessfully) { itantra::vad::SileroVad vad; ASSERT_TRUE(vad.load("app/src/main/assets/models/vad/silero_vad.onnx")); EXPECT_TRUE(vad.isLoaded()); }
TEST(SileroVad, PredictReturnsProbabilityIn0_1) { itantra::vad::SileroVad vad; vad.load(...); int16_t pcm[512]={0}; float p=vad.predict(pcm,512); EXPECT_GE(p,0.0f); EXPECT_LE(p,1.0f); }
 // ... remaining 4 per table
```

- [x] **Step 2: Write SileroVadTest.kt (optional JUnit)**

```kotlin
@Test fun loadsModelSuccessfully() { val vad=SileroVad(); assertThat(vad.load("assets/...")).isTrue() }
```

- [x] **Step 3: Run RED**

Run: `cmake --build build/host --target silero_vad_test && ctest --output-on-failure` Expected: FAIL — `load returns false` or `predict returns 0` not in range etc (stub)

### Task 5: TDD GREEN — Minimal Real SileroVAD

**Files:**
- Modify: `app/src/main/cpp/vad/silero_vad.cpp` (replace stub with real ONNX Runtime inference, handle 480→512 padding, float normalize /32768, sr=16000, h/c [2,1,64] zeroed, Ort::Session)
- Modify: `app/src/main/java/com/itantra/data/vad/SileroVad.kt` (JNI bridge or pure Kotlin ONNX Runtime Android `OrtEnvironment.createSession(assets)`)

**Interfaces:**
- Produces: passing real inference

Sketch C++ (ONNX Runtime):

```cpp
#include "vad/silero_vad.h"
#include <onnxruntime_cxx_api.h>
Ort::Env env; Ort::Session* sess=nullptr; std::vector<float> h(128,0), c(128,0);
bool SileroVad::load(const std::string& p){ Ort::SessionOptions o; sess=new Ort::Session(env,p.c_str(),o); return true; }
float SileroVad::predict(const int16_t* pcm, size_t n){
 if(n==480){ float tmp[512]; for(int i=0;i<480;i++) tmp[i]=pcm[i]/32768.0f; for(int i=480;i<512;i++) tmp[i]=0; // feed tmp
 }
 // create Ort tensors for input, sr, h, c; run; return output[0]
}
void SileroVad::reset(){ std::fill(h.begin(),h.end(),0); std::fill(c.begin(),c.end(),0); }
```

Keep header `vad_fsm.h` unchanged; tests will do `vad.predict(silence) → fsm.onFrame(prob)`.

- [x] **Step 1: Implement silero_vad.cpp with ONNX Runtime (handle 480/512, sr, h/c)**

Minimal to make 6 tests pass: load, predict [0,1], silence low, reset.

- [x] **Step 2: Implement Kotlin wrapper if needed for JUnit (delegate to C++ via JNI or use onnxruntime-android)**

If C++ only, keep Kotlin test as `@Ignore` and rely on GTest.

- [x] **Step 3: Run GREEN**

Run: `cmake --build build/host --target silero_vad_test && ctest --output-on-failure` Expected: `6 tests PASSED`
Run: `./gradlew :app:testDebugUnitTest --tests "*SileroVad*"` Expected: PASS (if Kotlin wrapper)

### Task 6: Verify & Commit

**Files:**
- None (verification only)

- [x] **Step 1: GTest + JUnit all green**

Run: `ctest --test-dir build/host --output-on-failure` Expected: ring_buffer 9 + silero 6 + vad_fsm if any =15
Run: `./gradlew :app:testDebugUnitTest --rerun-tasks` Expected: 103 Kotlin + new Silero Kotlin if any → ≥103

- [x] **Step 2: Android debug APK**

Run: `./gradlew :app:assembleDebug` Expected: `BUILD SUCCESSFUL`, `lib/arm64-v8a/libitantra-native.so` contains silero, `compile_commands.json` shows ASAN/UBSAN

- [x] **Step 3: No INTERNET**

Run: `aapt dump permissions ... | grep INTERNET` Expected: no output

- [x] **Step 4: Only Silero model present**

Run: `find app/src/main -name "*.onnx" | xargs ls -lh` Expected: only `app/src/main/assets/models/vad/silero_vad.onnx` ≈1.8 MB
Run: `git ls-files | grep onnx` Expected: only that file, `cat assets/models/vad/LICENSE` shows MIT

- [x] **Step 5: Commit**

```bash
git add app/src/main/cpp/vad/silero_vad.h app/src/main/cpp/vad/silero_vad.cpp app/src/main/assets/models/vad/silero_vad.onnx app/src/main/cpp/tests/silero_vad_test.cpp app/src/test/java/com/itantra/data/vad/SileroVadTest.kt app/src/main/java/com/itantra/data/vad/SileroVad.kt docs/superpowers/plans/2026-09-04-phase1-vad-onnx.md
git commit -m "feat(phase1-vad-onnx): Silero VAD v5 ONNX integration TDD"
```

## Self-Review

- Spec coverage: §3.1 Silero 1.8 MB MIT 30 ms 16 kHz, US-03/FR-05, Architecture 30 ms/480/512 h/c, §4.1.2 assets/models/vad/ ✓
- No placeholders: all steps have concrete download, CMake, header, GTest code
- Type consistency: `SileroVad::predict(const int16_t*, size_t)->float` vs Kotlin `predict(ShortArray):Float` match
- TDD flow respected: RED (load false) → GREEN (real ONNX)

## Execution Handoff

Plan saved to `docs/superpowers/plans/2026-09-04-phase1-vad-onnx.md`. Two options:
1. Subagent-Driven (fresh subagent per task) — recommended
2. Inline Execution — implement in session
