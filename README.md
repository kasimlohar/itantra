<![CDATA[<p align="center">
  <img src="docs/banner.jpg" alt="iTantra — Offline Multilingual Neural Transceiver" width="100%" />
</p>

<p align="center">
  <strong>iTantra</strong> — An offline Android neural transceiver that converts speech to ultra-compact text, transmits it over ad-hoc Wi-Fi Direct / Bluetooth, and re-synthesises intelligible voice on the peer device. No internet. No servers. No data plans.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%2024%2B-3DDC84?style=for-the-badge&logo=android" alt="Android 24+" />
  <img src="https://img.shields.io/badge/Language-Kotlin%20%2B%20C%2B%2B-7F52FF?style=for-the-badge&logo=kotlin" alt="Kotlin + C++" />
  <img src="https://img.shields.io/badge/ML%20Runtime-sherpa--onnx-00B4D8?style=for-the-badge" alt="sherpa-onnx" />
  <img src="https://img.shields.io/badge/Status-Active%20Development-F4A261?style=for-the-badge" alt="Active Development" />
  <img src="https://img.shields.io/badge/License-MIT%20%2F%20Apache--2.0-green?style=for-the-badge" alt="License" />
</p>

---

## 🧭 Table of Contents

- [The Problem](#-the-problem)
- [The Solution](#-the-solution)
- [Key Features](#-key-features)
- [Architecture](#️-architecture)
- [AI & ML Pipeline](#-ai--ml-pipeline)
- [Supported Languages](#-supported-languages)
- [Framing Protocol](#-framing-protocol)
- [Performance Targets](#-performance-targets)
- [Tech Stack](#-tech-stack)
- [Project Structure](#-project-structure)
- [Getting Started](#-getting-started)
- [Building the APK](#-building-the-apk)
- [Running on Device](#-running-on-device)
- [App Screens](#-app-screens)
- [Roadmap](#️-roadmap)
- [SIH Compliance](#-sih-compliance)
- [License](#-license)

---

## 🔥 The Problem

Voice audio is data-intensive — up to **256 kbps** raw PCM or **2.4–6 kbps** after vocoding. Over disaster-relief and tactical links (HF/VHF, congested Wi-Fi, Bluetooth), audio vocoders produce catastrophic distortion.

In alert and distress scenarios, voice communication must remain **inclusive** and **intelligible** — even for low-literacy users — yet raw audio cannot be reliably transmitted over links with **less than 300 bps** effective throughput available on entry-level devices **without cellular infrastructure**.

---

## 💡 The Solution

iTantra works like a **software-defined walkie-talkie** with a neural layer:

```
[Mic 16kHz PCM]
    → VAD (Silero v5, 30ms frames)
    → IndicConformer-120M CTC INT8 (ASR)
    → UTF-8 text (40–120 bytes / sentence)
    → 12-byte iTantra frame + CRC-16
    → Wi-Fi Direct P2P  ⇄  Bluetooth RFCOMM (fallback)
    → Frame decode + CRC verify
    → Piper VITS (TTS, 22.05 kHz)
    → Speaker
```

A 15-word sentence becomes ~60 bytes — under **160 bps** effective — enabling reliable delivery over even the most degraded links.

---

## ✨ Key Features

| Feature | Detail |
|---------|--------|
| 🔕 **Fully Offline** | Zero internet calls. No cloud APIs. Airplane-mode tested. |
| 🗣️ **10 Indian Languages** | hi · gu · mr · kn · ml · ta · te · or · bn · en |
| 📡 **Dual-Bearer D2D** | Wi-Fi Direct primary (5–12 ms, 200 m), Bluetooth RFCOMM fallback (20–35 ms) |
| 🧠 **On-Device Neural STT** | IndicConformer-120M CTC INT8 — WER ≤12.4% per language |
| 🔊 **Neural TTS** | Piper VITS + Hear2Read NG — MOS ≥3.8, first buffer <80 ms |
| 🎙️ **Push-to-Talk / Phone Mode** | Half-duplex floor-controlled PTT or continuous full-duplex phone mode |
| 🚨 **Priority Alert Preemption** | STREAM_ALARM max-volume, non-interruptible alert playback |
| ⚡ **Ultra-Low Latency** | E2E speech-to-speech ≤650 ms nominal / ≤800 ms P95 |
| 🔒 **Privacy First** | No INTERNET permission. No telemetry. Local Room DB only. |
| 📱 **Entry-Level Ready** | Runs on 2–3 GB RAM (Helio G36/Unisoc T606), heap ≤380 MB |

---

## 🏗️ Architecture

iTantra follows **Clean Architecture** with a strict layering of Android → Kotlin → C++ ML:

```
┌─────────────────────────────────────────────────────────┐
│                  Android UI Layer                        │
│  Jetpack Compose  ·  MVI ViewModels  ·  Material 3      │
│  Screens: Transceiver · Radar · Downloads · Settings    │
├─────────────────────────────────────────────────────────┤
│                  Core C++ ML Engine                      │
│  sherpa-onnx  ·  ONNX Runtime Mobile  ·  NDK r26        │
│  Silero VAD v5 → IndicConformer CTC → Piper VITS        │
│  Oboe (AAudio / OpenSL ES)  ·  espeak-ng phonemizer     │
├─────────────────────────────────────────────────────────┤
│                  D2D Transport Engine                    │
│  iTantra 12-Byte Frame + CRC-16-CCITT                   │
│  Wi-Fi Direct P2P (TCP) ⇄ Bluetooth RFCOMM (SPP)       │
└─────────────────────────────────────────────────────────┘
```

### Module Seams

| Module | Interface | Depth |
|--------|-----------|-------|
| `audio-io` (C++) | `AudioCapture::RingBuffer { push(PCM16), pop(30ms), preRoll(200ms) }` | Lock-free, GC-free, hides AAudio/OpenSL |
| `vad-fsm` (C++) | `VadFsm::onFrame(PCM) → State{Idle,Speaking,Pause,EOU}` | 0.6/0.35 threshold, 450 ms hangover |
| `asr-engine` (C++) | `AsrEngine::transcribe(PCM, Lang) → Text` | mmap-loaded, 2-thread big-core pin |
| `tts-engine` (C++) | `TtsEngine::synthesize(Text, Lang) → PCM 22kHz` | Streaming VITS, first-chunk <80 ms |
| `transport` (Kotlin) | `D2dTransport::send(Frame)` / `onFrameReceived(Frame)` | Dual-bearer, framing, CRC, PTT FSM |
| `protocol` (Kotlin/C++) | `FrameCodec::encode/decode` | Zero-copy 12B header + CRC-16-CCITT |
| `priority-router` | `Router::route(Frame) → Queue{standard, alert}` | Alert preemption, STREAM_ALARM, FIFO |
| `app` (Compose) | `Intent → State → Effect` via `StateFlow` | MVI, no business logic in Composables |

### Concurrency Model

- **Audio Thread** — Oboe `SCHED_FIFO` → lock-free ring → VAD (Little cores only)
- **ASR Thread** — Pinned to 2× big cores, wakes on EOU signal only
- **Transport Threads** — `Dispatchers.IO` + non-blocking NIO, 30 s keep-alive heartbeat
- **UI Thread** — `Dispatchers.Main` only for Compose recomposition via `StateFlow`

---

## 🧠 AI & ML Pipeline

### Speech-to-Text (STT)

| Model | Size | WER | RTF | License |
|-------|------|-----|-----|---------|
| IndicConformer-120M CTC **INT8** | 120–188 MB / lang | ≤12.4% per lang | <0.30 | CC-BY-4.0 |
| Meetsync Multi-Indic Conformer | 188 MB (shared hi/bn/gu/mr/kn) | ≤11% avg | <0.30 | MIT |
| Whisper-Tiny INT8 (English) | 42 MB | 7.2% en-IN | ~0.35 | MIT |

**Quantization:** Dynamic INT8 (`QInt8, per_channel, reduce_range`) over MatMul/Gemm/Conv — ≤0.8 pp WER degradation vs FP32.

### Voice Activity Detection (VAD)

- **Silero VAD v5** — dilated CNN+LSTM, 30 ms frames, 16 kHz mono
- 1.8 MB model, <4 MB RAM, <1.5 ms/frame on Cortex-A55
- Onset threshold p≥0.60 (3 consecutive frames), EOU hangover 450 ms (PTT) / 550 ms (phone)
- 200 ms pre-roll circular buffer retains initial unvoiced consonants

### Text-to-Speech (TTS)

| Model | Size | MOS | RTF | License |
|-------|------|-----|-----|---------|
| Piper VITS (medium voices) | 35–55 MB / voice | ≥3.8 | 0.08–0.18 | MIT |
| Hear2Read NG VITS | ~45 MB / voice | ≥3.8 | ~0.12 | Apache-2.0 |

---

## 🌐 Supported Languages

| Code | Language | Script | STT Model | TTS Voice |
|------|----------|--------|-----------|-----------|
| `hi` | Hindi | Devanagari | IndicConformer-hi | Piper rohan/pratham-medium |
| `bn` | Bengali | Bengali | Multi-Indic shared | Hear2Read NG |
| `gu` | Gujarati | Gujarati | Multi-Indic shared | Piper / Hear2Read |
| `mr` | Marathi | Devanagari | Multi-Indic shared | Hear2Read NG |
| `kn` | Kannada | Kannada | Multi-Indic shared | Hear2Read NG |
| `ml` | Malayalam | Malayalam | IndicConformer-ml | Hear2Read NG |
| `ta` | Tamil | Tamil | IndicConformer-ta | Hear2Read NG |
| `te` | Telugu | Telugu | IndicConformer-te | Hear2Read NG |
| `or` | Odia | Odia | IndicConformer-or | Hear2Read NG |
| `en` | English | Latin | Whisper-Tiny INT8 | Piper en-medium |

> **V1 Note:** Cross-language TX (SrcLang ≠ DstLang) plays text verbatim in destination voice phonetics — no NMT translation. Translation is a V1.1 research candidate.

---

## 📦 Framing Protocol

Every message is encoded into a compact binary frame before D2D transmission:

```
[0x49][0x54] | [VER:4 | MODE:4] | [ALERT:1|STREAM:1|PTT:1|RSV:5] |
[SRC_LANG:8] | [DST_LANG:8] | [SEQ_BE:16] | [LEN_BE:16] |
[UTF-8 PAYLOAD 1..2048 bytes] | [CRC-16-CCITT BE:16]
```

| Byte(s) | Field | Notes |
|---------|-------|-------|
| B0–B1 | Magic `0x49 0x54` | ASCII `"IT"` |
| B2 | Ver/Mode | `ver=0x01`, mode bit (half=0, duplex=1) |
| B3 | Flags | bit7=ALERT, bit6=STREAM, bit5=PTT_STATE |
| B4 | SrcLang | `01`=hi … `0A`=en |
| B5 | DstLang | `01`=hi … `0A`=en |
| B6–B7 | SeqId | Big-endian uint16 |
| B8–B9 | PayloadLen | Big-endian uint16 (1–2048) |
| — | UTF-8 Payload | 40–120 bytes typical (~160 bps effective) |
| Trail | CRC-16-CCITT | Over header + payload; drop on mismatch |

---

## 📊 Performance Targets

### End-to-End Latency Budget (Wi-Fi Direct / Snapdragon 680)

| Stage | ms | Notes |
|-------|----|-------|
| VAD Framing (Oboe) | 30 | 30 ms native ring buffer |
| EOU Pause Hangover | 450 | Shorten to 350 ms in PTT urgent mode |
| IndicConformer CTC INT8 | 100 | 2 threads big cores, 8× subsampling |
| Wi-Fi Direct TX | 8 | `TCP_NODELAY`, non-blocking NIO |
| Frame Parse + Route | 2 | Zero-copy byte buffer |
| Piper VITS Synthesis | 40 | Streaming chunked inference |
| AudioTrack Buffering | 20 | Oboe AAudio low-latency |
| **Total** | **≤650 ms** | **P95 SLO: ≤800 ms** |

### Key Performance Indicators (SIH)

| KPI | Target | Weight |
|-----|--------|--------|
| STT WER per language | ≤12.4% (threshold), ≤10% avg (stretch) | 40% |
| TTS MOS | ≥3.8 (threshold), ≥4.0 (stretch) | 40% |
| E2E Latency | ≤650 ms nominal, ≤800 ms P95 | 20% |
| Heap on 3 GB device | ≤380 MB stable | Efficiency gate |
| D2D Frame Delivery | ≥99% at 100 m LoS | Link resilience gate |
| Internet calls | 0 | Pass/Fail gate |

---

## 🛠️ Tech Stack

| Layer | Technology |
|-------|------------|
| **UI** | Jetpack Compose + Material 3, Navigation Compose |
| **Architecture** | MVI (Intent → StateFlow → Effect), Clean Architecture |
| **DI** | Hilt 2.50+ |
| **ML Runtime** | sherpa-onnx 1.10+ (ONNX Runtime Mobile) |
| **Audio I/O** | Google Oboe 1.8+ (AAudio + OpenSL ES fallback) |
| **TTS Phonemizer** | espeak-ng (bundled, offline) |
| **Native** | C++, NDK r26, CMake 3.22+, arm64-v8a |
| **D2D Transport** | Wi-Fi Direct (`WifiP2pManager`) + Bluetooth RFCOMM |
| **Persistence** | Room 2.6+ + DataStore Preferences |
| **Language** | Kotlin 1.9+, AGP 8.x, `compileSdk 34`, `minSdk 24` |
| **Build** | Gradle (KTS), ASAN/UBSan in debug, `-O2 -fvisibility=hidden` |

---

## 📁 Project Structure

```
itantra/
├── app/
│   ├── build.gradle.kts          # NDK, CMake, abiFilters arm64-v8a
│   └── src/main/
│       ├── cpp/                  # Native ML engine (C++)
│       │   ├── CMakeLists.txt
│       │   ├── audio/            # Oboe ring buffer, audio capture
│       │   ├── vad/              # Silero VAD v5 + FSM
│       │   ├── asr/              # IndicConformer + mel features
│       │   └── tts/              # Piper VITS adapter
│       ├── java/com/itantra/
│       │   ├── di/               # Hilt modules (ASR, TTS, Transport)
│       │   ├── data/
│       │   │   ├── local/        # Room DB + DataStore
│       │   │   ├── models/       # ModelManager (mmap LRU, <180ms swap)
│       │   │   └── transport/    # WifiDirectTransport, BluetoothTransport, FrameCodec, CRC-16
│       │   ├── domain/
│       │   │   ├── model/        # Language(10), Frame, TransmitMode, AlertPriority
│       │   │   └── usecase/      # TranscribeUseCase, SynthesizeUseCase, SendFrameUseCase
│       │   ├── presentation/
│       │   │   ├── transceiver/  # TransceiverViewModel (MVI) + TransceiverScreen
│       │   │   ├── radar/        # Mesh radar, device discovery
│       │   │   ├── downloads/    # Model pack manager
│       │   │   ├── settings/     # Language settings
│       │   │   └── theme/        # Material3 tokens, Indic typography
│       │   ├── service/          # ForegroundService + PARTIAL_WAKE_LOCK
│       │   ├── ITantraApp.kt     # Navigation host (4-tab bottom nav)
│       │   └── MainActivity.kt   # PTT hardware key (VOLUME_DOWN) listener
│       └── assets/models/        # mmap-loaded model packs (side-loaded)
│           ├── vad/silero_v5.onnx
│           ├── stt/{lang}/       # IndicConformer INT8 + tokens.txt per lang
│           └── tts/{lang}/       # Piper ONNX voice + config per lang
├── docs/
│   ├── PRD.md                    # Source of truth — product requirements
│   ├── Offline Multilingual Speech Transceiver Architecture.md
│   └── superpowers/plans/        # Phase-by-phase implementation plans
├── scripts/                      # Build + quantization helper scripts
└── .github/                      # CI pipeline (lint → test → assemble → UTMOS)
```

---

## 🚀 Getting Started

### Prerequisites

| Requirement | Version |
|------------|---------|
| Android Studio | Hedgehog 2023.1.1+ |
| Android NDK | r26 |
| CMake | 3.22+ |
| Kotlin | 1.9+ |
| Java | 17 |
| ADB | Latest platform-tools |

### Clone the Repository

```bash
git clone https://github.com/your-org/itantra.git
cd itantra
```

### Configure Local Properties

```properties
# local.properties
sdk.dir=C\:\\Users\\YourUser\\AppData\\Local\\Android\\Sdk
ndk.dir=C\:\\Users\\YourUser\\AppData\\Local\\Android\\Sdk\\ndk\\26.x.x
```

### Add Model Assets

Model packs are **not bundled** in the repo due to size (120–188 MB per language). Side-load them via ADB:

```bash
# Push STT model for Hindi
adb push models/stt/hi/ /sdcard/Android/data/com.itantra/files/models/stt/hi/

# Push TTS voice for Hindi
adb push models/tts/hi/ /sdcard/Android/data/com.itantra/files/models/tts/hi/

# Push VAD model (shared, 1.8 MB)
adb push models/vad/silero_v5.onnx /sdcard/Android/data/com.itantra/files/models/vad/
```

> Model packs can also be managed via the in-app **Downloads** screen once the APK is installed.

---

## 🔨 Building the APK

```bash
# Build debug APK
./gradlew :app:assembleDebug

# Install directly (USB debugging + Install via USB enabled)
./gradlew :app:installDebug

# Manual install (e.g. Xiaomi MIUI — enable 'Install via USB' in Developer Options first)
adb push app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/
# Then open Files app on device and tap the APK to install
```

### CI Checks

```bash
./gradlew lint                  # ktlint + detekt
./gradlew testDebugUnitTest     # JUnit + MockK + Turbine
./gradlew :app:assembleDebug    # aapt verifies no INTERNET permission
```

---

## 📲 Running on Device

1. Enable **USB Debugging** in Developer Options
2. **Xiaomi/MIUI only:** also enable **Install via USB** in Developer Options
3. Connect via USB: `adb devices` should show your device
4. Install and launch the APK
5. Grant **Microphone**, **Nearby Devices / Wi-Fi**, and **Bluetooth** permissions

### Two-Phone Walkie-Talkie Demo (SIH Evaluator Loop)

```
Phone A (Sender)                   Phone B (Receiver)
────────────────                   ──────────────────
Open Transceiver tab               Open Transceiver tab
Set Src=Hindi, Dst=Hindi           Set Src=Hindi, Dst=Hindi
Tap Radar → scan → connect    ──►  Accept P2P connection
Hold PTT button and speak     ──►  Receives frame over Wi-Fi Direct
Release PTT                         Piper TTS synthesizes + plays back
                              ◄──  Hold PTT, speak back
```

---

## 📱 App Screens

| Screen | Purpose |
|--------|---------|
| **Transceiver** | Main PTT/phone screen — mic VU meter, transcript stream, playback queue |
| **Radar** | Mesh node discovery — scan & connect to Wi-Fi Direct / Bluetooth peers |
| **Downloads** | Manage AI model packs — download, verify, swap language packs |
| **Settings** | Language pair, mode toggle (PTT ↔ Phone), audio config |

---

## 🗺️ Roadmap

| Phase | Timeline | Scope |
|-------|----------|-------|
| **Phase 0** — Inception | Week 0 | Repo, NDK, Oboe, CI with ASAN/UBSAN |
| **Phase 1** — ML Verification | Weeks 1–2 | IndicConformer INT8 + Piper WER/MOS harness |
| **Phase 2** — Android Native | Weeks 3–4 | Oboe + VAD + ASR/TTS JNI, mmap LRU |
| **Phase 3** — D2D Transport | Weeks 5–6 | Wi-Fi Direct + RFCOMM + PTT FSM |
| **Phase 4** — System Hardening | Weeks 7–8 | Compose UI, ForegroundService, E2E ≤800 ms |
| **Phase 5 (V1.1)** | Weeks 9–10 | BLE L2CAP CoC, AES-128-GCM PIN, Asset Pack |
| **Phase 6 (V2.0)** | Months 3–4 | Multi-hop relay, SeamlessM4T translation, hotword |

---

## 🏆 SIH Compliance

**Problem Statement:** 26173 | **Organization:** ISRO / Department of Space

| SIH Requirement | Status | Notes |
|-----------------|--------|-------|
| 10 Indian languages (hi/gu/mr/kn/ml/ta/te/or/bn/en) | ✅ | Per-lang IndicConformer + Piper/Hear2Read |
| Offline STT/TTS on low-power device | ✅ | INT8 120–188 MB, RTF <0.3, mmap |
| STT triggered on pauses, streams via Wi-Fi/BT | ✅ | Silero VAD 450 ms, 12B frame, <35 ms TX |
| TTS voice note + alert at max volume | ✅ | Piper VITS + STREAM_ALARM, preemptive |
| PTT walkie-talkie + phone mode | ✅ | Half-duplex floor + duplex toggle |
| Open-source only (MIT/Apache-2.0/CC-BY-4.0) | ✅ | sherpa-onnx / Piper / Oboe — no NC |
| Fully offline, no hosted API | ✅ | No INTERNET permission, airplane-mode tested |
| Low/mid-range phones (2–3 GB RAM) | ✅ | Helio G36 + Snap680 test matrix |

> **Evaluation weights:** Accuracy 40% · Efficiency 20% · Latency 20% · Deployability 20%

---

## 🔐 Permissions

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO"/>
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE"/>
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES"
    android:usesPermissionFlags="neverForLocation"/>
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.WAKE_LOCK"/>
<!-- No INTERNET permission — by design -->
```

---

## 📄 License

This project is licensed under the **MIT License**. All bundled AI models are open-source:

| Component | License |
|-----------|---------|
| sherpa-onnx / ONNX Runtime | Apache-2.0 |
| Google Oboe | Apache-2.0 |
| IndicConformer (AI4Bharat) | CC-BY-4.0 |
| Piper TTS | MIT |
| Hear2Read NG | Apache-2.0 |
| Silero VAD | MIT |
| espeak-ng | GPL-3.0 (bundled phoneme data only) |

> **Note:** Meta MMS-TTS (CC-BY-NC 4.0) is **excluded** from all release builds — benchmark-only per license requirements.

---

<p align="center">
  Built with ❤️ for <strong>Smart India Hackathon 2026</strong> — ISRO Problem Statement 26173<br/>
  <em>"Voice for every Indian, everywhere, offline."</em>
</p>
]]>
