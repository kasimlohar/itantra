# PRD: iTantra — Offline Multilingual Neural Transceiver for Low-Bitrate D2D Links

**Problem Statement ID:** 26173 | **Organization:** ISRO / Department of Space | **Category:** Software | **Theme:** Miscellaneous
**Document Version:** 1.0 | **Date:** 2026-09-03 | **Status:** Implementation-Ready Draft
**Primary Source:** SIH Problem Statement 26173 (ISRO) + `Offline Multilingual Speech Transceiver Architecture.md`
**Target Compliance:** Open-source only, Fully offline, Low/Mid-range Android, 10-language STT/TTS, Walkie-Talkie + Phone modes

---

## 1. Executive Summary

### 1.1 Problem Statement
Vocal audio is data-intensive (256 kbps PCM, 2.4–6 kbps vocoded) and fails over low-bitrate, high-loss tactical/disaster links (HF/VHF, congested Wi-Fi/BT) where audio vocoders produce catastrophic distortion; in alert/distress scenarios transmitted voice must remain inclusive and intelligible even for low-literacy users, yet raw audio cannot be reliably transmitted over <300 bps effective links available on entry-level devices without cellular infrastructure.

### 1.2 Proposed Solution
An offline Android neural transceiver that captures 16 kHz speech, segments utterances via on-device VAD/EOU (Silero VAD v5), transcribes to ultra-compact UTF-8 semantic text (IndicConformer-120M CTC INT8 via sherpa-onnx), frames it into a 12-byte binary protocol, streams it over ad-hoc Wi-Fi Direct (primary) with Bluetooth RFCOMM fallback at <300 bps effective rate, and re-synthesizes intelligible 22.05 kHz speech on the peer device via single-stage Piper VITS / Hear2Read NG (sherpa-onnx/Oboe), operating as a walkie-talkie (PTT half-duplex) or continuous phone (full-duplex) with preemptive non-interruptible alert playback at `STREAM_ALARM` max volume.

### 1.3 Success Criteria (Measurable KPIs — Mapped to SIH Evaluation Weights)

| # | KPI | Target (Threshold / Stretch) | Evaluation Weight | Measurement Method |
|---|-----|------------------------------|-------------------|-------------------|
| K1 | **STT Accuracy (WER / CER)** | WER ≤12.4% per language (threshold), ≤10% avg (stretch); CER + OI-WER reported for Dravidian (Tamil, Telugu, Kannada, Malayalam) | 40% (Accuracy) | Kathbath + FLEURS test sets, 500 utterances/language, SCRIBE OI-WER (`Offline Multilingual Speech Transceiver Architecture.md:316-318`) |
| K2 | **TTS Intelligibility (MOS)** | MOS ≥3.8 (threshold), ≥4.0 (stretch) on 1–5 scale; UTMOS auto-regression Δ <0.2 per CI build | 40% | Double-blind native-speaker panel (n≥10/lang) + NISQA/UTMOS CI gate (`...Architecture.md:335-338`) |
| K3 | **Latency — RTF & E2E Delta** | STT RTF <0.30, TTS RTF <0.20; Total E2E sentence-to-speech delta ≤800 ms (threshold), ≤650 ms nominal (stretch) — budget in §4.1.3 | 20% (Latency) | `simpleperf` + wall-clock: `T_e2e = T_VAD + T_EOU + T_ASR + T_TX + T_TTS + T_out` |
| K4 | **Efficiency — Footprint** | APK (base + 2 languages) ≤250 MB; per-language STT 120–188 MB INT8, TTS 35–55 MB; Idle CPU <3% on Cortex-A55; Stable heap ≤380 MB on 3 GB device; No LMK kill during 10-lang cycle | 20% (Efficiency) | Android Studio Profiler + `adb shell dumpsys meminfo`, 10-language mmap swap test (`...Architecture.md:450-452`) |
| K5 | **D2D Link Resilience** | Wi-Fi Direct 1-way <12 ms, RFCOMM <35 ms; 99% delivery success at 10/50/100 m LoS for 500×60-byte frames; Effective rate <300 bps | 20% (Latency/Efficiency) | Field range test, CRC-16 pass rate, TCP_NODELAY enabled |
| K6 | **Offline & Open-Source Compliance** | 0 network calls during STT/TTS/TX; Zero proprietary voice SDKs; All models CC-BY-4.0/MIT/Apache-2.0; F-Droid/reproducible build | Pass/Fail Gate | Network sandbox (`adb shell` + Charles), LICENSE audit |

> **SIH weights sum to 80% explicit (Efficiency 20 + Accuracy 40 + Latency 20); remaining 20% implied for deployability/demo. K4–K5 gate the deployability score. All KPIs must pass threshold for SIH submission.**

---

## 2. User Experience & Functionality

### 2.1 User Personas

| Persona | Description | Device | Languages | Primary Mode |
|---------|-------------|--------|-----------|--------------|
| **P1 — Field Responder (Primary)** | Disaster/tactical operator in no-cellular zone; needs hands-free, loud, non-literate-inclusive alerts | Entry 2–3 GB RAM, Helio G36/Unisoc T606, Android 12–13 Go | Hindi, English + regional | PTT Walkie-Talkie + Alert |
| **P2 — Community Volunteer** | Rural health/ASHA worker relaying distress messages between villages | Mid-range Snapdragon 680, 6 GB RAM | Bengali, Odia, Marathi, Gujarati | Phone (continuous) |
| **P3 — ISRO Evaluator / Lab Tester** | Validates loop with two phones: Phone-A (STT→TX) and Phone-B (RX→TTS) per PS requirement | Any two low/mid devices | All 10 | Verification Loop |
| **P4 — Embedded Node Operator (Future)** | ESP32-S3/Field node paired via RFCOMM as TX/RX peer | Embedded → Phone | Subset | Interop |

**Accessibility note:** P1/P2 include low-literacy users — voice is primary; text is never required to be read. UI must be fully operable via icon + color + haptics + voice prompt.

### 2.2 User Stories & Acceptance Criteria

#### US-01 — Push-to-Talk Transmission (Half-Duplex)
> As a **field responder (P1)** I want to **press-and-hold PTT to speak and release to send** so that **the channel is floor-controlled and never collides**.

**Acceptance Criteria:**
- AC-01.1: PTT bindable to on-screen circular button (≥48 dp, `Material 3` `FilledTonalButton`) **and** hardware `VOLUME_DOWN` (`KeyEvent.KEYCODE_VOLUME_DOWN`) — long-press = floor request, release = floor release. Debounce 80 ms.
- AC-01.2: Floor arbitration via 12-byte control frame (`FLAG_PTT=1/0`, `payload_len=0`) in `Offline Multilingual Speech Transceiver Architecture.md:172-189`; receiver mutes mic and shows `Receiving — Ch Bis y` within 50 ms of grant.
- AC-01.3: While floor held, VAD buffers speech; on release, finalized sentence (EOU ≥450 ms silence) dispatched as single frame (`FLAG_STREAM=0`). Partial streaming (`FLAG_STREAM=1`) allowed but disabled by default for half-duplex clarity.
- AC-01.4: UI states: `IDLE (grey) → LISTENING (pulsing green, VAD>0.6) → SENDING (blue, spinner) → SENT (check) → IDLE`. Haptic tick on floor grant/deny.
- AC-01.5: If peer holds floor, local PTT press yields `Channel Busy` toast + red flash, no mic capture.

#### US-02 — Continuous Phone Mode
> As a **volunteer (P2)** I want to **toggle off PTT so it works like a phone** so that **both sides can speak without floor control**.

**Acceptance Criteria:**
- AC-02.1: Mode toggle in top app bar (Compose `Switch` with icon `WalkieTalkie ↔ Call`); state persisted in `DataStore` and encoded in header `Byte 2 bits 4–7` (0=half-duplex, 1=duplex).
- AC-02.2: In duplex mode, VAD continuously segments and auto-sends each finalized sentence without PTT hold; simultaneous TX/RX allowed (two sockets or interleaved).
- AC-02.3: No floor control frames emitted in duplex; `FLAG_PTT` ignored. Latency unchanged (≤650 ms E2E).

#### US-03 — Voice-to-Text Triggered on Pause/Stoppage
> As a **speaker (P1/P2)** I want **sentences formed automatically after I pause** so that **I don't manually mark utterance boundaries**.

**Acceptance Criteria:**
- AC-03.1: Silero VAD v5 evaluates 30 ms 16 kHz mono frames, probability thresholds `p≥0.6` onset (3 consecutive frames), `p≥0.35` sustain, hangover 450–600 ms (configurable, default 450 ms PTT, 550 ms phone) (`...Architecture.md:138-149`).
- AC-03.2: Pre-roll 200 ms circular buffer retains initial unvoiced consonants; trailing silence trimmed before ASR dispatch.
- AC-03.3: EOU segmentation accuracy: sentence boundary fires within 480–540 ms of true 500 ms trailing pause in 95% of 100 calibration samples (clean 30 dB SNR and noisy 10 dB SNR) (`...Architecture.md:446-448`).
- AC-03.4: CPU in Idle state: only VAD runs on Little cores (Cortex-A55), <1.5 ms/30 ms window, <4 MB RAM, <3% CPU; ASR threads dormant until Speech Detected.

#### US-04 — Ultra-Low-Bitrate Text Transport
> As a **sender (P3)** I want **text instantly streamed over Wi-Fi/Bluetooth to the peer** so that **a 15-word sentence uses ~60 bytes (~160 bps)**.

**Acceptance Criteria:**
- AC-04.1: Framing protocol exactly 12-byte header + UTF-8 payload + CRC-16-CCITT (`...Architecture.md:172-182`):
  - B0-1 Magic `0x49 0x54` ("IT")
  - B2 Ver/Mode (ver 0x01 + mode bit)
  - B3 Flags (bit7 ALERT, bit6 STREAM, bit5 PTT_STATE)
  - B4 SrcLang 0x01–0x0A, B5 DstLang 0x01–0x0A (`Hindi=01, Gujarati=02, Marathi=03, Kannada=04, Malayalam=05, Tamil=06, Telugu=07, Odia=08, Bengali=09, English=0A`)
  - B6-7 SeqId BE uint16, B8-9 PayloadLen BE uint16 (1–2048)
  - Variable UTF-8 (40–120 bytes typical)
  - Trail CRC-16-CCITT over header+payload; drop & NACK on mismatch.
- AC-04.2: Transport priority: **Wi-Fi Direct (P2P TCP)** primary (5–12 ms, 150–200 m), **RFCOMM SPP** fallback (20–35 ms, 20–30 m) (`...Architecture.md:156-168`). BLE L2CAP CoC optional for nRF/ESP32-C3.
- AC-04.3: Auto-discovery via `WifiP2pManager` with GO negotiation 2.5–5.0 s; fallback RFCOMM discovery 1.2–2.5 s; socket `TCP_NODELAY` enabled, Nagle off.
- AC-04.4: No internet/GMS dependency; pairing via system P2P dialog + in-app `Nearby` fallback denied (proprietary). Manual IP fallback if GO fails.

#### US-05 — Text-to-Speech Voice Note & Alert Playback
> As a **receiver (P1)** I want **text auto-spoken as voice note, and alert messages at max non-interruptible volume** so that **I never miss distress calls**.

**Acceptance Criteria:**
- AC-05.1: On frame RX, `FrameParser` validates CRC → `PriorityRouter` checks `FLAG_ALERT`. If 0: enqueue FIFO voice-note, synthesize via Piper VITS (22.05 kHz) → `Oboe AudioTrack` low-latency buffer (20 ms) → playback queue with waveform + transcript card.
- AC-05.2: If `FLAG_ALERT=1`: **preempt** all playback, acquire `AudioManager.STREAM_ALARM` focus, `setStreamVolume(max)`, dismiss duck, route to TTS synthesis first buffer <80 ms, play at max until completion; non-interruptible by new standard messages or transient focus loss (`AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`).
- AC-05.3: TTS per-language voice: Piper medium 35–60 MB ONNX + `espeak-ng` phonemizer → ONNX Runtime; Hear2Read NG voices for Bengali/Kannada/Malayalam/Marathi/Odia/Tamil/Telugu (`...Architecture.md:102-111`). First-buffer dispatch <80 ms, RTF 0.08–0.18 on A55.
- AC-05.4: Playback UI: queued items show `▸ 00:04` duration, source lang badge, time, replay button. Alert items: red banner, alarm icon, persistent notification, vibration pattern `500-500-500 ms`.
- AC-05.5: On RX synthesis failure (missing voice model), show transcript-only fallback + download prompt; never silently drop.

#### US-06 — Multilingual Source & Destination Language Selection
> As a **user (P1/P2)** I want to **choose my speaking language and peer's listening language** so that **10 languages are supported offline**.

**Acceptance Criteria:**
- AC-06.1: Supported set (SIH mandate): `Hindi (hi), Gujarati (gu), Marathi (mr), Kannada (kn), Malayalam (ml), Tamil (ta), Telugu (te), Odia (or), Bengali (bn), English (en)` — UI dropdown with native script + ISO badge.
- AC-06.2: Only **active SrcLang STT + DstLang TTS** resident in RAM; LRU mmap swap <180 ms from flash (`...Architecture.md:365`). 10-lang simultaneous hold prohibited (would exceed 1.5 GB, LMK kill).
- AC-06.3: Model assets side-loaded in `assets/models/{stt|tts|vad}/{lang}/` via `mmap`; no runtime download. Update via Play `Asset Delivery` or manual `adb push`.
- AC-06.4: Cross-language TX allowed (SrcLang≠DstLang) but v1 plays text verbatim in destination voice phonetics (no NMT); translation explicitly V2 (see Non-Goals).

#### US-07 — Connection & Device Management
> As a **user (P3)** I want to **discover and pair peer phone/embedded node without router** so that **two phones loop-verify STT→TX→TTS**.

**Acceptance Criteria:**
- AC-07.1: Discovery screen lists `Wi-Fi Direct` peers (via `WifiP2pDeviceList`) + `Bluetooth` bonded devices (RFCOMM UUID `00001101-0000-1000-8000-00805F9B34FB`); status `Scanning → Found → Connecting → Connected → Encrypted`.
- AC-07.2: Connection state machine visible: GO negotiation, DHCP, socket open, heartbeat (30 s keep-alive). Auto-reconnect with exponential backoff 1/2/4/8 s.
- AC-07.3: Embedded interop: Wi-Fi SoftAP (ESP32-S3) or HC-05 SPP auto-detected if peer type = embedded.

#### US-08 — Offline Guarantees & Privacy
> As a **field operator (P1)** I want **everything to work with no internet and no data leaving the link** so that **mission is resilient and private**.

**Acceptance Criteria:**
- AC-08.1: Airplane-mode functional test: VAD + ASR + TTS + D2D loop passes with radios on, cellular/Wi-Fi internet off.
- AC-08.2: No telemetry, no cloud endpoints in manifest; `INTERNET` permission not requested (only `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` for P2P discovery, `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `WAKE_LOCK`).
- AC-08.3: Transcripts stored locally in Room DB encrypted at rest (SQLCipher optional); auto-expiry 7 days unless starred.

### 2.3 Non-Goals (Explicitly Out of Scope for V1)

- **No internet/cloud APIs** — no hosted STT/TTS, no Firebase, no Nearby Connections (GMS dependency), no Bhashini cloud endpoints.
- **No multi-hop mesh routing** — star topology only (single GO); relay across ≥3 hops is V2 (`...Architecture.md:407`).
- **No speech translation (NMT)** — text forwarded verbatim; Indic→Indic translation (SeamlessM4T/BhasaAnuvaad) is V1.1 research candidate only.
- **No proprietary/closed SDKs** — no closed voice-activation SDKs, no MMS-TTS CC-BY-NC 4.0 weights in release build (benchmark only, per `...Architecture.md:112-120`).
- **No server-side components** — no backend, auth, or user accounts.
- **No 600M/Whisper large models** — 600M 620 MB INT8 and Whisper >38% WER on Dravidian excluded from mobile path (`...Architecture.md:56-74`).
- **No iOS port** — Android only for SIH; design must not preclude future KMP but not required.
- **No always-on hotword** — PTT or VAD-gated listening; no "Hey iTantra" wake-word to save power.

---

## 3. AI System Requirements

### 3.1 Tool / Model Requirements

| Subsystem | Model / Engine | Runtime | Size / RAM | License | Constraints & Source |
|-----------|----------------|---------|-----------|---------|---------------------|
| **VAD / EOU** | **Silero VAD v5** — dilated CNN+LSTM, 30 ms frames, 16 kHz mono | ONNX Runtime C++ / sherpa-onnx VAD API (`sherpa-onnx:1.3 vad/index.html`) | 1.8 MB file, <4 MB RAM, <1.5 ms/frame on A55 | MIT | Offline, pre-roll 200 ms, hangover 450 ms (`...Architecture.md:137-149`) |
| **STT (Primary)** | **IndicConformer-120M CTC** per-lang — 17 blocks, d=512, 8 heads, 8× subsampling, 80-bin log-mel 25 ms/10 ms | sherpa-onnx `OfflineRecognizer.from_nemo_ctc` (ONNX Runtime Mobile) | FP32 470–493 MB → INT8 120–188 MB, ~240 MB active RAM, RTF 0.12–0.28, <120 ms/2.5 s utt | CC-BY-4.0 | Load per-lang BPE vocab `tokens.txt` (blank=vocab.size), dynamic quantization `QuantType.QInt8 per_channel reduce_range` (`...Architecture.md:34-44`) |
| **STT (North-Central Cluster)** | **Meetsync Multi-Indic Conformer** shared 5633-token dict (hi/bn/gu/mr/kn + as/bd/ks) | same | FP32 470 MB → INT8 188 MB, RTF 0.10–0.30 | MIT | Reduces APK for hi/bn/gu/mr subset; supplement with per-lang Tamil/Te/Mal/Or (`...Architecture.md:46-54`) |
| **STT (English fallback)** | Whisper-Tiny INT8 (39 M) dedicated en path | whisper.cpp / sherpa-onnx | 42 MB INT8, RTF ~0.35 (Snap680), WER 7.2% en-IN | MIT | Only for English; not for Indic (WER>38% Dravidian) (`...Architecture.md:66-74`) |
| **TTS (Primary)** | **Piper VITS single-stage** — VAE+flows+adversarial vocoder, 22.05 kHz, `espeak-ng` phonemizer | Piper native C++ + sherpa-onnx TTS / ONNX Runtime | 35–55 MB/voice, ~65 MB RAM, RTF 0.08–0.18, first buffer <80 ms | MIT | Voices: `hi_IN-rohan/pratham-medium` + Hear2Read NG extractions (45 MB/voice) (`...Architecture.md:92-111`) |
| **TTS (Alt)** | Hear2Read NG VITS (Piper-compatible) — 12-lang Indic | same | ~45 MB/voice, RTF 0.12 | Apache-2.0 | Extract `.onnx/.json` from NVDA add-on bundles |
| **Benchmark-only** | Meta MMS-TTS VITS 109 MB→38 MB INT8 | ONNX | MOS flat prosody | CC-BY-NC 4.0 **NC Flag** | **NOT shippable** — benchmark only for SIH audit risk |
| **Excluded from mobile** | IndicConformer-600M (2.4 GB→620 MB, RTF>1, 1800 ms), Indic-TTS FastPitch+HiFi-GAN (180 MB/lang, 350–550 ms) | — | — | MIT | Tablet/command node only |

**Quantization Recipe (Required):**
```python
from onnxruntime.quantization import quantize_dynamic, QuantType
quantize_dynamic(input_onnx, output_int8, weight_type=QuantType.QInt8,
                 op_types_to_quantize=["MatMul","Gemm","Conv"],
                 per_channel=True, reduce_range=True)
```
Accept ≤0.5–0.8% WER degradation vs FP32. Verify with Kathbath per-lang before bundling.

**On-Disk Layout (APK `assets/` or `filesDir/mmap`):**
```
models/
  vad/silero_v5.onnx
  stt/hi/indic_conformer_hi_int8.onnx + tokens.txt + vocab.json
  stt/gu/...  (10 langs = ~1.2–1.9 GB uncompressed; deliver via Play Asset Pack / split APK)
  tts/hi/hi_IN-rohan-medium.onnx + .onnx.json
  tts/ta/ta_IN-...onnx  (10 voices = ~350–550 MB)
```

### 3.2 Evaluation Strategy

#### 3.2.1 STT Accuracy — WER / CER / OI-WER

- **Datasets:** Primary **Kathbath** (1684 h, 12 langs, 1200+ districts) (`...Architecture.md:291-297`); cross-lingual control **FLEURS** (102 langs, n-way parallel) (`...Architecture.md:299-303`); TTS reference **IndicTTS** (27 h/lang studio, 48 kHz) (`...Architecture.md:305-307`).
- **Metrics:**
  - `WER = (S+D+I)/N` where S/D/I = substitutions/deletions/insertions.
  - CER alongside WER; **OI-WER via SCRIBE** for agglutinative `ta/te/kn/ml` where sandhi merges inflate naive WER (`...Architecture.md:317`).
  - RTF = `T_process / T_audio` ; require RTF <1.0, target <0.3 (`...Architecture.md:313-314`).
- **Acceptance (per Table 1 of research):** `hi 8.2%, bn 9.1%, ta 9.8%, te 10.4%, mr 10.1%, gu 11.3%, kn 10.9%, ml 11.7%, or 12.4%` clean WER on INT8; allow +2 pp noisy (10 dB SNR + 8 kHz bandpass + GSM simulation).
- **Harness:** Desktop Python `sherpa-onnx` harness → Kathbath test split → per-lang WER/CER CSV; CI fails if WER regresses >1 pp or RTF >0.35 on Snap680 reference.
- **Fine-tuning plan (if needed):** Noise augmentation (vehicle/machinery, bandpass, GSM codec) on Kathbath/IndicVoices; prosodic retune on IndicTTS 22.05 kHz subsets for or/gu/kn (`...Architecture.md:409-412`).

#### 3.2.2 TTS Naturalness — MOS + Automated Proxy

- **Human:** Double-blind MOS 1–5 (5=human) with n≥10 natives/lang, 20 sentences/lang from IndicTTS held-out; target ≥3.8, Piper→Hear2Read expected 3.8–4.1.
- **Automated:** UTMOS/NISQA per CI build on 50 synthetic clips; gate fail if predicted MOS drops >0.2 vs baseline.
- **RTF & Latency:** First audio chunk <80 ms; full-sentence RTF 0.08–0.18 on A55; measured via `AudioTrack` timestamp.

#### 3.2.3 E2E System Latency Budget (Snap680 / Wi-Fi Direct nominal)

| Stage | ms | % | Optimization |
|-------|----|---|--------------|
| Acoustic Ingest & VAD Framing | 30 | 4.6 | 30 ms Oboe native ring buffer |
| Pause Hangover (EOU) | 450 | 69.2 | Shorten to 350 ms in PTT urgent |
| Conformer CTC INT8 Inference | 100 | 15.4 | 2 threads on big cores, 8× subsampling |
| D2D TX (Wi-Fi Direct) | 8 | 1.2 | `TCP_NODELAY`, non-blocking NIO |
| Frame Parse & Routing | 2 | 0.3 | Zero-copy byte buffer |
| Piper VITS Synthesis | 40 | 6.2 | Streaming chunked inference |
| AudioTrack Buffering | 20 | 3.1 | AAudio/Oboe low-latency |
| **Total** | **650** | 100 | **Sub-second; SLO ≤800 ms P95** |

Budget source `...Architecture.md:322-333`.

#### 3.2.4 Efficiency — Memory / Power / Thermal

- **LMK gate:** Cycle all 10 STT+10 TTS via mmap LRU on 3 GB entry device → heap ≤380 MB, zero kills, swap <180 ms (`...Architecture.md:450-452`).
- **Idle power:** ForegroundService + `PARTIAL_WAKE_LOCK` on Little cores only VAD → `adb shell dumpsys batterystats` <30 mA idle; thermal throttling test: sustained 90 s at 2 threads → no >50% freq drop, RTF stays <1.0.
- **APK size:** Base APK ≤80 MB (sherpa-onnx .so ~15 MB + Oboe + Compose); per-lang packs via Play Asset Delivery; full 10-lang bundle noted as 1.5–2.0 GB (not required for SIH demo — ship 2-lang demo + downloadable packs).

#### 3.2.5 Link Resilience

- TX 500 sentences (<256 B) at 10/50/100 m LoS over Wi-Fi Direct + RFCOMM; success ≥99%, 1-way <25 ms, effective <300 bps (`...Architecture.md:454-456`).

---

## 4. Technical Specifications

### 4.1 Architecture Overview

#### 4.1.1 Component Diagram (Layers & Seams)

```
+------------------------------------------------------------------+
|                      Android Application Layer                     |
|  Compose UI ↔ PTT State Controller ↔ Priority AudioTrack Router  |
|  MVI ViewModels (Hilt) • Material 3 • Accessibility (48dp, RTL)  |
+------------------------------|------------------------------------+
                               |
+------------------------------|------------------------------------+
|                     Core C++ ML Engine (sherpa-onnx)             |
|  Silero VAD v5 (30ms) → IndicConformer CTC INT8 → Piper VITS    |
|  NDK r26 • CMake • arm64-v8a • Oboe (AAudio/OpenSL ES)          |
+------------------------------|------------------------------------+
                               |
+------------------------------|------------------------------------+
|                    D2D Transport Engine (Kotlin/NIO)             |
|           iTantra 12B Header + UTF-8 + CRC-16 Framing            |
|     Wi-Fi Direct P2P (TCP)  ⇄  Bluetooth RFCOMM (SPP) Fallback  |
+------------------------------------------------------------------+
```

**Module Seams (Clean Architecture + Depth):**

| Module | Interface Is Test Surface | Depth Reasoning |
|--------|---------------------------|-----------------|
| `audio-io` (C++ Oboe) | `AudioCapture::RingBuffer { push(PCM16), pop(30ms), preRoll(200ms) }` | Deep: hides AAudio/OpenSL ES, GC-free, lock-free ring; deletion concentrates JNI/buffer bugs |
| `vad-fsm` (C++) | `VadFsm::onFrame(PCM) -> State{Idle,Speaking,Pause,EOU}` | Deep: state machine with thresholds 0.6/0.35, hangover 450 ms; locality for pause tuning |
| `asr-engine` (C++ via sherpa-onnx) | `AsrEngine::transcribe(PCM utt, Lang)->Text` | Adapter over ONNX Runtime; mmap load, 2-thread pin, log-mel 80-bin extraction |
| `tts-engine` (C++ via Piper) | `TtsEngine::synthesize(Text, Lang)->PCM 22kHz` | Adapter; espeak-ng phonemize + streaming VITS; first-chunk <80 ms |
| `transport` (Kotlin) | `D2dTransport::send(Frame)`, `onFrameReceived(Frame)` | Deep: dual-bearer, framing, CRC, PTT state, TCP_NODELAY; hides WifiP2pManager + BluetoothSocket |
| `protocol` (Kotlin/C++) | `FrameCodec::encode/decode` | Zero-copy 12B header + CRC-16-CCITT; deletion concentrates framing bugs |
| `priority-router` | `Router::route(Frame)->Queue{standard, alert}` | Deep: alert preemption, STREAM_ALARM, FIFO vs preempt |
| `app` (Compose) | MVI `Intent → State (data class) → Effect` via `ViewModel` + `StateFlow` | Shallow now — deepen via `TransceiverViewModel` hiding PTT + connection + TTS queue |

**Data Flow (Tx path) — `...Architecture.md:384-391`:**
`[Mic 16kHz PCM via Oboe] → [RingBuffer 200ms pre-roll] → [Silero VAD 30ms] → FSM {Idle→Speaking→Pause→EOU} → [PCM utterance trimmed] → [80-bin log-mel] → [IndicConformer CTC INT8] → CTC greedy decode → UTF-8 Text → [FrameCodec 12B+CRC] → [Wi-Fi Direct / RFCOMM TCP_NODELAY] → (air, <300 bps) → [FrameParser+CRC] → [PriorityRouter] → [Piper VITS 22kHz] → [Oboe AudioTrack → Speaker]`

#### 4.1.2 Project Structure (Enforced)

```
app/
├── build.gradle.kts            # NDK, CMake, abiFilters arm64-v8a, packaging
├── src/main/
│   ├── cpp/                    # Native engine
│   │   ├── CMakeLists.txt      # sherpa-onnx, onnxruntime, oboe, espeak-ng
│   │   ├── audio/  audio_capture.cpp  ring_buffer.h  oboe_stream.cpp
│   │   ├── vad/    silero_vad.cpp  vad_fsm.cpp  vad_fsm.h
│   │   ├── asr/    asr_engine.cpp  mel_features.cpp
│   │   └── tts/    tts_engine.cpp  piper_adapter.cpp
│   ├── java/com/itantra/
│   │   ├── di/                 # Hilt modules: AsrModule, TtsModule, TransportModule
│   │   ├── data/
│   │   │   ├── local/ Room (Transcript DB) + DataStore (Prefs)
│   │   │   ├── models/ ModelManager (mmap, LRU swap, <180ms)
│   │   │   └── transport/ WifiDirectTransport, BluetoothTransport, FrameCodec, Crc16
│   │   ├── domain/
│   │   │   ├── model/ Language(10), Frame, TransmitMode, AlertPriority
│   │   │   └── usecase/ TranscribeUseCase, SynthesizeUseCase, SendFrameUseCase
│   │   └── presentation/
│   │       ├── transceiver/ TransceiverViewModel (MVI), TransceiverScreen (Compose)
│   │       ├── connection/ ConnectionViewModel, DiscoveryScreen
│   │       ├── settings/ LanguageSettingsScreen
│   │       └── theme/ Material3 tokens, typography (Indic script safe)
│   └── assets/models/  (or Play Asset Packs)
└── src/test|androidTest/       # TDD: unit (JUnit) + instrumented (Espresso/Compose Test)
```

**Build Constraints:**
- `minSdk 24` (NDK Oboe AAudio), `targetSdk 34`, `compileSdk 34`, Kotlin 1.9+, Compose BOM 2024+, `AGP 8.x`, `NDK r26`, `CMake 3.22+`.
- Native `.so` <15 MB sherpa-onnx + oboe; `arm64-v8a` required, `armeabi-v7a` optional stretch.
- Compiler: `-Wall -Wextra -Wpedantic -Werror`, `-O2`, `-fvisibility=hidden`; Sanitizers `AddressSanitizer` + `UBSanitizer` in `debug` (`cpp-pro:references/build-tooling.md`).

#### 4.1.3 Concurrency & Threading Model

- **Audio thread:** Oboe callback (real-time, `SCHED_FIFO` priority) → lock-free ring → VAD on Little cores only; **never** on JVM heap.
- **ASR thread:** Pinned to 2× big cores (`num_threads=2`), `std::thread` + `onnxruntime::SessionOptions.intraOpNumThreads=2`; wake only on EOU.
- **Transport threads:** Kotlin `Dispatchers.IO` + `java.nio.channels.SocketChannel` non-blocking; heartbeat 30 s.
- **UI thread:** `Dispatchers.Main` only for Compose recomposition; `StateFlow` single immutable state (`android-development: MVI`).

### 4.2 Integration Points

| Integration | API / Library | Usage | Offline Guarantee |
|-------------|---------------|-------|-------------------|
| **ML Runtime** | `sherpa-onnx 1.10+` (ONNX Runtime Mobile) + `onnxruntime` custom ops | ASR/ VAD / TTS unified `.so` <15 MB; JNI `com.k2fsa.sherpa.onnx.*` | Yes — no network |
| **Audio I/O** | `Google Oboe 1.8+` (AAudio + OpenSL ES fallback) | 16 kHz mono capture, 22.05 kHz TTS output, <30 ms buffering | Yes |
| **Wi-Fi P2P** | `android.net.wifi.p2p.WifiP2pManager`, `WifiManager.createWifiLock(HIGH_PERF)` | Discovery, GO negotiation, `ServerSocket`/`Socket` TCP, 250 Mbps/200 m | Yes (ad-hoc, no AP) |
| **Bluetooth** | `android.bluetooth.BluetoothAdapter`, `BluetoothSocket` RFCOMM SPP (UUID SPP) + `BluetoothLe` L2CAP CoC (API 29+) | Fallback 1.2–2 Mbps/30 m; BLE CoC 8–18 mA | Yes |
| **Tunneling** | `espeak-ng` (phonemizer for Piper) | Grapheme→phoneme for Indic scripts | Yes (bundled data) |
| **Persistence** | `Room 2.6+` + `SQLCipher` (optional) + `DataStore Preferences` | Transcripts, queue, settings; encryption at rest | Yes (local file) |
| **DI** | `Hilt 2.50+` | Scoped singletons for engines, transports | — |
| **DB/Auth** | **None** — no remote DB, no auth; device-local only | — | — |

**Permissions (Manifest — exact):**
```xml
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
<!-- No INTERNET -->
```

### 4.3 Security & Privacy

- **Threat Model:** Adversary on open Wi-Fi Direct/BT (eavesdrop/spoof), lost device, malicious payload. No cloud threat (offline).
- **Controls:**
  - **Transport trust:** P2P `WPS` + user-confirmed pairing; optional PSK in header reserved bits (V1.1: AES-128-GCM per-frame with pre-shared 6-digit PIN; V1 stores PIN in `EncryptedSharedPreferences`).
  - **Integrity:** CRC-16-CCITT mandatory; drop corrupt frames; sequence id detects replay/loss (no ACK in V1, alert frames retransmit 3× with 100 ms backoff).
  - **Local data:** Room DB encrypted (`SQLCipher` if ISRO requires); `MODE_PRIVATE` files; transcript auto-purge 7 days; share/export requires explicit user action.
  - **Permissions:** Least-privilege (no `INTERNET`, no `READ_CONTACTS`); location only for P2P discovery, gated with rationale dialog; never background location.
  - **Compliance:** All deps MIT/Apache-2.0/CC-BY-4.0; SBOM generated (`./gradlew cyclonedxBom`); license audit flags MMS-TTS NC as blocked; `INTERNET` absence verified in CI (`aapt dump permissions`).
  - **No telemetry/PII exfiltration:** Network security config blocks cleartext; CI `StrictMode` + `adb shell netstat` zero egress test in airplane mode.

### 4.4 Functional Requirements (Numbered — Testable)

| ID | Requirement | Priority | Verification |
|----|-------------|----------|--------------|
| FR-01 | App runs on **2 GB/3 GB Go** (Helio G36) and **6 GB** (Snap680) without LMK kill | P0 | `...Architecture.md:344-360` device matrix, mmap test |
| FR-02 | Single APK supports 10 languages; only active pair resident via mmap LRU <180 ms swap | P0 | ModelManager unit + device perf |
| FR-03 | Offline STT for each language with WER per §3.2.1; RTF <0.30 | P0 | Kathbath harness |
| FR-04 | Offline TTS 22.05 kHz, MOS ≥3.8, RTF <0.20, first chunk <80 ms | P0 | MOS + UTMOS CI |
| FR-05 | VAD Silero v5 30 ms, Idle <1.5 ms/frame, EOU 450 ms (PTT) / 550 ms (phone) ±60 ms | P0 | EOU calibration suite (100 samples) |
| FR-06 | 12-byte framing + CRC-16 as §2.2 US-04 AC-04.1; payload 1–2048 B UTF-8 | P0 | FrameCodec TDD (RED→GREEN) |
| FR-07 | Wi-Fi Direct primary (5–12 ms), RFCOMM fallback (20–35 ms), auto-switch | P0 | Transport integration test, field range |
| FR-08 | PTT half-duplex floor arbitration (press=1, release=0) + phone duplex toggle | P0 | PTT state machine test, KeyEvent |
| FR-09 | Alert `FLAG_ALERT=1` preempts, `STREAM_ALARM max × non-interruptible` until done | P0 | PriorityRouter test + audio focus |
| FR-10 | ForegroundService + PARTIAL_WAKE_LOCK for always-on listening; notification `Ongoing` | P0 | Battery/meas, Doze exemption |
| FR-11 | Native audio via Oboe, GC-free, lock-free ring, no underruns on 90 s run | P0 | simpleperf, underrun counter =0 |
| FR-12 | INT8 quantization per recipe, ≤0.8 pp WER loss | P0 | Quant regression test |
| FR-13 | Two-phone loop verification: Phone-A STT→Phone-B TTS ≤800 ms E2E | P0 | E2E instrumented test (2 devices) |

### 4.5 Non-Functional Requirements

| ID | NFR | Target | Rationale |
|----|-----|--------|-----------|
| NFR-01 | **Cold start** | App launch→ready <2.5 s mid, <4.0 s entry (with default lang loaded) | First responder experience |
| NFR-02 | **RAM ceiling** | ≤380 MB native heap stable on 3 GB (≤1.8 GB on 6 GB) | LMK avoidance |
| NFR-03 | **Storage** | Base APK ≤80 MB + per-lang packs; full 10-lang on 64 GB eMMC feasible (seq read 180 MB/s) | Go edition 32 GB |
| NFR-04 | **Thermal** | 2 threads big cores only; no throttling >50% for 90 s sustained | A55/A53 thermal limits |
| NFR-05 | **Accessibility** | TalkBack labels, 48 dp targets, 1.3× font scale, RTL mirroring, contrast AA | Low-literacy inclusive |
| NFR-06 | **Reliability** | 99% frame delivery at ≤100 m LoS, CRC fail <0.5% | Disaster link |
| NFR-07 | **Maintainability** | Clean Architecture layers, modules <200 LOC/class, 10 methods/class (android-development guideline), Hilt scoping | Evaluator code review |
| NFR-08 | **Testability** | TDD RED→GREEN, interface-is-test-surface, 80% domain coverage, sanitizers clean | Architecture deepening |

### 4.6 UI/UX Specification (Jetpack Compose — `android-compose-foundations`)

**Design System:** Material 3 `MaterialTheme`, dynamic color off (high-contrast tactical palette), `Typography` with `Noto Sans` + per-script `Noto Sans Devanagari/Bengali/Tamil/etc.` to ensure Indic glyph coverage; `Icons.Filled` + `Icons.Extended` for walkie/alert/call.

**Screens (4 + Dialogs):**

1. **TransceiverScreen (Primary)** — `Scaffold` + `TopAppBar` (Mode toggle + Connection dot + Language badges)
   - Center: PTT button (`300 dp` circle, `Modifier.combinedClickable`, `hapticFeedback`), mic VU meter (`Canvas` animated by VAD prob), transcript stream (`LazyColumn` with `stable` `TranscriptItem` keys).
   - Bottom: Playback queue (`HorizontalPager` or `LazyRow` of audio cards), Alert banner (red `Card` with `STREAM_ALARM` icon).
   - States from `TransceiverViewModel: StateFlow<TransceiverState>` (MVI: `Intent {PressPtt, ReleasePtt, ToggleMode, SetLangs, Play, Star}`).

2. **DiscoveryScreen** — Device list (`LazyColumn` with `WifiP2pDevice` + `BluetoothDevice`), scan FAB, `GO` badge, RSSI, connect button → navigates via `Navigation Compose` (`transceiver/{deviceId}`).

3. **LanguageSettingsScreen** — Two dropdowns (Src/Dst), model pack status (`Downloaded / 120 MB / Update`), swap button.

4. **HistoryScreen** — Room-backed list with search, star filter, export (share sheet) — not primary but required for transcript audit.

**Compose Guardrails (per skill):**
- Stable state: `@Stable data class TransceiverState(val isPttPressed:Boolean, val mode:Mode, val srcLang:Lang, val dstLang:Lang, val vadProb:Float, val queue:List<PlaybackItem>)` — no unstable `List<Any>`.
- No business logic in `@Composable`; ViewModel only.
- Modifiers: `Modifier.fillMaxWidth().padding(16.dp)` before `clip`; no fixed dimensions that break localization (use `weight`, `wrapContentSize`).
- Previews: `@Preview(showBackground=true, fontScale=1.3, widthDp=320)` for narrow + long Tamil string.
- Accessibility: `Modifier.semantics { contentDescription = "Press to talk" }`, `touchTarget 48dp`.

**Navigation:** `Navigation Compose` graph `discovery → transceiver → settings`; deep-link `itantra://connect?peerId=` (optional).

---

## 5. Risks & Roadmap

### 5.1 Technical Risks & Mitigations

| # | Risk | Likelihood | Impact | Mitigation | Owner | Trigger Metric |
|---|------|------------|--------|------------|-------|----------------|
| R1 | **LMK kills on 2 GB Go** (all langs = 1.5 GB) | High | P0 | Only 2 models resident + `mmap` + LRU; test on Helio G36 Go; `onTrimMemory` evict | Native | heap >380 MB |
| R2 | **Thermal throttling → RTF>1.0** | Med | P0 | Pin `num_threads=2` on big cores only; throttle detection via `simpleperf`; reduce EOU to 350 ms only for PTT urgent | Native | CPU >85% 30 s |
| R3 | **GC-induced audio underruns** | Med | P0 | Entire audio/VAD in C++ Oboe (no JVM alloc); lock-free ring; `AddressSanitizer`/`UBSanitizer` clean | Native | underrun >0/90 s |
| R4 | **Dravidian WER inflation** (sandhi, BPE) | High | P1 | Report CER+OI-WER (SCRIBE), orthographic normalization pre-score; prosodic fine-tune on IndicTTS for kn/or/gu/ta | ML | WER >12.4% ml/or |
| R5 | **MMS-TTS NC license trap** | Med | P0 | Ship Piper/Hear2Read MIT/Apache-2.0 only; NC weights blocked in `LICENSES.md` + CI license scan | PM | dependency audit fail |
| R6 | **Wi-Fi Direct GO negotiation 5 s, fragile on Go Edition** | High | P1 | RFCOMM automatic fallback <2.5 s; SoftAP fallback for ESP32; keep `WIFI_MODE_FULL_HIGH_PERF` lock; retry 3× | Transport | GO fail rate >10% |
| R7 | **Model quantization accuracy loss >0.8 pp** | Med | P1 | Per-layer keep float for attention softmax; `per_channel reduce_range`; per-lang WER gate before bundle | ML | WER delta >0.8 |
| R8 | **Piper edge voices (or/gu/kn) flat prosody** | Med | P2 | Fine-tune on IndicTTS 22 kHz single-speaker; fallback to Hear2Read; UTMOS gate | ML | MOS <3.7 |
| R9 | **No streaming RNN-T ONNX export** (tokenizer serialization) | Low | P2 | Use chunked CTC with 8× subsampling; accept non-streaming per-utterance latency 100 ms | ML | — |
| R10 | **Field range <100 m due to 2.4 GHz interference** | Med | P1 | Range test 10/50/100 m LoS; document fallback BT 30 m; advise elevation; log RSSI | QA | PDR >1% at 50 m |

### 5.2 Phased Rollout (8-Week Engineering Roadmap — Aligned to `...Architecture.md:414-442`)

> All phases enforce **TDD RED→GREEN→REFACTOR** (`test-driven-development` skill) and **Verification before Completion** (`Architecture design` + sanitizers).

#### Phase 0 — Inception (Week 0, 3 days)
- **Goals:** Repo init, `Hilt + Compose BOM + NDK r26 + CMake`, `.so` <15 MB, `Oboe` linked, CI with ASAN/UBSAN + `aapt` no-INTERNET check.
- **Deliverable:** `app` builds to `arm64-v8a` debug; `VadFsm` dummy test RED→GREEN.
- **Exit:** `./gradlew :app:assembleDebug` + `ninja simpleperf` pass.

#### Phase 1 — Core ML Pipeline Verification (Weeks 1–2) — *Research Roadmap Phase 1*
- **Scope:** Download IndicConformer INT8 + Piper/Hear2Read voices; quantize per recipe; desktop `sherpa-onnx` harness validates `Mic→VAD→Conformer→Piper` loop.
- **Tasks (TDD):** `FrameCodecTest` (12B+CRC 99% property tests), `VadFsmTest` (3-frame onset, 450 ms hangover), `AsrEngineTest` (Kathbath 100 samples/lang, WER asserts), `TtsEngineTest` (UTMOS ≥3.8).
- **Metrics:** Per-lang WER/CER/OI-WER table (§3.2.1), RTF <0.28 ASR / <0.18 TTS on Snap680 laptop proxy.
- **Exit:** Harness CSV + CI gate; no Android UI yet.

#### Phase 2 — Android Native Runtime Integration (Weeks 3–4) — *Roadmap Phase 2*
- **Scope:** `AudioCapture (Oboe)` + `VadFsm` (C++) + `AsrEngine`/`TtsEngine` mmap + 2-thread pin; bridging JNI; `ModelManager` LRU.
- **Tasks:** Implement ring buffer (lock-free `SPSC`), silence trimming, 80-bin log-mel; handle `mmap` + `num_threads=2`; thermal test 90 s.
- **Exit:** On-device: STT latency <120 ms/2.5 s, heap ≤380 MB cycling 10 langs, zero LMK, ASAN/UBSAN clean, `simpleperf` profile.

#### Phase 3 — Low-Latency D2D Transport (Weeks 5–6) — *Roadmap Phase 3*
- **Scope:** `WifiP2pManager` GO + `BluetoothSocket` RFCOMM fallback + `FrameCodec` + PTT state machine (hardware key + UI).
- **Tasks (TDD):** `WifiDirectTransportTest` (mock P2p), `BluetoothTransportTest` (loopback socket), `PttStateMachineTest` (floor grant/deny/preempt), `PriorityRouterTest` (alert vs FIFO).
- **Exit:** Two phones pair <5 s Wi-Fi, <2.5 s BT; 500 frames at 10 m PDR ≥99%, latency <12 ms Wi-Fi / <35 ms BT.

#### Phase 4 — System Hardening & Validation (Weeks 7–8) — *Roadmap Phase 4*
- **Scope:** Compose UI (4 screens, MVI), `ForegroundService + PARTIAL_WAKE_LOCK`, `STREAM_ALARM` alert preemption, accessibility, field range testing.
- **Tasks:** `TransceiverViewModelTest` (MVI intents), Compose `createComposeRule` for PTT states + 1.3× font / 320 dp narrow, instrumented E2E loop (2 physical devices) measuring E2E ≤650 ms nominal / ≤800 ms P95.
- **Hardening:** `simpleperf` + Android Profiler + batterystats; airplane-mode E2E; 100 m LoS range.
- **Exit (Pre-Deployment Verification):**
  - VAD boundary 480–540 ms ✓
  - Heap ≤380 MB, zero LMK ✓
  - PDR ≥99% + rate <300 bps ✓
  - MOS ≥3.8, WER per-lang ✓
  - SIH two-phone walkie-talkie demo video + APK (base + 2 langs) ✓

#### Phase 5 — V1.1 (Weeks 9–10, Post-SIH if time)
- **Scope:** BLE L2CAP CoC for nRF/ESP32-C3, AES-128-GCM PIN, model Asset Pack dynamic delivery, acoustic noise fine-tune, prosodic retune for or/gu/kn, ICS.
- **Non-Goal lift:** Single-hop only still; translation spike (Indic NMT distilled tiny) as experiment behind flag.

#### Phase 6 — V2.0 (Months 3–4, Beyond SIH)
- **Scope:** Multi-hop relay over GO (custom routing layer), full 10-lang Asset Pack store, SeamlessM4T/NLLB distilled translation (Src≠Dst), hotword option for P2.
- **Constraint:** Must retain offline + <380 MB heap via further distillation/quantization (e.g., 80 MB tiny models).

### 5.3 Milestones & Deliverables to SIH

| Milestone | Date (8-wk) | Artifact | SIH Relevance |
|-----------|-------------|----------|---------------|
| M1 — Model Pack | W2 | Quantized INT8 packs + WER CSV per lang | Accuracy 40% |
| M2 — Native Core | W4 | AAR/.so + profiler report + thermal log | Efficiency 20% |
| M3 — D2D Stack | W6 | Paired APKs + PDR/latency CSV at 10/50/100 m | Latency 20% |
| M4 — SIH Submission | W8 | **APK (≤250 MB base+2 langs), demo video (2-phone walkie/ phone modes + alert preempt), source zip, REPORT.md with Kathbath/MOS/latency/battery, SBOM LICENSES** | ISRO deployable |

### 5.4 Testing & Quality Strategy (TDD + Architecture Verification)

**TDD Iron Law:** No production code without failing test first (`test-driven-development:RED`). Every `domain/usecase` + `data/transport` + `cpp/vad/asr/tts` has `*Test` (JUnit/GTest), watched fail → minimal GREEN → REFACTOR.

| Layer | Tool | Coverage Gate | Examples |
|-------|------|---------------|----------|
| **C++ Native** | `GTest` + `GoogleTest`, `CMake` `enable_testing`, ASAN/UBSAN, `simpleperf` | 80% line, 0 sanitizer issues, RTF asserts | `vad_fsm_test.cpp` (3-frame onset), `ring_buffer_test.cpp` (200 ms pre-roll), `frame_codec_test.cpp` (CRC, endian) |
| **Kotlin Domain** | `JUnit5` + `MockK` + `Turbine` (Flow) | 85% | `TranscribeUseCaseTest`, `PriorityRouterTest` (alert preempts), `PttStateMachineTest` |
| **ViewModel (MVI)** | `JUnit` + `MainDispatcherRule` | 90% state transitions | `TransceiverViewModelTest` intents→state |
| **Compose UI** | `Compose Test` (`createComposeRule`, `onNodeWithTag`), `Espresso` | Screenshot + a11y | PTT button 48dp, alert banner red, language badge, fontScale 1.3 |
| **Instrumented E2E** | `AndroidX Test` + 2 physical devices (Helio G36 + Snap680) | P0 paths only | Discovery, floor, loop STT→TTS, airplane mode |
| **Benchmark** | Desktop `sherpa-onnx` Python + on-device `Benchmark` module | Per-lang CSV | Kathbath WER, UTMOS, `RTF = T_proc/T_audio` |
| **Verification before Completion** | `Architecture design` checklist: interface is test surface, locality, leverage, deletion test | — | Review each seam (audio-io, vad-fsm, asr, tts, transport, protocol) |

**CI Pipeline (GitHub Actions):**
`lint (ktlint/detekt) → testDebugUnitTest → cpp GTest + ASAN → assembleDebug (aapt INTERNET check) → UTMOS regression → artifact APK + BOM`

---

## Appendix A — SIH Compliance Matrix

| SIH Requirement | PRD Section | Compliance |
|-----------------|-------------|------------|
| 10 Indian languages hi/gu/mr/kn/ml/ta/te/or/bn/en | §2.2 US-06, §3.1 | Per-lang IndicConformer + Piper/Hear2Read |
| Lightweight, highly accurate STT/TTS locally on low-power device | §1.3 K1/K4, §3.1, §4.1.2 | INT8 120–188/35–55 MB, RTF <0.3/0.2, mmap, 2 threads |
| STT after detecting pauses/stoppages, forms sentences, streams via Wi-Fi/BT minimal latency | §2.2 US-03/US-04, §3.2.3 | Silero VAD 450 ms hangover, 12B framing, 5–35 ms TX |
| TTS after receiving text → voice note; alert at highest volume non-interruptible | §2.2 US-05, FR-09 | Piper VITS + `STREAM_ALARM` max, preemptive router |
| Two phones loop walkie-talkie PTT, if off works like phone | §2.2 US-01/US-02, FR-08 | Half-duplex floor + duplex toggle |
| Open-source only, no proprietary voice SDKs; TinyML Pytorch/TFLite/similar | §1.3 K6, §3.1, §4.3 | sherpa-onnx/ONNX Runtime/Piper/Oboe all MIT/Apache-2.0 |
| Fully offline, no hosted API | §2.2 US-08, FR-10 | No INTERNET perm, airplane-mode test |
| Runs on Low/Mid range phones | §1.3 K4, §4.5 NFR-01/02 | 2–3 GB Go + 6 GB matrix, ≤380 MB heap |
| Evaluation: Efficiency 20, Accuracy 40, Latency 20 | §1.3, §3.2 | WER/MOS/RTF/E2E + profiling |

## Appendix B — Framing Protocol Reference (Implement verbatim)

```
[0x49][0x54] | [VER:4 | MODE:4] | [ALERT:1|STREAM:1|PTT:1|RSV:5] | [SRC_LANG:8] | [DST_LANG:8] | [SEQ_BE:16] | [LEN_BE:16] | [UTF-8 PAYLOAD 1..2048] | [CRC16-CCITT BE:16 over header+payload]
LangEnum: 01 hi, 02 gu, 03 mr, 04 kn, 05 ml, 06 ta, 07 te, 08 or, 09 bn, 0A en
```

## Appendix C — Glossary & References

- **RTF** Real-Time Factor = `T_process / T_audio`; must be <1.0 (`...Architecture.md:313`).
- **WER/CER/OI-WER** Word/Character/Orthographically-Informed WER (`...Architecture.md:316`).
- **GO** Group Owner (Wi-Fi Direct star center).
- **MMS-TTS** Meta Massively Multilingual Speech TTS — NC license, **do not ship**.
- Primary research: `Offline Multilingual Speech Transceiver Architecture.md` — all tables, thresholds, and latency budget sourced there; this PRD is authoritative for SIH build decisions where PS and research diverge (PS prevails).

## Appendix D — Open Risks / Decisions Needed (For Review)

- **D1 — Asset Delivery:** Play Asset Packs (install-time) vs. side-loaded `filesDir` mmap for SIH offline eval (evaluators may lack Play). **Decision:** Ship side-loaded 2-lang demo + provide 10-lang packs as downloadable ZIP via `adb` for evaluation.
- **D2 — Cross-language behavior V1:** Verbatim phonetic playback confirmed; translation moved to V1.1. **Needs ISRO confirmation.**
- **D3 — Encryption V1:** CRC-16 only; PIN AES-GCM deferred to V1.1 to avoid complexity. Flag as known limitation in report.

---

*End of PRD — Next step: Review D1–D3, then implement Phase 1 per TDD (write failing `FrameCodecTest` first) — no production code before tests.*
