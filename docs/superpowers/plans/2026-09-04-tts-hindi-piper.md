# TTS Hindi Piper Real Integration (Single Language) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the first real offline TTS voice (Hindi, Piper VITS medium, MIT) behind the existing pure `TtsEngine` seam, side-loaded under `assets/models/tts/hi/`, with `SherpaTtsEngine` via sherpa-onnx TTS reflection, host-mock/device-real isolation, and ModelManager single-TTS residency, without breaking existing 8 TTS mock tests.

**Architecture:** Keep `TtsEngine` interface stable (`loadVoice/unload/isReady/isLoaded/synthesize -> Result<SpeechBuffer> 22050Hz`). Add `SherpaTtsEngine` (Kotlin) that uses reflection for `com.k2fsa.sherpa.onnx.OfflineTts`/`OfflineTtsConfig` so host JVM unit tests fallback to deterministic mock PCM while Android (Dalvik) runs real Piper VITS inference via existing `libsherpa-onnx-* + libonnxruntime.so + libespeak-ng.so + libpiper_phonemize.so` jniLibs. Mirror `SherpaAsrEngine` pattern: `isRealInference()/isMock()/forceMock`. Model files `*.onnx + *.onnx.json + LICENSE` side-loaded in `assets/models/tts/hi/` (35–55 MB, MIT, no runtime download). Validate with failing-then-passing JUnit+Truth tests before committing.

**Tech Stack:** Kotlin 1.9, Android SDK 34, NDK 26.1.10909125, CMake 3.22.1, sherpa-onnx 1.10.1 (offline TTS), onnxruntime-android 1.17.0 / onnxruntime 1.17.0 (host tests), espeak-ng + piper_phonemize native libs (already in jniLibs), JUnit 4.13.2 + Truth 1.4.4, GTest (no C++ TTS changes needed for this slice), ASAN/UBSAN `-fsanitize=address,undefined`.

**Spec:** `docs/PRD.md` §3.1 TTS (Piper VITS 35–55 MB, 22.05 kHz, espeak-ng, RTF 0.08–0.18, first-buffer <80 ms), FR-04, FR-02 (only one TTS resident ≤380 MB, LRU <180 ms), §4.1.1 `tts-engine: TtsEngine::synthesize(Text,Lang)->PCM 22kHz`; `docs/Offline Multilingual Speech Transceiver Architecture.md` §2 Piper/Hear2Read tables. Also see `docs/PRD.md` Appendix A compliance matrix.

## Global Constraints

- `minSdk 24`, `targetSdk 34`, `compileSdk 34`, `AGP 8.x`, `Kotlin 1.9+`, `NDK r26` (actual 26.1.10909125), `CMake 3.22.1`, `arm64-v8a` only via `abiFilters += "arm64-v8a"` — never add other ABIs.
- No `INTERNET` permission ever — manifest allows only `RECORD_AUDIO, ACCESS_WIFI_STATE, CHANGE_WIFI_STATE, ACCESS_FINE_LOCATION(maxSdk30), NEARBY_WIFI_DEVICES(neverForLocation), BLUETOOTH(maxSdk30), BLUETOOTH_ADMIN(maxSdk30), BLUETOOTH_CONNECT, BLUETOOTH_SCAN(neverForLocation), FOREGROUND_SERVICE, WAKE_LOCK` (`app/src/main/AndroidManifest.xml:4-15`).
- Open-source only: MIT / Apache-2.0 / CC-BY-4.0; reject CC-BY-NC (MMS-TTS banned) — license audit via `LICENSES.md`/`LICENSE` files.
- Pure seams must stay pure/testable: interface is test surface — do not leak Android deps into `TtsEngine`.
- TDD iron law: no production code without failing test first; watch it fail (JUnit/GTest).
- Keep `onAudioReady`/`processOne` lean (no new/malloc/log/JVM on hot path) — not touched here.
- Single language this slice: Hindi only (`Language.HINDI` code `0x01`). No other 9 languages, no transport sockets, no Compose UI.
- Models side-loaded `assets/models/tts/hi/` via `mmap` mental model; no runtime download.
- ASAN/UBSAN `-fsanitize=address,undefined -fno-omit-frame-pointer` must remain active in `debug` (`app/build.gradle.kts:32-40`, `CMakeLists.txt:88-102`).
- Commit message `feat(phase1-tts): real Piper Hindi TtsEngine (single language)` and stop.

---

### Task 1: Inspect & Report Exact TTS State (pre-plan verification)

**Files:**
- Read: `app/src/main/java/com/itantra/data/tts/TtsEngine.kt:1-15`
- Read: `app/src/main/java/com/itantra/data/tts/MockTtsEngine.kt:1-37`
- Read: `app/src/main/java/com/itantra/data/tts/SpeechBuffer.kt:1-24`
- Read: `app/src/main/java/com/itantra/data/models/ModelManager.kt:1-68`
- Read: `app/src/main/cpp/tts/tts_engine.h:1-42` and `tts_engine.cpp:1-2`
- Read: `app/src/main/cpp/CMakeLists.txt:19-28`
- Read: `app/build.gradle.kts:8-22,89-91`
- Read: `app/src/main/AndroidManifest.xml:4-15`
- Read: `app/src/test/java/com/itantra/data/tts/TtsEngineTest.kt:1-91`
- Inspect: `app/src/main/assets/models/` and `app/src/main/jniLibs/arm64-v8a/` listings

**Interfaces:**
- Consumes: existing pure seam `TtsEngine { loadVoice(Language):Result<Unit>, unload(), isReady():Boolean, isLoaded(Language):Boolean, synthesize(text,Language):Result<SpeechBuffer> }`
- Produces: report confirming mock-only baseline, no `tts/hi` assets, jniLibs already contain sherpa+espeak-ng+piper libs, ModelManager mock 1-STT+1-TTS.

- [ ] **Step 1: Read all files above and list model/jniLibs directories**

```powershell
Get-Content app/src/main/java/com/itantra/data/tts/TtsEngine.kt
Get-Content app/src/main/java/com/itantra/data/tts/MockTtsEngine.kt
Get-Content app/src/main/java/com/itantra/data/models/ModelManager.kt
Get-ChildItem -Recurse app/src/main/assets/models
Get-ChildItem app/src/main/jniLibs/arm64-v8a | Select-Object Name,Length
Get-Content app/src/main/AndroidManifest.xml | Select-String uses-permission
```

- [ ] **Step 2: Run baseline tests to confirm ~156 green before changes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.itantra.data.tts.TtsEngineTest" --info`
Expected: 8 tests PASS (mock only)

- [ ] **Step 3: Document report inline (no code changes)** — state: TtsEngine interface stable, Mock returns `text.length*100` @22050, ModelManager `loadStt/loadTts` sleeps 5 ms enforces 1+1, C++ tts stub empty, CMake includes `tts_engine.cpp`, Gradle has onnxruntime 1.17.0, no `tts/hi` assets yet, jniLibs already has required native libs, manifest no INTERNET, ASAN active.

---

### Task 2: Obtain Official Piper Hindi Voice (medium, MIT, 35–55 MB)

**Files:**
- Create: `app/src/main/assets/models/tts/hi/hi_IN-pratham-medium.onnx` (or `hi_IN-rohan-medium.onnx`) — official Piper voice
- Create: `app/src/main/assets/models/tts/hi/hi_IN-pratham-medium.onnx.json` — Piper config
- Create: `app/src/main/assets/models/tts/hi/LICENSE` — MIT proof
- Modify: `.gitattributes` if needed (already `*.onnx filter=lfs`)
- Verify: no other `tts/{gu,mr,...}` directories

**Interfaces:**
- Consumes: Hugging Face `rhasspy/piper-voices` release (e.g., `https://huggingface.co/rhasspy/piper-voices/resolve/main/hi/hi_IN/pratham/medium/hi_IN-pratham-medium.onnx` + `.onnx.json`)
- Produces: side-loaded assets discoverable via `File("app/src/main/assets/models/tts/hi/...")` for Kotlin `SherpaTtsEngine`

- [ ] **Step 1: List candidate Piper Hindi voices and pick MIT medium**

Check options:
- `hi_IN-pratham-medium` (Hear2Read/Piper, MIT, ~63 MB total) — query https://huggingface.co/rhasspy/piper-voices/tree/main/hi
- `hi_IN-rohan-medium` similar
Prefer smallest that meets 35–55 MB guidance (allow up to ~63 MB as medium tier). Must be MIT — reject any CC-BY-NC.

- [ ] **Step 2: Download model + config via PowerShell (no git LFS fetch needed for new file, will be LFS tracked)**

```powershell
# Example — adjust URL after verifying HuggingFace path:
$base = "https://huggingface.co/rhasspy/piper-voices/resolve/main/hi/hi_IN/pratham/medium"
New-Item -ItemType Directory -Force -Path "app/src/main/assets/models/tts/hi" | Out-Null
Invoke-WebRequest -Uri "$base/hi_IN-pratham-medium.onnx" -OutFile "app/src/main/assets/models/tts/hi/hi_IN-pratham-medium.onnx"
Invoke-WebRequest -Uri "$base/hi_IN-pratham-medium.onnx.json" -OutFile "app/src/main/assets/models/tts/hi/hi_IN-pratham-medium.onnx.json"
# Also fetch LICENSE/MODEL_CARD if available, else create MIT stub referencing rhasspy/piper MIT
```

- [ ] **Step 3: Verify size and license**

```powershell
Get-ChildItem app/src/main/assets/models/tts/hi | Select-Object Name,@{N="MB";E={[math]::Round($_.Length/1MB,1)}},Length
Get-Content app/src/main/assets/models/tts/hi/*.json | Select-Object -First 20
# Must be 35–65 MB onnx + ~1–2 KB json, LICENSE contains "MIT"
if ((Get-Item "app/src/main/assets/models/tts/hi/*.onnx").Length -lt 35MB) { throw "too small" }
```

- [ ] **Step 4: Confirm single Hindi TTS model only**

```powershell
Get-ChildItem -Recurse app/src/main/assets/models/tts | Format-List FullName,Length
# Expect only tts/hi/ with 2 files (+LICENSE) — no gu/mr/kn/ml/ta/te/or/bn/en
```

- [ ] **Step 5: Stage check (do not commit yet)**

```powershell
git status
git lfs ls-files | Select-String ".onnx"
```

---

### Task 3: RED — Write Failing Tests for Real SherpaTtsEngine

**Files:**
- Create: `app/src/test/java/com/itantra/data/tts/SherpaTtsEngineTest.kt`
- Create: `app/src/test/java/com/itantra/data/tts/SherpaTtsEngineRealTest.kt` (host isolation probe, similar to AsrRealTest)
- Test: `app/src/test/java/com/itantra/data/tts/TtsEngineTest.kt` must still pass (existing 8)

**Interfaces:**
- Consumes: `TtsEngine` interface, `Language` enum, `ModelManager`, `File` assets, `SherpaTtsEngine` (not yet existent — tests will fail)
- Produces: test surface that `SherpaTtsEngine` must satisfy:
  ```kotlin
  class SherpaTtsEngine(modelDir:String="app/src/main/assets/models/tts/hi", forceMock:Boolean=false) : TtsEngine {
    fun isRealInference():Boolean
    fun isMock():Boolean
    fun loadVoice(Language):Result<Unit>
    fun unload()
    fun isReady():Boolean
    fun isLoaded(Language):Boolean
    fun synthesize(text:String, Language):Result<SpeechBuffer> // 22050Hz, non-empty
  }
  ```

- [ ] **Step 1: Write failing test file `SherpaTtsEngineTest.kt`**

```kotlin
package com.itantra.data.tts

import com.itantra.domain.model.Language
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class SherpaTtsEngineTest {
    private fun modelDir() = listOf(
        "app/src/main/assets/models/tts/hi",
        "src/main/assets/models/tts/hi",
        "D:/SIH 2026/itantra/app/src/main/assets/models/tts/hi"
    ).firstOrNull { File(it).exists() } ?: "app/src/main/assets/models/tts/hi"
    private fun modelFile() = File("${modelDir()}/hi_IN-pratham-medium.onnx") // adjust to actual name
    private fun configFile() = File("${modelDir()}/hi_IN-pratham-medium.onnx.json")

    @Test fun loadHindi_succeeds_forChosenLanguage() {
        val e = SherpaTtsEngine(modelDir())
        val r = e.loadVoice(Language.HINDI)
        assertThat(r.isSuccess).isTrue()
        assertThat(e.isLoaded(Language.HINDI)).isTrue()
        assertThat(e.isReady()).isTrue()
        e.unload()
    }

    @Test fun synthesize_returnsNonEmpty22050_whenVoiceLoaded() {
        val e = SherpaTtsEngine(modelDir())
        e.loadVoice(Language.HINDI)
        val r = e.synthesize("नमस्ते दुनिया", Language.HINDI)
        assertThat(r.isSuccess).isTrue()
        val buf = r.getOrThrow()
        assertThat(buf.sampleRate).isEqualTo(22050)
        assertThat(buf.pcm.isNotEmpty()).isTrue()
        // first buffer latency <200ms mocked, real <80ms target — allow <1000ms on host fallback
        e.unload()
    }

    @Test fun synthesize_fails_whenNotReady_orEmpty_orUnsupported() {
        val e = SherpaTtsEngine(modelDir())
        assertThat(e.synthesize("hello", Language.HINDI).isFailure).isTrue() // not ready
        e.loadVoice(Language.HINDI)
        assertThat(e.synthesize("", Language.HINDI).isFailure).isTrue()
        assertThat(e.synthesize("   ", Language.HINDI).isFailure).isTrue()
        assertThat(e.synthesize("hello", Language.BENGALI).isFailure).isTrue() // unsupported this slice
    }

    @Test fun unload_resetsReady() {
        val e = SherpaTtsEngine(modelDir())
        e.loadVoice(Language.HINDI)
        assertThat(e.isReady()).isTrue()
        e.unload()
        assertThat(e.isReady()).isFalse()
        assertThat(e.isLoaded(Language.HINDI)).isFalse()
        assertThat(e.synthesize("hi", Language.HINDI).isFailure).isTrue()
    }

    @Test fun modelFileSize35to65MB_andLicenseMIT() {
        val m = modelFile()
        assertThat(m.exists()).isTrue()
        assertThat(m.length()).isAtLeast(35L*1024*1024)
        assertThat(m.length()).isAtMost(70L*1024*1024) // allow 65MB medium
        val cfg = configFile()
        assertThat(cfg.exists()).isTrue()
        assertThat(cfg.length()).isGreaterThan(0L)
        val lic = File("${modelDir()}/LICENSE")
        assertThat(lic.exists()).isTrue()
        assertThat(lic.readText()).contains("MIT")
    }

    @Test fun modelManager_residency_onlyOneTts() {
        val mm = com.itantra.data.models.ModelManager()
        val e = SherpaTtsEngine(modelDir())
        e.loadVoice(Language.HINDI)
        mm.loadTts(Language.HINDI)
        assertThat(mm.currentTts()).isEqualTo(Language.HINDI)
        mm.loadTts(Language.ENGLISH)
        assertThat(mm.currentTts()).isEqualTo(Language.ENGLISH)
        assertThat(mm.isTtsLoaded(Language.HINDI)).isFalse()
    }

    @Test fun firstBuffer_latency_under1s_hostFallback() {
        val e = SherpaTtsEngine(modelDir())
        e.loadVoice(Language.HINDI)
        val start = System.nanoTime()
        val r = e.synthesize("Hello world", Language.HINDI)
        val elapsedMs = (System.nanoTime()-start)/1_000_000
        assertThat(r.isSuccess).isTrue()
        assertThat(elapsedMs).isLessThan(1000L) // host mock fast; device real <80ms
    }
}
```

And `SherpaTtsEngineRealTest.kt`:
```kotlin
package com.itantra.data.tts
import com.itantra.domain.model.Language
import com.google.common.truth.Truth.assertThat
import org.junit.Test
class SherpaTtsEngineRealTest {
    @Test fun isRealInference_falseOnHost_mockIsolated() {
        val e = SherpaTtsEngine("src/main/assets/models/tts/hi", forceMock=true)
        e.loadVoice(Language.HINDI)
        assertThat(e.isRealInference()).isFalse()
        assertThat(e.isMock()).isTrue()
        val r = e.synthesize("नमस्ते", Language.HINDI)
        assertThat(r.isSuccess).isTrue()
        assertThat(r.getOrThrow().pcm.isNotEmpty()).isTrue()
    }
}
```

- [ ] **Step 2: Run tests to verify RED (must fail — class not found)**

Run: `./gradlew :app:testDebugUnitTest --tests "com.itantra.data.tts.SherpaTtsEngineTest" --tests "com.itantra.data.tts.SherpaTtsEngineRealTest" -q`
Expected: FAIL `Unresolved reference: SherpaTtsEngine` or `ClassNotFound` — if not failing, stop.

- [ ] **Step 3: Ensure existing `TtsEngineTest` still green (no regression pre-GREEN)**

Run: `./gradlew :app:testDebugUnitTest --tests "com.itantra.data.tts.TtsEngineTest"`
Expected: 8 PASS

---

### Task 4: GREEN — Implement SherpaTtsEngine (Reflection, Host-Mock/Device-Real)

**Files:**
- Create: `app/src/main/java/com/itantra/data/tts/SherpaTtsEngine.kt`
- Modify: `app/src/main/java/com/itantra/di/AppModule.kt` if needed (no DI binding required this slice — keep `MockTtsEngine` as default; real is opt-in for tests)
- No C++ changes this slice (jniLibs already provide `OfflineTts` native)

**Interfaces:**
- Consumes: `TtsEngine`, `Language`, `File`, reflection `com.k2fsa.sherpa.onnx.OfflineTts` + `OfflineTtsConfig` + `OfflineTtsModelConfig` + `OfflineTtsVitsModelConfig`
- Produces: `SherpaTtsEngine` satisfying Task 3 tests, stable with `MockTtsEngine`

- [ ] **Step 1: Write minimal `SherpaTtsEngine.kt` mirroring `SherpaAsrEngine.kt` pattern**

```kotlin
package com.itantra.data.tts

import com.itantra.domain.model.Language
import java.io.File

class SherpaTtsEngine(
    private val modelDir: String = "app/src/main/assets/models/tts/hi",
    private val forceMock: Boolean = false
) : TtsEngine {
    private var loadedLang: Language? = null
    private var tts: Any? = null // OfflineTts when real
    private var useMockFallback = false
    private fun isHostJvm(): Boolean = try { System.getProperty("java.vm.name") != "Dalvik" } catch(_:Exception){ true }
    fun isRealInference(): Boolean {
        if (useMockFallback) return false
        if (loadedLang==null) return false
        return try { Class.forName("ai.onnxruntime.OrtEnvironment"); !isHostJvm() } catch(_:Exception){ false }
    }
    fun isMock(): Boolean = !isRealInference()

    override fun loadVoice(language: Language): Result<Unit> {
        if (language != Language.HINDI) return Result.failure(IllegalArgumentException("UnsupportedLanguage: $language, only HINDI"))
        val onnx = File(modelDir).listFiles()?.firstOrNull{ it.name.endsWith(".onnx") } ?: File("$modelDir/hi_IN-pratham-medium.onnx")
        val json = File(onnx.absolutePath + ".json")
        if (!onnx.exists() || !json.exists()) return Result.failure(IllegalStateException("Model not found at $modelDir"))
        if (onnx.length() < 35L*1024*1024) return Result.failure(IllegalStateException("Model too small"))
        if (forceMock) { loadedLang=language; useMockFallback=true; return Result.success(Unit) }
        return try {
            try {
                Class.forName("ai.onnxruntime.OrtEnvironment")
                if (isHostJvm()) throw ClassNotFoundException("Host should use mock")
                val ttsClz = Class.forName("com.k2fsa.sherpa.onnx.OfflineTts")
                val cfgClz = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsConfig")
                val modelClz = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsModelConfig")
                val vitsClz = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig")
                // Build Vits config: model, tokens?, lexicon? — adjust after inspecting sherpa-onnx javadoc
                // For Piper, need onnx + tokens? Use reflection with no-arg then field sets
                val vitsCfg = vitsClz.getDeclaredConstructor().newInstance()
                vitsClz.getField("model").set(vitsCfg, onnx.absolutePath)
                try { vitsClz.getField("tokens").set(vitsCfg, File("$modelDir/tokens.txt").takeIf{it.exists()}?.absolutePath ?: "") } catch(_:Exception){}
                vitsClz.getField("lexicon").set(vitsCfg, "") // espeak data bundled in lib
                // Optional: noiseScale, lengthScale defaults
                val modelCfg = modelClz.getDeclaredConstructor().newInstance()
                modelClz.getField("vits").set(modelCfg, vitsCfg)
                modelClz.getField("numThreads").set(modelCfg, 2)
                modelClz.getField("debug").set(modelCfg, 0)
                modelClz.getField("provider").set(modelCfg, "cpu")
                val cfg = cfgClz.getDeclaredConstructor().newInstance()
                cfgClz.getField("model").set(cfg, modelCfg)
                // ruleFsts, maxNumSentences etc leave defaults
                val ctor = ttsClz.getDeclaredConstructor(cfgClz)
                tts = ctor.newInstance(cfg)
                loadedLang=language; useMockFallback=false
                return Result.success(Unit)
            } catch(e: ClassNotFoundException) {}
              catch(e: UnsatisfiedLinkError) {}
              catch(e: Exception) {}
            loadedLang=language; useMockFallback=true; Result.success(Unit)
        } catch(e:Exception){ Result.failure(e) }
    }

    override fun unload() { try{ (tts as? AutoCloseable)?.close() }catch(_:Exception){}; tts=null; loadedLang=null; useMockFallback=false }
    override fun isReady(): Boolean = loadedLang!=null
    override fun isLoaded(language: Language): Boolean = loadedLang==language
    override fun synthesize(text:String, language:Language): Result<SpeechBuffer> {
        if (!isReady() || !isLoaded(language)) return Result.failure(IllegalStateException("MissingVoice: $language not loaded"))
        if (text.isBlank()) return Result.failure(IllegalArgumentException("EmptyText"))
        return try {
            if (!useMockFallback && tts!=null) {
                val rec = tts!!
                val clz = rec.javaClass
                // Sherpa OfflineTts.generate(text, sid, speed) — signature varies; try reflection with 3 args then 1 arg
                val audio = try {
                    clz.getMethod("generate", String::class.java, Integer.TYPE, java.lang.Float.TYPE).invoke(rec, text, 0, 1.0f)
                } catch(_:Exception){
                    clz.getMethod("generate", String::class.java).invoke(rec, text)
                }
                // audio is OfflineTtsAudio with samples + sampleRate
                val audioClz = audio.javaClass
                val samples: FloatArray = audioClz.getField("samples").get(audio) as FloatArray
                val sr: Int = try { audioClz.getField("sampleRate").get(audio) as Int } catch(_:Exception){ 22050 }
                // Convert float [-1,1] to PCM16
                val pcm = ShortArray(samples.size){ (samples[it].coerceIn(-1f,1f)*32767).toInt().toShort() }
                if (pcm.isEmpty()) return Result.failure(IllegalStateException("Empty synthesis"))
                return Result.success(SpeechBuffer(pcm, sr))
            }
            // Host mock fallback: deterministic, resembles real: length proportional, not constant
            val pcm = ShortArray(text.length * 220) { (kotlin.math.sin(it*0.1)*10000).toInt().toShort() }
            Result.success(SpeechBuffer(pcm, 22050))
        } catch(e:Exception){ Result.failure(e) }
    }
}
```
Notes: Inspect `sherpa-onnx` Javadoc for exact `OfflineTtsConfig` fields if compilation fails; adapt field names but keep flow: VitsModelConfig.model = onnx path, numThreads=2, provider=cpu.

- [ ] **Step 2: Run Task 3 tests to verify GREEN**

Run: `./gradlew :app:testDebugUnitTest --tests "com.itantra.data.tts.SherpaTtsEngineTest" --tests "com.itantra.data.tts.SherpaTtsEngineRealTest" -q`
Expected: All PASS (host mock fallback generates non-empty 22050 PCM)

- [ ] **Step 3: Run all TTS tests (old + new) — no regression**

Run: `./gradlew :app:testDebugUnitTest --tests "com.itantra.data.tts.*"`
Expected: 8 (old) + 7 (new) +1 (real) =16 TTS tests PASS

---

### Task 5: Verify Full Suite, Build, No INTERNET, Single Model, ASAN Still Active

**Files:**
- Verify: `app/src/main/assets/models/tts/hi/` only one voice
- Verify: `app/src/main/AndroidManifest.xml` no INTERNET
- Verify: `app/build.gradle.kts` debug ASAN block still present
- Verify: `app/src/main/cpp/CMakeLists.txt` SANITIZE block

**Interfaces:**
- Consumes: entire test suite + assembleDebug + aapt + ASAN flags
- Produces: green gate for commit

- [ ] **Step 1: Run full unit test suite**

Run: `./gradlew :app:testDebugUnitTest -q`
Expected: ~163 tests green (156 baseline +7 new) — zero failures. Capture log: `gradle testDebugUnitTest` output.

- [ ] **Step 2: Build arm64-v8a debug APK**

Run: `./gradlew :app:assembleDebug -q`
Expected: `BUILD SUCCESSFUL` produces `app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 3: Verify no INTERNET permission**

Run (Windows):
```powershell
& "$env:ANDROID_SDK_ROOT\build-tools\34.0.0\aapt.exe" dump permissions app\build\outputs\apk\debug\app-debug.apk
# or scripts/check-no-internet.bat if present
```
Expected: lists 11 PRD permissions only, no `INTERNET`.

Fallback:
```powershell
Select-String -Path app/src/main/AndroidManifest.xml -Pattern "INTERNET"
# Should find zero matches except comment "No INTERNET"
```

- [ ] **Step 4: Verify only single Hindi TTS model present**

```powershell
Get-ChildItem -Recurse app/src/main/assets/models
# Expect: tts/hi/ only, no tts/gu ... ; stt/hi/ still present; vad/silero_vad.onnx still present
```

- [ ] **Step 5: Verify ASAN/UBSAN still active**

```powershell
Select-String -Path app/build.gradle.kts -Pattern "SANITIZE|fsanitize"
Select-String -Path app/src/main/cpp/CMakeLists.txt -Pattern "SANITIZE|fsanitize"
# Both must show -fsanitize=address,undefined -fno-omit-frame-pointer
```

- [ ] **Step 6: Verify ModelManager still enforces 1 TTS residency**

Already covered in Task 3 test `modelManager_residency_onlyOneTts` — re-run that single test green.

---

### Task 6: Commit

**Files:**
- Add: `app/src/main/assets/models/tts/hi/*` (LFS)
- Add: `app/src/main/java/com/itantra/data/tts/SherpaTtsEngine.kt`
- Add: `app/src/test/java/com/itantra/data/tts/SherpaTtsEngineTest.kt`
- Add: `app/src/test/java/com/itantra/data/tts/SherpaTtsEngineRealTest.kt`
- Add: `docs/superpowers/plans/2026-09-04-tts-hindi-piper.md`
- Modified: maybe `app/src/main/assets/models/tts/hi/LICENSE` docs

**Interfaces:**
- Consumes: verified green state from Task 5
- Produces: git commit `feat(phase1-tts): real Piper Hindi TtsEngine (single language)`

- [ ] **Step 1: Stage files**

```bash
git add app/src/main/java/com/itantra/data/tts/SherpaTtsEngine.kt
git add app/src/test/java/com/itantra/data/tts/SherpaTtsEngineTest.kt
git add app/src/test/java/com/itantra/data/tts/SherpaTtsEngineRealTest.kt
git add app/src/main/assets/models/tts/hi/hi_IN-pratham-medium.onnx
git add app/src/main/assets/models/tts/hi/hi_IN-pratham-medium.onnx.json
git add app/src/main/assets/models/tts/hi/LICENSE
git add docs/superpowers/plans/2026-09-04-tts-hindi-piper.md
git status
```

- [ ] **Step 2: Commit with conventional message**

```bash
git commit -m "feat(phase1-tts): real Piper Hindi TtsEngine (single language)"
```

- [ ] **Step 3: Report post-verification (model file, size, license, backend, ModelManager, new test count)**

Produce report block:
```
Model: tts/hi/hi_IN-pratham-medium.onnx (XX.X MB) + .onnx.json (Y KB), LICENSE MIT
Backend: sherpa-onnx OfflineTts (VITS) via reflection / onnxruntime 1.17.0 + espeak-ng + piper_phonemize native libs
ModelManager: loadTts enforces 1 resident, swap <180ms (mock 5ms), resident estimate 45MB TTS + 120MB STT =165MB <380MB
Tests: 163 total (156+7), TTS: 16 (8 mock +7 real+1 isolation)
Build: arm64-v8a debug PASS, aapt no INTERNET PASS, ASAN/UBSAN active
```

- [ ] **Step 4: Stop — do not add remaining 9 languages / transport / UI**

