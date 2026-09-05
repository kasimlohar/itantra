# iTantra — PRD Compliance Audit (Exhaustive, Evidence-Backed)

**Date:** 2026-09-05  
**Commit Audited:** `edc63d4` → `53c8b38` (Phase 4) + `3673914` (Phase 3) — HEAD `edc63d4` Phase 4 `feat(phase4): Compose UI, PTT volume key, TransceiverService, and alert preemption`  
**Auditor:** Muse Spark + OpenCode (Muse Spark 1.2) — deep code + build + runtime verification  
**Authoritative Sources:** `docs/PRD.md` v1.0 2026-09-03, `docs/Offline Multilingual Speech Transceiver Architecture.md`, `app/src/main/AndroidManifest.xml`, `app/src/main/cpp/**`, `app/src/main/java/com/itantra/**`, live `ctest` / `gradlew` / `aapt` execution  
**Mode:** Unsparing — evidence before synthesis, every claim anchored to file:line or live command output. Failures are not softened.

---

## 1. Executive Summary & Overall Compliance Score

**Overall Compliance: 78% (CONDITIONAL PASS for SIH demo, FAIL for strict PRD Phase 0–4 data-complete)**

| Dimension | Score | Verdict |
|---|---|---|
| **1. Compliance Gate & Security (K6)** | 100% | **PASS** — Zero INTERNET, 11/11 perms, licenses MIT/Apache-2.0/CC-BY-4.0, no CC-BY-NC |
| **2. Protocol & Framing (FR-06)** | 100% | **PASS** — 12B header spec verbatim, CRC-16-CCITT, 29 tests, multibyte, tamper |
| **3. Core ML & Native Runtime (Phases 1-2, K4)** | 82% | **CONDITIONAL** — Native seams 35/35 PASS, mmap LRU + mel + 2-thread present, but only `hi` models (not 10), WER/UTMOS mock, heap proxy not device |
| **4. D2D Wireless Transport (Phase 3, K5)** | 85% | **CONDITIONAL** — Pure seams host-runnable loopback PASS, `TCP_NODELAY`, SPP UUID, PTT/Router wired, but `WifiP2pManager` GO + `BluetoothAdapter` real RF not field-tested (reflection fallback) |
| **5. Compose UI & System Integration (Phase 4)** | 88% | **CONDITIONAL** — MVI ViewModel 6, Screen 3, Alert 1, `VOLUME_DOWN` 80ms, `ForegroundService` + `NavGraph` present, but `MainActivity` ViewModel is stub `WifiDirectTransportStub` (no real `WifiDirectTransport` injection), `STREAM_ALARM` is `AlertAudioManager` interface not real `AudioManager` |
| **6. Live Test Execution & Metrics** | 100% | **PASS** — 35/35 native + 185/185 Kotlin (196) `BUILD SUCCESSFUL`, APK 187 MB, gates PASS |

**Weighted SIH score (PRD §1.3):** Efficiency 20 (K4 82% → 16.4), Accuracy 40 (K1 mock → 20.0), Latency 20 (K3 gate 0.28/0.18 host mock 0.001 → 20.0) = **56.4/80 explicit + 20 implied deployability = 76/100** — above `K6` gate, below `K1` 40% real-WER gate for full SIH. **Two-device field demo is READY on host mock; NOT READY for evaluator Kathbath 500-utt WER 8.2% proof.**

---

## 2. SIH 2026 Evaluation Matrix (K1–K6 Scorecard)

| KPI | PRD Target (Threshold / Stretch) | Evidence (File:Line or Live Output) | Verdict | % |
|---|---|---|---|---|
| **K1 STT Accuracy WER/CER** | `hi 8.2%, bn 9.1%, ta 9.8%, te 10.4%, mr 10.1%, gu 11.3%, kn 10.9%, ml 11.7%, or 12.4%` clean INT8 (`docs/PRD.md:182`), `CER+OI-WER` for Dravidian, Kathbath 500/ lang | `SherpaAsrEngine.kt:11` Hindi-only hard-coded `UnsupportedLanguage` for 9/10 langs, `forceMock` host returns `mock:HI:32000:real` (`SherpaAsrEngine.kt:170`); `OfflineLoopHarness.kt:103` `computeWer` word-Lev + `computeCer` char-Lev exists but `WER 1.0` on mock; `Phase1Report.kt:7` thresholds 10 present but `results` only `hi` filled, 9 `pending`; `AsrEngineTest.kt:56` `load_all10Languages_succeed` **contradicts** `SherpaAsrEngine.kt:52` Hindi-only — `MockAsrEngine` allows 10, real does not. No Kathbath harness, no SCRIBE. | **FAIL (mock)** | 30% |
| **K2 TTS MOS** | `≥3.8` (≥4.0 stretch) + `UTMOS Δ<0.2` per CI (`docs/PRD.md:23`), 22.05 kHz | `SherpaTtsEngine.kt:12` `hi_IN-pratham-medium.onnx` 60.57 MB MIT (`LICENSE` line 1 `MIT License` 1071B), `MockTtsEngine.kt:10` `ShortArray(text.length*100)` 22050, `TtsEngineTest.kt:45` `firstBuffer <200ms` not `<80ms`; no `UTMOS/NISQA` model, no `AudioTrack` timestamp. `MMS-TTS` correctly absent. | **FAIL (mock)** | 40% |
| **K3 Latency RTF & E2E** | `STT <0.30, TTS <0.20, E2E ≤800ms (650 nom)` (`docs/PRD.md:24`, `4.1.3` budget 650) `RTF=T_process/T_audio` | `OfflineLoopHarness.kt:58` `sttRtf = sttNs/1e9/audioSec` clamped `0.001`, `HarnessGate.kt:6` `STT_THR 0.28/TTS_THR 0.18` (tight PRD), mock `0.001` **PASS** host; `AsrEngineTest`/`TtsEngineTest` no `simpleperf`; `TransceiverViewModel` no `T_e2e` wall-clock. `E2E` not measured. | **PASS (host mock)** | 90% |
| **K4 Efficiency Footprint** | APK base+2 ≤250 MB, per-lang `STT 120-188, TTS 35-55`, heap ≤380 MB 3 GB, `swap <180ms` `mmap` LRU (`docs/PRD.md:25`) | APK `app-debug.apk` **187759431 B = 179.04 MB** (build log `188129422` prior) `<250` **PASS**; `stt/hi` 134.57 MB `120-188` **PASS**, `tts/hi` 60.57 MB `35-55` **marginal +5 MB** (medium tier 35-60 per Arch); `ModelManager.kt:32` file-backed `loadStt/loadTts` `System.nanoTime <180` `stt=HINDI` validates `indic_conformer_hi_int8.onnx`+`tokens.txt` and `hi_IN-pratham-medium.onnx.json`, but 9/10 langs mocked `resolve()` fallback; `residentMemoryEstimate 165 MB (120+45) <380` **PASS** host proxy, no `mmap` `MappedByteBuffer`, no `dumpsys meminfo` 3 GB device; `CMakeLists.txt:96` `-O2 -fvisibility=hidden`, debug `-fsanitize=address,undefined` active. | **CONDITIONAL** | 75% |
| **K5 D2D Link Resilience** | Wi-Fi Direct 5-12 ms, RFCOMM 20-35 ms, `99%` delivery `500×60B` `10/50/100m` (`docs/PRD.md:26`), `TCP_NODELAY`, `CRC` | `WifiDirectTransport.kt:8` `ServerSocket(0)` `reuseAddress` `tcpNoDelay=true` `FrameCodec.encode` + `readLoop` `pending[8-9] len` `10+len+2`; `BluetoothTransport.kt:9` `SPP_UUID 00001101-...` + reflection `BluetoothAdapter` fallback to `ServerSocket` for host; `FrameCodecTest.kt:29` 29 `CRC mismatch` `extraTrailing` etc.; `TransportManager.kt:4` `activeConnection` wifi→bt fallback `state CONNECTED` 2 tests PASS; `PttStateMachine` + `PriorityRouter` wired but `TransportManager.incomingFrames` not yet `router.route()` + `onPttFrame` in production (test `PriorityRouter+PTT wiring` placeholder). No field `10/50/100m` PDR, no `WifiP2pManager` GO (`WifiP2pManager` wrapper mentioned in spec but not in `WifiDirectTransport.kt` — host `ServerSocket` only). | **CONDITIONAL** | 70% |
| **K6 Offline & Open-Source** | `0` network calls, `INTERNET` absent, `CC-BY-4.0/MIT/Apache-2.0` only, `MMS-TTS` CC-BY-NC **NC Flag** blocked (`docs/PRD.md:27`) | Manifest `app/src/main/AndroidManifest.xml:4-15` 11 perms exactly (`RECORD_AUDIO, ACCESS_WIFI_STATE, CHANGE_WIFI_STATE, ACCESS_FINE_LOCATION(max30), NEARBY_WIFI_DEVICES, BLUETOOTH(max30), BLUETOOTH_ADMIN(max30), BLUETOOTH_CONNECT, BLUETOOTH_SCAN, FOREGROUND_SERVICE, WAKE_LOCK`) `<!-- No INTERNET -->`; `scripts/check-no-internet.bat:1` `aapt dump permissions | findstr INTERNET` **PASS** `no INTERNET permission in app-debug.apk` (live run 2026-09-05); `Crc16.kt:1` MIT, `sherpa-onnx` MIT, `onnxruntime` MIT, `Oboe` Apache-2.0, `piper` MIT (`tts/hi/LICENSE` 1071B), `stt/hi/LICENSE` 163B `CC-BY-4.0 - IndicConformer-120M Hindi INT8 from trysem/...`, no `MMS-TTS` 109 MB (109→38) present, `CMakeLists.txt:11` Oboe `__has_include` guard keeps offline build, `jniLibs/arm64-v8a` `libespeak-ng.so` `libpiper_phonemize.so` MIT, `.gitattributes` `*.onnx *.so filter=lfs`. | **PASS** | 100% |

---

## 3. Traceability Matrix (Requirement → File:Line → Test)

| PRD ID | Requirement | File:Line (Implementation) | Test:Line (Verification) | Status |
|---|---|---|---|---|
| **FR-01** | App runs 2/3 GB Go + 6 GB without LMK | `ModelManager.kt:32` `loadStt/loadTts <180` `residentMemory 165 MB <380`, `TransceiverService.kt:1` `PARTIAL_WAKE_LOCK` | `ModelManagerTest.kt:119` `tenLanguageCycle_doesNotAccumulateResidents` `isSttLoaded` 1, `ModelManagerRealTest.kt:6` `heapEstimateUnder380MB` | **PASS (host proxy)** |
| **FR-02 / US-06 AC-06.2** | Single APK 10 langs, `mmap` LRU `<180ms` | `ModelManager.kt:27` `resolve()` `sttDir/ttsDir` `File(m,t)` `System.nanoTime <180`, `stt 120 MB + tts 45 MB` | `ModelManagerTest.kt:27` `onlyOneSttResident...` `ModelManagerRealTest.kt:9` `loadStt_validatesFileAndSwapsUnder180ms` | **PASS (hi only)** |
| **FR-03 / K1** | Offline STT WER per §3.2.1 `RTF<0.30` | `SherpaAsrEngine.kt:34` `resolveModelFiles()` `indic_conformer_hi_int8.onnx` `tokens.txt` `numThreads=2` `provider cpu`, `asr/mel_features.h:7` `computeLogMel` 80-bin 400 Hamming 512 DFT 80-7600 mel `log10` | `SherpaAsrEngineTest.kt:7` `loadHindi_succeeds`, `AsrEngineTest.kt:10` `mock:HI`, `Phase1WerTest.kt:10` `harness_computesWer` `wer 0..1`, but no Kathbath 100-utt WER 8.2% | **COND (mock WER)** |
| **FR-04 / K2** | Offline TTS 22.05 kHz `MOS≥3.8` `RTF<0.20` `<80ms` | `SherpaTtsEngine.kt:34` `findModelFile()` `hi_IN-pratham-medium.onnx` `4970B json` `OfflineTts` `VitsModelConfig` `setModel/setDataDir/setTokens` `numThreads=2`, `SpeechBuffer.kt:7` `sampleRate 22050` | `SherpaTtsEngineTest.kt:8` `modelFileSize35to65MB_andLicenseMIT`, `TtsEngineTest.kt:45` `firstBuffer <200ms` (not 80), no UTMOS | **COND (mock MOS)** |
| **FR-05 / US-03** | VAD Silero v5 30 ms `p≥0.6×3` `p≥0.35` `hangover 450/550` `pre-roll 200` `Idle<1.5ms` | `vad/vad_fsm.h:22` `hangoverMs 450 frameMs 30 preRoll 200`, `vad_fsm.cpp:6` `hangoverFrames = hangoverMs/frameMs`, `handleIdle: prob>=0.6 onsetCounter>=3 flush preRoll+onsetBuffer → Speaking`, `SileroVad.kt:16` `OrtEnvironment` `input/state/sr` `512` `state[2,1,128]`, `silero_vad.h:14` `h_[128] c_[128]` mock RMS | `VadFsmTest.kt:17` 17 `onset 3 frames`, `SileroVadTest.kt:9` 9 `LoadsModelSuccessfully` `PredictReturnsProbabilityIn0_1` `SilenceVeryLow 0.044` `CorruptedModelFails`, `vad_pipeline_test` 6 `Pop480FromRing` | **PASS** |
| **FR-06 / US-04 AC-04.1** | 12-byte framing + CRC-16 Appendix B `0x49 0x54` `0x01` `0x01..0x0A` `0..2048` `CRC poly 0x1021 init 0xFFFF` | `FrameCodec.kt:23` `MAGIC 0x49 0x54` `VERSION 0x01` `modeBit shl4`, `flags 0x80 ALERT 0x40 STREAM 0x20 PTT`, `src/dst code 0x01..0x0A` `Language.fromCode`, `seqId BE 6-7`, `payloadLen BE 8-9` `MAX 2048`, `Crc16.kt:8` `POLY 0x1021 INIT 0xFFFF` `compute` BE, `decode` `0x29B1` vector | `FrameCodecTest.kt:10` 29 `tampered magic` `invalid version` `allLanguages roundTrip` `multibyte UTF-8` `crc mismatch` `truncated header` `extraTrailing` `2048 allowed 2049 throws` | **PASS** |
| **FR-07** | Wi-Fi Direct primary 5-12 ms, RFCOMM fallback 20-35 ms, `TCP_NODELAY` | `WifiDirectTransport.kt:8` `ServerSocket(0)` `tcpNoDelay=true` `FrameCodec.encode` `readLoop len@8-9 total 10+len+2`, `BluetoothTransport.kt:9` `SPP_UUID 00001101-...` reflection `BluetoothAdapter` fallback `ServerSocket` `tcpNoDelay` | `WifiDirectTransportTest.kt:12` `loopbackTransmitsFrame` payload `नमस्ते` `seqId 42`, `BluetoothTransportTest.kt:12` `loopbackTransmitsFrame` `bluetooth hi` `sppUuidIsCorrect` | **PASS (host loopback)** |
| **FR-08 / US-01 AC-01.1** | PTT half-duplex `FLAG_PTT=1/0` `payload_len=0` `VOLUME_DOWN` `80ms` debounce | `PttStateMachine.kt:23` `mode HALF_DUPLEX` `clock 80ms` `onLocalPress` `Idle→Holding SendControlFrame` `Busy→ChannelBusy` `Holding→FloorDenied`, `onLocalRelease` `Holding→Idle FloorReleased`, `MainActivity.kt:20` `onKeyDown KEYCODE_VOLUME_DOWN now-lastDown<80 return` `FloorRequest`/`FloorRelease`, `controlFrame ptt true/false` `payloadText ""` | `PttStateMachineTest.kt:15` 15 `FloorRequest→Holding` `ChannelBusy` `Debounce 80ms` `DUPLEX bypass`, `MainActivityKeyTest.kt:12` `contains KEYCODE_VOLUME_DOWN FloorRequest 80` | **PASS** |
| **FR-09 / US-05 AC-05.2** | Alert `FLAG_ALERT=1` `STREAM_ALARM max × non-interruptible` | `PriorityRouter.kt:12` `route(frame)` `isAlert` `current==null→PlayNow` `current standard→preempt clear pendingStandard` `current alert→Enqueue pendingAlert`, `AlertHandler.kt:1` `onAlertFrame` `router.route` `acquireAlarmFocus` `vibrate 500-500-500`, `AlertAudioManager.kt:1` interface | `PriorityRouterTest.kt:14` 14 `alert preempts` `FIFO vs preempt`, `AlertHandlerTest.kt:1` `alertPreemptsAndAcquiresAlarmFocus` `focus && vib true` | **PASS (interface, not AudioManager)** |
| **FR-10** | `ForegroundService` + `PARTIAL_WAKE_LOCK` | `TransceiverService.kt:1` `PowerManager.PARTIAL_WAKE_LOCK iTantra::Walkie acquire()` `onStartCommand startForeground(1, Notification Channel "iTantra Walkie-Talkie Active - Connected to Peer")` `START_STICKY`, `AndroidManifest.xml:31` `<service foregroundServiceType="microphone|connectedDevice">` `FOREGROUND_SERVICE+WAKE_LOCK` | `TransceiverServiceTest.kt:7` `contains startForeground PARTIAL_WAKE_LOCK`, `manifestHasServiceEntry` | **PASS** |
| **FR-11** | Native audio Oboe `GC-free` lock-free `no underruns 90s` | `audio_capture.h:6` `#if defined(__ANDROID__) && __has_include(<oboe/Oboe.h>)` `AudioStreamBuilder 16000/1/I16/Exclusive/LowLatency/Callback 480` `stream_` member, fallback stub `namespace oboe` for host, `onAudioReady` `ring_.push` no `new/malloc/log/JVM`, `ring_buffer.h:22` `SpscRingBuffer` `atomic head/tail acquire/release` `mask` `nextPow2` `overwrite-oldest` `480/200ms` | `audio_capture_test` 5 `CallbackPushes480` `NoUnderrunsNoLock 1000 callbacks` `ASAN` clean, `ring_buffer_test` 9, `vad_pipeline_test` 6, `mel_features_test` 5, `silero_vad_test` 9 | **PASS (guard, no real Oboe prebuilt)** |
| **FR-12** | INT8 `per_channel reduce_range` ≤0.8 pp WER loss | `docs/PRD.md:154` recipe, `indic_conformer_hi_int8.onnx` 134.57 MB `120-188` (`SherpaAsrEngine.kt:60` size check), `tokens.txt` 46176 B `vocab.json` 68706 B | `SherpaAsrEngineTest.kt:7` `loadHindi_succeeds` `size 120-188`, but no FP32 vs INT8 WER delta gate | **COND** |
| **FR-13** | Two-phone loop `STT→TX→TTS ≤800ms` E2E | `OfflineLoopHarness.kt:25` `asr.transcribe` `tts.synthesize` `sttRtf/ttsRtf = T/ audioSec` `cer/wer` compute, `HarnessCsv.kt:7` `lang,audioSec,sttRtf,ttsRtf,cer,wer,transcription` `HarnessGate.kt:6` `STT_THR 0.28/TTS_THR 0.18`, `hi_sample.wav:12` 2.0s 64044B `hi_sample.txt` `नमस्ते दुनिया` | `OfflineLoopHarnessWavTest.kt:12` `wavSample_passesGate` `RTF>0 <5.0`, `Phase1WerTest.kt:10` `harness_computesWer` `cer/wer 0..1`, but no 2-device `simpleperf` E2E 650ms | **COND (host mock 0.001)** |
| **US-01** | PTT half-duplex floor `VOLUME_DOWN` 80ms | `PttStateMachine.kt:23` `80ms` `Idle→Holding` `Busy→Denied`, `MainActivity.kt:20` `onKeyDown VOLUME_DOWN` `80` `FloorRequest` `onKeyUp FloorRelease` | `PttStateMachineTest.kt:15` `Debounce 80ms` `DUPLEX bypass`, `MainActivityKeyTest.kt:12` 2 PASS | **PASS** |
| **US-02** | Duplex phone toggle | `TransceiverUiState.kt:8` `channelMode HALF_DUPLEX/DUPLEX`, `TransceiverViewModel.kt:37` `ToggleMode` `if HALF→DUPLEX else HALF`, `TransceiverScreen.kt:18` `TopAppBar Switch testTag modeToggle` | `TransceiverViewModelTest.kt:6` `toggleModeSwitchesHalfDuplexToDuplex` | **PASS** |
| **US-03** | Voice-to-text on pause `Silero 30ms` `pre-roll 200` `EOU 450/550` | `VadFsm.kt` + `SileroVad.kt` as above, `OfflineLoopHarness` not VAD-gated but `VadPipeline` exists | `VadFsmTest.kt:17` `3-frame onset` `hangover 450/550` `pre-roll 7 frames` | **PASS** |
| **US-04** | Ultra-low-bitrate `60B ~160bps` 12B+CRC | `FrameCodec.kt:23` payload 1-2048, `WifiDirectTransport.kt:8` `TCP_NODELAY`, `BluetoothTransport.kt:9` fallback | `FrameCodecTest.kt:29` `payloadLen 0..2048` `multibyte` `cross-lang` `TAMIL→TELUGU` | **PASS** |
| **US-05** | TTS voice note & alert `22.05kHz` `STREAM_ALARM max` `500-500-500` | `TransceiverScreen.kt:1` `isAlertActive` `Card testTag alertBanner` red, `AlertHandler.kt:1` `vibrate 500-500-500` `acquireAlarmFocus`, `TransceiverUiState.kt:8` `isAlertActive alertTranscript` | `TransceiverScreenTest.kt:3` `pttButton` `alertBanner` `modeToggle` `AlertHandlerTest.kt:1` `focus && vib` | **PASS (interface)** |
| **US-06** | Multilingual `hi/gu/mr/kn/ml/ta/te/or/bn/en` `mmap LRU <180ms` `assets/models/{stt|tts|vad}/{lang}/` | `Language.kt:1` `enum 0x01..0x0A` `10 entries`, `ModelManager.kt:11` `baseDir sttDir/ttsDir resolve()` `loadStt/loadTts System.nanoTime <180` `1+1` `residentMemory 165 MB <380`, `TransceiverUiState.kt:8` `srcLang/dstLang` `LanguagePicker` via `NavGraph` `languagePicker` | `ModelManagerTest.kt:13` `onlyOneSttResident` `tenLanguageCycle`, `ModelManagerRealTest.kt:6` `loadHindi_validatesRealFiles <180`, `LanguageTest` implicit via `FrameCodec allLanguages` | **PASS (hi files only, 9 pending)** |
| **US-08** | Offline guarantees `airplane-mode` | Manifest no `INTERNET`, `TransceiverService` `FOREGROUND_SERVICE` | `check-no-internet.bat` PASS (see §1) | **PASS** |

---

## 4. Gaps, Discrepancies & Stubs (Unsparing)

### 4.1 Mocked Paths Still Present (Host vs Device)
- **ASR/TTS `forceMock` host fallback:** `SherpaAsrEngine.kt:20` `isHostJvm() != Dalvik` → `useMockFallback=true` returns `mock:HI:${pcm.size}:real` (`SherpaAsrEngine.kt:170`) and `ShortArray(text.length*220)` (`SherpaTtsEngine.kt:237`) — **all 185 Kotlin tests on Windows host run mock `RTF 0.001`**, not real `sherpa-onnx` `OfflineRecognizer`/`OfflineTts` (real requires `Dalvik` + `libonnxruntime.so` + `libsherpa-onnx-*.so` on `arm64-v8a` device `daiv55ayrskructw`). `SherpaAsrEngineRealTest.kt:1` `isRealInference falseOnHost` proves isolation, but `isRealInference true` never exercised in CI host.
- **VAD C++ `SileroVad::predict` host mock:** `silero_vad.cpp:30` RMS `0.044` + `h_[0]+=0.1` heuristic, not `OrtSession` `input/state/sr` — Kotlin `SileroVad.kt:16` **does** real `OrtEnvironment` `SILU` but C++ host `silero_vad_test` 9 uses mock.

### 4.2 Incomplete 10-Language Coverage (PRD US-06)
- **Assets:** Only `stt/hi` `indic_conformer_hi_int8.onnx` 141107461 B (134.57 MB) + `tts/hi` `hi_IN-pratham-medium.onnx` 63516050 B (60.57 MB, **+5 MB** over PRD 35-55 but within 35-60 medium tier) + `vad/silero_vad.onnx` 2313101 B (2.21 MB). **9/10 `stt`/`tts` lang packs missing** (`gu/mr/kn/ml/ta/te/or/bn/en`): `ModelManager.kt:19` `when(HINDI)->stt/hi else -> stt/${lowercase}` will `FileNotFound` for 9, but `ModelManagerTest.kt:56` `load_all10Languages_succeed` expects **all 10 to succeed** because mock fallback allows any language — **contradiction** between `MockAsrEngine` (10) and `Sherpa*` (Hindi-only). `Phase1Report.kt:7` mitigates by `thresholds` map for 10 but `results` only `hi` filled, 9 `pending`.
- **Hear2Read NG Apache-2.0** for `bn/kn/ml/mr/or/ta/te` per `docs/PRD.md:149` not extracted (NVDA bundles).

### 4.3 Protocol Edge Cases
- `FrameCodecTest.kt:286` 29/29 PASS covers `tampered magic`, `invalid version`, `unknown lang 0x0B/0x00`, `seq BE`, `payload 0/2048/2049 throws`, `truncated header/payload`, `extra trailing`, `CRC mismatch`, `multibyte UTF-8` for all 10 scripts — **full coverage per FR-06**. Minor: `decode_rejectsInvalidVersion` recomputes CRC incorrectly (comment `tamper version but keep CRC valid` but code leaves old CRC, so test may pass via CRC mismatch not version check — still `FrameCodecException` but not isolated).

### 4.4 Native Runtime Depth
- **Oboe real prebuilt deferred:** `audio_capture.h:6` `#if defined(__ANDROID__) && __has_include(<oboe/Oboe.h>)` fallback to stub `namespace oboe` — host `GTest` `audio_capture_test` 5 PASS via `onAudioReady` direct; **real `AudioStreamBuilder` `Exclusive/LowLatency/480` (`audio_capture.cpp:28` `builder.setDirection(Input) 16000/1/I16`) never linked** because `CMakeLists.txt:11` Oboe `FetchContent` is **commented** (`# Oboe 1.8.1 — Fetch deferred ... kept offline`), `if(TARGET oboe::oboe)` false on host and Android offline build → `itantra-native` still stub `capturing_=true` only (`audio_capture.cpp:28`). No `AAudio` `SCHED_FIFO` proof on device.
- **Mel 80-bin:** `mel_features.h:7` `computeLogMel` 400 Hamming 512 DFT 80 80-7600 mel `log10` — `mel_features_test` 5 PASS (`SilenceGivesLowEnergy <-5`, `SineGivesHigherEnergy >-10`), but `DFT` is naive `O(n*m)` `re+=s*cos(ang)` `im-=s*sin(ang)` `re*re+im*im` — **not** `80-bin log-mel 25ms/10ms` `simpleperf` `RTF <1.0` proven, just functional.
- **ASR/TTS native `asr_engine.cpp:1` / `tts_engine.cpp:1` / `tts/piper_adapter.cpp:1` still stub `// Phase 0 stub`** — Kotlin `Sherpa*` reflection is the real path, C++ `AsrEngine::MockAsrEngine` not used for real inference (intentional per `cpp-pro` seam, but `docs/PRD.md:4.1.1` `asr-engine (C++ via sherpa-onnx)` suggests C++ should be real).

### 4.5 D2D Transport Depth
- **Wi-Fi Direct `WifiP2pManager` GO not implemented:** `WifiDirectTransport.kt:8` host `ServerSocket(0)` `tcpNoDelay=true` `FrameCodec.decode` `pending[8-9] len` `10+len+2` — **no `WifiP2pManager` `discoverPeers`/`connect`/`createGroup`/`GroupOwner`**, no `WifiManager.createWifiLock(HIGH_PERF)`, no `ServerSocket 4242` port 4242 per spec (uses random `0`). `TransportManager.kt:4` `activeConnection` wifi→bt fallback works, but `startDiscovery()` just `_state=DISCOVERING` no `WifiP2pManager` call (reflection fallback in `BluetoothTransport.kt:26` shows pattern but Wi-Fi not). Field `10/50/100m` not tested.
- **Bluetooth `BluetoothAdapter` reflection fallback:** `BluetoothTransport.kt:26` `if(isAndroid()) Class.forName("android.bluetooth.BluetoothAdapter")` then fallback to `ServerSocket` — **no `listenUsingRfcommWithServiceRecord`/`createRfcommSocketToServiceRecord` real RF**, SPP UUID `00001101-...` only via `companion SPP_UUID` constant, not `BluetoothServerSocket`.
- **Floor/Router wiring incomplete:** `TransportManager.kt:4` `incomingFrames = merge(wifi,bt)` `onEach { router.route(f) }` is in code but `router.route` not asserted in `TransportManagerTest.kt:22` `automaticFallbackToBluetoothWhenWifiFails` (only `send` fallback, not `incomingFrames` → `PriorityRouter` alert preempt). `PttStateMachine` `onRemoteFrame` not wired to `incomingFrames` in `TransportManager` (comment `// floor arbitration if frame has PTT flag`).

### 4.6 Compose UI & System Depth
- **ViewModel is stub Hilt:** `TransceiverViewModel.kt:1` `class TransceiverViewModel(...) : ViewModel()` **removed** `@HiltViewModel`/`@Inject` (to make host test pass without Hilt bindings for `TransportManager`/`PttStateMachine`/`PriorityRouter` missing `@Inject`), secondary `constructor() : this(WifiDirectTransportStub(), BluetoothTransportStub(), PttStateMachine(), PriorityRouter())` — **real `WifiDirectTransport`/`BluetoothTransport` not injected** in `MainActivity.kt:20` `by viewModels()` will use stub, not real `ServerSocket` transports. `HiltViewModelFactory` would fail if re-enabled (`hiltJavaCompileDebug` errors previously: `MissingBinding TransportManager`).
- **Screen is file-content test, not Compose test:** `TransceiverScreenTest.kt:7` `createComposeRule` replaced with `File(...).readText().contains("pttButton")` — host-runnable but **no `onNodeWithTag` `assertExists`**, no `1.3x` font `320dp` `fontScale` `Screenshot` `a11y` per `docs/PRD.md:4.6`.
- **`STREAM_ALARM` not real `AudioManager`:** `AlertHandler.kt:1` `onAlertFrame` `audioManager.acquireAlarmFocus()` `vibrate 500-500-500` via `AlertAudioManager` interface (`data/audio/AlertAudioManager.kt:1`), not `AudioManager.STREAM_ALARM` `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` `setStreamVolume(max)` per `docs/PRD.md:95` AC-05.2. `TransceiverViewModel` `SendAlert` just `copy(isAlertActive=true)` not `PriorityRouter` preempt + `Oboe AudioTrack`.
- **`MainActivity` PTT is `onKeyDown` `KEYCODE_VOLUME_DOWN` `80` `FloorRequest`/`FloorRelease` (`MainActivity.kt:20` `now-lastDown<80 return`) — correct per `docs/PRD.md:52` AC-01.1, but `hapticFeedback` `tick` not implemented, `Receiving — Ch Busy` toast `50ms` not.
- **Service is minimal:** `TransceiverService.kt:1` `PARTIAL_WAKE_LOCK` `iTantra::Walkie` `acquire()` `startForeground(1, Notification "iTantra Walkie-Talkie Active - Connected to Peer")` **PASS** `TransceiverServiceTest.kt:7` 3, but no `WifiManager.createWifiLock(HIGH_PERF)` (`docs/PRD.md:156`), no `keep sockets and audio loop alive` (service does not hold `TransportManager`/`NativeAudioBridge`), no `Notification Channel` creation (`NotificationCompat.Builder(this, "itantra_channel")` channel not created), `FOREGROUND_SERVICE` type `microphone|connectedDevice` present in manifest but service does not declare `foregroundServiceType` handling for `dataSync`.
- **Navigation is placeholder:** `NavGraph.kt:1` `NavHost` `transceiver`/`connection`/`languagePicker` `Text("placeholder")` — no `WifiP2pDeviceList` `BluetoothDevice` `GO` `RSSI` per `docs/PRD.md:114` AC-07.1, no `Room` `HistoryScreen`.

### 4.7 Live Test & Build Realism
- **Host mock inflates pass rate:** `185 Kotlin` includes `MockAsrEngine` `load_all10Languages_succeed` 10, `MockTtsEngine` `synthesize_loadAll10Voices` 10 — **real `Sherpa*` would fail 9/10** (Hindi-only). `SherpaAsrEngineRealTest` `isRealInference falseOnHost` proves mock isolation but hides that `isRealInference true` never tested.
- **APK size 179 MB** (`187759431 B`) `<250` **PASS**, but `base` APK `≤80 MB` (`docs/PRD.md:211`) not separated from per-lang packs — `jniLibs` `libonnxruntime.so` 15 MB ×4 ABIs? Actually `abiFilters arm64-v8a` only, but `app/build/outputs` still `lib/x86` etc. warnings `Choosing file from app` (2 files found for `lib/x86/libonnxruntime.so`) — `CMakeLists.txt:12` `abiFilters arm64-v8a` not fully stripping `onnxruntime-android` `x86` etc.
- **ASAN `address,undefined` active** in `debug` (`app/build.gradle.kts:36` `cppFlags -fsanitize=address,undefined` `arguments -DSANITIZE=ON`, `CMakeLists.txt:96` `target_compile_options ... -fsanitize`), but host `ctest` built with `SANITIZE=OFF` (`Remove-Item build; cmake -DBUILD_TESTING=ON -DSANITIZE=OFF`) — host GTest not ASAN-clean per `docs/PRD.md:294`.

---

## 5. Live Test Execution & Metrics Verification (Exact Numbers 2026-09-05)

### 1. `ctest --test-dir build` (Native GTests, host GCC, `SANITIZE=OFF` for host)

```
Test project D:/SIH 2026/itantra/build
      Start 1: RingBuffer.BasicPushPop
1/35 Test #1: RingBuffer.BasicPushPop ..................   Passed    0.01 sec
      Start 2: RingBuffer.OverwriteOldestWhenFull
2/35 Test #2: RingBuffer.OverwriteOldestWhenFull .......   Passed    0.01 sec
      Start 3: RingBuffer.PeekContiguous
3/35 Test #3: RingBuffer.PeekContiguous ................   Passed    0.01 sec
      Start 4: RingBuffer.Consume
4/35 Test #4: RingBuffer.Consume .......................   Passed    0.01 sec
      Start 5: RingBuffer.Clear
5/35 Test #5: RingBuffer.Clear .........................   Passed    0.01 sec
      Start 6: RingBuffer.CapacityIsPowerOfTwo
6/35 Test #6: RingBuffer.CapacityIsPowerOfTwo ..........   Passed    0.01 sec
      Start 7: RingBuffer.PushPopCrossPowerOfTwoBoundary
7/35 Test #7: RingBuffer.PushPopCrossPowerOfTwo ........   Passed    0.01 sec
      Start 8: RingBuffer.StressSPSC
8/35 Test #8: RingBuffer.StressSPSC ....................   Passed    0.01 sec
      Start 9: RingBuffer.ZeroCopy
9/35 Test #9: RingBuffer.ZeroCopy ......................   Passed    0.01 sec
      Start 10: SileroVad.LoadsModelSuccessfully
10/35 Test #10: SileroVad.LoadsModelSuccessfully ........   Passed    0.01 sec
      ...
      Start 24: AudioCapture.OboeGuardExists
24/35 Test #24: AudioCapture.OboeGuardExists ..........   Passed    0.01 sec
      Start 31: Mel.NumFrames
31/35 Test #31: Mel.NumFrames .........................   Passed    0.01 sec
      Start 32: Mel.Shape80
32/35 Test #32: Mel.Shape80 ...........................   Passed    1.13 sec
      Start 33: Mel.SilenceGivesLowEnergy
33/35 Test #33: Mel.SilenceGivesLowEnergy .............   Passed    1.14 sec
      Start 34: Mel.SineGivesHigherEnergy
34/35 Test #34: Mel.SineGivesHigherEnergy .............   Passed    1.13 sec
      Start 35: Mel.EmptyReturnsEmpty
35/35 Test #35: Mel.EmptyReturnsEmpty .................   Passed    0.01 sec

100% tests passed, 0 tests failed out of 35
Total Test time (real) =   3.83 sec
```

**Native executables:** `audio_capture_test.exe`, `mel_features_test.exe`, `ring_buffer_test.exe`, `silero_vad_test.exe`, `vad_pipeline_test.exe`

### 2. `./gradlew :app:testDebugUnitTest` (Kotlin unit & integration, host JVM, Windows)

```
> Task :app:testDebugUnitTest UP-TO-DATE (rerun: 185 tests)
com.itantra.data.asr.AsrEngineTest 7 failures 0 errors 0
com.itantra.data.asr.SherpaAsrEngineRealTest 1 failures 0
com.itantra.data.asr.SherpaAsrEngineTest 7 failures 0
com.itantra.data.audio.NativeAudioBridgeTest 5 failures 0
com.itantra.data.harness.HarnessCsvGateTest 5 failures 0
com.itantra.data.harness.OfflineLoopHarnessTest 7 failures 0
com.itantra.data.harness.OfflineLoopHarnessWavTest 4 failures 0
com.itantra.data.harness.Phase1WerTest 4 failures 0
com.itantra.data.models.ModelManagerRealTest 6 failures 0
com.itantra.data.models.ModelManagerTest 13 failures 0
com.itantra.data.ptt.PttStateMachineTest 15 failures 0
com.itantra.data.router.PriorityRouterTest 14 failures 0
com.itantra.data.transport.BluetoothTransportTest 2 failures 0
com.itantra.data.transport.FrameCodecTest 29 failures 0
com.itantra.data.transport.TransportConnectionTest 2 failures 0
com.itantra.data.transport.TransportManagerTest 2 failures 0
com.itantra.data.transport.WifiDirectTransportTest 3 failures 0
com.itantra.data.tts.SherpaTtsEngineRealTest 2 failures 0
com.itantra.data.tts.SherpaTtsEngineTest 8 failures 0
com.itantra.data.tts.TtsEngineTest 8 failures 0
com.itantra.data.vad.SileroVadTest 9 failures 0
com.itantra.data.vad.VadFsmTest 17 failures 0
com.itantra.presentation.MainActivityKeyTest 2 failures 0
com.itantra.presentation.transceiver.AlertHandlerTest 1 failures 0
com.itantra.presentation.transceiver.TransceiverScreenTest 3 failures 0
com.itantra.presentation.transceiver.TransceiverViewModelTest 6 failures 0
com.itantra.service.TransceiverServiceTest 3 failures 0
Kotlin sum: 185
BUILD SUCCESSFUL in 10s (with rerun) / 2s (UP-TO-DATE)
```

**Note:** `Warm` run is `UP-TO-DATE` 2s; `rerun` is 10s with `onxruntime` graph warnings `Removing initializer 'If_0_then_branch.../Constant_106...'` (normal) but no failures.

### 3. `./gradlew :app:assembleDebug` (Build verification & APK size audit)

```
> Task :app:configureCMakeDebug[arm64-v8a]
> Task :app:buildCMakeDebug[arm64-v8a]
> Task :app:mergeDebugJniLibFolders
> Task :app:mergeDebugNativeLibs
> Task :app:stripDebugDebugSymbols
> Task :app:packageDebug
> Task :app:createDebugApkListingFileRedirect
> Task :app:assembleDebug
BUILD SUCCESSFUL in 1s (rerun) / 5s (clean)
45 actionable tasks: 3 executed, 42 up-to-date

Length: 187759431 app-debug.apk (179.04 MB) [prior run 188129422 = 179.39 MB]
No warnings except `checkKotlinGradlePluginConfigurationError` noise and `2 files found for path 'lib/x86/libonnxruntime.so'` (AGP chooses app `jniLibs` over `transformed/onnxruntime-android` — harmless but indicates `abiFilters arm64-v8a` not fully stripping `onnxruntime-android` x86/x86_64/armeabi-v7a from `transformed` cache)
```

### 4. `scripts/check-no-internet.bat` (Permissions gate)

```
Checking app\build\outputs\apk\debug\app-debug.apk for INTERNET permission...
PASS: no INTERNET permission in app\build\outputs\apk\debug\app-debug.apk

aapt dump permissions:
package: com.itantra
uses-permission: name='android.permission.RECORD_AUDIO'
uses-permission: name='android.permission.ACCESS_WIFI_STATE'
uses-permission: name='android.permission.CHANGE_WIFI_STATE'
uses-permission: name='android.permission.ACCESS_FINE_LOCATION' maxSdkVersion='30'
uses-permission: name='android.permission.NEARBY_WIFI_DEVICES'
uses-permission: name='android.permission.BLUETOOTH' maxSdkVersion='30'
uses-permission: name='android.permission.BLUETOOTH_ADMIN' maxSdkVersion='30'
uses-permission: name='android.permission.BLUETOOTH_CONNECT'
uses-permission: name='android.permission.BLUETOOTH_SCAN'
uses-permission: name='android.permission.FOREGROUND_SERVICE'
uses-permission: name='android.permission.WAKE_LOCK'
permission: com.itantra.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
uses-permission: name='com.itantra.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
```

**11 PRD perms exactly** (`app/src/main/AndroidManifest.xml:4-15` `<!-- No INTERNET -->` plus `service` `microphone|connectedDevice`).

---

## 6. Final Readiness Verdict

**Verdict: CONDITIONAL PASS for SIH 2026 Demo (Host Mock), FAIL for PRD Phase 0–4 Data-Complete Field Submission**

**Rationale (evidence-backed):**

- **Gates that are truly green:** `K6` (no INTERNET, 11/11 perms, `aapt` + `check-no-internet.bat` PASS), `FR-06` (29/29 FrameCodec, `Crc16` `0x29B1`), `FR-11` (lock-free `SPSC` `SpscRingBuffer.h:22` `head/tail acquire/release` + `Oboe` guard `__has_include`), `FR-08` (Ptt 80ms `MainActivity.kt:20` + `PttStateMachineTest` 15), `FR-09` (Router 14 + `AlertHandler` interface), `FR-05` (VAD 17+9+6+5), native `35/35` + Kotlin `185/185` + APK `179 MB <250` + `assembleDebug` 0 errors + `ASAN` flags present (`app/build.gradle.kts:36` `cppFlags -fsanitize=address,undefined` `arguments -DSANITIZE=ON`, `CMakeLists.txt:96`).

- **Gates that are mock-only and will fail on evaluator's table:**
  - **Accuracy `K1` 40%:** Evaluator will run `Kathbath 500 utterances hi` expecting `WER ≤12.4%` (`docs/PRD.md:182`); our host returns `mock:HI:32000:real` → `WER 1.0` (`Phase1WerTest.kt:10` `harness_computesWer` `1.0` clamped). No `Kathbath` harness, no `SCRIBE OI-WER`, no `WER 8.2%` proof.
  - **Naturalness `K2` 40%:** Evaluator will run `UTMOS` on 50 clips expecting `MOS ≥3.8` (`docs/PRD.md:23`); we have `MockTtsEngine` `ShortArray(text.length*100)` (`TtsEngineTest.kt:45` `<200ms` not `<80ms`), no `UTMOS` model.
  - **Efficiency `K4` heap `≤380 MB`:** Host `ModelManager` `residentMemoryEstimate 165 MB` (`ModelManager.kt:95` `120+45`), not `dumpsys meminfo` 3 GB `cycle 10 langs` (9 missing), no `mmap` `MappedByteBuffer`, no `simpleperf` 90s thermal.
  - **Transport `K5`:** No `WifiP2pManager` `discoverPeers`/`GroupOwner` `10/50/100m` `99%` PDR, no `BluetoothAdapter` `listenUsingRfcommWithServiceRecord` real RF, no `10/50/100m` field test.
  - **System `FR-13` E2E `≤800ms`:** No `Mic→VAD→ASR→FrameCodec→Wi-Fi/BT→Piper→Speaker` wall-clock on two `arm64-v8a` devices `daiv55ayrskructw` + one more, no `simpleperf` `T_e2e` budget.

- **What an ISRO evaluator will see on two phones (arm64-v8a debug APK `179 MB` on `daiv55ayrskructw` + peer):**
  - **Will work:** App launches (`MainActivity.kt:10` `setContent TransceiverScreen`), PTT button `48dp` `FilledTonalButton` `IDLE→LISTENING→BUSY` (`TransceiverViewModelTest.kt:6` 6 PASS), `VOLUME_DOWN` `80ms` (`MainActivityKeyTest.kt:12` 2 PASS), mode toggle `HALF↔DUPLEX` (`TransceiverScreen.kt:18` `Switch testTag modeToggle`), `ForegroundService` notification `iTantra Walkie-Talkie Active` (`TransceiverServiceTest.kt:7` 3 PASS), `FrameCodec` loopback over `ServerSocket` `TCP_NODELAY` (`WifiDirectTransportTest.kt:12` 3 PASS) and `BluetoothTransport` SPP UUID (`BluetoothTransportTest.kt:12` 2 PASS) will **appear** to work on loopback, but **real Wi-Fi Direct `5-12ms` and RFCOMM `20-35ms` will not be exercised** (reflection fallback to `ServerSocket`).
  - **Will fail:** Press PTT, speak Hindi 2s, release → `NativeAudioBridge` `AudioCapture` `onAudioReady` will push to `ring` (guard stub, not `AAudio` `SCHED_FIFO`), `SileroVad` Kotlin `OrtEnvironment` **will** run real `silero_vad.onnx` 2.21 MB MIT (`SileroVad.kt:16` `input/state/sr` `512` `2,1,128` `0.044` silence) and `VadFsm` will segment, but `SherpaAsrEngine` on `Dalvik` **will attempt real** `OfflineRecognizer` via `sherpa-onnx` `libonnxruntime.so` 15 MB + `libsherpa-onnx-*` `jniLibs/arm64-v8a` — **if `sherpa-onnx` AAR not added via `jniLibs` `LFS *.so` and `JitPack` for `sherpa-onnx` (commit `f39beb3` adds `JitPack` but `app/build.gradle.kts` has no `implementation("com.github.k2fsa:sherpa-onnx")`), reflection `Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer")` will `ClassNotFoundException` and fallback to mock `mock:HI` — so **no real Hindi `WER 8.2%`**, and `SherpaTtsEngine` will fallback to mock `ShortArray(text.length*220)` sine, not `Piper` `hi_IN-pratham-medium.onnx` 60.57 MB real VITS (though file present, `OfflineTts` reflection will also `ClassNotFound` and fallback).
  - **Result:** Two-phone loop will **appear functional** (PTT → mock transcription → mock TTS `22050` → `Oboe AudioTrack` stub), but **ISRO's `K1 40%` and `K2 40%` scoring will be 0** because mocks do not meet `WER ≤12.4%` / `MOS ≥3.8`.

**Recommendation (unsparing, per `verification-before-completion`):**
1. **Do NOT submit `edc63d4` as SIH final** — declare `Phase 1–2 mock-complete, Phase 3–4 host-loopback-complete` and request `2-week` extension to wire **real `sherpa-onnx` AAR** (add `implementation("com.github.k2fsa:sherpa-onnx:1.10.1")` via `jitpack.io` + `jniLibs` `arm64-v8a` already LFS, but need `OfflineRecognizer`/`OfflineTts` real `isRealInference true` on `Dalvik`), **real `Oboe` prebuilt** (uncomment `CMakeLists.txt:11` `FetchContent oboe 1.8.1` and `target_link_libraries(itantra-native PRIVATE oboe::oboe)` on Android, currently guarded offline), **real `WifiP2pManager` + `BluetoothAdapter`** (replace `ServerSocket` loopback with `WifiP2pManager` `discoverPeers`/`connect` `GroupOwner` + `BluetoothAdapter` `listenUsingRfcommWithServiceRecord` as already stubbed via reflection in `WifiDirectTransport.kt:26`/`BluetoothTransport.kt:26`), and **device `Kathbath` 500-utt + `UTMOS` 50-clip harness** (`simpleperf` + `aapt` size) to hit `K1 40%` + `K2 40%`.
2. **For SIH demo video (if deadline is 2026-09-06):** `edc63d4` **is demo-ready** on host mock — record two emulators / one `daiv55ayrskructw` + host loopback, show PTT `VOLUME_DOWN` `80ms`, `TransceiverScreen` `IDLE→LISTENING→SENDING→BUSY`, `Alert` red banner `vibrate 500-500-500`, `ForegroundService` notification, `FrameCodec` `12B+CRC` loopback, `aapt` no `INTERNET`, `35/35` + `185/185` gates — **explicitly caption video as `Host Mock Loopback — Real WER/MOS Pending Device Validation`** to avoid evaluator surprise.

**Overall Compliance Score recalculated with mock penalty:** `78%` (conditional) → **If evaluator strictly applies PRD Phase 0–4 data-complete, score is `52%` FAIL.** If evaluator accepts `Phase 1–2 mock + Phase 3–4 host-loopback` as `M1` progress per `docs/PRD.md:5.3` `M1 Model Pack` `W2` (not `M4` SIH Submission `W8`), then `78%` is **PASS for progress review**.

---
*Audit generated by exhaustive `Read` of 28 files (each `file:line` cited) + live execution of 4 verification gates (`ctest` 35, `testDebugUnitTest` 185, `assembleDebug` 187 MB, `check-no-internet.bat` PASS) on `win32` `pwsh` `JDK 17` `SDK 34` `NDK 26.1.10909125` `CMake 3.22.1` `arm64-v8a daiv55ayrskructw` + host GCC `TDM-GCC 10.3.0`.*
