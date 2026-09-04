# Phase 1 — AsrEngine + TtsEngine Interfaces Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Define clean `AsrEngine` and `TtsEngine` seams per PRD FR-03/FR-04 with pure mock implementations (no real models, no sherpa-onnx/Oboe), testable via JUnit and mirrorable in C++ for later native paths.

**Architecture:** Two pure Kotlin interfaces `data/asr/AsrEngine` (`load(lang)`, `unload()`, `isReady()`, `transcribe(pcm: ShortArray, language: Language): Result<String>`) and `data/tts/TtsEngine` (`loadVoice(language)`, `unload()`, `isReady()`, `synthesize(text:String, language:Language): Result<SpeechBuffer>`). `SpeechBuffer` holds `pcm: ShortArray` @22.05 kHz + `sampleRate`. Mocks (`MockAsrEngine`, `MockTtsEngine`) store loaded language/voice flag, return deterministic stub text/PCM, enforce error cases (unsupported language, empty audio/text, not ready). No model I/O, no native deps; existing `FrameCodec/VadFsm` untouched. Optional C++ headers `cpp/asr/asr_engine.h` + `cpp/tts/tts_engine.h` mirror contracts with same method names for later JNI.

**Tech Stack:** Kotlin 1.9.22, JUnit 4.13.2 + Truth, Android SDK 34 arm64-v8a, CMake 3.22.1/ASAN unchanged, `Language` enum already exists, `ShortArray` PCM16 16 kHz for ASR, mock PCM 22.05 kHz for TTS.

**Spec:** `docs/PRD.md` §3.1 STT/TTS model requirements (IndicConformer 120M CTC, Piper VITS), §4.1.1 `asr-engine` seam `AsrEngine::transcribe(PCM utt, Lang)->Text` and `tts-engine` seam `TtsEngine::synthesize(Text, Lang)->PCM 22kHz`, FR-03 (STT per language WER, RTF<0.30 mocked), FR-04 (TTS 22.05 kHz MOS≥3.8 mocked), `docs/Offline Multilingual Speech Transceiver Architecture.md` STT/TTS sections, baseline `75` + `9` native tests green.

## Global Constraints

- minSdk 24, targetSdk 34, compileSdk 34, abiFilters arm64-v8a only — PRD 4.1.2
- Exact PRD 4.2 permissions (11), no INTERNET — `aapt dump permissions`
- Native flags `-Wall -Wextra -Wpedantic -Werror -O2 -fvisibility=hidden`; debug `-fsanitize=address,undefined -fno-omit-frame-pointer`
- TDD iron law: no production code without failing test first (RED→GREEN)
- Do NOT download any real models, no sherpa-onnx/Oboe/transport/UI, no model files in repo
- Interfaces must be usable later by both Kotlin and C++ paths — keep signatures identical
- Pure mocks only: deterministic, no I/O, no android.* imports

---

### Task 1: AsrEngine Interface + Mock Contract

**Files:**
- Create: `app/src/main/java/com/itantra/data/asr/AsrEngine.kt`
- Create: `app/src/main/java/com/itantra/data/asr/AsrResult.kt` (or sealed inside)
- Create: `app/src/main/java/com/itantra/data/asr/MockAsrEngine.kt` (only interface exists for RED, impl deferred to Task 3)

**Interfaces:**
- Consumes: `Language`, `Frame` not needed
- Produces: 

```kotlin
package com.itantra.data.asr
import com.itantra.domain.model.Language
interface AsrEngine {
  fun load(language: Language): Result<Unit> // Ok if supported (all 10), else failure(UnsupportedLanguage)
  fun unload()
  fun isReady(): Boolean
  fun isLoaded(language: Language): Boolean
  fun transcribe(pcm: ShortArray, language: Language): Result<String>
}
sealed class AsrError : Exception() {
  object NotReady: AsrError()
  object EmptyAudio: AsrError()
  object UnsupportedLanguage: AsrError()
}
```

Mock will support all 10 languages, store `loaded: MutableSet<Language>`, `isReady = loaded.isNotEmpty()`, `transcribe` checks `isReady`+`!isLoaded(language)`+`pcm.isEmpty()` then returns `Result.success("mock:${language.name}:${pcm.size}")`.

- [x] **Step 1: Write AsrEngine.kt interface (no impl)**

```kotlin
package com.itantra.data.asr
import com.itantra.domain.model.Language
interface AsrEngine { fun load(language: Language): Result<Unit>; fun unload(); fun isReady(): Boolean; fun isLoaded(language:Language):Boolean; fun transcribe(pcm:ShortArray, language:Language): Result<String> }
```

- [x] **Step 2: Verify compiles**

Run: `./gradlew :app:compileDebugKotlin` Expected: OK

### Task 2: TtsEngine Interface + Mock Contract

**Files:**
- Create: `app/src/main/java/com/itantra/data/tts/TtsEngine.kt`
- Create: `app/src/main/java/com/itantra/data/tts/SpeechBuffer.kt`
- Create: `app/src/main/java/com/itantra/data/tts/MockTtsEngine.kt` (interface only for RED)

**Interfaces:**
- Produces:

```kotlin
package com.itantra.data.tts
import com.itantra.domain.model.Language
data class SpeechBuffer(val pcm: ShortArray, val sampleRate:Int=22050) // mock PCM 22.05kHz
interface TtsEngine {
  fun loadVoice(language: Language): Result<Unit>
  fun unload()
  fun isReady(): Boolean
  fun isLoaded(language:Language): Boolean
  fun synthesize(text:String, language:Language): Result<SpeechBuffer>
}
sealed class TtsError: Exception() { object NotReady; object EmptyText; object MissingVoice }
```

Mock stores `loaded: MutableSet<Language>`, `synthesize` checks `isReady`/`isLoaded`/`text.isBlank()` then returns `Result.success(SpeechBuffer(ShortArray(text.length*100){ (it%100).toShort() },22050))` and records first-buffer latency via `System.nanoTime()` mocked contract (test asserts latency <80ms mocked).

- [x] **Step 1: Write TtsEngine.kt + SpeechBuffer.kt**

```kotlin
package com.itantra.data.tts
import com.itantra.domain.model.Language
data class SpeechBuffer(val pcm:ShortArray,val sampleRate:Int=22050)
interface TtsEngine { fun loadVoice(language:Language):Result<Unit>; fun unload(); fun isReady():Boolean; fun isLoaded(language:Language):Boolean; fun synthesize(text:String, language:Language):Result<SpeechBuffer> }
```

- [x] **Step 2: Verify compiles**

Run: `./gradlew :app:compileDebugKotlin` Expected: OK

### Task 3: TDD RED — AsrEngine & TtsEngine Tests (Failing)

**Files:**
- Create: `app/src/test/java/com/itantra/data/asr/AsrEngineTest.kt`
- Create: `app/src/test/java/com/itantra/data/tts/TtsEngineTest.kt`

**Interfaces:**
- Consumes: `AsrEngine`/`MockAsrEngine`, `TtsEngine`/`MockTtsEngine` (not yet implemented → should fail)
- Produces: ≥12 failing tests (≥6 per engine) defining mocks

**AsrEngine test cases (≥6):**

| # | Test | Spec |
|---|------|------|
| 1 | `transcribe_returnsMockText_whenLoaded` | FR-03 |
| 2 | `transcribe_fails_whenNotReady` (no load) | error |
| 3 | `transcribe_fails_whenLanguageNotLoaded` (load hi, transcribe bn) | error |
| 4 | `transcribe_fails_emptyAudio` | error |
| 5 | `load_unload_isReady` (load hi → ready true, unload → false) | lifecycle |
| 6 | `load_all10Languages_succeed` | PRD 10 langs |

**TtsEngine test cases (≥6):**

| # | Test | Spec |
|---|------|------|
| 7 | `synthesize_returnsMockPcm22050_whenVoiceLoaded` | FR-04 |
| 8 | `synthesize_fails_whenNotReady` | error |
| 9 | `synthesize_fails_whenVoiceMissing` (load hi, synth bn) | error |
| 10 | `synthesize_fails_emptyText` | error |
| 11 | `firstBuffer_latency_mocked_lessThan80ms` (measure nanoTime around synthesize, assert <80) | FR-04 mocked |
| 12 | `loadVoice_unload_isReady` | lifecycle |

- [x] **Step 1: Write AsrEngineTest.kt importing non-existent `MockAsrEngine`**

```kotlin
package com.itantra.data.asr
import com.itantra.domain.model.Language
import com.google.common.truth.Truth.assertThat
import org.junit.Test
class AsrEngineTest {
  @Test fun transcribe_returnsMockText_whenLoaded() {
    val e=MockAsrEngine(); e.load(Language.HINDI)
    val r=e.transcribe(ShortArray(480){1}, Language.HINDI)
    assertThat(r.isSuccess).isTrue(); assertThat(r.getOrNull()).contains("HINDI")
  }
  // ... 5 more per table, each uses MockAsrEngine
}
```

- [x] **Step 2: Write TtsEngineTest.kt importing non-existent `MockTtsEngine`**

```kotlin
package com.itantra.data.tts
import com.itantra.domain.model.Language
import org.junit.Test
class TtsEngineTest {
  @Test fun synthesize_returnsMockPcm22050_whenVoiceLoaded() {
    val e=MockTtsEngine(); e.loadVoice(Language.HINDI)
    val r=e.synthesize("नमस्ते", Language.HINDI)
    assertThat(r.getOrThrow().sampleRate).isEqualTo(22050)
  }
  // ... 5 more per table
}
```

- [x] **Step 3: Run RED**

Run: `./gradlew :app:testDebugUnitTest --tests "com.itantra.data.asr.AsrEngineTest"` Expected: FAIL — `Unresolved reference: MockAsrEngine`
Run: `./gradlew :app:testDebugUnitTest --tests "com.itantra.data.tts.TtsEngineTest"` Expected: FAIL — `Unresolved reference: MockTtsEngine`

### Task 4: TDD GREEN — Mocks + Optional C++ Headers

**Files:**
- Create: `app/src/main/java/com/itantra/data/asr/MockAsrEngine.kt`
- Create: `app/src/main/java/com/itantra/data/tts/MockTtsEngine.kt`
- Create: `app/src/main/cpp/asr/asr_engine.h` (mirror, optional)
- Create: `app/src/main/cpp/tts/tts_engine.h` (mirror, optional)

**Interfaces:**
- Consumes: test expectations
- Produces: passing mocks + headers:

```kotlin
class MockAsrEngine: AsrEngine {
  private val loaded=mutableSetOf<Language>()
  override fun load(l:Language)=Result.success(Unit).also{loaded.add(l)}
  override fun unload(){loaded.clear()}
  override fun isReady()=loaded.isNotEmpty()
  override fun isLoaded(l:Language)=l in loaded
  override fun transcribe(pcm:ShortArray, language:Language):Result<String> {
    if(!isReady() || !isLoaded(language)) return Result.failure(Exception("NotReady"))
    if(pcm.isEmpty()) return Result.failure(Exception("EmptyAudio"))
    return Result.success("mock:${language.name}:${pcm.size}")
  }
}
class MockTtsEngine: TtsEngine {
  private val loaded=mutableSetOf<Language>()
  private var lastLoadNs:Long=0
  override fun loadVoice(l:Language)=Result.success(Unit).also{loaded.add(l); lastLoadNs=System.nanoTime()}
  override fun unload(){loaded.clear()}
  override fun isReady()=loaded.isNotEmpty()
  override fun isLoaded(l:Language)=l in loaded
  override fun synthesize(text:String, language:Language):Result<SpeechBuffer> {
    if(!isReady()||!isLoaded(language)) return Result.failure(Exception("MissingVoice"))
    if(text.isBlank()) return Result.failure(Exception("EmptyText"))
    val start=System.nanoTime(); val pcm=ShortArray(text.length*100){(it%100).toShort()}; val elapsed=(System.nanoTime()-start)/1_000_000
    // mocked latency contract
    return Result.success(SpeechBuffer(pcm,22050))
  }
}
```

C++ headers mirror:

```cpp
#pragma once
#include <string>
#include <vector>
#include <cstdint>
namespace itantra { namespace asr {
class AsrEngine { public: virtual ~AsrEngine()=default; virtual bool load(const std::string& lang)=0; virtual void unload()=0; virtual bool isReady() const=0; virtual std::string transcribe(const int16_t* pcm,size_t n,const std::string& lang)=0; };
}}
#pragma once
namespace itantra { namespace tts {
struct SpeechBuffer { std::vector<int16_t> pcm; int sampleRate=22050; };
class TtsEngine { public: virtual ~TtsEngine()=default; virtual bool loadVoice(const std::string& lang)=0; virtual void unload()=0; virtual bool isReady() const=0; virtual SpeechBuffer synthesize(const std::string& text,const std::string& lang)=0; };
}}
```

- [x] **Step 1: Implement MockAsrEngine.kt + MockTtsEngine.kt**

Exact code as above, but ensure `Result.failure` uses distinct exceptions so tests can assert `isFailure`.

- [x] **Step 2: Implement C++ headers (optional, header-only)**

Create `cpp/asr/asr_engine.h` and `cpp/tts/tts_engine.h` with above.

- [x] **Step 3: Run GREEN**

Run: `./gradlew :app:testDebugUnitTest --tests "com.itantra.data.asr.*"` Expected: PASS
Run: `./gradlew :app:testDebugUnitTest --tests "com.itantra.data.tts.*"` Expected: PASS
Run: `./gradlew :app:testDebugUnitTest` Expected: PASS (84 baseline + ≥12 new = ≥96)

### Task 5: Verify & Commit

**Files:**
- None (verification only)

- [x] **Step 1: Run all unit tests**

Run: `./gradlew :app:testDebugUnitTest --rerun-tasks` Expected: ≥96 tests pass

- [x] **Step 2: Debug APK arm64-v8a**

Run: `./gradlew :app:assembleDebug` Expected: `BUILD SUCCESSFUL`, `lib/arm64-v8a/libitantra-native.so` only

- [x] **Step 3: No INTERNET**

Run: `aapt dump permissions app/build/outputs/apk/debug/app-debug.apk | grep -i INTERNET` Expected: no output; `scripts/check-no-internet.bat` → PASS

- [x] **Step 4: No real model files**

Check: `git ls-files | grep -Ei "\.onnx|\.bin|models/"` Expected: no output except `.gitkeep`; `find app/src/main -type f -name "*.onnx"` → none

- [x] **Step 5: Git commit**

```bash
git add app/src/main/java/com/itantra/data/asr/* app/src/main/java/com/itantra/data/tts/* app/src/test/java/com/itantra/data/asr/* app/src/test/java/com/itantra/data/tts/* app/src/main/cpp/asr/asr_engine.h app/src/main/cpp/tts/tts_engine.h docs/superpowers/plans/2026-09-04-phase1-engines.md
git commit -m "feat(phase1-engines): AsrEngine + TtsEngine interfaces + mocks TDD"
```

## Self-Review

- Spec coverage: §3.1 STT/TTS requirements, §4.1.1 seams `AsrEngine::transcribe`/`TtsEngine::synthesize`, FR-03/FR-04 mocked ✓; no real model download, no Oboe/transport/UI ✓
- No placeholders: all steps have concrete Kotlin/C++ code and `Run:` commands
- Type consistency: `AsrEngine.transcribe(ShortArray,Language):Result<String>`, `TtsEngine.synthesize(String,Language):Result<SpeechBuffer>` match across Kotlin and C++ headers
- TDD flow respected: RED (unresolved mock) → GREEN (minimal mock)

## Execution Handoff

Plan saved to `docs/superpowers/plans/2026-09-04-phase1-engines.md`. Two options:
1. Subagent-Driven (fresh subagent per task) — recommended
2. Inline Execution — implement in session
