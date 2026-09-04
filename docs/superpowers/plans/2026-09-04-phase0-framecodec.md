# Phase 0 + FrameCodec Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver PRD Phase 0 skeleton (arm64-v8a debug, Hilt+Compose BOM+NDK/CMake+ASAN/UBSAN+no-INTERNET gate) and a pure FrameCodec (12-byte framing + CRC-16-CCITT per Appendix B, FR-06) under strict TDD RED→GREEN.

**Architecture:** Single `app` module with `data/transport/FrameCodec` + `Crc16` as pure Kotlin test surface (zero Android deps), 10-byte header (magic 0x49 0x54 + ver/mode + flags + src/dst lang + BE seq/len) + UTF-8 payload (1–2048, 0 allowed for PTT control) + BE CRC-16-CCITT (poly 0x1021, init 0xFFFF) over header+payload. NDK stub `libitantra-native` with sanitizer flags validates CMake toolchain for later sherpa-onnx/Oboe. Manifest lists exact PRD 4.2 permissions (no INTERNET); CI gate runs `aapt dump permissions` check.

**Tech Stack:** Kotlin 1.9.22, AGP 8.5.2, Gradle 8.7, Compose BOM 2024.06.00, Hilt 2.51.1, JUnit 4.13.2 + Truth, AndroidX Test, CMake 3.22+, NDK 30.0.16138531, arm64-v8a only.

**Spec:** `PRD.md` (4.1.2 project structure, 4.1.3 concurrency (stub), Appendix B framing, FR-06, Phase 0 + Phase 1 FrameCodec slice) and `Offline Multilingual Speech Transceiver Architecture.md` §4 framing/PTT (technical evidence).

**Status (2026-09-04):** ✅ **COMPLETED** — Commit `089f53e` (`feat(phase0): skeleton + FrameCodec TDD per PRD Appendix B`). Verified: 29 unit tests green, `app-debug.apk` arm64-v8a only (9.1 MB), `libitantra-native.so` 636 KB with ASAN/UBSAN, `aapt` confirms no `INTERNET`. See Execution Log at bottom.

## Global Constraints

- minSdk 24, targetSdk 34, compileSdk 34, NDK r26+ (30.0.16138531 present), CMake 3.22+, `abiFilters "arm64-v8a"` only — PRD 4.1.2
- Permissions exactly `RECORD_AUDIO, ACCESS_WIFI_STATE, CHANGE_WIFI_STATE, ACCESS_FINE_LOCATION(maxSdk 30), NEARBY_WIFI_DEVICES(neverForLocation), BLUETOOTH(maxSdk30), BLUETOOTH_ADMIN(maxSdk30), BLUETOOTH_CONNECT, BLUETOOTH_SCAN(neverForLocation), FOREGROUND_SERVICE, WAKE_LOCK` — NO INTERNET — PRD 4.2
- Native flags `-Wall -Wextra -Wpedantic -Werror -O2 -fvisibility=hidden`; debug sanitizers `AddressSanitizer + UndefinedBehaviorSanitizer` with `-fsanitize=address,undefined -fno-omit-frame-pointer` — PRD 4.1.2
- Open-source only MIT/Apache-2.0/CC-BY-4.0; MMS-TTS banned from release — PRD 1.3 K6, 3.1
- TDD iron law: no production code without failing test first (RED→GREEN→REFACTOR) — PRD 5.4
- Zero INTERNET permission verified via `aapt dump permissions` in CI — PRD 4.3 K6
- Language enum 0x01 hi, 0x02 gu, 0x03 mr, 0x04 kn, 0x05 ml, 0x06 ta, 0x07 te, 0x08 or, 0x09 bn, 0x0A en — Appendix B
- Frame = magic 0x49 0x54 | ver(4b low) + mode(4b high) | flags bit7 ALERT bit6 STREAM bit5 PTT | src | dst | seq BE uint16 | len BE uint16 | payload UTF-8 0..2048 (1..2048 for text) | CRC-16-CCITT BE over header+payload (init 0xFFFF poly 0x1021) — Appendix B
- Pure codec — no Android/transport deps; interface is test surface for later D2D — PRD 4.1.1

---

### Task 1: Repository & Gradle Skeleton — ✅ Done

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts` (root)
- Create: `gradle.properties`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `gradle/wrapper/gradle-wrapper.jar` (via wrapper download)
- Create: `gradlew` / `gradlew.bat`
- Modify: `.gitignore` (add `/.gradle`, `/build`, `/app/build`, `/.idea` if not present)

**Interfaces:**
- Consumes: Android SDK 34, JDK 17, NDK 30.0.16138531, CMake 4.1.2
- Produces: Executable `./gradlew` that can resolve `com.android.application` 8.5.2 + Kotlin 1.9.22 from Maven Central

- [x] **Step 1: Create settings.gradle.kts**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "iTantra"
include(":app")
```

- [x] **Step 2: Create root build.gradle.kts**

```kotlin
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("com.google.dagger.hilt.android") version "2.51.1" apply false
    id("org.jetbrains.kotlin.kapt") version "1.9.22" apply false
}
```

- [x] **Step 3: Create gradle.properties**

```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRResources=true
```

- [x] **Step 4: Generate wrapper (Gradle 8.7)**

Run: `gradle wrapper --gradle-version 8.7` (or download distribution manually) and verify `gradle/wrapper/gradle-wrapper.properties` contains `distributionUrl=https\://services.gradle.org/distributions/gradle-8.7-bin.zip`
→ *Executed:* Downloaded `gradle-wrapper.jar` (43,453 B) from `https://github.com/gradle/gradle/raw/v8.7.0/gradle/wrapper/gradle-wrapper.jar`; `gradle-wrapper.properties` points to `gradle-8.7-bin.zip`.

- [x] **Step 5: Verify wrapper**

Run: `./gradlew --version` (or `gradlew.bat --version` on Windows) Expected: Gradle 8.7, JVM 17
→ *Verified via `testDebugUnitTest` bootstrap:* Gradle 8.7 downloaded dependencies and executed tasks successfully.

### Task 2: App Module, Manifest, Hilt, Compose BOM, Build Config — ✅ Done

**Files:**
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/itantra/ITantraApp.kt`
- Create: `app/src/main/java/com/itantra/MainActivity.kt` (minimal stub)
- Create: `app/proguard-rules.pro`

**Interfaces:**
- Consumes: root gradle plugins
- Produces: `app` module that compiles with `compileSdk 34`, `minSdk 24`, `targetSdk 34`, `abiFilters arm64-v8a`, Compose BOM, Hilt, and exact permissions (no INTERNET)

- [x] **Step 1: Create app/build.gradle.kts**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.kapt")
}
android {
    namespace = "com.itantra"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.itantra"
        minSdk = 24; targetSdk = 34; versionCode = 1; versionName = "0.1.0-phase0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
            externalNativeBuild { cmake { cppFlags += "-fsanitize=address,undefined -fno-omit-frame-pointer"; arguments += listOf("-DSANITIZE=ON") } }
        }
        release { isMinifyEnabled = false }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    packaging { jniLibs { useLegacyPackaging = true } }
}
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom); implementation("androidx.compose.ui:ui"); implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.12.0"); implementation("androidx.activity:activity-compose:1.8.2")
    implementation("com.google.dagger:hilt-android:2.51.1"); kapt("com.google.dagger:hilt-compiler:2.51.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")
    testImplementation("junit:junit:4.13.2"); testImplementation("com.google.truth:truth:1.4.4")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
```

- [x] **Step 2: Create AndroidManifest.xml with exact PRD 4.2 permissions (no INTERNET)**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.RECORD_AUDIO"/>
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>
    <uses-permission android:name="android.permission.CHANGE_WIFI_STATE"/>
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30"/>
    <uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" android:usesPermissionFlags="neverForLocation"/>
    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30"/>
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30"/>
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
    <uses-permission android:name="android.permission.WAKE_LOCK"/>
    <application android:name=".ITantraApp" android:label="iTantra" ...>
        <activity android:name=".MainActivity" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity>
    </application>
</manifest>
```

- [x] **Step 3: Create ITantraApp.kt**

```kotlin
package com.itantra
import android.app.Application; import dagger.hilt.android.HiltAndroidApp
@HiltAndroidApp class ITantraApp : Application()
```

- [x] **Step 4: Create minimal MainActivity.kt**

```kotlin
package com.itantra
import android.os.Bundle; import androidx.activity.ComponentActivity; import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
@AndroidEntryPoint class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { } }
}
```

- [x] **Step 5: Run `gradlew :app:assembleDebug --dry-run` to verify DSL parses (no full build yet)**
→ *Verified via full `assembleDebug` in Task 7 (BUILD SUCCESSFUL).*

### Task 3: NDK/CMake Stub + Sanitizers + No-INTERNET CI Check — ✅ Done

**Files:**
- Create: `app/src/main/cpp/CMakeLists.txt`
- Create: `app/src/main/cpp/native-lib.cpp` (dummy JNI)
- Create: `scripts/check-no-internet.sh` (or .bat)
- Create: `.github/workflows/ci.yml` (or `scripts/ci-check.bat` local)

**Interfaces:**
- Consumes: NDK 30.0.16138531, CMake 4.1.2, app module externalNativeBuild
- Produces: `libitantra-native.so` for arm64-v8a with `-Wall -Wextra -Wpedantic -Werror -fvisibility=hidden` + ASAN/UBSAN in debug; CI step `aapt dump permissions app/build/outputs/apk/debug/app-debug.apk | ! grep INTERNET`

- [x] **Step 1: Write CMakeLists.txt**

```cmake
cmake_minimum_required(VERSION 3.22)
project(itantra-native LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
add_library(itantra-native SHARED native-lib.cpp)
target_compile_options(itantra-native PRIVATE -Wall -Wextra -Wpedantic -Werror -O2 -fvisibility=hidden)
if(SANITIZE)
  target_compile_options(itantra-native PRIVATE -fsanitize=address,undefined -fno-omit-frame-pointer)
  target_link_options(itantra-native PRIVATE -fsanitize=address,undefined)
endif()
find_library(log-lib log)
target_link_libraries(itantra-native PRIVATE ${log-lib})
```

- [x] **Step 2: Write native-lib.cpp**

```cpp
#include <jni.h>
extern "C" JNIEXPORT jstring JNICALL Java_com_itantra_NativeLib_hello(JNIEnv* env, jclass) {
  return env->NewStringUTF("it-01");
}
```

- [x] **Step 3: Create scripts/check-no-internet.bat**

```bat
@echo off
aapt dump permissions app\build\outputs\apk\debug\app-debug.apk | findstr /I INTERNET >nul && (echo FAIL: INTERNET permission found & exit /b 1) || (echo PASS: no INTERNET permission)
```

- [x] **Step 4: Verify CMake configures**

Run: `./gradlew :app:assembleDebug -x test` expected: `libitantra-native.so` under `app/build/intermediates/cmake/debug/obj/arm64-v8a/`
→ *Verified:* `app/build/intermediates/cxx/Debug/1oo174ud/obj/arm64-v8a/libitantra-native.so` (636 KB) and `aapt list` shows `lib/arm64-v8a/libitantra-native.so` only; `compile_commands.json` confirms `-Wall -Wextra -Wpedantic -Werror -O2 -fvisibility=hidden -fsanitize=address,undefined -fno-omit-frame-pointer`.

### Task 4: Domain Model (Language, TransmitMode, Frame) — ✅ Done

**Files:**
- Create: `app/src/main/java/com/itantra/domain/model/Language.kt`
- Create: `app/src/main/java/com/itantra/domain/model/TransmitMode.kt`
- Create: `app/src/main/java/com/itantra/domain/model/Frame.kt`

**Interfaces:**
- Consumes: none
- Produces: `enum class Language(val code: Byte)` 0x01..0x0A, `enum class TransmitMode(val bit: Int)` HALF_DUPLEX 0 / DUPLEX 1, `data class Frame(val mode, val isAlert, val isStream, val pttPressed, val srcLang, val dstLang, val seqId, val payloadText, val seqRaw Int 0..65535)`

- [x] **Step 1: Write Language.kt**

```kotlin
package com.itantra.domain.model
enum class Language(val code: Byte) {
    HINDI(0x01), GUJARATI(0x02), MARATHI(0x03), KANNADA(0x04), MALAYALAM(0x05),
    TAMIL(0x06), TELUGU(0x07), ODIA(0x08), BENGALI(0x09), ENGLISH(0x0A);
    companion object { fun fromCode(c: Byte) = entries.find { it.code==c } }
}
```

- [x] **Step 2: Write TransmitMode.kt**

```kotlin
package com.itantra.domain.model
enum class TransmitMode(val bit: Int){ HALF_DUPLEX(0), DUPLEX(1); companion object{ fun fromBit(b:Int)=if(b==0) HALF_DUPLEX else DUPLEX } }
```

- [x] **Step 3: Write Frame.kt**

```kotlin
package com.itantra.domain.model
data class Frame(
    val mode: TransmitMode, val isAlert:Boolean, val isStream:Boolean, val pttPressed:Boolean,
    val srcLang: Language, val dstLang: Language, val seqId:Int, val payloadText:String
) { init{ require(seqId in 0..65535) } }
```

### Task 5: TDD RED — FrameCodec Tests (Failing) — ✅ Done

**Files:**
- Create: `app/src/test/java/com/itantra/data/transport/FrameCodecTest.kt`
- Create: `app/src/test/java/com/itantra/data/transport/Crc16Test.kt` (optional small)

**Interfaces:**
- Consumes: Language, TransmitMode, Frame (from Task 4)
- Produces: Failing tests that define `FrameCodec.encode(Frame): ByteArray` and `FrameCodec.decode(ByteArray): Frame` + `Crc16.compute`

Test cases (each a separate `fun` with explicit name):
1. `encode_setsMagicBytes_0x49_0x54`
2. `encode_encodesVersionLowNibble_0x01_andModeHighNibble`
3. `encode_encodesFlags_alert_stream_ptt_bits7_6_5`
4. `decode_rejectsBadMagic`
5. `encode_allLanguages_0x01_to_0x0A_roundTrip`
6. `decode_rejectsUnknownLangCode`
7. `seqId_bigEndian_roundTrip` (0, 1, 0x0102, 0xFFFF)
8. `payloadLen_bigEndian_matchesUtf8ByteCount` (hello=5, hindi UTF-8 variable)
9. `crc_roundTrip_validFrame_decodes` (known vector)
10. `decode_rejectsCrcMismatch` (flip payload byte)
11. `payloadLengthBounds_0_allowed_forControl_1_to_2048_valid_2049_throws`
12. `emptyOrTruncatedFrame_throws`
13. `flags_reservedBits_zeroed`
14. `decode_extraTrailingBytes_throws` and `utf8_payload_withMultibyte_roundTrip` (e.g., "नमस्ते")

- [x] **Step 1: Write FrameCodecTest.kt with 14+ tests importing non-existent `com.itantra.data.transport.FrameCodec`**

```kotlin
@Test fun encode_setsMagicBytes() {
  val f = Frame(TransmitMode.HALF_DUPLEX,false,false,false,Language.HINDI,Language.ENGLISH,0,"hi")
  val b = FrameCodec.encode(f)
  assertThat(b[0]).isEqualTo(0x49.toByte()); assertThat(b[1]).isEqualTo(0x54.toByte())
}
@Test(expected=FrameCodecException::class) fun decode_rejectsBadMagic(){ FrameCodec.decode(byteArrayOf(0x00,0x00,0,0,1,10,0,0,0,1,104.toByte(),0,0)) }
... // all above — actual file has 29 tests
```

- [x] **Step 2: Run RED**

Run: `./gradlew :app:testDebugUnitTest --tests "com.itantra.data.transport.FrameCodecTest"` Expected: FAIL — `Unresolved reference: FrameCodec`
→ *Observed:* `Unresolved reference: FrameCodec` in 4s (pre-implementation), confirmed TDD iron law.

### Task 6: TDD GREEN — Implement Crc16 + FrameCodec Minimal — ✅ Done

**Files:**
- Create: `app/src/main/java/com/itantra/data/transport/Crc16.kt`
- Create: `app/src/main/java/com/itantra/data/transport/FrameCodec.kt`
- Create: `app/src/main/java/com/itantra/data/transport/FrameCodecException.kt`

**Interfaces:**
- Consumes: FrameCodecTest expectations
- Produces: Passing implementation

- [x] **Step 1: Implement Crc16.kt (CCITT-FALSE)**

```kotlin
object Crc16 {
  fun compute(data: ByteArray, offset:Int=0, len:Int=data.size-offset): Int {
    var crc=0xFFFF; for(i in offset until offset+len){ crc = crc xor ((data[i].toInt() and 0xFF) shl 8); repeat(8){ crc = if(crc and 0x8000 !=0) (crc shl 1) xor 0x1021 else crc shl 1; crc=crc and 0xFFFF } }; return crc
  }
}
```
→ *Actual:* `app/src/main/java/com/itantra/data/transport/Crc16.kt:1` — `POLY 0x1021`, `INIT 0xFFFF`, loop masked `and 0xFFFF`, passes known vector `123456789→0x29B1`.

- [x] **Step 2: Implement FrameCodec.kt**

Encode: validate src/dst not null, payload bytes `text.toByteArray(UTF_8)` length 0..2048 (throw if >2048), header 10 bytes: magic, ver/mode `(mode.bit shl 4) or 0x01`, flags `(alert?0x80:0)|(stream?0x40:0)|(ptt?0x20:0)`, src, dst, seq BE, len BE; compute CRC over header+payload; return header+payload+crc BE. Decode: check min 12 bytes, magic, version low nibble ==0x01, mode bit 0/1, lang codes 0x01..0x0A, len matches remaining minus 2, CRC matches; throw FrameCodecException otherwise.
→ *Actual:* `app/src/main/java/com/itantra/data/transport/FrameCodec.kt:1` — `HEADER_SIZE 10`, `CRC_SIZE 2`, `MAX_PAYLOAD 2048`, `encode` validates payload, `decode` checks magic/version/mode/lang/len/CRC and throws `FrameCodecException`.

- [x] **Step 3: Run GREEN**

Run: `./gradlew :app:testDebugUnitTest` Expected: PASS, `BUILD SUCCESSFUL`, 14+ tests green
→ *Observed:* `:app:testDebugUnitTest` `BUILD SUCCESSFUL` in 2–15s; `TEST-com.itantra.data.transport.FrameCodecTest.xml` → `tests="29" failures="0" errors="0"` (all green).

### Task 7: Verify — Build, Permissions, Structure — ✅ Done

**Files:**
- None (verification only) but ensure `app/src/main/cpp/audio/`, `vad/`, `asr/`, `tts/` stub dirs exist per PRD 4.1.2 (can be empty with `.gitkeep`)

- [x] **Step 1: Run all unit tests**

Run: `./gradlew :app:testDebugUnitTest` Expected: all pass, no warnings
→ *Result:* 29 tests pass, 0 failures.

- [x] **Step 2: Debug APK for arm64-v8a**

Run: `./gradlew :app:assembleDebug` Expected: `app/build/outputs/apk/debug/app-debug.apk` exists; `unzip -l` shows `lib/arm64-v8a/libitantra-native.so` and no `lib/armeabi-v7a`
→ *Result:* `BUILD SUCCESSFUL` in 47s (first build installed `CMake 3.22.1`); `app/build/outputs/apk/debug/app-debug.apk` 9,158,508 B; `aapt list` → `lib/arm64-v8a/libitantra-native.so` only.

- [x] **Step 3: No INTERNET permission**

Run: `aapt dump permissions app/build/outputs/apk/debug/app-debug.apk` (or `apkanalyzer manifest permissions`) Expected: no `android.permission.INTERNET`
→ *Result:* `aapt dump permissions` lists 11 PRD permissions + `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, no `INTERNET`; `scripts/check-no-internet.bat` → `PASS: no INTERNET permission`.

- [x] **Step 4: Structure audit**

Check tree matches PRD 4.1.2: `app/src/main/cpp/CMakeLists.txt`, `app/src/main/java/com/itantra/di/`, `data/local`, `data/models`, `data/transport`, `domain/model`, `domain/usecase`, `presentation/` stubs exist; `src/test` and `src/androidTest` present
→ *Result:* All paths exist; `.so` 636 KB in `cxx/Debug/.../obj/arm64-v8a/`; `compile_commands.json` confirms `-Wall -Wextra -Wpedantic -Werror -O2 -fvisibility=hidden -fsanitize=address,undefined`.

- [x] **Step 5: Git commit**

```bash
git add docs/superpowers/plans/2026-09-04-phase0-framecodec.md app/ gradle/ settings.gradle.kts build.gradle.kts gradle.properties scripts/
git commit -m "feat(phase0): skeleton + FrameCodec TDD per PRD Appendix B"
```
→ *Executed:* `089f53e feat(phase0): skeleton + FrameCodec TDD per PRD Appendix B` (45 files, +1503 lines).

## Self-Review

- Spec coverage: PRD 4.1.2 structure ✓, 4.1.3 concurrency stubbed (no real threading yet, by design — transport threads deferred), Appendix B 10-byte header + CRC ✓, FR-06 1–2048 bounds ✓, Phase 0 CI + ASAN/UBSAN + no-INTERNET ✓, Phase 1 FrameCodec TDD ✓. Gaps: VAD/ASR/TTS, Oboe, transport sockets intentionally deferred per user instruction.
- Placeholders: none — all steps have concrete code.
- Type consistency: `Frame(mode:TransmitMode,... seqId:Int, payloadText:String)` used consistently in encode/decode signatures.

---

## Execution Log (2026-09-04)

**Commit:** `089f53e` on `main` — 45 files changed, 1503 insertions. Preceded by `be5ddd0 development setup done!`.

**Test evidence:**
- `gradlew :app:testDebugUnitTest --tests "com.itantra.data.transport.FrameCodecTest"` → 29 tests, 0 failures, 0 errors (`app/build/test-results/testDebugUnitTest/TEST-com.itantra.data.transport.FrameCodecTest.xml:2`).
- Reported explicitly as RED then GREEN per TDD iron law.

**Build evidence:**
- `gradlew :app:assembleDebug` → `BUILD SUCCESSFUL` (47s fresh, 2s incremental). APK `app/build/outputs/apk/debug/app-debug.apk` 9,158,508 B, `lib/arm64-v8a/libitantra-native.so` only.
- Native toolchain: AGP selected `NDK 26.1.10909125` (from SDK) vs PRD spec `30.0.16138531` present on machine; effective compiler is `clang++.exe --target=aarch64-none-linux-android24 ... -std=c++17` with flags `-Wall -Wextra -Wpedantic -Werror -O2 -fvisibility=hidden -g -O1 -fsanitize=address,undefined -fno-omit-frame-pointer` (from `app/.cxx/Debug/.../compile_commands.json:1`). CMake `3.22.1` was auto-installed via SDK license check, matching `3.22+` requirement despite environment reporting `4.1.2`.
- Permissions: `aapt dump permissions` → 11 PRD permissions only; `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` auto-added by AGP (acceptable, not `INTERNET`).

**Deviations & follow-ups:**
- NDK: Spec says `30.0.16138531` but build used `26.1.10909125` (latest stable selected by AGP). Next phases should pin `ndkVersion "26.1.10909125"` or install 30.x if strict r30 needed. No functional impact for Phase 0.
- CMake: Environment `4.1.2` vs build `3.22.1` — build correctly enforced `3.22.1` as declared in `app/build.gradle.kts:externalNativeBuild.cmake.version`. Keep pinned.
- Header size naming: PRD calls it "12-byte header" but implementation follows Appendix B verbatim (10-byte header + 2-byte CRC = 12-byte minimal frame when `payload_len=0`). Tests cover `0` (control) and `1..2048` (text) per FR-06.
- No additional runtime deps introduced; MMS-TTS absent, licenses remain MIT/Apache-2.0/CC-BY-4.0.
