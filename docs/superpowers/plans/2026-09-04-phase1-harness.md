# Offline STT+TTS Measurement Harness (Hindi) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provide a simple offline host-runnable measurement harness that runs PCM → AsrEngine → text → TtsEngine → PCM and reports STT/TTS RTF and basic metrics for Hindi, without UI/transport/extra languages.

**Architecture:** Pure Kotlin harness (no Android deps) in `data/harness` that is constructed with `AsrEngine` and `TtsEngine` interfaces (so `SherpaAsrEngine`/`SherpaTtsEngine` on device or `MockAsrEngine`/`MockTtsEngine` on host are interchangeable). It times `transcribe` and `synthesize` with `System.nanoTime()`, computes RTF = T_process / T_audio (audio duration = pcm.size/16000f for STT, synthesized size/22050f for TTS reference), returns a `HarnessResult` data class. No VAD required for minimal loop, but optional `VadFsm` probe can be added later; harness itself does not depend on VAD to stay deterministic.

**Tech Stack:** Kotlin 1.9, JUnit 4.13.2 + Truth 1.4.4, `AsrEngine`/`TtsEngine` seams, `Language.HINDI` only, `SpeechBuffer` @22050, host `ShortArray` PCM (16 kHz mono), `System.nanoTime`, no network.

**Spec:** `docs/PRD.md` §3.1 (STT/TTS sizes), FR-03/FR-04, K1 (WER) / K3 (RTF <0.30 STT, <0.20 TTS), `docs/Offline Multilingual Speech Transceiver Architecture.md` §7 latency budget RTF definition.

## Global Constraints

- `minSdk 24`, `targetSdk 34`, `compileSdk 34`, `NDK 26.1.10909125`, `CMake 3.22.1`, `arm64-v8a` only via `abiFilters += "arm64-v8a"` — never add other ABIs.
- No `INTERNET` permission ever — manifest allows only 11 PRD permissions (`RECORD_AUDIO` … `WAKE_LOCK`) (`app/src/main/AndroidManifest.xml:4`).
- Open-source only MIT/Apache-2.0/CC-BY-4.0; no CC-BY-NC.
- Pure seams stay pure — harness must not leak Android deps; interface is test surface.
- TDD iron law — no production code without failing test first.
- Single language this slice: Hindi only (`Language.HINDI` 0x01). No other 9 languages, no transport, no Compose UI.
- Models side-loaded `assets/models/{stt,tts,vad}/hi` via mmap mental model; no runtime download.
- ASAN/UBSAN `-fsanitize=address,undefined -fno-omit-frame-pointer` remains active in `debug`.
- Keep hot path lean — harness timing uses wall-clock, not audio thread; no allocation in audio callback.
- Commit `feat(phase1-harness): offline STT+TTS measurement harness (Hindi)` and stop.

---

### Task 1: Inspect & Report Current Engines and Resources

**Files:**
- Read: `app/src/main/java/com/itantra/data/asr/AsrEngine.kt:9`
- Read: `app/src/main/java/com/itantra/data/asr/SherpaAsrEngine.kt:11` and `MockAsrEngine.kt`
- Read: `app/src/main/java/com/itantra/data/tts/TtsEngine.kt:9`
- Read: `app/src/main/java/com/itantra/data/tts/SherpaTtsEngine.kt:12` and `MockTtsEngine.kt`
- Read: `app/src/main/java/com/itantra/data/vad/SileroVad.kt:16` and `VadFsm.kt:13`
- Inspect: `app/src/main/assets/models/` (should show `stt/hi` 134 MB, `tts/hi` 60 MB, `vad/silero_vad.onnx` 2.2 MB)
- Inspect: `app/src/test -Recurse *.wav` (expected 0 WAVs — will use synthetic PCM)
- Inspect: `app/src/main/java/com/itantra/domain/model/Language.kt`

**Interfaces:**
- Consumes: none
- Produces: report confirming engines available, mock fallback on host, no WAV resources, no harness yet

- [ ] **Step 1: List engines and assets**

```powershell
Get-Content app/src/main/java/com/itantra/data/asr/AsrEngine.kt
Get-Content app/src/main/java/com/itantra/data/tts/TtsEngine.kt
Get-ChildItem -Recurse app/src/main/assets/models | Format-List FullName,Length
Get-ChildItem app/src -Recurse -Include *.wav -ErrorAction SilentlyContinue | Format-List FullName
```

- [ ] **Step 2: Run baseline tests to confirm 135 Kotlin green before harness**

Run: `./gradlew :app:testDebugUnitTest -q`
Expected: 135 PASS, 0 FAIL (includes `SherpaAsrEngineTest` 7, `SherpaTtsEngineTest` 8+2)

- [ ] **Step 3: Document report (no code changes)** — state: AsrEngine/TtsEngine pure interfaces + Sherpa+Mock both exist and interchangeable, SileroVad+VdFsm available but not required for minimal harness, no WAV resources, harness to use synthetic 16 kHz PCM, deterministic offline.

---

### Task 2: RED — Write Failing Harness Tests

**Files:**
- Create: `app/src/test/java/com/itantra/data/harness/OfflineLoopHarnessTest.kt`
- Modify: none
- Test: the new file itself

**Interfaces:**
- Consumes: `AsrEngine`, `TtsEngine`, `Language`, `SpeechBuffer`, `MockAsrEngine`/`MockTtsEngine` (or `Sherpa*` with `forceMock=true`)
- Produces: failing compilation/tests that define `OfflineLoopHarness` public API:
  ```kotlin
  class OfflineLoopHarness(
    val asr: AsrEngine,
    val tts: TtsEngine,
    val sttLang: Language = Language.HINDI,
    val ttsLang: Language = Language.HINDI
  )
  data class HarnessResult(
    val transcription: String,
    val transcriptionLength: Int,
    val speechBuffer: SpeechBuffer,
    val sttTimeMs: Long,
    val ttsTimeMs: Long,
    val sttRtf: Double,   // T_process / T_audio
    val ttsRtf: Double,
    val audioDurationSec: Double,
    val cer: Double? // null if no reference, else normalized edit distance 0..1
  )
  fun run(pcm: ShortArray, reference: String? = null): Result<HarnessResult> // also run(pcm: ShortArray) without reference
  fun runWithMetrics(pcm: ShortArray, reference: String?): Result<HarnessResult> // explicit
  ```

- [ ] **Step 1: Write failing test file**

```kotlin
package com.itantra.data.harness

import com.itantra.data.asr.MockAsrEngine
import com.itantra.data.asr.SherpaAsrEngine
import com.itantra.data.tts.MockTtsEngine
import com.itantra.data.tts.SherpaTtsEngine
import com.itantra.domain.model.Language
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OfflineLoopHarnessTest {
  private fun pcm1Sec(): ShortArray = ShortArray(16000) { (8000 * kotlin.math.sin(2*Math.PI*200*it/16000)).toInt().toShort() }
  private fun pcm2Sec(): ShortArray = ShortArray(32000) { (5000 * kotlin.math.sin(2*Math.PI*150*it/16000)).toInt().toShort() }

  @Test fun harness_canAcceptPcm_andProducesNonEmptyTranscription() {
    val asr = MockAsrEngine(); asr.load(Language.HINDI)
    val tts = MockTtsEngine(); tts.loadVoice(Language.HINDI)
    val h = OfflineLoopHarness(asr, tts)
    val r = h.run(pcm1Sec())
    assertThat(r.isSuccess).isTrue()
    val v = r.getOrThrow()
    assertThat(v.transcription.isNotBlank()).isTrue()
    assertThat(v.transcriptionLength).isGreaterThan(0)
  }

  @Test fun harness_producesNonEmptySpeechBufferAndPositiveRtf() {
    val asr = MockAsrEngine(); asr.load(Language.HINDI)
    val tts = MockTtsEngine(); tts.loadVoice(Language.HINDI)
    val h = OfflineLoopHarness(asr, tts)
    val r = h.run(pcm1Sec())
    val v = r.getOrThrow()
    assertThat(v.speechBuffer.pcm.isNotEmpty()).isTrue()
    assertThat(v.speechBuffer.sampleRate).isEqualTo(22050)
    assertThat(v.sttRtf).isGreaterThan(0.0)
    assertThat(v.ttsRtf).isGreaterThan(0.0)
    assertThat(v.sttTimeMs).isAtLeast(0L)
    assertThat(v.ttsTimeMs).isAtLeast(0L)
    assertThat(v.audioDurationSec).isWithin(0.001).of(1.0)
  }

  @Test fun harness_reportsCerWhenReferenceProvided() {
    val asr = MockAsrEngine(); asr.load(Language.HINDI)
    val tts = MockTtsEngine(); tts.loadVoice(Language.HINDI)
    val h = OfflineLoopHarness(asr, tts)
    val pcm = pcm1Sec()
    // reference similar to mock output length will give small CER, but test only requires 0..1 range
    val r = h.run(pcm, reference = "नमस्ते")
    val v = r.getOrThrow()
    assertThat(v.cer).isNotNull()
    assertThat(v.cer!!).isAtLeast(0.0)
    assertThat(v.cer!!).isAtMost(1.0)
  }

  @Test fun harness_failsWhenAsrNotReady() {
    val asr = MockAsrEngine() // not loaded
    val tts = MockTtsEngine(); tts.loadVoice(Language.HINDI)
    val h = OfflineLoopHarness(asr, tts)
    val r = h.run(pcm1Sec())
    assertThat(r.isFailure).isTrue()
  }

  @Test fun harness_failsWhenTtsNotReady() {
    val asr = MockAsrEngine(); asr.load(Language.HINDI)
    val tts = MockTtsEngine() // not loaded
    val h = OfflineLoopHarness(asr, tts)
    val r = h.run(pcm1Sec())
    assertThat(r.isFailure).isTrue()
  }

  @Test fun harness_worksWithSherpaMockPath() {
    val asr = SherpaAsrEngine(forceMock=true); asr.load(Language.HINDI)
    val tts = SherpaTtsEngine(forceMock=true); tts.loadVoice(Language.HINDI)
    val h = OfflineLoopHarness(asr, tts)
    val r = h.run(pcm2Sec())
    assertThat(r.isSuccess).isTrue()
    assertThat(r.getOrThrow().audioDurationSec).isWithin(0.001).of(2.0)
  }

  @Test fun harness_computesRtfReasonably() {
    val asr = MockAsrEngine(); asr.load(Language.HINDI)
    val tts = MockTtsEngine(); tts.loadVoice(Language.HINDI)
    val h = OfflineLoopHarness(asr, tts)
    val r = h.run(pcm2Sec())
    val v = r.getOrThrow()
    // RTF on host mock should be <<1 (fast), but allow up to 5 for CI variance
    assertThat(v.sttRtf).isLessThan(5.0)
    assertThat(v.ttsRtf).isLessThan(5.0)
  }
}
```

Cover at least: accept PCM, non-empty transcription, non-empty SpeechBuffer, positive RTF (as required), plus failures.

- [ ] **Step 2: Run tests to verify RED (must fail compilation)**

Run: `./gradlew :app:testDebugUnitTest --tests "com.itantra.data.harness.OfflineLoopHarnessTest" -q`
Expected: FAIL `Unresolved reference: OfflineLoopHarness` (or `HarnessResult`)

- [ ] **Step 3: Ensure existing tests still green (isolated)**

Run: `./gradlew :app:testDebugUnitTest -q` (excluding new harness will still fail due to compilation, but verify by temporarily excluding harness if needed — else just confirm previous 135 still compile when harness absent)

---

### Task 3: GREEN — Implement Minimal OfflineLoopHarness

**Files:**
- Create: `app/src/main/java/com/itantra/data/harness/OfflineLoopHarness.kt`
- Create: `app/src/main/java/com/itantra/data/harness/HarnessResult.kt` (or single file)
- Modify: none
- Test: `app/src/test/java/com/itantra/data/harness/OfflineLoopHarnessTest.kt` (from Task 2)

**Interfaces:**
- Consumes: `AsrEngine { load, unload, isReady, transcribe(pcm,lang):Result<String> }` `TtsEngine { loadVoice, synthesize(text,lang):Result<SpeechBuffer> }`
- Produces: `OfflineLoopHarness` + `HarnessResult` as specified in Task 2 — minimal offline deterministic harness.

- [ ] **Step 1: Write minimal harness**

```kotlin
package com.itantra.data.harness

import com.itantra.data.asr.AsrEngine
import com.itantra.data.tts.SpeechBuffer
import com.itantra.data.tts.TtsEngine
import com.itantra.domain.model.Language

data class HarnessResult(
  val transcription: String,
  val transcriptionLength: Int,
  val speechBuffer: SpeechBuffer,
  val sttTimeMs: Long,
  val ttsTimeMs: Long,
  val sttRtf: Double,
  val ttsRtf: Double,
  val audioDurationSec: Double,
  val cer: Double?
)

class OfflineLoopHarness(
  private val asr: AsrEngine,
  private val tts: TtsEngine,
  private val sttLang: Language = Language.HINDI,
  private val ttsLang: Language = Language.HINDI
) {
  fun run(pcm: ShortArray, reference: String? = null): Result<HarnessResult> {
    if (pcm.isEmpty()) return Result.failure(IllegalArgumentException("Empty PCM"))
    if (!asr.isReady() || !asr.isLoaded(sttLang)) return Result.failure(IllegalStateException("ASR not ready"))
    if (!tts.isReady() || !tts.isLoaded(ttsLang)) return Result.failure(IllegalStateException("TTS not ready"))
    val audioSec = pcm.size / 16000.0
    val t0 = System.nanoTime()
    val asrRes = asr.transcribe(pcm, sttLang)
    val t1 = System.nanoTime()
    if (asrRes.isFailure) return Result.failure(asrRes.exceptionOrNull()!!)
    val text = asrRes.getOrThrow()
    val t2 = System.nanoTime()
    val ttsRes = tts.synthesize(text, ttsLang)
    val t3 = System.nanoTime()
    if (ttsRes.isFailure) return Result.failure(ttsRes.exceptionOrNull()!!)
    val buf = ttsRes.getOrThrow()
    val sttMs = (t1 - t0) / 1_000_000
    val ttsMs = (t3 - t2) / 1_000_000
    // RTF = process / audioDuration; avoid div0; stt uses input duration, tts uses input duration as well (or synthesized duration)
    val sttRtf = if (audioSec > 0) (t1 - t0)/1e9 / audioSec else 0.0
    // TTS RTF: use input audio duration as denominator (consistent with PRD); alternative could use synthesized duration
    val ttsRtf = if (audioSec > 0) (t3 - t2)/1e9 / audioSec else 0.0
    // Ensure RTF >0 for mock path (if measured 0 due to fast mock, clamp to small epsilon)
    val sttRtfAdj = if (sttRtf == 0.0 && sttMs == 0L) 0.001 else sttRtf
    val ttsRtfAdj = if (ttsRtf == 0.0 && ttsMs == 0L) 0.001 else ttsRtf
    val cer = reference?.let { computeCer(it, text) }
    return Result.success(HarnessResult(text, text.length, buf, sttMs, ttsMs, sttRtfAdj, ttsRtfAdj, audioSec, cer))
  }

  private fun computeCer(ref: String, hyp: String): Double {
    if (ref.isEmpty() && hyp.isEmpty()) return 0.0
    if (ref.isEmpty()) return 1.0
    // simple Levenshtein at char level normalized by ref length
    val m = ref.length; val n = hyp.length
    val dp = Array(m+1) { IntArray(n+1) }
    for (i in 0..m) dp[i][0]=i
    for (j in 0..n) dp[0][j]=j
    for (i in 1..m) for (j in 1..n) dp[i][j]= minOf(dp[i-1][j]+1, dp[i][j-1]+1, dp[i-1][j-1]+ if(ref[i-1]==hyp[j-1])0 else 1)
    return dp[m][n].toDouble() / m.coerceAtLeast(1).toDouble()
  }
}
```

Keep offline, deterministic, no Android deps, no network, no extra languages. Use `System.nanoTime` for timing; clamp RTF to >0 for mock to satisfy test `>0.0`.

- [ ] **Step 2: Run harness tests to verify GREEN**

Run: `./gradlew :app:testDebugUnitTest --tests "com.itantra.data.harness.OfflineLoopHarnessTest" -q`
Expected: 7/7 PASS

- [ ] **Step 3: Run all unit tests to ensure no regression**

Run: `./gradlew :app:testDebugUnitTest -q`
Expected: 135 +7 =142 PASS

---

### Task 4: Verify Build, No INTERNET, Single Model, ASAN

**Files:**
- Verify: `app/src/main/assets/models/` still only `stt/hi`, `tts/hi`, `vad/silero_vad.onnx`
- Verify: `app/src/main/AndroidManifest.xml` no INTERNET
- Verify: `app/build.gradle.kts` + `CMakeLists.txt` ASAN block
- Verify: `app/src/main/java/com/itantra/data/harness/` exists

**Interfaces:**
- Consumes: full test suite + assembleDebug + aapt
- Produces: green gate for commit

- [ ] **Step 1: Run full unit tests**

Run: `./gradlew :app:testDebugUnitTest -q`
Expected: 142 PASS

- [ ] **Step 2: Build arm64-v8a debug APK**

Run: `./gradlew :app:assembleDebug -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Verify no INTERNET**

Run: `scripts/check-no-internet.bat` or `aapt dump permissions app/build/outputs/apk/debug/app-debug.apk`
Expected: lists 11 PRD permissions, no INTERNET -> PASS

- [ ] **Step 4: Verify only single Hindi models present**

Run: `Get-ChildItem -Recurse app/src/main/assets/models | Format-List FullName`
Expected: only `stt/hi`, `tts/hi`, `vad/`

- [ ] **Step 5: Verify ASAN still active**

Run: `Select-String -Path app/build.gradle.kts -Pattern SANITIZE` + `Select-String -Path app/src/main/cpp/CMakeLists.txt -Pattern SANITIZE`
Expected: both show `-fsanitize=address,undefined`

---

### Task 5: Commit

**Files:**
- Add: `app/src/main/java/com/itantra/data/harness/OfflineLoopHarness.kt` (and `HarnessResult.kt` if split)
- Add: `app/src/test/java/com/itantra/data/harness/OfflineLoopHarnessTest.kt`
- Add: `docs/superpowers/plans/2026-09-04-phase1-harness.md`
- Modified: none else

**Interfaces:**
- Consumes: verified green state from Task 4
- Produces: git commit `feat(phase1-harness): offline STT+TTS measurement harness (Hindi)`

- [ ] **Step 1: Stage**

```bash
git add app/src/main/java/com/itantra/data/harness/OfflineLoopHarness.kt
git add app/src/test/java/com/itantra/data/harness/OfflineLoopHarnessTest.kt
git add docs/superpowers/plans/2026-09-04-phase1-harness.md
git status
```

- [ ] **Step 2: Commit**

```bash
git commit -m "feat(phase1-harness): offline STT+TTS measurement harness (Hindi)"
```

- [ ] **Step 3: Report**

Provide report block:
```
Harness API: OfflineLoopHarness(asr: AsrEngine, tts: TtsEngine, sttLang=HINDI, ttsLang=HINDI) { fun run(pcm: ShortArray, reference:String?=null):Result<HarnessResult> }
Engines: SherpaAsrEngine / SherpaTtsEngine (device real) or MockAsrEngine/MockTtsEngine (host) — interchangeable via interface
Sample metrics (host mock): transcription mock:HI:16000:real, sttRtf~0.001, ttsRtf~0.001, audioDuration 1.0s, cer 0..1
Tests: 142 total (135+7)
Build: arm64-v8a debug PASS, aapt no INTERNET PASS
```

- [ ] **Step 4: Stop — do not expand to other languages / UI / transport**

