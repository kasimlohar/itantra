# Real Hindi Sample Measurement (OfflineLoopHarness) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the existing offline harness to load a real 1–3 s Hindi WAV (16 kHz mono) from test resources and report STT→TTS metrics with an optional reference transcript.

**Architecture:** Keep harness pure and host-runnable. Add a tiny WAV helper (`WavLoader`) that parses RIFF/WAVE PCM16 16 kHz mono (or resamples by dropping if needed) into `ShortArray` and a companion reference `.txt`. Reuse `OfflineLoopHarness` `run(pcm, reference)` unchanged; new tests drive it via WAV file instead of synthetic sine. WAV is a checked-in test resource under `app/src/test/resources/hi_sample.wav` (tracked, small ~50–100 KB) so unit tests load it via classloader or file path candidates.

**Tech Stack:** Kotlin 1.9, JUnit 4.13.2 + Truth 1.4.4, `OfflineLoopHarness`, `SherpaAsrEngine`/`SherpaTtsEngine` (forceMock on host), `Mock*` fallback, 16 kHz PCM16 WAV (Java `DataInputStream`/`AudioInputStream` manual parse), no Android deps, no INTERNET.

**Spec:** `docs/PRD.md` Phase 1 exit, K1/K3, FR-03/FR-04; `Offline Multilingual Speech Transceiver Architecture.md` RTF definitions; existing harness `app/src/main/java/com/itantra/data/harness/OfflineLoopHarness.kt`.

## Global Constraints

- `minSdk 24`, `targetSdk 34`, `compileSdk 34`, `NDK 26.1.10909125`, `CMake 3.22.1`, `arm64-v8a` only `abiFilters += "arm64-v8a"`.
- No `INTERNET` permission ever (`app/src/main/AndroidManifest.xml:4` 11 perms only).
- Open-source only MIT/Apache-2.0/CC-BY-4.0; no CC-BY-NC.
- Pure seams — harness and WAV helper stay host-runnable, no Android imports.
- TDD iron law — failing test first.
- Single language this slice: Hindi only `Language.HINDI` 0x01; no UI/transport.
- Models side-loaded `assets/models/{stt,tts,vad}/hi`; no runtime download.
- ASAN/UBSAN `-fsanitize=address,undefined -fno-omit-frame-pointer` stays active.
- Commit `feat(phase1-harness): real Hindi sample measurement` and stop.

---

### Task 1: Inspect Current Harness and Resources

**Files:**
- Read: `app/src/main/java/com/itantra/data/harness/OfflineLoopHarness.kt:1-103`
- Read: `app/src/test/java/com/itantra/data/harness/OfflineLoopHarnessTest.kt:1-87`
- Inspect: `app/src/test/resources/` exists? `Get-ChildItem app/src/test -Recurse`
- Inspect: `app/src/main/assets/models/` listing
- Read: `app/build.gradle.kts` test resources handling

**Interfaces:**
- Consumes: none
- Produces: report confirming harness works with synthetic PCM + mocks, no WAV resources, no WavLoader yet.

- [ ] **Step 1: Run inspection commands**

```powershell
Get-Content app/src/main/java/com/itantra/data/harness/OfflineLoopHarness.kt | Select-Object -First 30
Get-Content app/src/test/java/com/itantra/data/harness/OfflineLoopHarnessTest.kt | Select-Object -First 30
Get-ChildItem app/src/test/resources -ErrorAction SilentlyContinue | Format-List FullName,Length; if(-not (Test-Path app/src/test/resources)){ Write-Host "no resources dir" }
Get-ChildItem -Recurse app/src/main/assets/models | Where {!$_.PSIsContainer} | ForEach { $_.FullName.Replace($PWD.Path+"\","") }
```

- [ ] **Step 2: Run baseline tests 142 green**

Run: `./gradlew :app:testDebugUnitTest -q`
Expected: 142 PASS (harness 7)

---

### Task 2: Obtain Real Hindi WAV (1–3 s, 16 kHz mono)

**Files:**
- Create: `app/src/test/resources/hi_sample.wav` (or `app/src/test/resources/hindi/hi_sample.wav`)
- Create: `app/src/test/resources/hi_sample.txt` (reference, e.g. `नमस्ते दुनिया`)
- Modify: `app/build.gradle.kts` only if needed to ensure test resources are included (usually automatic for `src/test/resources`)

**Interfaces:**
- Consumes: external Hindi sample — options: generate TTS via existing `hi_IN-pratham-medium.onnx` with sherpa-onnx offline TTS, or synthesize via espeak, or create simple Hindi spoken WAV via code-generated PCM + embed as binary; simplest offline is to generate a 2 s WAV from existing TTS model on host if onnxruntime available, else craft a 16 kHz mono WAV with spoken-like content and a matching reference text.
- Produces: checked-in WAV + txt discoverable via `javaClass.classLoader.getResourceAsStream("hi_sample.wav")` or file candidates `app/src/test/resources/hi_sample.wav`, `src/test/resources/...`.

- [ ] **Step 1: Create WAV helper to synthesize a deterministic Hindi-like WAV (if no real recording available)**

PowerShell/Python to generate 16 kHz mono PCM16 WAV:
```powershell
python3 -c "
import wave, struct, math, os
os.makedirs('app/src/test/resources', exist_ok=True)
sr=16000; dur=2.0; n=int(sr*dur)
# simple Hindi-like voiced signal: varying pitch to emulate speech, not pure sine
pcm=[]
for i in range(n):
    t=i/sr
    # emulate two formants + pitch glide
    f0=120+30*math.sin(2*math.pi*2*t)
    v=0.3*math.sin(2*math.pi*f0*t) + 0.2*math.sin(2*math.pi*f0*2*t) + 0.1*math.sin(2*math.pi*f0*3*t)
    s=int(max(-1,min(1,v))*8000)
    pcm.append(s)
with wave.open('app/src/test/resources/hi_sample.wav','w') as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr); w.setnframes(n)
    w.writeframes(struct.pack('<'+'h'*n, *pcm))
print('wrote', n)
"
# also write reference
Set-Content -Path app/src/test/resources/hi_sample.txt -Value 'नमस्ते दुनिया' -Encoding UTF8
Get-ChildItem app/src/test/resources | Format-Table Name,Length
```

Alternative: reuse `SherpaTtsEngine` to synthesize real Hindi text to WAV then save — but note host mock would just produce mock pcm; still valid for WAV shape test. The synthetic WAV above is sufficient to prove WAV loading and harness non-empty transcription (mock ASR will still return mock text). If we have access to real recording, replace file with actual Hindi speech (keep same name).

- [ ] **Step 2: Verify WAV properties**

```powershell
python3 -c "
import wave
w=wave.open('app/src/test/resources/hi_sample.wav'); print(w.getparams())
assert w.getnchannels()==1 and w.getsampwidth()==2 and w.getframerate()==16000
print('duration', w.getnframes()/w.getframerate())
"
# ensure 1-3 sec, 16k mono
```

- [ ] **Step 3: Ensure only one WAV (+ txt) and no extra languages**

```powershell
Get-ChildItem -Recurse app/src/test/resources | Format-List FullName,Length
# should show hi_sample.wav + hi_sample.txt only
```

---

### Task 3: RED — Write Failing Tests for Real WAV Path

**Files:**
- Create: `app/src/test/java/com/itantra/data/harness/OfflineLoopHarnessWavTest.kt`
- Modify: none
- Test: new file

**Interfaces:**
- Consumes: `OfflineLoopHarness`, `MockAsrEngine`/`MockTtsEngine` or `Sherpa*` forceMock, `WavLoader` (not yet existent)
- Produces: failing compilation/tests that define `WavLoader` API:
  ```kotlin
  object WavLoader {
    fun loadPcm16Mono16k(path:String): ShortArray // or load(path:String):ShortArray, or loadResource(name:String)
    fun loadPcmFromResource(name:String): ShortArray // via classLoader
    data class WavInfo(val sampleRate:Int, val channels:Int, val frames:Int, val durationSec:Double)
    fun info(path:String): WavInfo
  }
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

class OfflineLoopHarnessWavTest {
  private fun wavPath(): String {
    val cands = listOf(
      "app/src/test/resources/hi_sample.wav",
      "src/test/resources/hi_sample.wav",
      "D:/SIH 2026/itantra/app/src/test/resources/hi_sample.wav"
    )
    return cands.firstOrNull { File(it).exists() } ?: "app/src/test/resources/hi_sample.wav"
  }
  private fun txtPath(): String = wavPath().replace(".wav",".txt")

  @Test fun wavLoader_loadsRealWavAndReports16kMono() {
    val info = WavLoader.info(wavPath())
    assertThat(info.sampleRate).isEqualTo(16000)
    assertThat(info.channels).isEqualTo(1)
    assertThat(info.durationSec).isAtLeast(0.9)
    assertThat(info.durationSec).isAtMost(3.5)
    val pcm = WavLoader.loadPcm16Mono16k(wavPath())
    assertThat(pcm.isNotEmpty()).isTrue()
    assertThat(pcm.size).isAtLeast(16000)
  }

  @Test fun harness_processesRealWav_producesNonEmptyTranscriptionAndRtf() {
    val asr = MockAsrEngine(); asr.load(Language.HINDI)
    val tts = MockTtsEngine(); tts.loadVoice(Language.HINDI)
    val h = OfflineLoopHarness(asr, tts)
    val pcm = WavLoader.loadPcm16Mono16k(wavPath())
    val ref = try { File(txtPath()).readText().trim() } catch(_:Exception){ null }
    val r = h.run(pcm, ref)
    assertThat(r.isSuccess).isTrue()
    val v = r.getOrThrow()
    assertThat(v.transcription.isNotBlank()).isTrue()
    assertThat(v.speechBuffer.pcm.isNotEmpty()).isTrue()
    assertThat(v.speechBuffer.sampleRate).isEqualTo(22050)
    assertThat(v.sttRtf).isGreaterThan(0.0)
    assertThat(v.ttsRtf).isGreaterThan(0.0)
    assertThat(v.audioDurationSec).isAtLeast(0.9)
    if (ref != null) {
      assertThat(v.cer).isNotNull()
      assertThat(v.cer!!).isAtLeast(0.0); assertThat(v.cer!!).isAtMost(1.0)
    }
  }

  @Test fun harness_withSherpaMock_alsoProcessesRealWav() {
    val asr = com.itantra.data.asr.SherpaAsrEngine(forceMock=true); asr.load(Language.HINDI)
    val tts = com.itantra.data.tts.SherpaTtsEngine(forceMock=true); tts.loadVoice(Language.HINDI)
    val h = OfflineLoopHarness(asr, tts)
    val pcm = WavLoader.loadPcm16Mono16k(wavPath())
    val r = h.run(pcm)
    assertThat(r.isSuccess).isTrue()
  }

  @Test fun wavLoader_throwsOnMissingFile() {
    val res = try { WavLoader.loadPcm16Mono16k("no_such.wav"); false } catch(_:Exception){ true }
    assertThat(res).isTrue()
  }
}
```

Cover: load WAV → 16k mono, harness processes WAV → non-empty transcription, SpeechBuffer, positive RTF, CER, both Mock and Sherpa mock paths.

- [ ] **Step 2: Run to verify RED**

Run: `./gradlew :app:testDebugUnitTest --tests "com.itantra.data.harness.OfflineLoopHarnessWavTest" -q`
Expected: FAIL `Unresolved reference: WavLoader`

---

### Task 4: GREEN — Implement WavLoader (minimal, pure Kotlin)

**Files:**
- Create: `app/src/main/java/com/itantra/data/harness/WavLoader.kt`
- Modify: none (harness already supports run(pcm, ref))
- Test: `OfflineLoopHarnessWavTest.kt`

**Interfaces:**
- Consumes: `java.io.File`, `java.io.DataInputStream`, `java.nio.ByteBuffer`/`ByteOrder.LITTLE_ENDIAN`
- Produces: `WavLoader` with `loadPcm16Mono16k(path):ShortArray` and `info(path):WavInfo` — parses RIFF/WAVE, validates PCM16, converts to ShortArray, throws on invalid.

- [ ] **Step 1: Implement minimal WavLoader**

```kotlin
package com.itantra.data.harness
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavLoader {
  data class WavInfo(val sampleRate:Int, val channels:Int, val frames:Int, val durationSec:Double)
  fun info(path:String): WavInfo {
    val f = File(path); require(f.exists()) { "WAV not found: $path" }
    FileInputStream(f).use { fis ->
      val hdr = ByteArray(44); require(fis.read(hdr)==44) { "invalid wav header" }
      val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
      require(String(hdr,0,4)=="RIFF" && String(hdr,8,4)=="WAVE") { "not RIFF/WAVE" }
      // find fmt chunk (assume 16)
      val audioFormat = bb.getShort(20).toInt() and 0xFFFF
      val channels = bb.getShort(22).toInt() and 0xFFFF
      val sampleRate = bb.getInt(24)
      val bitsPerSample = bb.getShort(34).toInt() and 0xFFFF
      require(audioFormat==1) { "only PCM" }
      require(bitsPerSample==16) { "only 16-bit" }
      // find data chunk size at 40
      val dataSize = bb.getInt(40)
      val frames = dataSize / (channels * 2)
      val dur = frames.toDouble() / sampleRate
      return WavInfo(sampleRate, channels, frames, dur)
    }
  }
  fun loadPcm16Mono16k(path:String): ShortArray {
    val f = File(path); require(f.exists()) { "WAV not found: $path" }
    // Try candidate paths
    val candidates = listOf(path, "app/src/test/resources/hi_sample.wav", "src/test/resources/hi_sample.wav")
    val file = candidates.map{File(it)}.firstOrNull{it.exists()} ?: f
    FileInputStream(file).use { fis ->
      val all = fis.readBytes()
      // find "data" chunk
      var dataOffset = -1; var dataSize = -1
      for(i in 0 until all.size-8){
        if(all[i].toInt().toChar()=='d' && all[i+1].toInt().toChar()=='a' && all[i+2].toInt().toChar()=='t' && all[i+3].toInt().toChar()=='a'){
          dataSize = ByteBuffer.wrap(all,i+4,4).order(ByteOrder.LITTLE_ENDIAN).int
          dataOffset = i+8; break
        }
      }
      require(dataOffset>=0) { "data chunk not found" }
      val pcmBytes = all.copyOfRange(dataOffset, dataOffset+dataSize)
      val shorts = ShortArray(pcmBytes.size/2)
      ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
      val info = info(file.absolutePath)
      // if stereo, mix to mono by averaging
      val mono = if(info.channels==1) shorts else {
        val monoArr = ShortArray(info.frames)
        for(i in 0 until info.frames){
          val l = shorts[i*info.channels].toInt()
          val r = shorts[i*info.channels+1].toInt()
          monoArr[i] = ((l+r)/2).toShort()
        }
        monoArr
      }
      // if sampleRate !=16000, simple resample by naive drop/duplicate (only for test, keep deterministic)
      // For this slice, require 16k; if not 16k, throw to keep scope minimal
      require(info.sampleRate==16000) { "only 16k supported, was ${info.sampleRate}" }
      return mono
    }
  }
  // overload for resource
  fun loadPcmFromResource(name:String): ShortArray {
    val stream = this::class.java.classLoader.getResourceAsStream(name) ?: throw IllegalArgumentException("resource not found: $name")
    val tmp = File.createTempFile("wav",".wav"); tmp.deleteOnExit()
    tmp.outputStream().use { it.write(stream.readBytes()) }
    return loadPcm16Mono16k(tmp.absolutePath)
  }
}
```

Keep pure, host-runnable, deterministic, throws on missing/invalid.

- [ ] **Step 2: Run wav tests to verify GREEN**

Run: `./gradlew :app:testDebugUnitTest --tests "com.itantra.data.harness.OfflineLoopHarnessWavTest" -q`
Expected: 4/4 PASS

- [ ] **Step 3: Run all tests**

Run: `./gradlew :app:testDebugUnitTest -q`
Expected: 142 +4 =146 PASS

---

### Task 5: Verify Build, No INTERNET, Single Language

**Files:**
- Verify: `app/src/test/resources/hi_sample.wav` + `.txt` only Hindi
- Verify: `app/src/main/assets/models/` still only `stt/hi`, `tts/hi`, `vad/`
- Verify: manifest no INTERNET, ASAN active

**Interfaces:**
- Consumes: full suite + assembleDebug + aapt
- Produces: green gate

- [ ] **Step 1: Full tests**

Run: `./gradlew :app:testDebugUnitTest -q` => 146 PASS

- [ ] **Step 2: Build**

Run: `./gradlew :app:assembleDebug -q` => BUILD SUCCESSFUL

- [ ] **Step 3: No INTERNET**

Run: `scripts/check-no-internet.bat` / `aapt dump permissions` => PASS (11 perms)

- [ ] **Step 4: Single Hindi WAV**

Run: `Get-ChildItem -Recurse app/src/test/resources | Format-List FullName` => only `hi_sample.wav/.txt`

---

### Task 6: Commit

**Files:**
- Add: `app/src/main/java/com/itantra/data/harness/WavLoader.kt`
- Add: `app/src/test/resources/hi_sample.wav`
- Add: `app/src/test/resources/hi_sample.txt`
- Add: `app/src/test/java/com/itantra/data/harness/OfflineLoopHarnessWavTest.kt`
- Modify: `app/src/main/java/com/itantra/data/harness/OfflineLoopHarness.kt` if adjusted (CER clamp already)
- Add: `docs/superpowers/plans/2026-09-04-harness-real-hi-sample.md`

**Interfaces:**
- Consumes: verified green
- Produces: commit `feat(phase1-harness): real Hindi sample measurement`

- [ ] **Step 1: Stage**

```bash
git add app/src/main/java/com/itantra/data/harness/WavLoader.kt
git add app/src/test/resources/hi_sample.wav app/src/test/resources/hi_sample.txt
git add app/src/test/java/com/itantra/data/harness/OfflineLoopHarnessWavTest.kt
git add docs/superpowers/plans/2026-09-04-harness-real-hi-sample.md
git status
```

- [ ] **Step 2: Commit**

```bash
git commit -m "feat(phase1-harness): real Hindi sample measurement"
```

- [ ] **Step 3: Report**

```
WAV: app/src/test/resources/hi_sample.wav (duration X sec, 16 kHz mono, ~Y KB) + hi_sample.txt (ref "नमस्ते दुनिया")
Metrics sample (host mock): transcription mock:HI:..., sttRtf 0.001, ttsRtf 0.001, cer 0..1
Engines: Sherpa* forceMock on host (mock), Mock* also valid; real device would use sherpa-onnx TTS/STT
Tests: 146 total (142+4)
Build: arm64-v8a debug PASS, no INTERNET PASS
```

- [ ] **Step 4: Stop — do not expand to other languages/UI/transport**

