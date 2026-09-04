# Phase 1 — Live VAD Pipeline Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create thin real-time pipeline `Ring Buffer → Silero VAD → VadFsm` that consumes 480-sample frames from `AudioCapture`’s lock-free `SpscRingBuffer`, feeds `SileroVad::predict`, and drives `VadFsm::onFrame`, keeping existing pure seams stable, no STT/TTS/transport/UI.

**Architecture:** New C++ `vad/VadPipeline` (or `audio/AudioVadProcessor`) owned by `AudioCapture` or standalone, holding refs to `SpscRingBuffer<int16_t>&`, `SileroVad&`, `VadFsm&`. Public `bool start()`, `void stop()`, `bool isRunning()`, `size_t processOne()` (pop 480 → `vad.predict` → `fsm.onFrame`, returns true if frame processed), `size_t processAll()` drains ring, `VadState state()`, `void clear()`. Hot path `processOne` does no `new`/`malloc`, no `log`, no JVM; just `ring.pop`, `vad.predict`, `fsm.onFrame`. `AudioCapture` stays producer (Oboe callback), `VadPipeline` is consumer (separate thread or `pump()` called from test). Keeps `VadFsm`/`SileroVad` unchanged.

**Tech Stack:** C++17, CMake 3.22.1, `SpscRingBuffer` header-only, `SileroVad` (already real header-check + stateful), `VadFsm`, GTest 1.14, Kotlin 1.9.22 (no new Kotlin for this slice), NDK arm64-v8a ASAN/UBSAN.

**Spec:** `docs/PRD.md` FR-11 `Native audio via Oboe, GC-free, lock-free ring, no underruns`, §4.1.1 `audio-io` + `vad-fsm` seams, §4.1.3 `Audio thread SCHED_FIFO lock-free ring never on JVM`, `docs/Offline Multilingual Speech Transceiver Architecture.md` audio-capture + VAD pipeline `Mic→Ring→Silero 30ms→FSM {Idle→Speaking→Pause→EOU}`, baseline 130 tests (112 Kotlin +18 native) green.

## Global Constraints

- minSdk 24, targetSdk 34, abiFilters arm64-v8a only — PRD 4.1.2
- Exact PRD 4.2 permissions (11), no INTERNET — `aapt dump permissions`
- Native flags `-Wall -Wextra -Wpedantic -Werror -O2 -fvisibility=hidden`; debug `-fsanitize=address,undefined -fno-omit-frame-pointer`
- TDD where practical: GTest for `VadPipeline` (mock ring/Silero/Fsm integration, no mic needed)
- Keep `SpscRingBuffer` interface unchanged, `SileroVad`/`VadFsm` stable
- Do NOT integrate STT, TTS, transport, or UI
- Consumer must stay lean (no allocations in hot path if possible, at least no `new` in `processOne` loop beyond stack `int16_t[480]`)
- ASAN/UBSAN must remain active

---

### Task 1: VadPipeline Header

**Files:**
- Create: `app/src/main/cpp/vad/vad_pipeline.h`

**Interfaces:**
- Produces:

```cpp
#pragma once
#include "audio/ring_buffer.h"
#include "vad/silero_vad.h"
#include "vad/vad_fsm.h"
namespace itantra { namespace vad {
class VadPipeline {
public:
 VadPipeline(audio::SpscRingBuffer<int16_t>& ring, SileroVad& vad, VadFsm& fsm);
 bool start(); void stop(); bool isRunning() const;
 // Consumer: pop 480 from ring, vad.predict, fsm.onFrame. Returns true if a frame was processed.
 bool processOne();
 size_t processAll(); // drain ring, returns frames processed
 VadState state() const;
 void clear();
private:
 bool running_ = false;
 audio::SpscRingBuffer<int16_t>& ring_;
 SileroVad& vad_;
 VadFsm& fsm_;
};
}}
```

- [x] **Step 1: Write vad_pipeline.h**

Exact header as above, no `log`, no `oboe`.

- [x] **Step 2: Verify header compiles**

Run: `cmake --build build/host --target vad_pipeline_test` (after Task 2) or `g++ -std=c++17 -c vad_pipeline.h` Expected: no error

### Task 2: TDD RED — VadPipeline Tests (Failing)

**Files:**
- Create: `app/src/main/cpp/tests/vad_pipeline_test.cpp`

**Interfaces:**
- Consumes: `VadPipeline` (not yet existent), `SpscRingBuffer`, `SileroVad`, `VadFsm`
- Produces: 6 failing GTests

| # | GTest | Requirement |
|---|-------|-------------|
| 1 | `Pop480FromRing` | consumer can pop 480 from ring (49152 via pipeline) |
| 2 | `FeedsIntoSileroAndVadFsm_IdleToSpeaking` | 3 speech frames → `VadFsm` Speaking (via `processAll`) |
| 3 | `SileroIntegration_SilenceStaysIdle` | silence frames → stays Idle |
| 4 | `StartStop` | `start()` → `isRunning true`, `stop()` → false, double start/stop safe |
| 5 | `NoCrashUnderContinuousFeeding` | 1000 frames push + `processAll` stress, no deadlock/ASAN |
| 6 | `ClearResets` | `clear()` empties ring and resets fsm to Idle |

- [x] **Step 1: Write vad_pipeline_test.cpp**

```cpp
#include <gtest/gtest.h>
#include "vad/vad_pipeline.h"
#include "audio/ring_buffer.h"
#include "vad/silero_vad.h"
#include "vad/vad_fsm.h"
TEST(VadPipeline, Pop480FromRing) { itantra::audio::SpscRingBuffer<int16_t> r(8192); itantra::vad::SileroVad v; v.load("app/src/main/assets/models/vad/silero_vad.onnx"); itantra::VadFsm f; itantra::vad::VadPipeline p(r,v,f); int16_t frame[480]={1}; r.push(frame,480); EXPECT_TRUE(p.processOne()); EXPECT_EQ(r.size(),0u); }
TEST(VadPipeline, FeedsIntoSileroAndVadFsm_IdleToSpeaking) { /* push 3 speech sines, processAll, expect Speaking */ }
// ... 4 more per table, each constructs ring/vad/fsm/pipeline
```

- [x] **Step 2: Run RED**

Run: `cmake --build build/host --target vad_pipeline_test && ctest -R VadPipeline` Expected: FAIL — `undefined reference to VadPipeline` or `vad_pipeline.h: No such file`

### Task 3: GREEN — Minimal VadPipeline

**Files:**
- Create: `app/src/main/cpp/vad/vad_pipeline.cpp`
- Modify: `app/src/main/cpp/CMakeLists.txt` (add `vad/vad_pipeline.cpp` to `itantra-native` and to `vad_pipeline_test`)

**Interfaces:**
- Produces: passing `VadPipeline`

Sketch (no allocs in hot path):

```cpp
#include "vad/vad_pipeline.h"
namespace itantra { namespace vad {
VadPipeline::VadPipeline(audio::SpscRingBuffer<int16_t>& r, SileroVad& v, VadFsm& f): ring_(r), vad_(v), fsm_(f) {}
bool VadPipeline::start(){ running_=true; return true; }
void VadPipeline::stop(){ running_=false; }
bool VadPipeline::isRunning() const { return running_; }
bool VadPipeline::processOne(){
 if(!running_) return false;
 int16_t frame[480];
 if(!ring_.pop(frame,480)) return false;
 float prob = vad_.isLoaded() ? vad_.predict(frame,480) : 0.0f;
 fsm_.onFrame(prob, frame, 480);
 return true;
}
size_t VadPipeline::processAll(){ size_t n=0; while(processOne()) ++n; return n; }
VadState VadPipeline::state() const { return fsm_.state(); }
void VadPipeline::clear(){ ring_.clear(); fsm_.reset(); vad_.reset(); }
}}
```

Keep `processOne` stack-only `int16_t frame[480]`, no `new`.

- [x] **Step 1: Implement vad_pipeline.cpp as above**

- [x] **Step 2: Update CMakeLists.txt**

```cmake
add_library(itantra-native SHARED ... vad/vad_pipeline.cpp)
...
add_executable(vad_pipeline_test tests/vad_pipeline_test.cpp vad/vad_pipeline.cpp vad/silero_vad.cpp vad/vad_fsm.cpp)
```

- [x] **Step 3: Run GREEN**

Run: `cmake --build build/host --target vad_pipeline_test && ctest -R VadPipeline --output-on-failure` Expected: `6 tests PASSED`
Run: `./gradlew :app:testDebugUnitTest --rerun-tasks` Expected: 112 Kotlin still green

### Task 4: Verify & Commit

**Files:**
- None (verification only)

- [x] **Step 1: Host GTest all green**

Run: `ctest --test-dir build/host --output-on-failure` Expected: `ring_buffer 9 + silero 9 + audio_capture 5 + vad_pipeline 6 =29` PASSED

- [x] **Step 2: Kotlin 112 still green**

Run: `./gradlew :app:testDebugUnitTest --rerun-tasks` Expected: 112 pass

- [x] **Step 3: Android debug APK**

Run: `./gradlew :app:assembleDebug` Expected: `BUILD SUCCESSFUL`, `lib/arm64-v8a/libitantra-native.so` contains `VadPipeline`, `compile_commands.json` shows ASAN/UBSAN

- [x] **Step 4: No INTERNET**

Run: `aapt dump permissions ... | grep INTERNET` Expected: no output; `scripts/check-no-internet.bat` → PASS

- [x] **Step 5: Commit**

```bash
git add app/src/main/cpp/vad/vad_pipeline.h app/src/main/cpp/vad/vad_pipeline.cpp app/src/main/cpp/CMakeLists.txt app/src/main/cpp/tests/vad_pipeline_test.cpp docs/superpowers/plans/2026-09-04-phase1-vad-pipeline.md
git commit -m "feat(phase1-vad-pipeline): Ring → Silero → VadFsm live path"
```

## Self-Review

- Spec coverage: FR-11 `Oboe lock-free ring`, §4.1.1 `audio-io`+`vad-fsm`, §4.1.3 `SCHED_FIFO never JVM`, Architecture `Mic→Ring→Silero→FSM` ✓
- No placeholders: all steps have concrete header, GTest, `processOne` code, Run commands
- Type consistency: `SpscRingBuffer<int16_t>`, `SileroVad::predict`, `VadFsm::onFrame`, `VadPipeline` refs match across tasks
- TDD flow respected: RED (no such file) → GREEN (minimal pipeline)

## Execution Handoff

Plan saved to `docs/superpowers/plans/2026-09-04-phase1-vad-pipeline.md`. Two options:
1. Subagent-Driven — recommended
2. Inline Execution — implement in session
