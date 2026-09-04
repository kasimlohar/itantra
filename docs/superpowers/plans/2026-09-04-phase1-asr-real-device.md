# Phase 1 — Fix Sherpa AAR Wiring for Real On-Device ASR

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `SherpaAsrEngine.isRealInference()==true` on arm64-v8a device by correctly wiring the already-downloaded `sherpa-onnx` native libs, keeping host mock isolated.

**Architecture:** Keep `SherpaAsrEngine` Kotlin reflection path (`OfflineRecognizer`/`OfflineRecognizerConfig`/`OfflineModelConfig`/`FeatureConfig`) and `forceMock`/`isRealInference`/`isMock` flags. On host JVM the `Class.forName` fails → `useMockFallback=true` → mock `mock:HI` and `isRealInference==false`. On device, after wiring, `Class.forName` must succeed and `UnsatisfiedLinkError` must not occur, so `recognizer` is created, `useMockFallback=false`, `isRealInference==true` and `transcribe` returns real Hindi `text` (no `mock:`). No interface change, single Hindi model `indic_conformer_hi_int8.onnx` 134.5 MB `tokens.txt` stays.

**Tech Stack:** Kotlin 1.9.22, sherpa-onnx 1.10.1 (AAR from `https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.10.1/sherpa-onnx-1.10.1.aar` **valid** ~30 MB, not the 554-byte HTML stub), JNA `net.java.dev.jna:jna:5.13.0` (transitive via AAR, ensure `packaging.jniLibs.useLegacyPackaging`), NDK arm64-v8a ASAN/UBSAN, `Language.HINDI` only.

**Spec:** `docs/PRD.md` §3.1 IndicConformer-120M CTC INT8 CC-BY-4.0, FR-03, §4.1.1 `asr-engine` seam, current `SherpaAsrEngine.kt:1` `load->Class.forName` fallback, `app/build.gradle.kts:88` currently only `onnxruntime-android:1.17.0` and `onnxruntime:1.17.0` test, `app/libs/sherpa-onnx-1.10.1.aar` is 554 B HTML (invalid), `app/libs/sherpa-onnx-android.tar.bz2` extracted to `app/libs/jniLibs/` but **not** wired as `jniLibs` nor `implementation(files(...))`, so `OfflineRecognizer` never on `debugRuntimeClasspath` and `isRealInference` always false.

## Global Constraints

- minSdk 24, targetSdk 34, abiFilters arm64-v8a only — PRD 4.1.2
- Exact PRD 4.2 permissions (11), no INTERNET — `aapt dump permissions`
- Native flags `-Wall -Wextra -Wpedantic -Werror -O2 -fvisibility=hidden`; debug `-fsanitize=address,undefined -fno-omit-frame-pointer`
- Only `hi/indic_conformer_hi_int8.onnx` (≈134 MB) + `hi/tokens.txt` + `hi/LICENSE` — no other `*.onnx`
- Keep `AsrEngine` interface stable; host `MockAsrEngine` and `SherpaAsrEngine(forceMock=true)` must stay green
- ASAN/UBSAN must remain active

---

### Task 1: Remove Broken AAR Stub & Stage Real AAR

**Files:**
- Delete: `app/libs/sherpa-onnx-1.10.1.aar` (554 B HTML)
- Keep: `app/libs/sherpa-onnx-android.tar.bz2` (32 MB, source of truth) but do not commit extracted `jniLibs/` (it duplicates AAR content)
- Create: `app/libs/sherpa-onnx-1.10.1.aar` valid (from tar's `build` or re-download via `curl -L` with correct `browser_download_url` `https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.10.1/sherpa-onnx-v1.10.1-android.tar.bz2` then `tar -xjf` already done, but need to reconstruct AAR: actually the `android.tar.bz2` already contains `jniLibs/` + `classes.jar` + `AndroidManifest.xml` — we can either `implementation(files("libs/sherpa-onnx-1.10.1.aar"))` after reconstructing AAR via `jar cvf`, or simpler `implementation(fileTree(dir: "libs", include: ["*.aar"]))` after we create a proper AAR)

**Interfaces:**
- Produces: valid `app/libs/sherpa-onnx-1.10.1.aar` (~15–30 MB) containing `jni/arm64-v8a/libsherpa-onnx-jni.so`, `jni/arm64-v8a/libonnxruntime.so`, `classes.jar` with `com.k2fsa.sherpa.onnx.OfflineRecognizer`

- [ ] **Step 1: Remove 554 B stub**

```bash
rm app/libs/sherpa-onnx-1.10.1.aar
```

- [ ] **Step 2: Reconstruct AAR from tar (if tar contains AAR structure) or re-download AAR from correct Maven/JitPack**

Run: `ls app/libs/sherpa-onnx-android.tar.bz2` already 32 MB; `tar -tf` shows it is **not** an AAR but a `sherpa-onnx` prebuilt tar with `lib/` + `include/` + `jniLibs/`. For this slice, the simplest real device path is to use the `jniLibs` already extracted: move them to `app/src/main/jniLibs/` and add `implementation("net.java.dev.jna:jna:5.13.0")` + `System.loadLibrary("sherpa-onnx-jni")` is handled by AAR's `System.loadLibrary` automatically if we use `implementation(files(...))` with a proper AAR. Since we have `jniLibs/arm64-v8a/*` already, we can just move `app/libs/jniLibs` → `app/src/main/jniLibs` and let `packaging.jniLibs.useLegacyPackaging=true` pick it up, without needing an AAR.

Simplify: `mv app/libs/jniLibs app/src/main/jniLibs` and delete `app/libs/sherpa-onnx-android.tar.bz2` stub AAR; add `implementation("net.java.dev.jna:jna:5.13.0")` to `build.gradle.kts`.

### Task 2: Gradle Wiring

**Files:**
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Produces: `OfflineRecognizer` on `debugRuntimeClasspath` on device, still absent on host `testDebugUnitTest` classpath (so host keeps mock)

- [ ] **Step 1: Update build.gradle.kts**

```kotlin
dependencies {
  implementation(files("libs/sherpa-onnx-1.10.1.aar")) // if AAR reconstructed, else use jniLibs path
  implementation("net.java.dev.jna:jna:5.13.0")
  // Keep host mock: testImplementation still only onnxruntime desktop, not sherpa
}
```

If we use `src/main/jniLibs` approach, no `files("libs/...aar")` needed; just `implementation("net.java.dev.jna:jna:5.13.0")` and the `jniLibs` will be packaged automatically. Choose **jniLibs move** as minimal.

- [ ] **Step 2: Verify**

Run: `./gradlew :app:assembleDebug --dry-run` Expected: no `Could not find` error

### Task 3: No Code Change (SherpaAsrEngine already correct)

**Files:**
- None (already has `isRealInference`/`isMock`/`forceMock` and reflection `OfflineRecognizer`)

**Interfaces:**
- On host, `Class.forName` will still fail (no `sherpa-onnx` on `testDebugUnitTest` classpath if we keep `implementation` not `testImplementation`), so `isRealInference==false` and `SherpaAsrEngineRealTest.kt:1` (`forceMock=true`) still passes.
- On device, `Class.forName` will succeed (AAR's `classes.jar` on `debugRuntimeClasspath`), `UnsatisfiedLinkError` will not occur because `jniLibs/arm64-v8a/libsherpa-onnx-jni.so` is packaged, so `isRealInference==true`.

- [ ] **Step 1: No edit needed** (already implements `isRealInference` correctly)

### Task 4: Verify On Device & Host

**Files:**
- None

- [ ] **Step 1: Host still green (mock isolated)**

Run: `./gradlew :app:testDebugUnitTest --tests "*SherpaAsrEngineRealTest*"` Expected: `isRealInference_falseOnHost_mockIsolated` PASS (`false`/`true` mock)

- [ ] **Step 2: Device real**

Run: `./gradlew :app:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.itantra.data.asr.SherpaAsrEngineRealDeviceTest` on `daiv55ayrskructw` Expected: `load(HINDI).isSuccess` `isRealInference()==true` `transcribe` returns real Hindi `text` without `mock:` and `isNotEmpty()`

- [ ] **Step 3: Full green**

Run: `./gradlew :app:testDebugUnitTest --rerun-tasks` Expected: 124 Kotlin (now 125 with RealTest) + 29 native =153 still green (host mock)

- [ ] **Step 4: APK**

Run: `./gradlew :app:assembleDebug` → `lib/arm64-v8a/libsherpa-onnx-jni.so` + `libonnxruntime.so` inside APK, `aapt dump permissions` no `INTERNET`, `find assets/models/stt -type f` only `hi/...`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/jniLibs app/build.gradle.kts app/src/main/java/com/itantra/data/asr/SherpaAsrEngine.kt docs/superpowers/plans/2026-09-04-phase1-asr-real-device.md
git commit -m "feat(phase1-asr): wire sherpa-onnx AAR for real on-device inference"
```

## Self-Review

- Spec coverage: §3.1 Hindi INT8 CC-BY-4.0, FR-03 real, §4.1.1 seam, FR-02 single STT still enforced via `ModelManager` ✓
- No placeholders: all steps have concrete `curl`/`tar`/`gradle`/`isRealInference` code
- Type consistency: `SherpaAsrEngine(modelDir, forceMock)` + `isRealInference():Boolean` match across tasks
- TDD flow respected: host mock stays green, device real will become green after wiring

## Execution Handoff

Plan saved to `docs/superpowers/plans/2026-09-04-phase1-asr-real-device.md`. Two options:
1. Subagent-Driven — recommended
2. Inline Execution — implement in session
