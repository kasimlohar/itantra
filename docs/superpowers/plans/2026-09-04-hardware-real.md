# Hardware Real — Sherpa-ONNX, Oboe, Wi-Fi Direct, Bluetooth, STREAM_ALARM Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire real Android hardware runtimes (sherpa-onnx Dalvik, Oboe AAudio, WifiP2pManager, BluetoothAdapter, AudioManager STREAM_ALARM) while keeping host-JVM tests 100% green via `isHostJvm()`/`__has_include` fallbacks.

**Architecture:** Keep pure seams host-runnable: `Sherpa*` already has `isHostJvm()` → `useMockFallback` + reflection `OfflineRecognizer/OfflineTts` (add JitPack dep so `Class.forName` succeeds on Dalvik). `AudioCapture` already has `#if defined(__ANDROID__) && __has_include(<oboe/Oboe.h>)` guard — enable `CMake FetchContent oboe` for `ANDROID` to provide header and link `oboe::oboe` to `itantra-native`. `WifiDirectTransport`/`BluetoothTransport` keep `ServerSocket` host fallback but add real `WifiP2pManager`/`BluetoothAdapter` reflection branches on Android. `AlertAudioManager` gets real `AndroidAlertAudioManager : AlertAudioManager` using `AudioManager/Vibrator`, plus `Hilt` `TransportModule`/`AudioModule` to inject real vs stub. All host tests continue to use `forceMock` or `__ANDROID__` false.

**Tech Stack:** Kotlin 1.9, `com.github.k2fsa:sherpa-onnx:1.10.1` via `jitpack.io`, `onnxruntime-android:1.17.0`, `Oboe 1.8.1`, `WifiP2pManager`, `BluetoothAdapter` SPP UUID `00001101-...`, `AudioManager.STREAM_ALARM` `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`, `Vibrator`, Hilt 2.51.1, `arm64-v8a` only.

**Spec:** `docs/PRD.md:3.1` `IndicConformer 120M 80-bin`, `docs/PRD.md:4.1.1` `asr-engine/tts-engine`, `docs/PRD.md:4.1.2` NDK r26, `docs/PRD.md:2.2 US-04/FR-07` Wi-Fi/BT, `docs/PRD.md:2.2 US-05` `STREAM_ALARM`, `docs/PRD_COMPLIANCE_AUDIT.md` gaps 1-4

## Global Constraints

- `minSdk 24`, `targetSdk 34`, `NDK r26`, `CMake 3.22+`, `arm64-v8a` only `abiFilters += "arm64-v8a"` — never add other ABIs.
- No `INTERNET` permission ever — 11 PRD perms only (`app/src/main/AndroidManifest.xml:4`).
- Open-source only MIT/Apache-2.0/CC-BY-4.0; no CC-BY-NC; `*.onnx *.so filter=lfs`.
- Pure seams host-runnable — `isHostJvm()`/`__has_include` fallbacks keep `testDebugUnitTest` (185) + `ctest` (35) green on Windows.
- TDD iron law — failing test first (for new `Android*` classes, test via reflection that file contains `isHostJvm` branch).
- ASAN `address,undefined` active in `debug`.

---

### Task 1: Real Sherpa-ONNX Runtime Dependency

**Files:**
- Modify: `app/build.gradle.kts:76` add `jitpack.io` repo + `sherpa-onnx` dep
- Modify: `settings.gradle.kts` (or `build.gradle.kts` root) ensure `jitpack.io` maven
- Verify: `app/src/main/java/com/itantra/data/asr/SherpaAsrEngine.kt:11` already has real `OfflineRecognizer` reflection `numThreads=2`, `SherpaTtsEngine.kt:12` real `OfflineTts` — no code change, just dep so `Class.forName` succeeds on Dalvik

**Interfaces:**
- Consumes: `jitpack.io` maven
- Produces: `Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer")` succeeds on device, `isRealInference` true on Dalvik, mock on host via `isHostJvm()`.

- [ ] **Step 1: Inspect current deps**

```powershell
Get-Content app/build.gradle.kts | Select-String "jitpack|sherpa-onnx|onnxruntime"
Get-Content settings.gradle.kts | Select-String "jitpack|maven"
Get-Content app/src/main/java/com/itantra/data/asr/SherpaAsrEngine.kt | Select-String "isHostJvm|OfflineRecognizer"
```

- [ ] **Step 2: Add JitPack + sherpa-onnx dep (keep host mock)**

```kotlin
// app/build.gradle.kts top
// already has mavenCentral, add:
// In settings.gradle.kts dependencyResolutionManagement { repositories { google(); mavenCentral(); maven { url = uri("https://jitpack.io") } } }
// In app/build.gradle.kts dependencies:
implementation("com.github.k2fsa:sherpa-onnx:1.10.1")
```

If `settings.gradle.kts` not present, add to `app/build.gradle.kts` `repositories { maven { url = uri("https://jitpack.io") } }`.

Keep `testImplementation("com.microsoft.onnxruntime:onnxruntime:1.17.0")` for host, real uses `onnxruntime-android`.

- [ ] **Step 3: Verify host tests still green (mock fallback)**

Run: `./gradlew :app:testDebugUnitTest --tests "com.itantra.data.asr.SherpaAsrEngineTest" --tests "com.itantra.data.tts.SherpaTtsEngineTest" -q` — expect 7+8 PASS (host `isHostJvm true` → `useMockFallback`).

- [ ] **Step 4: Verify no INTERNET added**

Run: `Select-String -Path app/build.gradle.kts -Pattern "INTERNET"` — expect 0.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts settings.gradle.kts
git commit -m "feat(hardware): add sherpa-onnx 1.10.1 via JitPack (host mock, device real)"
```

---

### Task 2: Real Oboe 1.8.1 AAudio on Android

**Files:**
- Modify: `app/src/main/cpp/CMakeLists.txt:11` enable Oboe for ANDROID
- Modify: `app/src/main/cpp/audio/audio_capture.h:6` already has `#if defined(__ANDROID__) && __has_include(<oboe/Oboe.h>)` — no change
- Modify: `app/src/main/cpp/audio/audio_capture.cpp:1` already has `#if defined(__ANDROID__) && __has_include` guard — no change (already real builder code)
- Test: `app/src/main/cpp/tests/audio_capture_test.cpp:75` `OboeGuardExists` already checks `__ANDROID__` + `oboe/Oboe.h` + `__has_include`

**Interfaces:**
- Consumes: `oboe::AudioStreamBuilder` `Direction::Input 16000/1/I16 Exclusive/LowLatency 480` (`audio_capture.cpp:28`)
- Produces: `itantra-native` links `oboe::oboe` on Android, host `ctest` 35 still PASS via stub.

- [ ] **Step 1: Enable CMake FetchContent for ANDROID**

```cmake
if(ANDROID)
  include(FetchContent)
  FetchContent_Declare(oboe URL https://github.com/google/oboe/archive/refs/tags/1.8.1.zip DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
  FetchContent_MakeAvailable(oboe)
endif()
...
find_library(log-lib log)
if(log-lib) target_link_libraries(itantra-native PRIVATE ${log-lib}) endif()
if(TARGET oboe::oboe)
  target_link_libraries(itantra-native PRIVATE oboe::oboe)
endif()
if(TARGET oboe::oboe)
  target_link_libraries(audio_capture_test PRIVATE oboe::oboe)
  ...
  target_link_libraries(mel_features_test PRIVATE oboe::oboe)
endif()
```

Currently `CMakeLists.txt:11` is commented `# Oboe 1.8.1 — Fetch deferred...` with `# if(ANDROID)` — uncomment to real `if(ANDROID)` as above. Keep `__has_include` guard so host without Oboe still builds via stub.

- [ ] **Step 2: Run host ctest still PASS (stub)**

Run: `Remove-Item -Recurse -Force build; cmake -B build -S app/src/main/cpp -DBUILD_TESTING=ON -DSANITIZE=OFF; cmake --build build --target audio_capture_test; ./build/audio_capture_test.exe` — expect 6/6 PASS (`OboeGuardExists` now checks `__has_include`).

- [ ] **Step 3: Run Android assemble (offline stub still builds)**

Run: `./gradlew :app:assembleDebug -q` — expect `BUILD SUCCESSFUL` even without network (Oboe fetch will attempt download but `__has_include` fallback keeps build green offline; if network available, it will fetch and link).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/cpp/CMakeLists.txt
git commit -m "feat(hardware): enable Oboe 1.8.1 FetchContent for ANDROID (host stub via __has_include)"
```

---

### Task 3: Real WifiP2pManager + BluetoothAdapter (Host Fallback)

**Files:**
- Modify: `app/src/main/java/com/itantra/data/transport/WifiDirectTransport.kt:8` add real `WifiP2pManager` reflection branch
- Modify: `app/src/main/java/com/itantra/data/transport/BluetoothTransport.kt:9` add real `BluetoothAdapter` reflection (already has `isAndroid()` reflection but make `startServer`/`connectTo` actually call `listenUsingRfcommWithServiceRecord`/`createRfcommSocketToServiceRecord` on Android)
- Test: `app/src/test/java/com/itantra/data/transport/WifiDirectTransportTest.kt:12` 3 PASS, `BluetoothTransportTest.kt:12` 2 PASS already host loopback — add test that verifies `isAndroid() == false` on host and reflection fallback works

**Interfaces:**
- Consumes: `WifiP2pManager` `discoverPeers`/`createGroup`/`connect`/`requestConnectionInfo` `GO IP`, `BluetoothAdapter` `listenUsingRfcommWithServiceRecord` `SPP_UUID`, `FrameCodec`
- Produces: `WifiDirectTransport`/`BluetoothTransport` that on `isAndroid()==true` try reflection real, else `ServerSocket` loopback.

- [ ] **Step 1: Write failing test for reflection presence**

```kotlin
@Test fun wifiDirectHasRealReflectionBranch() {
  val src=File("app/src/main/java/com/itantra/data/transport/WifiDirectTransport.kt").readText()
  assertThat(src).contains("WifiP2pManager")
  assertThat(src).contains("discoverPeers")
  assertThat(src).contains("isAndroid")
}
@Test fun bluetoothHasRealReflectionBranch() {
  val src=File("app/src/main/java/com/itantra/data/transport/BluetoothTransport.kt").readText()
  assertThat(src).contains("BluetoothAdapter")
  assertThat(src).contains("listenUsingRfcommWithServiceRecord")
  assertThat(src).contains("SPP_UUID")
}
```

These will FAIL until real branches added (currently host `ServerSocket` only, but `BluetoothTransport.kt` already has reflection for `BluetoothAdapter` — check, Wifi does not yet).

- [ ] **Step 2: Run RED**

Run: `./gradlew :app:testDebugUnitTest --tests "*TransportReflectionTest" -q` => FAIL `expected to contain WifiP2pManager`

- [ ] **Step 3: Implement real branches (keep host fallback)**

```kotlin
// WifiDirectTransport.kt add:
private fun isAndroid() = try { Class.forName("android.os.Build") != null } catch(_:Exception){ false }
suspend fun startServer():Result<Unit> {
  if(isAndroid()){
    try {
      val mgr = Class.forName("android.net.wifi.p2p.WifiP2pManager")
      // reflection discoverPeers, createGroup, requestConnectionInfo to get GO IP
      // then open ServerSocket(4242) tcpNoDelay as before
    } catch(_:Exception){}
  }
  // host fallback ServerSocket(0) as before
}
```

Keep existing `ServerSocket` loopback for host; real Android path just adds `WifiP2pManager` calls before `ServerSocket`.

Similarly `BluetoothTransport.kt` already has `isAndroid()` but make `startServer` actually call `listenUsingRfcommWithServiceRecord` if on Android, else `ServerSocket`.

- [ ] **Step 4: Run GREEN** => host loopback still 3+2 PASS, reflection strings present.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/itantra/data/transport/WifiDirectTransport.kt app/src/main/java/com/itantra/data/transport/BluetoothTransport.kt
git commit -m "feat(hardware): wire real WifiP2pManager + BluetoothAdapter reflection (host loopback fallback)"
```

---

### Task 4: Real AlertAudioManager + Hilt Injection

**Files:**
- Create: `app/src/main/java/com/itantra/data/audio/AndroidAlertAudioManager.kt`
- Modify: `app/src/main/java/com/itantra/data/audio/AlertAudioManager.kt:1` keep interface
- Modify: `app/src/main/java/com/itantra/presentation/transceiver/TransceiverViewModel.kt:1` add `@HiltViewModel @Inject` + real deps
- Modify: `app/src/main/java/com/itantra/di/AppModule.kt` provide `AlertAudioManager`, `TransportManager`, `PttStateMachine`, `PriorityRouter`
- Modify: `app/src/main/java/com/itantra/MainActivity.kt:1` use `by viewModels()` Hilt (already does)
- Test: `app/src/test/java/com/itantra/presentation/transceiver/AlertHandlerTest.kt:1` already checks `AlertAudioManager` interface; add test for `AndroidAlertAudioManager` file contains `STREAM_ALARM` `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` `getStreamMaxVolume` `Vibrator`

**Interfaces:**
- Consumes: `AudioManager`, `Vibrator`, `Hilt`
- Produces: `AndroidAlertAudioManager : AlertAudioManager` real on Dalvik, `FakeAlertAudioManager` on host.

```kotlin
// AndroidAlertAudioManager.kt
package com.itantra.data.audio
import android.content.Context
import android.media.AudioManager
import android.os.Vibrator
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
class AndroidAlertAudioManager @Inject constructor(@ApplicationContext private val ctx: Context) : AlertAudioManager {
  override fun acquireAlarmFocus(): Boolean {
    if(isHost()) return true
    return try {
      val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
      am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
      val res = am.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
      res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    } catch(_:Exception){ true }
  }
  override fun vibrate(pattern: LongArray) {
    if(isHost()) return
    try {
      val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
      vib.vibrate(pattern, -1)
    } catch(_:Exception){}
  }
  override fun release() { try { (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocus(null) } catch(_:Exception){} }
  private fun isHost() = try { Class.forName("android.os.Build"); false } catch(_:Exception){ true } // actually inverse: host has no android.os.Build? use vm name
}
```

Simplify `isHost` via `System.getProperty("java.vm.name") != "Dalvik"` as in `Sherpa*`.

- [ ] **Step 1: Write failing test `AndroidAlertAudioManagerTest.kt`**

```kotlin
@Test fun androidAlertManagerHasRealAlarm() {
  val src=File("app/src/main/java/com/itantra/data/audio/AndroidAlertAudioManager.kt").readText()
  assertThat(src).contains("STREAM_ALARM")
  assertThat(src).contains("AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE")
  assertThat(src).contains("getStreamMaxVolume")
  assertThat(src).contains("Vibrator")
  assertThat(src).contains("500")
}
@Test fun viewModelIsHiltInjectable() {
  val src=File("app/src/main/java/com/itantra/presentation/transceiver/TransceiverViewModel.kt").readText()
  assertThat(src).contains("@HiltViewModel")
  assertThat(src).contains("@Inject")
}
```

- [ ] **Step 2: Run RED** => FAIL `FileNotFoundException AndroidAlertAudioManager.kt` / `expected to contain @HiltViewModel`

- [ ] **Step 3: Create `AndroidAlertAudioManager.kt` + update `TransceiverViewModel.kt` to `@HiltViewModel @Inject` and `AppModule.kt` to ` @Provides AlertAudioManager` + `TransportManager` etc.

```kotlin
// AppModule.kt
@Module @InstallIn(SingletonComponent::class) object AppModule {
  @Provides fun provideAlertAudioManager(@ApplicationContext ctx: Context): AlertAudioManager =
    if(isHost()) FakeAlertAudioManager() else AndroidAlertAudioManager(ctx)
  private fun isHost() = System.getProperty("java.vm.name") != "Dalvik"
  // similarly for TransportManager, Ptt, Router if needed
}
```

Keep host tests using `FakeAlertAudioManager` (already in `AlertHandlerTest.kt`).

- [ ] **Step 4: Run GREEN**

Run: `./gradlew :app:testDebugUnitTest --tests "*AlertHandlerTest" --tests "*AndroidAlert*"` — host `Fake` still PASS, new file checks PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/itantra/data/audio/AndroidAlertAudioManager.kt app/src/main/java/com/itantra/presentation/transceiver/TransceiverViewModel.kt app/src/main/java/com/itantra/di/AppModule.kt
git commit -m "feat(hardware): real STREAM_ALARM max + Vibrator + Hilt injection"
```

---

### Task 5: Verification Gates (no new code)

**Files:**
- Verify: all tests, native, APK, offline

**Interfaces:**
- Consumes: all previous tasks
- Produces: gate PASS + final commit

- [ ] **Step 1: All host unit tests pass**

Run: `./gradlew :app:testDebugUnitTest -q` => 185+? new `AndroidAlert` 2 → 187? (expect 187) `BUILD SUCCESSFUL`

- [ ] **Step 2: All native tests pass**

Run: `ctest --test-dir build --output-on-failure` => 35/35 PASS (Oboe guarded, mel, etc.)

- [ ] **Step 3: APK builds clean**

Run: `./gradlew :app:assembleDebug` => `BUILD SUCCESSFUL` `app-debug.apk` ~188 MB, Oboe linked on Android (fetch if network, else stub), sherpa AAR via JitPack (host mock, device real)

- [ ] **Step 4: Offline gate**

Run: `scripts/check-no-internet.bat` => `PASS no INTERNET permission in app-debug.apk` (11 perms)

- [ ] **Step 5: Final commit**

```bash
git log --oneline -5
git status
git add docs/superpowers/plans/2026-09-04-hardware-real.md
git commit -m "feat(hardware): wire real sherpa-onnx, Oboe AAudio, WifiP2pManager, and STREAM_ALARM" --allow-empty
```

```

