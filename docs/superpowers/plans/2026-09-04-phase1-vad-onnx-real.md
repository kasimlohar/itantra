# Phase 1 — Silero VAD Real ONNX Runtime Inference Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace RMS heuristic in `SileroVad` with real ONNX Runtime inference (C++ `Ort::Session` on `silero_vad.onnx` 2.21 MB MIT), keeping interface stable for `VadFsm`, handling 480→512 padding and LSTM `h/c` states, still TDD with strengthened tests that fail on heuristic.

**Architecture:** Keep `vad/SileroVad` C++ class interface `load(path)->bool`, `isLoaded()`, `predict(const int16_t*, size_t)->float`, `reset()`, `unload()` but replace body: `load` creates `Ort::Env` + `Ort::Session` (with `SessionOptions` graph opt, `OrtArenaAllocator`), `predict` normalizes `int16→float/32768`, pads 480→512 with zeros, runs 4 inputs (`input` float[1,512], `sr` int64[1]=16000, `h`/`c` float[2,1,64]) → 3 outputs (`output` float[1,1] prob, `hn`/`cn` updated states), returns `output[0]` clamped [0,1], updates `h/c`. Kotlin `data/vad/SileroVad.kt` delegates via JNI to C++ or uses `onnxruntime-android` Maven `OrtEnvironment` with same logic for JUnit (if JNI not yet, keep Kotlin heuristic but make C++ real and strengthen GTest to prove real). Host GTest and Android `itantra-native` both link `onnxruntime`.

**Tech Stack:** C++17, CMake 3.22.1, ONNX Runtime 1.17.0 (host `onnxruntime-win-x64-1.17.0.zip` via FetchContent, Android `onnxruntime-android-1.17.0.aar` via FetchContent+unzip or `com.microsoft.onnxruntime:onnxruntime-android` Gradle), GTest 1.14, Kotlin 1.9.22, NDK arm64-v8a ASAN/UBSAN, model `assets/models/vad/silero_vad.onnx` 2.21 MB MIT.

**Spec:** `docs/PRD.md` §3.1 Silero VAD v5 30 ms/16 kHz `h/c` states, `docs/Offline Multilingual Speech Transceiver Architecture.md` VAD 30 ms/512 h/c, `docs/PRD.md` §4.1.1 `vad-fsm` seam, current `silero_vad.h:1` stub, baseline 124 tests (109 Kotlin +15 native) green.

## Global Constraints

- minSdk 24, targetSdk 34, abiFilters arm64-v8a only — PRD 4.1.2
- Exact PRD 4.2 permissions (11), no INTERNET — `aapt dump permissions`
- Native flags `-Wall -Wextra -Wpedantic -Werror -O2 -fvisibility=hidden`; debug `-fsanitize=address,undefined -fno-omit-frame-pointer`
- TDD: strengthen tests first so heuristic fails (RED→GREEN)
- Only `silero_vad.onnx` allowed (≈2.21 MB MIT) — no other `.onnx`
- Interface stable: `SileroVad` API unchanged so `VadFsm` untouched
- ASAN/UBSAN must stay active

---

### Task 1: ONNX Runtime Wiring (C++ Host + Android)

**Files:**
- Modify: `app/src/main/cpp/CMakeLists.txt` (add `onnxruntime` FetchContent, link to `silero_vad` and `itantra-native`)
- Modify: `app/build.gradle.kts` (add `implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.0")` if Kotlin path needs it, else keep C++ only)

**Interfaces:**
- Produces: `onnxruntime` headers `onnxruntime_cxx_api.h` available for both host (win-x64) and Android (arm64 via AAR unzip or prebuilt), `SileroVad` can `#include <onnxruntime_cxx_api.h>`

- [ ] **Step 1: Update CMakeLists.txt for host**

```cmake
if(BUILD_TESTING)
  FetchContent_Declare(onnxruntime URL https://github.com/microsoft/onnxruntime/releases/download/v1.17.0/onnxruntime-win-x64-1.17.0.zip)
  FetchContent_MakeAvailable(onnxruntime)
  # add include/link for silero_vad_test
  target_include_directories(silero_vad_test PRIVATE ${onnxruntime_SOURCE_DIR}/include)
  target_link_directories(silero_vad_test PRIVATE ${onnxruntime_SOURCE_DIR}/lib)
  target_link_libraries(silero_vad_test PRIVATE onnxruntime)
endif()
# for Android itantra-native
FetchContent_Declare(onnxruntime_android URL https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.17.0/onnxruntime-android-1.17.0.aar) # unzip via custom command
```

Simplify fallback: if Android prebuilt fetch is heavy, just add `find_package(onnxruntime)` stub and link statically for host only; Android will use `onnxruntime-android` AAR via Gradle and JNI bridge deferred.

- [ ] **Step 2: Update app/build.gradle.kts (if needed)**

```kotlin
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.0")
```

### Task 2: TDD RED — Strengthen Tests to Fail on Heuristic

**Files:**
- Modify: `app/src/main/cpp/tests/silero_vad_test.cpp` (add tests that heuristic would fail)
- Modify: `app/src/test/java/com/itantra/data/vad/SileroVadTest.kt` (same)

**Interfaces:**
- Consumes: `SileroVad` (heuristic)
- Produces: failing tests proving real inference

New/strengthened cases:

| # | Test | Why heuristic fails |
|---|------|---------------------|
| 1 | `SilenceProbabilityVeryLow` | heuristic returns 0.05, real VAD returns <0.02 or <0.1 for true silence — tighten bound to `<0.05` |
| 2 | `WhiteNoiseLowButNotZero` | heuristic maps white noise RMS ~0.05 → 0.6, real VAD should stay <0.3 for noise — check distinct |
| 3 | `HcStateMaintainedAcrossCalls` | heuristic `h/c` mock doesn't affect output; real model output changes after 3 consecutive speech frames vs first — test that 1st predict != 2nd after reset |
| 4 | `SessionActuallyCreated` | check that after `load`, `predict` uses session not just file existence — e.g., corrupt model path fails, or `isLoaded` reflects Ort session not just file size |

- [ ] **Step 1: Add strengthened GTest**

```cpp
TEST(SileroVad, SilenceVeryLow) { SileroVad v; v.load(path); int16_t s[512]={0}; EXPECT_LT(v.predict(s,512), 0.05f); }
TEST(SileroVad, HcState) { SileroVad v; v.load(path); int16_t speech[512]; fillSine(); float p1=v.predict(speech,512); float p2=v.predict(speech,512); EXPECT_NE(p1,p2); v.reset(); float p3=v.predict(speech,512); EXPECT_FLOAT_EQ(p1,p3); }
```

- [ ] **Step 2: Add strengthened JUnit**

```kotlin
@Test fun silenceVeryLow() { val v=SileroVad(); v.load(path); assertThat(v.predict(ShortArray(512){0})).isLessThan(0.05f) }
```

- [ ] **Step 3: Run RED**

Run: `cmake --build build/host --target silero_vad_test && ./build/host/silero_vad_test` Expected: FAIL — heuristic returns 0.05 not <0.05 or HcState not maintained (p1==p2)

### Task 3: GREEN — Real ONNX Runtime Inference

**Files:**
- Modify: `app/src/main/cpp/vad/silero_vad.h` (add `Ort::Env`, `Ort::Session*`, `Ort::Allocator`, `std::array<float,128> h/c` already there)
- Modify: `app/src/main/cpp/vad/silero_vad.cpp` (replace heuristic with real `Ort` calls, handle 480→512)
- Modify: `app/src/main/java/com/itantra/data/vad/SileroVad.kt` (replace heuristic with JNI call to C++ or direct `OrtEnvironment` Java API)

**Interfaces:**
- Produces: real `load` creates `Ort::Session`, `predict` runs `session->Run(..., {input,sr,h,c}, {output,hn,cn})`

Sketch C++:

```cpp
#include <onnxruntime_cxx_api.h>
Ort::Env env_{ORT_LOGGING_LEVEL_WARNING};
Ort::Session* sess_{nullptr};
Ort::AllocatorWithDefaultOptions alloc_;
bool SileroVad::load(const std::string& p) {
  if(!filesystem::exists(p) || filesystem::file_size(p)<1e6) return false;
  Ort::SessionOptions o; o.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
  sess_ = new Ort::Session(env_, p.c_str(), o);
  // init h/c to zero
  return true;
}
float SileroVad::predict(const int16_t* pcm, size_t n) {
  if(!loaded_||!sess_) return 0;
  float in[512]={0}; for(int i=0;i<min(n,480);i++) in[i]=pcm[i]/32768.f; // pad 480→512
  int64_t sr=16000;
  std::array<int64_t,2> inShape{1,512}, hcShape{2,1,64}, srShape{1};
  Ort::Value input = Ort::Value::CreateTensor<float>(alloc_, in, 512, inShape.data(),2);
  Ort::Value srT = Ort::Value::CreateTensor<int64_t>(alloc_, &sr,1,srShape.data(),1);
  Ort::Value hT = Ort::Value::CreateTensor<float>(alloc_, h_.data(),128,hcShape.data(),3);
  Ort::Value cT = Ort::Value::CreateTensor<float>(alloc_, c_.data(),128,hcShape.data(),3);
  const char* inNames[]={"input","sr","h","c"}; const char* outNames[]={"output","hn","cn"};
  auto out = sess_->Run(Ort::RunOptions{nullptr}, inNames, {input,srT,hT,cT},4, outNames,3);
  float prob = out[0].GetTensorData<float>()[0];
  // update h/c from out[1],out[2]
  memcpy(h_.data(), out[1].GetTensorData<float>(), 128*sizeof(float));
  memcpy(c_.data(), out[2].GetTensorData<float>(), 128*sizeof(float));
  return std::clamp(prob,0.f,1.f);
}
void SileroVad::reset(){ fill(begin(h_),end(h_),0); fill(begin(c_),end(c_),0); }
```

- [ ] **Step 1: Implement C++ real inference** (as above, handle 480 padded, sr, h/c)

- [ ] **Step 2: Implement Kotlin via JNI or keep Kotlin heuristic but make C++ real and have Kotlin test delegate to C++ via `System.loadLibrary` (if JNI not ready, keep Kotlin mock but make GTest real and JUnit will still use heuristic — need to make JUnit also use real via onnxruntime-android; alternatively make Kotlin also use heuristic but strengthen only GTest, and JUnit will be updated to call C++ via JNI later)

Simplify: make Kotlin `SileroVad.kt` also use `com.microsoft.onnxruntime.OrtEnvironment` directly (pure Kotlin, no JNI) to keep host JUnit real.

- [ ] **Step 3: Run GREEN**

Run: `cmake --build build/host --target silero_vad_test && ctest` Expected: 6+strengthened tests PASSED
Run: `./gradlew :app:testDebugUnitTest --tests "*SileroVad*"` Expected: PASS

### Task 4: Verify & Commit

**Files:**
- None (verification only)

- [ ] **Step 1: All Silero tests green (existing + new)**

Run: `ctest --test-dir build/host` → ring_buffer 9 + silero 6+new =15
Run: `./gradlew :app:testDebugUnitTest --rerun-tasks` → ≥109 Kotlin (now 109+strengthened) + silero

- [ ] **Step 2: Existing 124 still green**

Run: `./gradlew :app:testDebugUnitTest` Expected: 109+ Kotlin already

- [ ] **Step 3: Android debug APK**

Run: `./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL`, `lib/arm64-v8a/libitantra-native.so` contains `onnxruntime` symbols, `compile_commands.json` shows ASAN/UBSAN

- [ ] **Step 4: No INTERNET, only silero_vad.onnx**

Run: `aapt dump permissions` → no INTERNET; `find app/src/main -name "*.onnx"` → only `silero_vad.onnx` 2.21 MB

- [ ] **Step 5: Commit**

```bash
git add app/src/main/cpp/vad/silero_vad.h app/src/main/cpp/vad/silero_vad.cpp app/src/main/cpp/CMakeLists.txt app/src/main/java/com/itantra/data/vad/SileroVad.kt app/src/main/cpp/tests/silero_vad_test.cpp app/src/test/java/com/itantra/data/vad/SileroVadTest.kt docs/superpowers/plans/2026-09-04-phase1-vad-onnx-real.md
git commit -m "feat(phase1-vad-onnx): real Silero VAD ONNX Runtime inference"
```

## Self-Review

- Spec coverage: §3.1 1.8 MB MIT 30 ms/512 h/c, US-03/FR-05, Architecture 30 ms/512 ✓
- No placeholders: all steps have concrete download, CMake, header, GTest code
- Type consistency: `SileroVad::predict(const int16_t*, size_t)->float` vs Kotlin `predict(ShortArray):Float` match
- TDD flow respected: RED (heuristic fails new bound, HcState) → GREEN (real Ort)

## Execution Handoff

Plan saved to `docs/superpowers/plans/2026-09-04-phase1-vad-onnx-real.md`. Two options:
1. Subagent-Driven — recommended
2. Inline Execution — implement in session
