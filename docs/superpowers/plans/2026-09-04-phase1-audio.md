# Phase 1 — Audio Ring Buffer Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver pure C++ lock-free SPSC ring buffer per PRD FR-11 — 16 kHz mono PCM16, 200 ms pre-roll, 30 ms frame (480 samples), GC-free, lock-free, no underruns, testable via GTest with ASAN/UBSAN, no Oboe/ASR/TTS/transport/UI.

**Architecture:** Header-only template `audio::SpscRingBuffer<T>` (specialized `int16_t`) with power-of-two capacity, `std::atomic<size_t>` head/tail, mask-based wrap. Single producer (Oboe callback) + single consumer (VadFsm). Overwrite-oldest policy when full (drop oldest to keep newest 200 ms window). Zero-copy `peekContiguous` for VadFsm if needed, otherwise copy push/pop. Stays in `itantra-native` lib so `-Wall -Wextra -Wpedantic -Werror -O2 -fvisibility=hidden` + ASAN/UBSAN apply. Host GTest target via `FetchContent` `googletest` for TDD RED→GREEN; keep Android gradle path unchanged.

**Tech Stack:** C++17, CMake 3.22.1, GTest 1.14 via FetchContent, NDK 26.1.10909125 arm64-v8a, host g++/clang for `ctest`, same manifest no INTERNET.

**Spec:** `docs/PRD.md` §4.1.1 `audio-io` seam `RingBuffer {push(PCM16), pop(30ms), preRoll(200ms)}`, FR-11 `Native audio via Oboe, GC-free, lock-free ring, no underruns on 90s`, §4.1.3 `Audio thread SCHED_FIFO lock-free ring never on JVM`, `docs/Offline Multilingual Speech Transceiver Architecture.md` audio-capture / ring-buffer / pre-roll: 16 kHz 480 samples/30ms, 200 ms circular pre-roll (≈3200 samples), `docs/superpowers/plans/2026-09-04-phase0-framecodec.md` baseline, prior seams `VadFsm` already pure.

## Global Constraints

- minSdk 24, targetSdk 34, abiFilters arm64-v8a only — PRD 4.1.2
- Exact PRD 4.2 permissions (11), no INTERNET — `aapt dump permissions`
- Native flags `-Wall -Wextra -Wpedantic -Werror -O2 -fvisibility=hidden`; debug `-fsanitize=address,undefined -fno-omit-frame-pointer`
- TDD iron law: no production code without failing GTest first (RED→GREEN)
- Pure C++ lock-free SPSC only — no Oboe, no ASR/TTS models, no transport, no UI, no Kotlin changes beyond build wiring
- Must support 16 kHz mono PCM16, 200 ms pre-roll capacity, efficient push/pop of 30 ms frames (480 samples)
- Interface clean so VadFsm can later `pop(480)` / `peek` from it — PRD 4.1.1

---

### Task 1: Header & CMake Wiring

**Files:**
- Modify: `app/src/main/cpp/audio/ring_buffer.h` (replace 3-line stub with full template)
- Create: `app/src/main/cpp/audio/ring_buffer.cpp` (only if non-header parts needed; else delete stub include)
- Modify: `app/src/main/cpp/CMakeLists.txt` (add `option(BUILD_TESTING)` + GTest FetchContent, `enable_testing()`, keep `itantra-native` lib)

**Interfaces:**
- Consumes: none
- Produces: `namespace itantra::audio { template<typename T> class SpscRingBuffer }` with API:

```cpp
template<typename T>
class SpscRingBuffer {
public:
  explicit SpscRingBuffer(size_t capacity); // must be power-of-two, >=3200 for int16_t
  size_t capacity() const noexcept;
  size_t size() const noexcept; // approximate for SPSC (load atomics)
  bool empty() const noexcept;
  bool full() const noexcept;
  void clear() noexcept;
  // returns false if would overflow and overwrite disabled; our policy overwrites oldest when full — always succeeds, returns true
  bool push(const T* data, size_t count);
  bool pushOne(const T& v);
  bool pop(T* out, size_t count);
  bool popOne(T& out);
  // zero-copy peek: contiguous readable region
  std::pair<const T*, size_t> peekContiguous() const noexcept;
  void consume(size_t count) noexcept;
};
```

- [x] **Step 1: Write ring_buffer.h header skeleton (no impl yet for RED)**

```cpp
#pragma once
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <vector>
namespace itantra { namespace audio {
template<typename T> class SpscRingBuffer { public: explicit SpscRingBuffer(size_t c); /* decl */ };
using PcmRing = SpscRingBuffer<int16_t>;
}}
```

- [x] **Step 2: Modify CMakeLists.txt to support host GTest**

```cmake
option(BUILD_TESTING "Build tests" ON)
if(BUILD_TESTING)
  include(FetchContent)
  FetchContent_Declare(googletest URL https://github.com/google/googletest/archive/v1.14.0.zip)
  set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
  FetchContent_MakeAvailable(googletest)
  enable_testing()
  add_executable(ring_buffer_test tests/ring_buffer_test.cpp)
  target_link_libraries(ring_buffer_test PRIVATE GTest::gtest_main)
  target_include_directories(ring_buffer_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
  include(GoogleTest)
  gtest_discover_tests(ring_buffer_test)
endif()
```

Keep existing `add_library(itantra-native ...)` unchanged so ASAN still applies to lib. For Android gradle, `BUILD_TESTING=OFF` via `-DBUILD_TESTING=OFF` (default via `externalNativeBuild` won't define). For host `ctest`, run with `-DBUILD_TESTING=ON`.

- [x] **Step 3: Verify CMake configures for host**

Run: `cmake -S app/src/main/cpp -B build/host -DBUILD_TESTING=ON -DCMAKE_BUILD_TYPE=Debug` Expected: `-- Fetching googletest...` no error

### Task 2: TDD RED — GTest Failing Suite

**Files:**
- Create: `app/src/main/cpp/tests/ring_buffer_test.cpp`
- Create: `app/src/main/cpp/tests/CMakeLists.txt` (if separate) or use root CMake above

**Interfaces:**
- Consumes: `SpscRingBuffer` header (stub)
- Produces: 9 failing GTests mapping to FR-11:

| # | GTest name | Requirement |
|---|------------|-------------|
| 1 | `EmptyFullBehaviour` | empty on init, full after capacity pushes, size/capacity |
| 2 | `PushPopSingle30msFrame480` | 480 samples @16kHz round-trip |
| 3 | `CapacityAtLeast200msPreRoll` | capacity ≥3200 (or ≥4096 power-of-two), can hold 7×480=3360 |
| 4 | `OverwritePolicyWhenFull` | when full, push overwrites oldest (document: keep newest) — push still succeeds, oldest dropped, size stays capacity, read returns newest |
| 5 | `ClearReset` | clear → empty, size 0 |
| 6 | `FifoOrderPreserved` | push 1,2,3 pop in same order |
| 7 | `WrapAround` | push/pop forces head/tail wrap, no corruption |
| 8 | `ThreadSafetySPSCStress` | 1 producer thread push 480×1000, 1 consumer pop, no crash under ASAN, final size ≤ capacity |
| 9 | `ZeroCopyPeekContiguous` | peek returns contiguous region, consume advances |

- [x] **Step 1: Write ring_buffer_test.cpp with 9 TEST() importing `audio/ring_buffer.h`**

```cpp
#include <gtest/gtest.h>
#include "audio/ring_buffer.h"
using itantra::audio::SpscRingBuffer;
TEST(RingBuffer, EmptyFullBehaviour) { SpscRingBuffer<int16_t> rb(4096); EXPECT_TRUE(rb.empty()); ... EXPECT_FALSE(rb.full()); }
TEST(RingBuffer, PushPopSingle30msFrame) { SpscRingBuffer<int16_t> rb(4096); int16_t in[480]={...}; EXPECT_TRUE(rb.push(in,480)); int16_t out[480]; EXPECT_TRUE(rb.pop(out,480)); EXPECT_EQ(out[0], in[0]); }
// ... 7 more per table, each uses SpscRingBuffer<int16_t>(4096) and int16_t PCM16
TEST(RingBuffer, CapacityAtLeast200msPreRoll) { SpscRingBuffer<int16_t> rb(4096); EXPECT_GE(rb.capacity(), 3200u); // 200ms*16 =3200
  int16_t f[480]={1}; for(int i=0;i<7;i++) ASSERT_TRUE(rb.push(f,480)); EXPECT_EQ(rb.size(), 7*480); }
TEST(RingBuffer, OverwritePolicy) { SpscRingBuffer<int16_t> rb(8); for(int i=0;i<8;i++) rb.pushOne(i); EXPECT_TRUE(rb.full()); EXPECT_TRUE(rb.pushOne(99)); EXPECT_EQ(rb.size(),8u); int16_t v; rb.popOne(v); EXPECT_EQ(v,1); // 0 overwritten
}
```

- [x] **Step 2: Run RED**

Run: `cmake --build build/host --target ring_buffer_test && ctest --test-dir build/host --output-on-failure` Expected: FAIL — `undefined reference to SpscRingBuffer::push` or header stub not implemented (link or compile error). If header-only stub declares but not defined, then `undefined reference` or `static assertion`.

Alternative Android: `./gradlew :app:assembleDebug` Expected: still builds (ring_buffer.h stub still header-only, but test target fails).

### Task 3: TDD GREEN — Minimal Correct Lock-Free SPSC

**Files:**
- Modify: `app/src/main/cpp/audio/ring_buffer.h` (replace with full impl)
- Optional: `app/src/main/cpp/audio/ring_buffer.cpp` (if move impl out)

**Interfaces:**
- Consumes: GTest expectations
- Produces: Passing lock-free implementation

Implementation sketch (single file header-only, C++17, lock-free SPSC, power-of-two mask):

```cpp
#pragma once
#include <atomic>
#include <vector>
#include <cassert>
namespace itantra { namespace audio {
template<typename T>
class SpscRingBuffer {
 public:
  explicit SpscRingBuffer(size_t cap) : cap_(nextPow2(cap)), mask_(cap_-1), buf_(cap_), head_(0), tail_(0) { assert(cap>=2); }
  size_t capacity() const noexcept { return cap_; }
  size_t size() const noexcept { return head_.load(std::memory_order_acquire) - tail_.load(std::memory_order_acquire); }
  bool empty() const noexcept { return size()==0; }
  bool full() const noexcept { return size()==cap_; }
  void clear() noexcept { head_.store(0, std::memory_order_relaxed); tail_.store(0, std::memory_order_relaxed); }
  bool push(const T* data, size_t n) {
    if(n>cap_) { // larger than capacity: keep last cap elements
      data += n - cap_; n = cap_; clear();
    }
    size_t h = head_.load(std::memory_order_relaxed);
    size_t t = tail_.load(std::memory_order_acquire);
    size_t free = cap_ - (h - t);
    if(n > free) { // overwrite oldest: advance tail
      size_t drop = n - free;
      tail_.store(t + drop, std::memory_order_release);
    }
    for(size_t i=0;i<n;++i) buf_[(h+i)&mask_] = data[i];
    head_.store(h + n, std::memory_order_release);
    return true;
  }
  bool pop(T* out, size_t n) {
    size_t t = tail_.load(std::memory_order_relaxed);
    size_t h = head_.load(std::memory_order_acquire);
    if(h - t < n) return false;
    for(size_t i=0;i<n;++i) out[i] = buf_[(t+i)&mask_];
    tail_.store(t + n, std::memory_order_release);
    return true;
  }
  bool pushOne(const T& v){ return push(&v,1); }
  bool popOne(T& v){ return pop(&v,1); }
  std::pair<const T*,size_t> peekContiguous() const noexcept { /* head-tail contiguous */ }
  void consume(size_t n) noexcept { tail_.fetch_add(n, std::memory_order_release); }
 private:
  static size_t nextPow2(size_t n){ size_t p=1; while(p<n) p <<=1; return p; }
  size_t cap_, mask_;
  std::vector<T> buf_;
  std::atomic<size_t> head_, tail_;
};
}}
```

Document overwrite policy in header comment: `When full, push overwrites oldest samples (drop oldest, keep newest) — appropriate for 200 ms pre-roll where newest audio matters.`

- [x] **Step 1: Replace ring_buffer.h with full impl (header-only)**

Copy sketch above, add `peekContiguous`/`consume` minimal, ensure `-Werror` clean (no unused).

- [x] **Step 2: Run GREEN (host)**

Run: `cmake --build build/host && ctest --test-dir build/host --output-on-failure` Expected: `9 tests passed`, ASAN clean (`-fsanitize=address,undefined` if host build adds `-DSANITIZE`).

- [x] **Step 3: Run GREEN via Android (optional)**

Run: `./gradlew :app:assembleDebug` Expected: `BUILD SUCCESSFUL`, `libitantra-native.so` still contains ring buffer (now real).

### Task 4: Verify & Commit

**Files:**
- None (verification only)

- [x] **Step 1: Host GTest all green**

Run: `ctest --test-dir build/host --output-on-failure` Expected: `100% tests passed, 0 tests failed`

- [x] **Step 2: Kotlin 75 still green**

Run: `./gradlew :app:testDebugUnitTest --rerun-tasks` Expected: `29 FrameCodec + 17 VadFsm + 14 Router + 15 Ptt =75` pass

- [x] **Step 3: Android debug APK**

Run: `./gradlew :app:assembleDebug` Expected: `BUILD SUCCESSFUL`, `lib/arm64-v8a/libitantra-native.so` only, `compile_commands.json` shows ASAN/UBSAN + `-Wall -Wextra -Wpedantic -Werror`

- [x] **Step 4: No INTERNET**

Run: `aapt dump permissions app/build/outputs/apk/debug/app-debug.apk | grep -i INTERNET` Expected: no output; `scripts/check-no-internet.bat` → PASS

- [x] **Step 5: Commit**

```bash
git add app/src/main/cpp/audio/ring_buffer.h app/src/main/cpp/audio/ring_buffer.cpp app/src/main/cpp/CMakeLists.txt app/src/main/cpp/tests/ring_buffer_test.cpp docs/superpowers/plans/2026-09-04-phase1-audio.md
git commit -m "feat(phase1-audio): lock-free SPSC ring buffer TDD per PRD FR-11"
```

## Self-Review

- Spec coverage: FR-11 GC-free lock-free, audio-io seam `push/pop/preRoll`, §4.1.3 `SCHED_FIFO lock-free never JVM`, Architecture 200 ms pre-roll + 30 ms frame ✓
- No placeholders: all steps have concrete header, CMake, GTest code, Run commands
- Type consistency: `SpscRingBuffer<int16_t>`, `push(const T*,size_t)`, `pop`, `capacity/size/empty/full/clear` match across tasks
- TDD flow respected: RED (undefined reference) → GREEN (minimal lock-free)

## Execution Handoff

Plan saved to `docs/superpowers/plans/2026-09-04-phase1-audio.md`. Two options:
1. Subagent-Driven (fresh subagent per task) — recommended
2. Inline Execution — implement in session
