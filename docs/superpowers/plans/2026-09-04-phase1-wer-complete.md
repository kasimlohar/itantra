# Phase 1 — WER + 10-Lang Table + Tight RTF Complete Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close remaining Phase 1 metrics gaps — add WER (word-level) to harness, extend CSV with wer column, provide 10-lang WER/CER table placeholder (hi populated), and tighten RTF gate to PRD 0.28/0.18.

**Architecture:** Extend `data/harness/OfflineLoopHarness.kt` to compute `wer` alongside `cer` when reference provided (word-level Levenshtein normalized by ref words). Extend `HarnessResult` with `wer:Double?` (nullable, backward compat). Update `HarnessCsv` header to `lang,audioSec,sttRtf,ttsRtf,cer,wer,transcription` and `row()` to emit both. Update `HarnessGate` thresholds to `STT_THR=0.28 TTS_THR=0.18` (PRD) — host mock `0.001` still passes. Add pure `Phase1Report` helper that holds per-lang thresholds (`hi 8.2%, bn 9.1%...or 12.4%` per PRD) and can render markdown table from collected results (hi filled, others pending). All pure Kotlin, host-runnable.

**Tech Stack:** Kotlin 1.9, JUnit 4.13.2 + Truth 1.4.4, `Language` enum (10 entries), `HarnessResult`, `WavLoader`, no Android deps.

**Spec:** `docs/PRD.md:173-183` WER/CER/OI-WER, `docs/PRD.md:428-432` Phase 1 Tasks/Metrics/Exit, `docs/Offline Multilingual Speech Transceiver Architecture.md:313 WER/CER`

## Global Constraints

- `minSdk 24`, `targetSdk 34`, `arm64-v8a` only, no `INTERNET` (11 perms), MIT/Apache-2.0/CC-BY-4.0 only, pure seams host-runnable, TDD RED first, single language still hi only but table lists 10, no UI/transport, ASAN active, commit `feat(phase1): WER + 10-lang table — complete Phase 1`.

---

### Task 1: Inspect Current Harness Gaps

**Files:**
- Read: `app/src/main/java/com/itantra/data/harness/OfflineLoopHarness.kt:13` (has cer only)
- Read: `app/src/main/java/com/itantra/data/harness/HarnessCsv.kt:7` (header 6 cols)
- Read: `app/src/main/java/com/itantra/data/harness/HarnessGate.kt:6` (0.30/0.20)
- Read: `docs/PRD.md:182` WER acceptance table

**Interfaces:**
- Consumes: none
- Produces: report — missing wer, header missing wer, gate not tight, no 10-lang table.

- [ ] **Step 1: Inspect**

```powershell
Get-Content app/src/main/java/com/itantra/data/harness/OfflineLoopHarness.kt | Select-String "cer|wer"
Get-Content app/src/main/java/com/itantra/data/harness/HarnessCsv.kt
Get-Content app/src/main/java/com/itantra/data/harness/HarnessGate.kt
```

---

### Task 2: RED — Write Failing Tests for WER + Table

**Files:**
- Create: `app/src/test/java/com/itantra/data/harness/Phase1WerTest.kt`

**Interfaces:**
- Consumes: `OfflineLoopHarness`, `HarnessResult`, `HarnessCsv`, `Phase1Report`
- Produces: failing tests expecting `wer` field, CSV 7 cols, `Phase1Report` table:

```kotlin
package com.itantra.data.harness
import com.itantra.data.asr.MockAsrEngine
import com.itantra.data.tts.MockTtsEngine
import com.itantra.domain.model.Language
import com.google.common.truth.Truth.assertThat
import org.junit.Test
class Phase1WerTest {
  @Test fun harness_computesWer() {
    val asr=MockAsrEngine().apply{load(Language.HINDI)}; val tts=MockTtsEngine().apply{loadVoice(Language.HINDI)}
    val res=OfflineLoopHarness(asr,tts).run(ShortArray(16000){0}, "नमस्ते दुनिया").getOrThrow()
    assertThat(res.wer).isNotNull(); assertThat(res.wer!!).isAtLeast(0.0); assertThat(res.wer!!).isAtMost(1.0)
  }
  @Test fun csv_headerHasWer() {
    assertThat(HarnessCsv.header()).isEqualTo("lang,audioSec,sttRtf,ttsRtf,cer,wer,transcription")
    val asr=MockAsrEngine().apply{load(Language.HINDI)}; val tts=MockTtsEngine().apply{loadVoice(Language.HINDI)}
    val res=OfflineLoopHarness(asr,tts).run(ShortArray(16000){0}, "नमस्ते").getOrThrow()
    val row=HarnessCsv.row(Language.HINDI,res); assertThat(row.split(",").size).isAtLeast(7)
  }
  @Test fun phase1Report_has10LangsAndRendersTable() {
    val rep=Phase1Report()
    assertThat(rep.expectedLangs().size).isEqualTo(10)
    assertThat(rep.thresholdFor(Language.HINDI)).isWithin(0.01).of(0.082)
    val asr=MockAsrEngine().apply{load(Language.HINDI)}; val tts=MockTtsEngine().apply{loadVoice(Language.HINDI)}
    val res=OfflineLoopHarness(asr,tts).run(ShortArray(16000){0}, "नमस्ते").getOrThrow()
    rep.record(Language.HINDI, res)
    val md=rep.toMarkdown()
    assertThat(md).contains("hi"); assertThat(md).contains("WER"); assertThat(md.split("\n").size).isAtLeast(12)
  }
  @Test fun gate_tightThresholdStillPassesMock() {
    val asr=MockAsrEngine().apply{load(Language.HINDI)}; val tts=MockTtsEngine().apply{loadVoice(Language.HINDI)}
    val res=OfflineLoopHarness(asr,tts).run(ShortArray(16000){0}, "नमस्ते").getOrThrow()
    // tight 0.28/0.18 mock 0.001 should still pass
    assertThat(HarnessGate.STT_THR).isWithin(0.001).of(0.28)
    assertThat(HarnessGate.TTS_THR).isWithin(0.001).of(0.18)
    assertThat(HarnessGate.check(res).pass).isTrue()
  }
}
```

- [ ] **Step 1: Write file**
- [ ] **Step 2: Run `./gradlew :app:testDebugUnitTest --tests "com.itantra.data.harness.Phase1WerTest" -q` Expected FAIL `Unresolved reference: wer` / `Phase1Report`

---

### Task 3: GREEN — Extend HarnessResult + Compute WER + Update CSV/Gate + Phase1Report

**Files:**
- Modify: `app/src/main/java/com/itantra/data/harness/OfflineLoopHarness.kt:13` add `wer:Double?` to `HarnessResult`, compute `computeWer` alongside `computeCer`, pass to result
- Modify: `app/src/main/java/com/itantra/data/harness/HarnessCsv.kt:7` header 7 cols, row include wer
- Modify: `app/src/main/java/com/itantra/data/harness/HarnessGate.kt:6` thresholds 0.30→0.28, 0.20→0.18
- Create: `app/src/main/java/com/itantra/data/harness/Phase1Report.kt`

**Interfaces:**
- Consumes: `Language`, `HarnessResult`
- Produces: updated APIs satisfying Task 2

- [ ] **Step 1: Modify OfflineLoopHarness.kt add wer**

```kotlin
data class HarnessResult(val transcription:String, val transcriptionLength:Int, val speechBuffer:SpeechBuffer, val sttTimeMs:Long, val ttsTimeMs:Long, val sttRtf:Double, val ttsRtf:Double, val audioDurationSec:Double, val cer:Double?, val wer:Double?)
...
val wer = reference?.let{ computeWer(it, text) }
...
private fun computeWer(ref:String, hyp:String):Double { if(ref.isBlank() && hyp.isBlank()) return 0.0; if(ref.isBlank()) return 1.0; val rw=ref.trim().split(Regex("\\s+")); val hw=hyp.trim().split(Regex("\\s+")); val m=rw.size; val n=hw.size; val dp=Array(m+1){IntArray(n+1)}; for(i in 0..m) dp[i][0]=i; for(j in 0..n) dp[0][j]=j; for(i in 1..m) for(j in 1..n) dp[i][j]=minOf(dp[i-1][j]+1, dp[i][j-1]+1, dp[i-1][j-1]+ if(rw[i-1]==hw[j-1])0 else 1); return (dp[m][n].toDouble()/m).coerceIn(0.0,1.0) }
```

- [ ] **Step 2: Update HarnessCsv.kt header/row**

```kotlin
fun header()="lang,audioSec,sttRtf,ttsRtf,cer,wer,transcription"
fun row(...)= "...,${cerStr},${werStr},$clean" // wer after cer
```

- [ ] **Step 3: Update HarnessGate.kt thresholds**

```kotlin
const val STT_THR=0.28; const val TTS_THR=0.18
```

- [ ] **Step 4: Create Phase1Report.kt**

```kotlin
package com.itantra.data.harness
import com.itantra.domain.model.Language
class Phase1Report {
  private val thresholds=mapOf(Language.HINDI to 0.082, Language.BENGALI to 0.091, Language.TAMIL to 0.098, Language.TELUGU to 0.104, Language.MARATHI to 0.101, Language.GUJARATI to 0.113, Language.KANNADA to 0.109, Language.MALAYALAM to 0.117, Language.ODIA to 0.124, Language.ENGLISH to 0.072)
  private val results=mutableMapOf<Language,HarnessResult>()
  fun expectedLangs()=Language.entries.toList()
  fun thresholdFor(l:Language)=thresholds[l]!!
  fun record(l:Language, r:HarnessResult){ results[l]=r }
  fun toMarkdown():String {
    val sb=StringBuilder("| Lang | WER | CER | RTF STT | RTF TTS | Thr |\n|---|---|---|---|---|---|\n")
    for(lang in expectedLangs()){
      val r=results[lang]; val wer=r?.wer?.let{String.format("%.2f%%", it*100)}?:"pending"; val cer=r?.cer?.let{String.format("%.2f%%", it*100)}?:"pending"; val stt=r?.let{String.format("%.3f", it.sttRtf)}?:"-"; val tts=r?.let{String.format("%.3f", it.ttsRtf)}?:"-"; val thr=String.format("%.1f%%", thresholdFor(lang)*100)
      sb.append("| ${lang.name.lowercase()} | $wer | $cer | $stt | $tts | $thr |\n")
    }
    return sb.toString()
  }
}
```

- [ ] **Step 5: Run Task 2 tests GREEN**

Run: `./gradlew :app:testDebugUnitTest --tests "com.itantra.data.harness.Phase1WerTest" -q` => 4/4 PASS

- [ ] **Step 6: Run all tests (fix existing HarnessCsv expectations: header now 7 cols)**

Need to update `HarnessCsvGateTest.kt:11` expectation from 6 to 7 (`split(",").size >=6` already passes, but header string will fail — update it to expect wer). The test currently expects `lang,audioSec,sttRtf,ttsRtf,cer,transcription` (6 cols). After change it will be 7, so test will fail until we update that test to new header. Update `HarnessCsvGateTest.kt` to new header or relax to `contains wer`.

Run: `./gradlew :app:testDebugUnitTest -q` => 151+4=155? Actually 151+4=155, but one existing test will need fix: `HarnessCsvGateTest.csv_headerAndRow` expects old header. Fix it to `contains` or update expected string. Do that before final run.

---

### Task 4: Verify + Commit

**Files:**
- Verify: same 10-lang table renders, single hi populated
- Test: all green, build, no INTERNET

- [ ] **Step 1: Full tests**

Run: `./gradlew :app:testDebugUnitTest -q` => 155 PASS (151+4)

- [ ] **Step 2: Build**

Run: `./gradlew :app:assembleDebug -q` => BUILD SUCCESSFUL

- [ ] **Step 3: No INTERNET, ASAN**

Run: `scripts/check-no-internet.bat` => PASS

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/itantra/data/harness/OfflineLoopHarness.kt app/src/main/java/com/itantra/data/harness/HarnessCsv.kt app/src/main/java/com/itantra/data/harness/HarnessGate.kt app/src/main/java/com/itantra/data/harness/Phase1Report.kt app/src/test/java/com/itantra/data/harness/Phase1WerTest.kt
# also update HarnessCsvGateTest.kt header expectation if modified
git add app/src/test/java/com/itantra/data/harness/HarnessCsvGateTest.kt
git commit -m "feat(phase1): WER + 10-lang table — complete Phase 1"
```

- [ ] **Step 5: Report**

```
WER added (word Levenshtein, 0..1), CSV now 7 cols with wer, Gate tightened 0.28/0.18, Phase1Report 10 langs hi 8.2% ... or 12.4%
Tests: 155 (151+4)
Build: arm64-v8a PASS, no INTERNET PASS
Phase 1 now: FrameCodec 29, VadFsm 17, WER/CER table (hi populated, 9 pending), RTF 0.28/0.18, Harness CSV+gate
```

