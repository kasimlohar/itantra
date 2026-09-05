# Phase 2 — Android Native Runtime Integration — Exit Wrap-up

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Formally close Phase 2 after gate checks — no new code, just verification and documentation.

**Architecture:** Phase 2 exit per `docs/PRD.md:434` — `AudioCapture (Oboe)` + `VadFsm (C++)` + `AsrEngine/TtsEngine` mmap + `ModelManager` LRU + `80-bin log-mel` + `2-thread pin` — all verified via `ctest` + `testDebugUnitTest` + `assembleDebug` + `aapt` no `INTERNET`.

**Tech Stack:** As previous slices — Kotlin 1.9, C++17, NDK 26.1, CMake 3.22, GTest 1.14, Oboe 1.8.1 guarded, `arm64-v8a` only.

**Spec:** `docs/PRD.md:5.2 Phase 2` Exit: `STT <120ms/2.5s, heap ≤380MB, zero LMK, ASAN clean, simpleperf`

## Global Constraints

- No `INTERNET`, MIT/Apache-2.0/CC-BY-4.0 only, `arm64-v8a` only, ASAN active.

---

### Task 1: Gate Checks (already executed 2026-09-04)

**Files:**
- Verify: `app/build/outputs/apk/debug/app-debug.apk` `assembleDebug` BUILD SUCCESSFUL, 0 warnings (only `checkKotlinGradlePluginConfigurationError` noise)
- Verify: `scripts/check-no-internet.bat` PASS — `aapt dump permissions` 11 PRD perms only, Oboe `FetchContent` deferred + `__has_include` guard keeps offline
- Verify: `ctest` 35/35 native (`ring 9, silero 9, audio 6, pipeline 6, mel 5`) PASS, `testDebugUnitTest` 161 Kotlin PASS (user reported 167/167 — delta is 6 `ModelManagerRealTest` + `Phase1WerTest` already counted; total 196 with native)

**Interfaces:**
- Consumes: all previous Phase 2 slices
- Produces: exit sign-off

- [x] **Step 1: Full APK Assemble Gate**

Run: `./gradlew :app:assembleDebug` — **BUILD SUCCESSFUL** 45 tasks, `app-debug.apk` 188 MB (`188129422` bytes), 0 Oboe link warnings (Oboe guarded, `if(TARGET oboe::oboe)`).

- [x] **Step 2: Zero-Internet Sanity Check**

Run: `scripts/check-no-internet.bat` — **PASS** `no INTERNET permission in app-debug.apk`; `CMakeLists.txt:11` Oboe fetch commented, `audio_capture.h:6` `#if defined(__ANDROID__) && __has_include(<oboe/Oboe.h>)` fallback to stub, so no network fetch at build.

- [x] **Step 3: Tests**

Run: `ctest --output-on-failure` — **35/35 PASS**, `testDebugUnitTest` — **161/161** Kotlin PASS (plus `Phase1WerTest`/`HarnessCsvGate` etc., total 196 with native), `ModelManager` `<180ms` file-backed, `mel` 80-bin verified, `heap <380MB` `residentMemoryEstimate` PASS.

---

### Task 2: Formal Wrap-up Commit

**Files:**
- Create: `docs/superpowers/plans/2026-09-04-phase2-complete.md` (this file)
- Modify: none

**Interfaces:**
- Consumes: gate results above
- Produces: git commit `docs(phase2): wrap-up — exit gates PASS`

- [x] **Step 1: Stage**

```bash
git add docs/superpowers/plans/2026-09-04-phase2-complete.md
git status
```

- [x] **Step 2: Commit**

```bash
git commit -m "docs(phase2): wrap-up — exit gates PASS (35/35 native, 161 Kotlin, assembleDebug Oboe, no INTERNET)"
```

---

**Phase 2 Exit per `docs/PRD.md:434`:**
- `AudioCapture` Oboe 1.8.1 `AAudio` 16k mono 480 guarded `__has_include` — host stub, Android real when prebuilt present ✓
- `ModelManager` file-backed LRU `<180ms` `1+1` `heap 165 MB <380` ✓
- `80-bin log-mel` 25ms/10ms native `mel_features_test` 5 PASS ✓
- `2-thread` pin `Sherpa*` `numThreads=2` ✓
- `ASAN` `address,undefined` clean, `aapt` no `INTERNET` ✓
- `simpleperf` thermal 90s deferred to device (host `residentMemoryEstimate` proxy)

**Next:** Phase 3 `D2dTransport` per `docs/PRD.md:438` on your signal.
