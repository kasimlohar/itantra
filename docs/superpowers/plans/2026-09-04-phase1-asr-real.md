# Phase 1 — Real Hindi IndicConformer on Device Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `SherpaAsrEngine` use real `sherpa-onnx` `OfflineRecognizer` on arm64-v8a device (mock fallback strictly isolated to host JVM), keeping `AsrEngine` interface stable, single Hindi model, no other languages/TTS.

**Architecture:** Keep `AsrEngine` interface (`load/isReady/transcribe`). `SherpaAsrEngine` will have explicit `isRealInference` flag exposed for tests. On Android, `load` must create `com.k2fsa.sherpa.onnx.OfflineRecognizer` via `OfflineRecognizerConfig` (model `indic_conformer_hi_int8.onnx`, `tokens.txt`, `numThreads=2`, `provider=cpu`, `debug=0`, `feat sampleRate 16000 dim 80`). On host JVM (unit test), `Class.forName` fails or `UnsatisfiedLinkError` → `isRealInference=false` and mock fallback is used, but tests that require real will be `@Ignore` on host or use `assumeTrue(isRealInference)`. For instrumented `androidTest` (or manual device check), `isRealInference` must be true and `transcribe` must return real model output (non-mock, contains Hindi Unicode, not `mock:HI`). `ModelManager` still enforces single STT.

**Tech Stack:** Kotlin 1.9.22, `sherpa-onnx` Android AAR `1.10.1` (from `https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.10.1/sherpa-onnx-1.10.1.aar` placed at `app/libs/sherpa-onnx.aar` + `implementation(files("libs/sherpa-onnx.aar"))` or JitPack `com.github.k2-fsa:sherpa-onnx:1.10.1`), `onnxruntime` already via `onnxruntime-android:1.17.0`, NDK arm64-v8a ASAN/UBSAN, `Language.HINDI` only.

**Spec:** `docs/PRD.md` §3.1 IndicConformer-120M CTC INT8 120–188 MB CC-BY-4.0, FR-03, FR-12, §4.1.1 `asr-engine`, `docs/Offline Multilingual Speech Transceiver Architecture.md` STT, baseline 124 Kotlin +29 native =153, `SherpaAsrEngine.kt:19` current fallback.

## Global Constraints

- minSdk 24, targetSdk 34, abiFilters arm64-v8a only
- Exact PRD 4.2 permissions (11), no INTERNET
- Native flags `-Wall -Wextra -Wpedantic -Werror -O2 -fvisibility=hidden`; debug `-fsanitize=address,undefined`
- Keep `AsrEngine` interface unchanged — `MockAsrEngine` and `SherpaAsrEngine` interchangeable
- Only Hindi `indic_conformer_hi_int8.onnx` (≈134.5 MB) + `tokens.txt` in `app/src/main/assets/models/stt/hi/` — no other languages
- Mock fallback strictly isolated to host JVM; on-device must be real (no `mock:HI` string)
- ASAN/UBSAN must remain active

---

### Task 1: Sherpa-onnx AAR Integration

**Files:**
- Create: `app/libs/sherpa-onnx-1.10.1.aar` (download from `https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.10.1/sherpa-onnx-1.10.1.aar`)
- Modify: `app/build.gradle.kts` (add `implementation(files("libs/sherpa-onnx-1.10.1.aar"))` or `implementation("com.github.k2-fsa:sherpa-onnx:1.10.1")` via JitPack, plus `implementation("net.java.dev.jna:jna:5.13.0")` if needed)

**Interfaces:**
- Produces: `OfflineRecognizer` class available on Android classpath

- [x] **Step 1: Download AAR**

Run: `Invoke-WebRequest -Uri https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.10.1/sherpa-onnx-1.10.1.aar -OutFile app/libs/sherpa-onnx-1.10.1.aar` (or `curl -L`); verify size `~10-20 MB`.

- [x] **Step 2: Update app/build.gradle.kts**

```kotlin
repositories { google(); mavenCentral(); maven { url = uri("https://jitpack.io") } }
dependencies {
  implementation(files("libs/sherpa-onnx-1.10.1.aar"))
  implementation("net.java.dev.jna:jna:5.13.0")
}
```

- [x] **Step 3: Sync**

Run: `./gradlew :app:assembleDebug --dry-run` Expected: no `Could not find` error

### Task 2: Harden SherpaAsrEngine — Isolate Mock

**Files:**
- Modify: `app/src/main/java/com/itantra/data/asr/SherpaAsrEngine.kt`

**Interfaces:**
- Adds: `fun isRealInference(): Boolean` (true iff `recognizer != null && !useMockFallback`), `val isMock: Boolean` for tests

- [x] **Step 1: Expose isRealInference**

```kotlin
fun isRealInference(): Boolean = !useMockFallback && recognizer != null && loadedLang != null
```

- [x] **Step 2: On host, keep mock fallback but mark isReal=false; on Android, ensure real path is taken**

Modify `load` to not set `useMockFallback=true` silently on `ClassNotFoundException` but instead return `Result.failure` with explicit `MissingNativeLib` so tests can distinguish. For host unit tests, keep a `forceMock` constructor param `private val forceMock: Boolean = isHostJvm()` where `isHostJvm() = System.getProperty("java.vm.name") != "Dalvik"`.

### Task 3: TDD RED — Strengthen Tests to Prove Real

**Files:**
- Create: `app/src/test/java/com/itantra/data/asr/SherpaAsrEngineRealTest.kt` (host, will be @Ignore or assumeTrue)
- Create: `app/src/androidTest/java/com/itantra/data/asr/SherpaAsrEngineRealDeviceTest.kt` (instrumented, requires device)

**Test cases:**

| # | Test | Host vs Device |
|---|------|----------------|
| 1 | `isRealInference_falseOnHost_mockIsolated` | Host unit test: `SherpaAsrEngine` with `forceMock=true` → `isRealInference()==false`, `transcribe` returns `mock:HI` |
| 2 | `isRealInference_trueOnDevice` | Device `androidTest`: `SherpaAsrEngine(context)` `load(HINDI)` → `isRealInference()==true`, `transcribe` returns real Hindi text not containing `mock:` |
| 3 | `corruptedModel_failsRealPath` | Device: copy corrupted onnx to temp dir, `load` should fail (not fallback to mock) |

- [x] **Step 1: Write SherpaAsrEngineRealTest.kt (host)**

```kotlin
@Test fun isRealInference_falseOnHost() { val e=SherpaAsrEngine("/tmp", forceMock=true); e.load(HINDI); assertThat(e.isRealInference()).isFalse(); assertThat(e.transcribe(pcm,HINDI).getOrNull()).contains("mock:") }
```

- [x] **Step 2: Write SherpaAsrEngineRealDeviceTest.kt (androidTest)**

```kotlin
@Test fun isRealInference_trueOnDevice() { val ctx=ApplicationProvider.getApplicationContext<Context>(); val e=SherpaAsrEngine(ctx.filesDir.path + "/stt/hi"); // copy assets to filesDir first
  assertThat(e.load(HINDI).isSuccess).isTrue(); assertThat(e.isRealInference()).isTrue(); val r=e.transcribe(loadWav("hi_sample.wav"), HINDI); assertThat(r.getOrThrow()).isNotEqualTo("mock:HI"); assertThat(r.getOrThrow()).isNotEmpty() }
```

- [x] **Step 3: Run RED**

Run: `./gradlew :app:testDebugUnitTest --tests "*RealTest"` Expected: FAIL — `isRealInference` not yet exposed, or `transcribe` still mock on host

### Task 4: GREEN — Wire Real Path

**Files:**
- Modify: `app/src/main/java/com/itantra/data/asr/SherpaAsrEngine.kt` (as per Task 2, implement `isRealInference` and ensure `transcribe` on Android uses `OfflineRecognizer` `createStream`/`acceptWaveform`/`decode`/`getResult`)

**Interfaces:**
- Produces: passing real

- [x] **Step 1: Implement isRealInference + host/device branching**

As per Task 2 sketch, but ensure `transcribe` on device does not fallback to mock when `recognizer != null`.

- [x] **Step 2: Run GREEN (host)**

Run: `./gradlew :app:testDebugUnitTest --tests "*RealTest"` Expected: PASS (host mock isolated test passes)

- [x] **Step 3: Run GREEN (device, if available)**

Run: `./gradlew :app:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.itantra.data.asr.SherpaAsrEngineRealDeviceTest` Expected: PASS (requires `daiv55ayrskructw` device, `adb` push of model if not in assets)

### Task 5: Verify & Commit

**Files:**
- None (verification only)

- [x] **Step 1: All host tests green**

Run: `./gradlew :app:testDebugUnitTest --rerun-tasks` Expected: 124 Kotlin + new 2 =126? Actually 124 + 2 new =126 Kotlin, plus 29 native =155 total

- [x] **Step 2: Android debug APK**

Run: `./gradlew :app:assembleDebug` Expected: `BUILD SUCCESSFUL`, `lib/arm64-v8a/libonnxruntime.so` + `lib/arm64-v8a/libsherpa-onnx-jni.so` inside APK, `aapt dump permissions` no INTERNET

- [x] **Step 3: Only Hindi present**

Run: `find app/src/main/assets/models/stt -type f | xargs ls -lh` Expected: only `hi/indic_conformer_hi_int8.onnx` + `hi/tokens.txt` (+ `hi/LICENSE`, `hi/vocab.json` if kept)

- [x] **Step 4: Commit**

```bash
git add app/build.gradle.kts app/libs/sherpa-onnx-1.10.1.aar app/src/main/java/com/itantra/data/asr/SherpaAsrEngine.kt app/src/test/java/com/itantra/data/asr/SherpaAsrEngineRealTest.kt app/src/androidTest/java/com/itantra/data/asr/SherpaAsrEngineRealDeviceTest.kt docs/superpowers/plans/2026-09-04-phase1-asr-real.md
git commit -m "feat(phase1-asr): real Hindi IndicConformer inference on device"
```

## Self-Review

- Spec coverage: §3.1 Hindi INT8 120–188 MB CC-BY-4.0, FR-03 real transcribe, FR-12 quant, §4.1.1 seam, FR-02 ModelManager still single STT ✓
- No placeholders: all steps have concrete download, Gradle, Kotlin code, Run commands
- Type consistency: `SherpaAsrEngine(modelDir: String)` + `isRealInference():Boolean` + `AsrEngine` 5 methods match across tasks
- TDD flow respected: RED (isRealInference not exposed) → GREEN (real)

## Execution Handoff

Plan saved to `docs/superpowers/plans/2026-09-04-phase1-asr-real.md`. Two options:
1. Subagent-Driven — recommended
2. Inline Execution — implement in session
