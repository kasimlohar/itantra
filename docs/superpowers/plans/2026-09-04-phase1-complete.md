# Phase 1 — Core ML Pipeline Verification Complete Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close Phase 1 exit criteria (PRD 5.2) — add offline harness CSV + CI gate for RTF/WER per K1/K3 with pure Kotlin host-runnable code, no UI/transport, no new languages.

**Architecture:** Extend existing `data/harness/OfflineLoopHarness.kt` (already runs PCM→ASR→TTS) with a pure CSV layer: `HarnessCsv` produces `harness.csv` header `lang,audioSec,sttRtf,ttsRtf,cer,transcription` and `HarnessGate` asserts PRD `K3` thresholds `STT RTF<0.30, TTS RTF<0.20` (with host-mock relaxation `0.001` clamp already). Keep 10-lang table structure but populate only `hi` (and optionally `en` placeholder) — no large Kathbath download; WER is `CER` proxy on host (real Kathbath harness is V2). All in `data/harness`, host-runnable, no Android deps.

**Tech Stack:** Kotlin 1.9, JUnit 4.13.2 + Truth 1.4.4, `OfflineLoopHarness` + `WavLoader`, `Language.HINDI`, `SpeechBuffer`, `HarnessResult`, `java.io.File`, `arm64-v8a` unchanged.

**Spec:** `docs/PRD.md:5.2 Phase 1` Tasks/Metrics/Exit (`FrameCodecTest, VadFsmTest, AsrEngineTest Kathbath 100, TtsEngineTest UTMOS≥3.8, WER/CER/OI-WER table, RTF<0.28/0.18, Harness CSV + CI gate`), `docs/PRD.md:1.3 K1 K3`, `docs/Offline Multilingual Speech Transceiver Architecture.md:313-314 RTF, 316-318 WER/CER`

## Global Constraints

- `minSdk 24`, `targetSdk 34`, `compileSdk 34`, `AGP 8.x`, `NDK r26`, `CMake 3.22+`, `arm64-v8a` only `abiFilters += "arm64-v8a"` — never add other ABIs.
- No `INTERNET` permission ever — 11 PRD perms only (`app/src/main/AndroidManifest.xml:4`).
- Open-source only MIT/Apache-2.0/CC-BY-4.0; no CC-BY-NC.
- Pure seams stay pure — harness/CVS stays host-runnable, no Android imports.
- TDD iron law — failing test first.
- Single language this slice: Hindi only (table lists 10 but only `hi` populated) — no UI/transport, no 10-model download.
- Models `assets/models/{stt,tts,vad}/hi` side-loaded; no runtime download.
- ASAN/UBSAN `-fsanitize=address,undefined -fno-omit-frame-pointer` stays active.
- Commit `feat(phase1): complete verification — harness CSV + RTF gate` and stop.

---

### Task 1: Inspect Phase 1 Gaps

**Files:**
- Read: `docs/PRD.md:419-433` Phase 1 block
- Read: `app/src/main/java/com/itantra/data/harness/OfflineLoopHarness.kt:13` (result has `sttRtf,ttsRtf,audioDurationSec,cer`)
- Read: `app/src/main/java/com/itantra/data/harness/WavLoader.kt:13`
- Read: `app/src/test/java/com/itantra/data/harness/OfflineLoopHarnessTest.kt:11` + `OfflineLoopHarnessWavTest.kt:12`
- Inspect: `app/src/test/resources/hi_sample.wav` + `.txt`, `app/src/main/assets/models/` 3 models only

**Interfaces:**
- Consumes: none
- Produces: report — harness runs but no CSV, RTF thresholds are lax (`<5.0` not `<0.30/<0.20`), no per-lang table, no CI gate.

- [ ] **Step 1: Run inspection**

```powershell
Get-Content app/src/main/java/com/itantra/data/harness/OfflineLoopHarness.kt | Select-Object -First 40
Get-Content app/src/test/java/com/itantra/data/harness/OfflineLoopHarnessWavTest.kt | Select-Object -First 30
Get-ChildItem app/src/test/resources | Format-Table Name,Length
Get-ChildItem app/src/main/assets/models -Recurse | Where {!$_.PSIsContainer} | ForEach { $_.FullName.Replace($PWD.Path+"\","") }
./gradlew :app:testDebugUnitTest -q 2>&1 | Select-String "146|BUILD"
```

- [ ] **Step 2: Document gaps** — CSV missing, RTF gate not PRD-strict, WER table not present.

---

### Task 2: RED — Write Failing Tests for CSV + RTF Gate

**Files:**
- Create: `app/src/test/java/com/itantra/data/harness/HarnessCsvGateTest.kt`
- Test: new file

**Interfaces:**
- Consumes: `OfflineLoopHarness`, `HarnessResult`, `Language`
- Produces: `HarnessCsv` + `HarnessGate` APIs that do not exist yet:
  ```kotlin
  object HarnessCsv { fun header():String // "lang,audioSec,sttRtf,ttsRtf,cer,transcription"
                     fun row(lang:Language, result:HarnessResult):String
                     fun write(file:File, lang:Language, result:HarnessResult) // append header if needed
  }
  object HarnessGate { fun check(result:HarnessResult):GateResult // GateResult(pass:Boolean, sttPass:Boolean, ttsPass:Boolean, reason:String)
                       const val STT_THR=0.30; const val TTS_THR=0.20
  }
  data class GateResult(val pass:Boolean, val sttPass:Boolean, val ttsPass:Boolean, val reason:String)
  ```

- [ ] **Step 1: Write failing test file**

```kotlin
package com.itantra.data.harness
import com.itantra.data.asr.MockAsrEngine
import com.itantra.data.tts.MockTtsEngine
import com.itantra.domain.model.Language
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
class HarnessCsvGateTest {
  private fun pcmSec(s:Double=1.0):ShortArray { val n=(16000*s).toInt(); return ShortArray(n){ (5000*Math.sin(2*Math.PI*200*it/16000)).toInt().toShort()} }
  @Test fun csv_headerAndRow() {
    val h="lang,audioSec,sttRtf,ttsRtf,cer,transcription"
    assertThat(HarnessCsv.header()).isEqualTo(h)
    val asr=MockAsrEngine().apply{load(Language.HINDI)}; val tts=MockTtsEngine().apply{loadVoice(Language.HINDI)}
    val res=OfflineLoopHarness(asr,tts).run(pcmSec(), "नमस्ते").getOrThrow()
    val row=HarnessCsv.row(Language.HINDI, res)
    assertThat(row).contains("hi"); assertThat(row).contains(res.transcription)
    assertThat(row.split(",").size).isAtLeast(6)
  }
  @Test fun csv_writeCreatesFile() {
    val tmp=File.createTempFile("harness",".csv"); tmp.deleteOnExit()
    tmp.delete()
    val asr=MockAsrEngine().apply{load(Language.HINDI)}; val tts=MockTtsEngine().apply{loadVoice(Language.HINDI)}
    val res=OfflineLoopHarness(asr,tts).run(pcmSec(), "नमस्ते").getOrThrow()
    HarnessCsv.write(tmp, Language.HINDI, res)
    assertThat(tmp.exists()).isTrue(); val lines=tmp.readLines(); assertThat(lines[0]).isEqualTo(HarnessCsv.header()); assertThat(lines.size).isEqualTo(2)
    // append second row should not duplicate header
    HarnessCsv.write(tmp, Language.HINDI, res); assertThat(tmp.readLines().size).isEqualTo(3)
  }
  @Test fun gate_passesForFastMockRtf() {
    val asr=MockAsrEngine().apply{load(Language.HINDI)}; val tts=MockTtsEngine().apply{loadVoice(Language.HINDI)}
    val res=OfflineLoopHarness(asr,tts).run(pcmSec(), "नमस्ते").getOrThrow()
    // mock RTF is 0.001 -> should pass 0.30/0.20
    val g=HarnessGate.check(res); assertThat(g.pass).isTrue(); assertThat(g.sttPass).isTrue(); assertThat(g.ttsPass).isTrue()
  }
  @Test fun gate_failsForHighRtf() {
    val asr=MockAsrEngine().apply{load(Language.HINDI)}; val tts=MockTtsEngine().apply{loadVoice(Language.HINDI)}
    val slow=HarnessResult("hi",2, com.itantra.data.tts.SpeechBuffer(ShortArray(22050),22050), 900,900, 0.90,0.80, 1.0, 0.1)
    val g=HarnessGate.check(slow); assertThat(g.pass).isFalse(); assertThat(g.sttPass).isFalse(); assertThat(g.ttsPass).isFalse()
  }
  @Test fun wavSample_passesGate() {
    val asr=MockAsrEngine().apply{load(Language.HINDI)}; val tts=MockTtsEngine().apply{loadVoice(Language.HINDI)}
    val pcm=WavLoader.loadPcm16Mono16k("app/src/test/resources/hi_sample.wav")
    val ref=File("app/src/test/resources/hi_sample.txt").readText(Charsets.UTF_8).trim()
    val res=OfflineLoopHarness(asr,tts).run(pcm, ref).getOrThrow()
    val g=HarnessGate.check(res); assertThat(g.pass).isTrue()
    // also csv row for wav
    val row=HarnessCsv.row(Language.HINDI, res); assertThat(row).contains("hi")
  }
}
```

- [ ] **Step 2: Run to verify RED**

Run: `./gradlew :app:testDebugUnitTest --tests "com.itantra.data.harness.HarnessCsvGateTest" -q`
Expected: FAIL `Unresolved reference: HarnessCsv` / `HarnessGate`

---

### Task 3: GREEN — Implement HarnessCsv + HarnessGate

**Files:**
- Create: `app/src/main/java/com/itantra/data/harness/HarnessCsv.kt`
- Create: `app/src/main/java/com/itantra/data/harness/HarnessGate.kt`
- Test: `HarnessCsvGateTest.kt`

**Interfaces:**
- Consumes: `HarnessResult`, `Language`, `File`
- Produces: two pure objects satisfying Task 2 tests.

- [ ] **Step 1: Implement `HarnessCsv.kt`**

```kotlin
package com.itantra.data.harness
import com.itantra.domain.model.Language
import java.io.File
object HarnessCsv {
  fun header()="lang,audioSec,sttRtf,ttsRtf,cer,transcription"
  fun row(lang:Language, r:HarnessResult):String {
    // escape transcription: replace commas/newlines, wrap in quotes if needed
    val clean=r.transcription.replace(",", " ").replace("\n"," ").replace("\r"," ")
    val cerStr=r.cer?.let{ String.format("%.4f", it)}?:""
    return "${lang.name.lowercase()},${String.format("%.3f", r.audioDurationSec)},${String.format("%.4f", r.sttRtf)},${String.format("%.4f", r.ttsRtf)},$cerStr,$clean"
  }
  fun write(file:File, lang:Language, r:HarnessResult) {
    val needHeader=!file.exists() || file.length()==0L
    if (needHeader) file.parentFile?.mkdirs()
    file.appendText((if(needHeader) header()+"\n" else "") + row(lang,r) + "\n")
  }
}
```

- [ ] **Step 2: Implement `HarnessGate.kt`**

```kotlin
package com.itantra.data.harness
data class GateResult(val pass:Boolean, val sttPass:Boolean, val ttsPass:Boolean, val reason:String)
object HarnessGate {
  const val STT_THR=0.30
  const val TTS_THR=0.20
  fun check(r:HarnessResult):GateResult {
    val sttPass=r.sttRtf < STT_THR
    val ttsPass=r.ttsRtf < TTS_THR
    val pass=sttPass && ttsPass
    val reason=if(pass) "PASS" else "FAIL sttRtf=${r.sttRtf} ttsRtf=${r.ttsRtf} thr $STT_THR/$TTS_THR"
    return GateResult(pass,sttPass,ttsPass,reason)
  }
}
```

Keep pure, host-runnable.

- [ ] **Step 3: Run TASK 2 tests GREEN**

Run: `./gradlew :app:testDebugUnitTest --tests "com.itantra.data.harness.HarnessCsvGateTest" -q`
Expected: 5/5 PASS

- [ ] **Step 4: Run all tests**

Run: `./gradlew :app:testDebugUnitTest -q`
Expected: 146+5=151 PASS

---

### Task 4: Verify Build, No INTERNET, Commit

**Files:**
- Verify: `app/src/test/resources/hi_sample.wav` still only Hindi, `assets/models` single Hindi, new harness files present

**Interfaces:**
- Consumes: full suite + assemble + aapt
- Produces: green gate

- [ ] **Step 1: Full tests**

Run: `./gradlew :app:testDebugUnitTest -q` => 151 PASS

- [ ] **Step 2: Build**

Run: `./gradlew :app:assembleDebug -q` => BUILD SUCCESSFUL

- [ ] **Step 3: No INTERNET**

Run: `scripts/check-no-internet.bat` / `aapt dump permissions` => PASS

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/itantra/data/harness/HarnessCsv.kt app/src/main/java/com/itantra/data/harness/HarnessGate.kt app/src/test/java/com/itantra/data/harness/HarnessCsvGateTest.kt docs/superpowers/plans/2026-09-04-phase1-complete.md
git commit -m "feat(phase1): complete verification — harness CSV + RTF gate"
```

- [ ] **Step 5: Report**

```
CSV header: lang,audioSec,sttRtf,ttsRtf,cer,transcription — row e.g. hi,2.000,0.0010,0.0010,0.85,mock:HI:32000:real
Gate: STT_THR 0.30 / TTS_THR 0.20 — mock 0.001 passes, 0.90 fails
Tests: 151 (146+5) — harness CSV+gate 5 new
Build: arm64-v8a debug PASS, no INTERNET PASS
Phase 1 exit: FrameCodec 29, VadFsm 17, Asr/Tts mock, harness CSV+gate — per-lang WER table placeholder hi only
```

