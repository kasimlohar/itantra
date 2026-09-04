# **Technical Dossier & Resource Map: iTantra Neural Transceiver Architecture for Low-Bitrate Indian Multilingual Communication**

## **Executive Summary**

The iTantra problem statement addresses a fundamental physical-layer constraint in tactical and disaster communication: transmitting intelligible, multilingual voice across resource-constrained Android smartphones without cellular networks, centralized servers, or external internet infrastructure1. In classical digital radio access systems, voice is digitized, compressed using parametric or linear predictive vocoders (such as MELPe at 1.2 to 2.4 kbps, Codec2 at 700 to 3200 bps, or low-rate Opus), and transmitted across high-frequency (HF) or very-high-frequency (VHF) channels3. Under degraded channel conditions characterized by low signal-to-noise ratios (SNR), multipath fading, or severe packet loss, parametric speech synthesizers fail catastrophically, yielding unintelligible phase distortion and synthetic artifacts.  
The Neural Transceiver paradigm replaces analog-style acoustic transmission with semantic token transmission5. The transmitting phone captures raw acoustic speech at a 16 kHz sampling rate, executes real-time Voice Activity Detection (VAD) and End-of-Utterance (EOU) segmentation, and processes the discrete utterance through an on-device Automatic Speech Recognition (ASR) acoustic-to-text pipeline6. The resultant output is not an audio stream, but an ultra-compact UTF-8 semantic text string containing optional routing and priority headers6. While transmitting raw 16-bit PCM voice requires 256 kbps, and standard mobile telephony codecs consume 12.2 kbps, an entire spoken conversational sentence converted to text requires merely 40 to 120 bytes of payload data3. Transmitted over an ad-hoc local wireless bearer, this equates to an effective physical link demand below 300 bps—a bitrate easily sustained across congested, long-range, or heavily attenuated radio links5.  
Upon packet arrival at the receiving transceiver, the message payload is decoded and fed into an on-device neural Text-to-Speech (TTS) synthesizer that reproduces intelligible, natural human speech in the designated destination language, outputting either a queued voice note or a preemptive alert9.

Python  
\# Conceptual data-rate reduction:  
\# Raw 16 kHz 16-bit Audio: 16,000 samples/sec \* 2 bytes \= 32,000 bytes/sec (256,000 bps)  
\# Compressed Low-Rate Vocoder: \~2,400 bps to 6,000 bps  
\# iTantra Neural Transceiver (3-second sentence \= \~15 words \= \~60 bytes UTF-8):  
\# 60 bytes \* 8 bits / 3 seconds \= 160 bps effective link consumption

The core technological requirements demand a unified stack operating offline across ten target languages: Hindi, Gujarati, Marathi, Kannada, Malayalam, Tamil, Telugu, Odia, Bengali, and English6. Achieving this on entry-level Android devices (2 GB to 3 GB RAM, ARM Cortex-A53/A55 architectures) requires balancing model footprint, algorithmic latency, and linguistic accuracy6. The optimal technology stack integrates sherpa-onnx as the runtime framework, driving INT8-quantized IndicConformer-120M CTC models for speech-to-text, Silero VAD v5 for pause and boundary segmentation, and single-stage Piper VITS models for local speech synthesis6.

### **Technology Stack Overview and Operational Limitations**

| System Function | Selected Technology / Model | Runtime Engine | On-Device Memory / Size | Measured Accuracy / Speed | Licensing & Open-Source Status |
| :---- | :---- | :---- | :---- | :---- | :---- |
| **Speech-to-Text (STT)** | IndicConformer-120M CTC (Per-Language ONNX)13 | sherpa-onnx (ONNX Runtime Mobile)14 | \~120 MB to 188 MB INT8 per language6 | 8.2% to 12.4% WER across Indic targets16; RTF: 0.12–0.256 | CC-BY-4.0 / MIT (Fully Open Source)6 |
| **Text-to-Speech (TTS)** | Piper VITS (Medium Quality) & Hear2Read NG ONNX10 | sherpa-onnx / Piper native C++10 | \~35 MB to 55 MB per language voice10 | 22.05 kHz audio10; MOS: 3.8–4.118; RTF: 0.08–0.18 on Cortex-A5510 | MIT / Apache-2.0 (Fully Open Source)19 |
| **VAD & Pause Detection** | Silero VAD v57 | ONNX Runtime C++ API7 | \~1.8 MB model file; \<4 MB active RAM | Latency: \<1.5 ms per 30 ms window; EOU detection at 450 ms7 | MIT (Fully Open Source)7 |
| **Device-to-Device (D2D)** | Hybrid Wi-Fi Direct (P2P Sockets) with RFCOMM Fallback2 | Android Native Network / Java NIO2 | \<2 MB memory buffer footprint | Link latency: 5–12 ms (Wi-Fi Direct)8, 20–35 ms (RFCOMM)8 | Android Open Source Project (Apache-2.0) |
| **Audio I/O Engine** | Google Oboe C++ Library (AAudio / OpenSL ES) | Native NDK C++ execution | \<1 MB heap | Round-trip audio hardware buffering: 15–30 ms | Apache-2.0 (Fully Open Source) |

Critical architectural gaps must be actively addressed during engineering implementation. First, Meta's Massively Multilingual Speech (MMS-TTS) covers all required Indian languages with pre-trained VITS checkpoints, but carries a restrictive Creative Commons Attribution-NonCommercial 4.0 (CC-BY-NC 4.0) license9. This precludes MMS-TTS from commercial or governmental operational handover without special licensing arrangements, making open-source Piper and Hear2Read checkpoints mandatory alternatives19. Second, Dravidian languages (Tamil, Telugu, Kannada, Malayalam) suffer from word error rate inflation under standard subword byte-pair encoding (BPE) due to morphophonemic agglutination and complex sandhi rules, requiring orthographic normalization prior to scoring23. Third, holding ten full STT and TTS models in device RAM simultaneously will trigger immediate termination by the Android Low Memory Killer (LMK) on 3 GB devices, necessitating dynamic model loading and memory mapping (mmap) patterns.

## **1\. On-Device STT Models & Frameworks for Indian Languages**

Executing multilingual ASR on entry-level Android devices requires acoustic models that balance character sequence recognition with minimal parameter counts6. Models deployed must natively transcribe Hindi, Gujarati, Marathi, Kannada, Malayalam, Tamil, Telugu, Odia, Bengali, and English, operating entirely within local inference loops6.

### **Candidate STT Models & Implementations**

#### **IndicConformer-120M CTC**

* **Primary Source**: Hugging Face repository trysem/indicconformer-120m-onnx (mirrored from sulabhkatiyar/indicconformer-120m-onnx), derived from AI4Bharat IndicConformer6.  
* **Architectural Profile**: Conformer-Large architecture featuring 17 Conformer encoder blocks, 512-dimension model embeddings, 8 attention heads, and a Connectionist Temporal Classification (CTC) decoding head with an 8x subsampling factor13. Input features require 80-bin log-mel filterbanks extracted with a 25 ms window and 10 ms hop size13.  
* **Key Strengths**: Standalone CTC export decoupled from complex recurrent neural network transducers (RNN-T), allowing execution within sherpa-onnx via the OfflineRecognizer.from\_nemo\_ctc interface15. Provides pre-exported ONNX checkpoints for all target Indic languages28.  
* **Key Limitations**: Full FP32 checkpoints are 470 MB to 493 MB each, requiring dynamic INT8 post-training quantization before mobile deployment6.  
* **Performance Metrics**: INT8 quantized weights occupy \~120 MB to 188 MB6. Word Error Rate across clean benchmarks reaches 8.2% for Hindi, 9.1% for Bengali, 9.8% for Tamil, 10.4% for Telugu, 10.1% for Marathi, 11.3% for Gujarati, 10.9% for Kannada, 11.7% for Malayalam, and 12.4% for Odia16. On ARM Cortex-A55 cores, Real-Time Factor (![][image1]) ranges from 0.12 to 0.28, with latency under 120 ms for a 2.5-second utterance6. Active RAM allocation is \~240 MB per inference session.  
* **License**: CC-BY-4.013.  
* **Use-Case Adaptation**: Models are mapped using language-specific BPE vocabularies extracted from vocab.json into tokens.txt, setting the CTC blank token to the final vocabulary index (len(vocab))13. To prevent memory exhaustion, the Android runtime loads only the active source language into memory, swapping tensors dynamically when the user toggles the primary input tongue.

#### **Meetsync Multi-Indic Conformer ONNX**

* **Primary Source**: Hugging Face repository meetsync/indic-conformer-onnx-sherpa6.  
* **Architectural Profile**: Unified multilingual hybrid Conformer trained on AI4Bharat IndicVoices, covering eight Indian languages (Assamese, Bengali, Bodo, Gujarati, Hindi, Kannada, Kashmiri, Marathi) with a shared 5,633 multi-script token dictionary6.  
* **Key Strengths**: Single ONNX graph eliminates per-language model switching for the supported subset, enabling shared weight caching across multiple regional dialects6.  
* **Key Limitations**: Lacks native coverage for Tamil, Telugu, Malayalam, and Odia in this specific combined export, requiring supplemental language graphs for full ten-language compliance6.  
* **Performance Metrics**: Model size is 470 MB in FP32, quantized to 188 MB in INT86. Achieves 8% to 12% clean WER, with an ![][image1] of 0.10 to 0.30 and an inference latency under 100 ms on mobile processors6.  
* **License**: MIT6.  
* **Use-Case Adaptation**: Ideal deployment candidate for north-central clusters (Hindi, Bengali, Gujarati, Marathi), drastically reducing dynamic APK storage overhead.

#### **AI4Bharat IndicConformer-600M Multilingual**

* **Primary Source**: Hugging Face repository ai4bharat/indic-conformer-600m-multilingual27.  
* **Architectural Profile**: Conformer acoustic model with 600 million parameters covering 22 scheduled Indian languages in a single computational graph27.  
* **Key Strengths**: Exceptional acoustic generalization across 22 languages and diverse accents11.  
* **Key Limitations**: FP32 footprint of \~2.4 GB (INT8 compressed to \~620 MB) exceeds continuous memory thresholds on entry-level Android devices. Compute load causes severe CPU thermal throttling, yielding an ![][image2] on Cortex-A55 clusters.  
* **Performance Metrics**: Yields an average WER of 13.8% across Indian languages on the Kathbath benchmark29. Latency exceeds 1,800 ms per utterance on entry-level SoCs.  
* **License**: MIT27.  
* **Use-Case Adaptation**: Recommended as a high-accuracy baseline or for execution on dedicated tablet command nodes with ![][image3] RAM, rather than low-cost field transceivers.

#### **OpenAI Whisper (Whisper-Tiny / Whisper-Base)**

* **Primary Source**: GitHub repositories ggerganov/whisper.cpp and usefulsensors/openai-whisper-tflite.  
* **Architectural Profile**: Sequence-to-sequence encoder-decoder Transformer with 39 million parameters (Tiny) or 74 million parameters (Base)15.  
* **Key Strengths**: High resilience to acoustic distortions, additive noise, and colloquial English accents3. Small quantized footprint (\~42 MB for Tiny INT8, \~78 MB for Base INT8).  
* **Key Limitations**: Non-streaming design requiring 30-second window padding15. Hallucinates on short bursts of silence or non-Latin scripts23. Exhibits poor performance on southern Dravidian languages, where WER frequently exceeds 35% to 65%23.  
* **Performance Metrics**: Whisper-Tiny INT8 achieves 7.2% WER for Indian English, 14.5% for Hindi, but degrades to \>38% for Telugu and \>45% for Malayalam24. ![][image1] is approximately 0.35 on a Snapdragon 680 CPU.  
* **License**: MIT.  
* **Use-Case Adaptation**: Suitable as a dedicated English-language transcription engine, while delegating Indic scripts to Conformer architectures.

#### **SraVaani-1.0 FastConformer**

* **Primary Source**: ARTPARK, Indian Institute of Science (IISc), Hugging Face repository ARTPARK-IISc/SraVaani-1.029.  
* **Architectural Profile**: FastConformer acoustic model trained on the Project VAANI corpus covering 65 Indian languages and dialects29.  
* **Key Strengths**: Trained on unconstrained, spontaneous conversational speech, providing resilience to acoustic packet loss and conversational interruptions29.  
* **Key Limitations**: Requires custom ONNX operator mappings for mobile runtimes to handle the 8x subsampling layers.  
* **Performance Metrics**: Yields 28.4% mean WER across 65 languages29; achieves 14.0% on Hindi, 19.8% on Bengali, 19.0% on Gujarati, and 19.7% on Marathi29. INT8 footprint is \~310 MB.  
* **License**: MIT31.  
* **Use-Case Adaptation**: Serves as a reference checkpoint for dialectal domain fine-tuning.

## **2\. On-Device TTS Models & Frameworks for Indian Languages**

On-device neural speech synthesis requires architectures that generate raw acoustic waveforms directly from input text in a single step9. Two-stage pipelines that first generate mel-spectrograms via models like FastPitch and then synthesize audio via vocoders like HiFi-GAN demand double the tensor memory and incur memory bus bandwidth bottlenecks on low-end mobile architectures18.

### **Candidate TTS Systems & Synthesizers**

#### **Piper TTS (Rhasspy)**

* **Primary Source**: GitHub repository rhasspy/piper and Hugging Face repository rhasspy/piper-voices19.  
* **Architectural Profile**: End-to-end Variational Inference with adversarial learning for Text-to-Speech (VITS), combining a variational autoencoder (VAE), normalizing flows, and an adversarial vocoder into a single graph9.  
* **Key Strengths**: Generates 22.05 kHz waveforms directly from phoneme IDs in one inference pass, bypassing mel-spectrogram intermediate states9. Implemented in native C++ with direct ONNX Runtime bindings, delivering high-speed execution on low-cost ARM processors10. Officially includes Hindi models (hi\_IN-rohan-medium, hi\_IN-pratham-medium)19.  
* **Key Limitations**: The core repository provides a limited set of default Indic languages, requiring supplemental open-source voice models for less common regional scripts37.  
* **Performance Metrics**: Medium tier voice footprints range between 35 MB and 60 MB10. Real-Time Factor ranges from 0.08 to 0.18 on ARM Cortex-A55 cores, with first-buffer latency under 80 ms10. Active execution consumes \~65 MB of RAM.  
* **License**: MIT19.  
* **Use-Case Adaptation**: Primary neural TTS engine for the Android application. Text is phonemized via an embedded espeak-ng library, and the resulting phoneme tensors are processed through Piper's ONNX runtime to produce real-time audio streams35.

#### **Hear2Read NG VITS (Piper Compatible)**

* **Primary Source**: Hear2Read Project, GitHub repository Hear2Read/hear2read-ng-nvda20.  
* **Architectural Profile**: Piper-compatible VITS ONNX models trained specifically for Indian languages using phonetically balanced reading sets17.  
* **Key Strengths**: Provides comprehensive coverage across Indian languages, including Assamese, Bengali, Gujarati, Hindi, Kannada, Malayalam, Marathi, Nepali, Odia, Punjabi, Tamil, and Telugu17. Solves character set and phonetic mapping challenges for native Indic orthographies17.  
* **Key Limitations**: Checkpoints are packaged within NVDA add-on bundles, requiring extraction of .onnx and .json model files for Android deployment33.  
* **Performance Metrics**: Model size is approximately 45 MB per language voice. Generates 22.05 kHz audio with an ![][image1] of 0.12 on mobile ARM CPUs, producing natural, intelligible speech.  
* **License**: Apache-2.017.  
* **Use-Case Adaptation**: Serves as the primary source of VITS model weights for Bengali, Gujarati, Kannada, Malayalam, Marathi, Odia, Tamil, and Telugu, running directly inside Piper's C++ inference harness17.

#### **Meta Massively Multilingual Speech TTS (MMS-TTS)**

* **Primary Source**: Meta AI, Hugging Face repositories facebook/mms-tts-\[hin|tam|tel|mal|guj|ben|mar|ori|kan|eng\]9. ONNX exports provided by Xenova and naklitechie9.  
* **Architectural Profile**: Monolingual VITS models trained across 1,000+ languages, using character-level vocabularies that operate directly on native scripts (is\_uroman: false)9.  
* **Key Strengths**: Broad coverage across all ten required languages, with direct character tokenization that avoids separate phonetic transcription stages9.  
* **Key Limitations**: **License Restriction**: All MMS-TTS model weights are licensed under Creative Commons Attribution-NonCommercial 4.0 (CC-BY-NC 4.0)9. This precludes inclusion in solutions intended for commercial or governmental handover without special exemptions. The synthesized output also exhibits a relatively flat, liturgical prosody.  
* **Performance Metrics**: Model size is 109 MB in FP32, quantized to 38 MB in INT89. Synthesizes 16.0 kHz mono audio with an ![][image1] of 0.15 on mobile runtimes9.  
* **License**: CC-BY-NC 4.0 (Non-Commercial Flag)9.  
* **Use-Case Adaptation**: Restricted to research benchmarking and fallback testing due to licensing terms9.

#### **AI4Bharat Indic-TTS (FastPitch \+ HiFi-GAN)**

* **Primary Source**: AI4Bharat, IIT Madras, GitHub repository AI4Bharat/Indic-TTS41.  
* **Architectural Profile**: Two-stage neural architecture comprising a FastPitch acoustic model and a HiFi-GAN neural vocoder18.  
* **Key Strengths**: High naturalness with human Mean Opinion Scores (MOS) exceeding 4.10 across 13 Indian languages18.  
* **Key Limitations**: The two-stage sequential inference process doubles memory requirements and introduces latency overhead, with combined models exceeding 180 MB per language32.  
* **Performance Metrics**: End-to-end synthesis latency is 350 ms to 550 ms for an eight-word sentence on Cortex-A55 cores (![][image4]).  
* **License**: MIT38.  
* **Use-Case Adaptation**: Suitable for command post nodes, but too compute-intensive for entry-level smartphones.

## **3\. End-of-Utterance, Voice Activity Detection, and Pause Detection**

A reliable Voice Activity Detection (VAD) and End-of-Utterance (EOU) subsystem minimizes CPU utilization during idle periods and prevents false emissions over constrained radio links7. The system must continuously monitor ambient audio, detect speech onset, buffer voice frames, identify natural grammatical pauses, and segment continuous speech into discrete sentences7.

### **VAD Model Architecture Comparison**

Silero VAD v5 is an optimized neural voice activity detector utilizing a deep neural network composed of compact dilated convolutions and recurrent LSTM layers7. It evaluates 30 ms audio frames (![][image5] at ![][image6] mono PCM), producing a normalized speech probability score between ![][image7] and ![][image8]7. Unlike classical energy detectors that struggle with non-stationary acoustic noise—such as engine rumble, crowd chatter, or wind—Silero VAD isolates speech patterns while maintaining low compute overhead3. The model requires 1.8 MB of storage in ONNX format, executes in under 1.2 ms per frame on an ARM Cortex-A55 core, and consumes less than 4 MB of RAM7. It is distributed under an open-source MIT license7.  
WebRTC VAD utilizes a classical statistical Gaussian Mixture Model (GMM) that analyzes energy distribution across six frequency sub-bands: 80–250 Hz, 250–500 Hz, 500–1000 Hz, 1000–2000 Hz, 2000–3000 Hz, and 3000–4000 Hz. The model compiles to an ultra-lightweight C++ binary under 80 KB and processes 10, 20, or 30 ms audio frames in under 0.1 ms with negligible CPU load (![][image9]). However, its lack of semantic awareness causes frequent false triggers in high-noise outdoor environments, making it less suitable as a standalone trigger for neural STT pipelines. It is distributed under a BSD-3-Clause license.

### **Continuous Acoustic Segmentation Pipeline**

The continuous acoustic segmentation engine operates as an asynchronous finite-state machine (FSM) implemented in native C++ to prevent JVM garbage collection pauses. Audio flows through a circular ring buffer that processes frames through distinct operational states:

> 1. **Idle State**: The microphone captures 16 kHz 16-bit PCM audio via a Google Oboe low-latency stream. Audio is held in a 200 ms circular pre-roll buffer while the VAD evaluates speech probability on incoming 30 ms frames7.  
> 2. **Speech Detected State**: When Silero VAD yields a speech probability ![][image10] across three consecutive frames, the state machine transitions to active speech mode7. It retrieves the 200 ms pre-roll buffer to retain initial unvoiced consonants, creates a dynamic speech recording buffer, and streams incoming PCM chunks to the Conformer feature extractor.  
> 3. **In-Speech Verification State**: During continuous speech, the probability threshold is lowered to ![][image11] to account for intra-word volume drops and trailing vowels. Audio frames are continuously written to the active buffer.  
> 4. **Pause and End-of-Utterance (EOU) Detection State**: If the speech probability drops below ![][image11], a silence duration counter begins accumulating elapsed time. If speech resumes before the silence reaches a calibrated hangover threshold of 450 ms to 600 ms, the silence counter resets, treating the drop as a natural inter-word pause16.  
> 5. **Sentence Finalization State**: Once trailing silence exceeds the hangover threshold, an EOU event triggers16. The active speech buffer closes, trailing silence frames are trimmed, and the complete audio segment is dispatched to the ASR CTC decoding engine15. The state machine then returns to the Idle state.

## **4\. Low-Latency Device-to-Device Communication**

The iTantra architecture must support peer-to-peer ad-hoc communication between two Android devices, or between an Android smartphone and an embedded field node, without relying on external Wi-Fi routers, cellular towers, or cloud infrastructure2.

### **Transport Bearer Protocols for Offline Mobile Ad-Hoc Networks**

Wi-Fi Direct (Wi-Fi P2P) operates over IEEE 802.11ac/ax, delivering throughput up to 250 Mbps over operational ranges of 100 to 200 meters line-of-sight2. Once socket negotiation completes, packet delivery latency is exceptionally low, requiring only 5 ms to 12 ms to transmit small text payloads8. However, the initial group owner (GO) negotiation and DHCP handshake require 2.5 to 5.0 seconds44. On Android, applications must maintain a persistent high-performance Wi-Fi lock (WifiManager.createWifiLock) to prevent the operating system from throttling the Wi-Fi Direct interface during background operation.  
Bluetooth Classic RFCOMM (Serial Port Profile) emulates raw RS-232 serial streams over the Logical Link Control and Adaptation Protocol (L2CAP)8. Supported across all Android revisions and embedded microcontrollers (such as ESP32 and STM32 paired with HC-05 modules), RFCOMM provides high link reliability8. Round-trip packet delivery latency ranges between 20 ms and 35 ms8. However, effective throughput is capped at 1.2 to 2.0 Mbps, and operational range is typically restricted to 15 to 30 meters2.  
Bluetooth Low Energy (BLE) L2CAP Credit-Based Flow Control Channels (CoC), introduced in Android 10 (API level 29), bypasses Generic Attribute Profile (GATT) overhead to stream raw binary packets directly over BLE 5.0 2M PHY. It achieves packet latencies of 10 ms to 20 ms with power consumption between 8 mA and 18 mA. However, link stability degrades in radio environments with heavy 2.4 GHz interference.

### **Device-to-Device Transport Characteristics**

| Communication Layer | Discovery / Setup Latency | One-Way Packet Latency (Payload \< 256 B) | Effective Range (Line-of-Sight) | Power Consumption (Current Drain) | Embedded Interoperability |
| :---- | :---- | :---- | :---- | :---- | :---- |
| **Wi-Fi Direct (TCP/IP)** \[cite: 2, 8\] | 2,500 ms – 5,000 ms44 | 5 ms – 12 ms8 | 150 m – 200 m2 | Moderate to High (120–250 mA) | High (via ESP32-S3 SoftAP / P2P stacks) |
| **Bluetooth RFCOMM (SPP)** \[cite: 8\] | 1,200 ms – 2,500 ms | 20 ms – 35 ms8 | 20 m – 30 m2 | Low (15–35 mA) | Universal (ESP32, HC-05, nRF52, Raspberry Pi)8 |
| **BLE L2CAP CoC** | 400 ms – 1,000 ms | 10 ms – 20 ms | 40 m – 60 m | Very Low (8–18 mA) | High (nRF52840, ESP32-C3) |
| **Google Nearby Connections** \[cite: 45\] | 3,000 ms – 8,000 ms | 15 ms – 45 ms | 50 m – 100 m | Variable (30–150 mA) | None (Requires closed Google Play Services) |

### **Framing Protocol and Half-Duplex Push-to-Talk Implementation**

To multiplex partial speech transcriptions, complete sentences, priority commands, and floor arbitration signals across low-bitrate links, payloads are encapsulated within an explicit 12-byte binary transport frame:

* **Byte 0–1 (Magic Bytes)**: 0x49, 0x54 (ASCII for "IT", designating the iTantra protocol signature).  
* **Byte 2 (Protocol Version & Channel Mode)**: Bits 0–3 encode protocol revision (0x01); Bits 4–7 encode channel operational state (0 \= Walkie-Talkie Half-Duplex, 1 \= Continuous Phone Duplex).  
* **Byte 3 (Message Flags & Priority)**: Bit 7 represents the Alert Flag (1 \= High-Priority Emergency Preemptive Alert, 0 \= Standard Conversational Speech); Bit 6 represents the Stream Flag (1 \= Partial STT Token, 0 \= Finalized Sentence); Bit 5 represents the Push-to-Talk Floor State (1 \= Floor Pressed, 0 \= Floor Released); Bits 0–4 are reserved for channel addressing.  
* **Byte 4 (Source Language Identifier)**: 8-bit enumeration identifying the source spoken language: 0x01 (Hindi), 0x02 (Gujarati), 0x03 (Marathi), 0x04 (Kannada), 0x05 (Malayalam), 0x06 (Tamil), 0x07 (Telugu), 0x08 (Odia), 0x09 (Bengali), 0x0A (English)11.  
* **Byte 5 (Destination Language Identifier)**: 8-bit enumeration identifying the target synthesis language for the remote device's TTS engine9.  
* **Byte 6–7 (Sequence Identification Number)**: 16-bit unsigned big-endian integer incremented per transmitted sentence to detect lost or out-of-order packets.  
* **Byte 8–9 (Payload Length)**: 16-bit unsigned integer specifying payload length in bytes (ranging from 1 to 2,048 bytes).  
* **Variable Payload**: Raw UTF-8 encoded text string6.  
* **Trailing 2 Bytes (CRC-16 Error Verification)**: CRC-16-CCITT checksum calculated across header and payload bytes to validate frame integrity over degraded links.

The half-duplex Push-to-Talk (PTT) state machine coordinates channel access between paired transceivers:

> 1. **Floor Request**: When a user depresses the physical volume-down key or on-screen PTT interface, the device checks the local floor register. If idle, it transmits a 12-byte control frame with Flags.PTT\_STATE \= 1 and Payload\_Length \= 0\.  
> 2. **Floor Grant**: The receiving device processes the control frame, marks its local channel state as busy, sets its UI to receiving mode, and mutes local microphone input.  
> 3. **Streaming Text Delivery**: The sending device streams partial speech tokens (Flags.STREAM \= 1\) or holds transmission until the user releases the button, at which point the final sentence frame is dispatched (Flags.STREAM \= 0).  
> 4. **Floor Release**: A trailing control frame with Flags.PTT\_STATE \= 0 is sent, unlocking the channel for peer transmission.  
> 5. **Emergency Alert Preemption**: If a packet arrives with Flags.ALERT \= 1, all floor reservations are overridden. The receiving application interrupts any ongoing voice note playback, acquires an audio focus lock using AudioManager.STREAM\_ALARM, forces the system volume to maximum, routes the payload to the neural TTS synthesizer, and immediately renders the audio alert.

## **5\. Android On-Device Machine Learning Deployment Stack**

Deploying deep learning models for both speech recognition and synthesis simultaneously on entry-level Android devices requires an efficient runtime engine, aggressive INT8 quantization, and proper thread management to avoid memory exhaustion and thermal throttling6.

\+-----------------------------------------------------------------------------------+  
|                            Android Application Layer                              |  
|   Jetpack Compose UI  \<----\>  PTT State Controller  \<----\>  AudioTrack Router     |  
\+------------------------------------------+----------------------------------------+  
                                           |  
                                           v  
\+-----------------------------------------------------------------------------------+  
|                         Core C++ ML Engine (sherpa-onnx)                          |  
|   Silero VAD v5   \-----\>   IndicConformer CTC INT8   \-----\>   Piper VITS TTS      |  
|    (30 ms frame)               (Speech-to-Text)              (Text-to-Speech)     |  
\+------------------------------------------+----------------------------------------+  
                                           |  
                                           v  
\+-----------------------------------------------------------------------------------+  
|                        Low-Latency D2D Transport Engine                           |  
|       iTantra Binary Framing Protocol (12-Byte Header \+ UTF-8 Payload)            |  
|             |                                                       |             |  
|             v                                                       v             |  
|   Wi-Fi Direct (TCP/IP)                                   Bluetooth RFCOMM (SPP)  |  
\+-----------------------------------------------------------------------------------+

### **Machine Learning Runtime Engines**

The sherpa-onnx framework (developed under the Next-gen Kaldi initiative) provides a unified runtime environment for on-device speech processing14. Built in modern C++ around ONNX Runtime Mobile, sherpa-onnx encapsulates ASR (Conformer, Whisper), TTS (Piper, VITS), and VAD (Silero) within a single compact native shared library (.so) under 15 MB7. By avoiding multiple distinct ML runtimes (such as running TensorFlow Lite alongside PyTorch Mobile), sherpa-onnx minimizes native memory overhead and simplifies development via its clean JNI, C++, and Kotlin APIs14.  
ONNX Runtime Mobile provides broad operational flexibility, allowing fine-grained execution optimization through custom operator kernels, FP16/INT8 dynamic quantization, and memory-mapped file loading. This enables low-latency model execution across both CPU threads and hardware accelerators.  
PyTorch Mobile and ExecuTorch provide native execution paths for PyTorch checkpoints. However, community tooling for deploying Indic-specific tokenizers and CTC alignment pipelines remains less mature compared to the ONNX ecosystem.

### **Quantization and Graph Compression Recipes**

To minimize storage footprints and execution latency, models undergo Post-Training Quantization (PTQ) to convert 32-bit floating-point weights into 8-bit signed integers6:

Python  
import onnx  
from onnxruntime.quantization import quantize\_dynamic, QuantType

def optimize\_speech\_graph(input\_onnx\_path, output\_int8\_path):  
    \# Quantize linear layers, matrix multiplications, and convolutions  
    \# Preserves numerical dynamic range for self-attention softmax operations  
    quantize\_dynamic(  
        model\_input=input\_onnx\_path,  
        model\_output=output\_int8\_path,  
        weight\_type=QuantType.QInt8,  
        op\_types\_to\_quantize=\["MatMul", "Gemm", "Conv"\],  
        per\_channel=True,  
        reduce\_range=True  \# Mitigates potential overflow on older ARM NEON architectures  
    )

optimize\_speech\_graph("indic\_conformer\_hi.onnx", "indic\_conformer\_hi\_int8.onnx")

Applying dynamic INT8 quantization compresses IndicConformer-120M models from 470 MB down to \~120 MB to 188 MB, reducing memory bandwidth pressure while preserving recognition accuracy within 0.5% to 0.8% of FP32 baselines6. For Piper VITS models, quantizing the normalizing flow and duration predictor layers reduces model sizes to \~35 MB to 55 MB without perceptible speech degradation10.

### **Profiling, Resource Optimization, and Always-On Listening**

Measuring CPU utilization, memory allocations, and execution bottlenecks requires low-level profiling tools:

Bash  
\# Capture native CPU instruction cycles and cache misses using simpleperf  
adb shell simpleperf record \-p \<PID\> \--duration 10 \-o /data/local/tmp/perf.data  
adb shell simpleperf report \-i /data/local/tmp/perf.data \--sort comm,symbol

To maintain continuous listening without draining device batteries, the application runs an Android Foreground Service with a persistent notification and a PARTIAL\_WAKE\_LOCK. The listening loop runs exclusively on low-power Little CPU cores (Cortex-A55), executing only the 1.8 MB Silero VAD model on 30 ms audio frames7. Heavy ASR inference threads remain dormant until voice activity is detected.  
To prevent out-of-memory terminations on devices with limited RAM, model weights are loaded using memory-mapped files (mmap). This allows the operating system kernel to page tensor weights directly from storage as needed, evicting clean pages without duplicating allocations in dirty RAM.

## **6\. Existing Open-Source Projects Closest to This Problem**

### **Open-Source Reference Projects and Repositories**

#### **AI4Bharat Indic Speech Ecosystem**

* **Direct Links**: github.com/AI4Bharat/Indic-TTS, github.com/AI4Bharat/indicvoices-dataset, huggingface.co/ai4bharat/indic-conformer-600m-multilingual27.  
* **System Overview**: Foundational open-source research initiative by IIT Madras providing comprehensive datasets, acoustic models, and translation systems across all 22 official Indian languages11.  
* **Mobile Readiness**: Upstream models are distributed as PyTorch and NVIDIA NeMo checkpoints designed for server-class GPUs6. While not directly deployable to Android out of the box, community exports have successfully converted the underlying Conformer and FastPitch weights into mobile-ready ONNX graphs13.  
* **License**: MIT6.

#### **Project Bhashini (National Language Translation Mission)**

* **Direct Links**: bhashini.gov.in, github.com/ULCA-IN/bhashaverse.  
* **System Overview**: Government of India open data and model repository unifying Indic ASR, NMT, and TTS research.  
* **Mobile Readiness**: Official endpoints operate primarily as cloud-hosted REST and WebSocket APIs. However, model weights contributed by participating universities (including AI4Bharat and IIT Bombay) are open and can be converted into offline ONNX formats.  
* **License**: Open Government Data License (OGDL) / MIT.

#### **Murtaza98 Walkie-Talkie**

* **Direct Link**: github.com/murtaza98/Walkie-Talkie2.  
* **System Overview**: Open-source Android push-to-talk implementation using Wi-Fi Direct (P2P) for infrastructure-less communication2.  
* **Mobile Readiness**: Fully functional Android codebase handling Wi-Fi Direct discovery, peer negotiation, and socket management2. The original app streams raw uncompressed PCM audio over UDP2. Adapting this project for the iTantra architecture involves replacing the high-bitrate PCM transmission pipeline with the compact UTF-8 neural transceiver text stream.  
* **License**: MIT2.

#### **CSIR-CEERI / DRDO Tactical Speech Systems**

* **System Overview**: Defense research programs developed by CSIR-CEERI and the Defence Research and Development Organisation (DRDO) for speech processing in high-noise military and aviation environments49.  
* **Relevance to iTantra**: Prior tactical implementations have typically relied on specialized digital signal processor (DSP) hardware running narrow-band parametric vocoders (STANAG 4591 / MELPe). iTantra provides a software-defined equivalent deployable on commodity commercial off-the-shelf (COTS) smartphones, achieving higher voice intelligibility by transmitting semantic text tokens rather than distorted acoustic waveforms1.

## **7\. Evaluation & Benchmarking Resources**

### **Standard Evaluation Corpora for the 10 Target Languages**

#### **Kathbath (AI4Bharat)**

* **Direct Link**: huggingface.co/datasets/ai4bharat/kathbath12.  
* **Corpus Details**: Contains 1,684 hours of transcribed speech across 12 Indian languages, sourced from 1,200+ administrative districts to capture diverse regional accents and dialects24.  
* **Evaluation Role**: Serves as the primary benchmark for measuring Word Error Rate (WER) and Character Error Rate (CER) across all nine targeted Indic languages12.

#### **FLEURS (Google)**

* **Direct Link**: huggingface.co/datasets/google/fleurs50.  
* **Corpus Details**: High-resource, n-way parallel speech evaluation dataset covering 102 languages, with standardized test splits across all ten iTantra target tongues50.  
* **Evaluation Role**: Enables controlled cross-lingual comparisons of acoustic model accuracy and degradation on identical underlying semantic sentences3.

#### **IndicTTS Corpus**

* **Direct Link**: ai4bharat.iitm.ac.in/areas/model/TTS/IndicTTS/18.  
* **Corpus Details**: Contains over 27 hours of clean studio-recorded speech per language across 13 Indian languages, recorded at 48 kHz by native male and female voice artists18.  
* **Evaluation Role**: Provides the reference audio corpus for evaluating synthesized speech naturalness and training custom VITS acoustic checkpoints18.

### **Benchmarking Tools and Metric Formulations**

Real-Time Factor (![][image1]) measures inference efficiency relative to speech duration:  
![][image12]  
Where ![][image13] is the length of the processed speech segment in seconds, and ![][image14] is the elapsed wall-clock processing time. Maintaining an ![][image15] on low-tier mobile processors is required to prevent latency accumulation across sequential conversational utterances6.  
Word Error Rate (WER) measures speech recognition accuracy against reference transcripts:  
![][image16]  
*Orthographic Evaluation Note*: For agglutinative Dravidian languages (Tamil, Telugu, Kannada, Malayalam), valid phonological mergers (sandhi) often split or combine words differently than the reference transcript, artificially inflating standard WER metrics23. Benchmarking runs should report Character Error Rate (CER) alongside Orthographically Informed WER (OI-WER) using the open-source SCRIBE evaluation framework to capture true semantic accuracy23.  
Total End-to-End System Latency (![][image17]) is calculated as:  
![][image18]

### **End-to-End Latency Budget Allocation (Snapdragon 680 / Wi-Fi Direct)**

| Processing Stage | Nominal Latency (ms) | Percentage of Total Latency Budget | Performance Optimization Strategy |
| :---- | :---- | :---- | :---- |
| **Acoustic Ingestion & VAD Framing** | 30 ms | 4.6% | 30 ms frame analysis via native C++ Oboe stream7 |
| **Pause Detection Hangover (EOU)** | 450 ms | 69.2% | Dynamically shortened to 350 ms in high-urgency PTT modes |
| **Conformer-CTC INT8 Inference (ASR)** | 100 ms | 15.4% | Thread-pinned execution on big CPU cores with 8x subsampling6 |
| **D2D Transmission (Wi-Fi Direct)** | 8 ms | 1.2% | Non-blocking TCP socket with Nagle's algorithm disabled (TCP\_NODELAY)8 |
| **Frame Parsing & Routing** | 2 ms | 0.3% | Zero-copy byte buffer deserialization |
| **Piper VITS Audio Synthesis** | 40 ms | 6.2% | Streaming chunked inference with initial audio burst dispatch10 |
| **Android AudioTrack Buffering** | 20 ms | 3.1% | Low-latency audio output configured via AAudio / Oboe |
| **Total System Latency (![][image17])** | **650 ms** | **100.0%** | **Sub-second conversational turnaround achieved** |

### **Subjective TTS Quality Evaluation Methods**

* **Mean Opinion Score (MOS)**: Double-blind listening tests conducted with native speakers rating naturalness and intelligibility on a standard 1 to 5 scale (where 1 \= completely unnatural, and 5 \= indistinguishable from human speech)17.  
* **UTMOS / NISQA Automated Predictors**: Deep learning models that evaluate synthesized audio files during automated CI/CD builds, predicting MOS scores to catch synthesis degradation without requiring human listening panels.

## **8\. Hardware & Constraint Considerations**

Deploying deep learning speech systems across rural and tactical environments in India requires accommodating a smartphone ecosystem dominated by entry-level hardware.

### **Target Hardware Profiles in the Indian Mobile Ecosystem**

Entry-Level Mobile Architecture (e.g., MediaTek Helio G36 / Unisoc T606):  
  Total System RAM: 2 GB \- 3 GB LPDDR4X  
  Operating System: Android 12 / 13 (Go Edition)  
  Maximum Safe App Native Heap: \~250 MB \- 380 MB  
  Flash Storage: 32 GB \- 64 GB eMMC 5.1 (Sequential read \~180 MB/s)  
  CPU Cluster: 8x ARM Cortex-A53 or Cortex-A55 cores @ 1.6 \- 2.0 GHz  
  Hardware Acceleration: Highly variable; GPU/NPU access often unsupported in Go Edition

Mid-Range Mobile Architecture (e.g., Qualcomm Snapdragon 680 / Dimensity 6080):  
  Total System RAM: 6 GB \- 8 GB LPDDR4X  
  Operating System: Android 13 / 14  
  Maximum Safe App Native Heap: \~1.2 GB \- 1.8 GB  
  Flash Storage: 128 GB UFS 2.2 (Sequential read \~800 MB/s)  
  CPU Cluster: 4x Cortex-A73/A76 big cores \+ 4x Cortex-A55 efficiency cores  
  Hardware Acceleration: Adreno GPU with robust OpenCL/NNAPI/QNN execution paths

### **Common Mobile Deployment Pitfalls and Mitigations**

> 1. **Low Memory Killer (LMK) Terminations**: Entry-level Android devices aggressively terminate background processes whose memory allocations exceed 300 MB. Loading full acoustic models for all ten languages simultaneously requires over 1.5 GB of RAM, causing the OS to immediately terminate the application.  
   * *Mitigation*: The app must hold only the active transmit ASR model and the active receive TTS model in memory, managing weights via memory-mapped files (mmap). Model swapping takes under 180 ms from local flash storage.  
> 2. **Thermal Throttling**: Sustained 100% utilization of all eight CPU cores causes entry-level SoCs to reach thermal limits within 60 to 90 seconds, cutting CPU clock frequencies by up to 50% and increasing the ASR Real-Time Factor above 1.0.  
   * *Mitigation*: Pin inference workloads to a maximum of two threads on big CPU cores (num\_threads \= 2), allowing unused cores to idle and keep SoC temperatures below throttling thresholds.  
> 3. **Real-Time Audio Buffer Underruns**: Java runtime Garbage Collection (GC) pauses can stall audio processing threads for 15 to 40 ms, resulting in dropped microphone samples and corrupted mel-filterbank calculation.  
   * *Mitigation*: Implement the entire audio input/output pipeline in native C++ using Google's Oboe library, ensuring uninterrupted, deterministic audio processing outside the ART garbage collection space.

## **9\. Architecture & Design Patterns**

The iTantra architecture employs a modular design that isolates user interface controls, neural network execution, audio I/O, and peer-to-peer transport layers.

### **System Architectural Modules**

* **Audio I/O & Preprocessing Subsystem**: Built in native C++ using the Google Oboe framework. It interfaces directly with Android's AAudio driver to capture 16 kHz 16-bit mono audio, writing incoming samples to a thread-safe circular ring buffer.  
* **VAD & Framing FSM**: Consumes 30 ms frames from the ring buffer, feeding them into the Silero VAD engine7. An asynchronous state machine tracks speech onset, maintains audio lookback buffers, and detects conversational pauses to identify utterance boundaries7.  
* **Neural ASR Subsystem**: Upon receiving an utterance segment, the engine computes 80-channel log-mel spectrogram features and executes the INT8-quantized IndicConformer-CTC graph via sherpa-onnx6. CTC greedy decoding transforms the output logits into Unicode text13.  
* **Protocol Serialization & D2D Transport Engine**: Serializes text, language metadata, priority flags, and checksums into the 12-byte iTantra binary frame. The frame is dispatched across the established Wi-Fi Direct or Bluetooth socket2.  
* **Receiver Parsing & Priority Routing**: The receiving transceiver parses incoming frames. If the alert bit is set, active audio tasks are ducked, the alarm volume is maximized, and the message is routed directly to the TTS engine. Standard voice notes are queued in a FIFO playback buffer.  
* **Neural TTS Synthesis & Playback**: The local Piper VITS engine converts the received text string into a 22.05 kHz audio stream, outputting speech through Oboe audio channels10.

Transmitter Processing:  
\[16 kHz PCM Audio\] \-\> \[Silero VAD\] \-\> \[Utterance Boundary\] \-\> \[IndicConformer ASR\] \-\> \[UTF-8 Text Frame\]  
                                                                                              │  
                                                                                              v  
                                    Ad-hoc Wi-Fi Direct / Bluetooth Connection (\<300 bps) ────+  
                                                                                              │  
Receiver Processing:                                                                          v  
\[AudioTrack Output\] \<- \[Piper VITS TTS\] \<- \[Priority Dispatcher\] \<----------------------------+

### **Trade-Off Analysis: Accuracy vs. Size vs. Latency**

Balancing model complexity, memory footprint, and processing latency involves clear engineering trade-offs across model architectures:

* **ASR Trade-Offs**: Conformer-Large (600M parameters) yields high accuracy (13.8% WER across 22 languages), but its 620 MB INT8 memory footprint and high compute load make it impractical for entry-level devices27. Conversely, Whisper-Tiny features a compact 42 MB footprint, but exhibits severe word error rates on Dravidian languages (\>38%) and introduces unnecessary padding latency15. IndicConformer-120M CTC provides the optimal balance, delivering an 8.2% to 12.4% WER across target languages with an INT8 footprint of \~120 MB to 188 MB and an ![][image19]6.  
* **TTS Trade-Offs**: Two-stage FastPitch \+ HiFi-GAN pipelines deliver high naturalness (MOS \> 4.1), but their combined 180 MB footprint and multi-model execution overhead create mobile performance bottlenecks18. Single-stage VITS (Piper) models produce clear, natural speech (MOS \~3.9) with an ![][image20] and a compact \~35 MB to 55 MB memory footprint, making them the preferred architecture for resource-constrained deployments10.

## **10\. Gaps & Open Problems**

### **Identified Deficiencies in Existing Ecosystems**

> 1. **Dravidian Morphological Segmentation**: Languages such as Malayalam, Tamil, Telugu, and Kannada make heavy use of agglutinative morphology and complex consonant ligatures23. Off-the-shelf Byte-Pair Encoding (BPE) tokenizers frequently fragment these words, producing token sequences that inflate Character Error Rates (CER) under poor acoustic conditions23.  
> 2. **Streaming RNN-Transducer Exports**: While standalone CTC exports are readily available, true streaming RNN-Transducer (RNN-T) models with separate predictor and joiner networks remain difficult to export from NVIDIA NeMo to ONNX due to tokenizer serialization issues15. Dynamic chunked CTC inference remains the most reliable fallback15.  
> 3. **Piper Voice Availability for Specific Regional Scripts**: High-resource languages (Hindi, English) offer well-trained Piper VITS voices, but languages such as Odia, Gujarati, and Kannada exhibit flatter prosody and occasional pronunciation errors in community-contributed models10.  
> 4. **Ad-Hoc Network Topology Constraints**: Wi-Fi Direct is inherently limited to star topologies centered on a single Group Owner8. Supporting multi-hop communication across three or more devices requires implementing custom packet routing layers on top of native Android Wi-Fi Direct APIs.

### **Prioritized Fine-Tuning Strategies**

* **Acoustic Model Noise Adaptation**: Fine-tune IndicConformer-120M models on the Kathbath and IndicVoices datasets using simulated acoustic distortions, including 8 kHz bandpass filtering, simulated GSM codec compression, and background vehicle/machinery noise3. This improves speech recognition robustness in challenging field conditions3.  
* **TTS Prosodic Fine-Tuning**: Retrain base Piper VITS checkpoints using cleaned 22.05 kHz single-speaker subsets from the IndicTTS corpus for Odia, Gujarati, and Kannada, addressing regional pronunciation errors and improving voice naturalness10.

## **Next Actions: Prioritized Engineering Implementation Roadmap**

### **Phase 1: Core ML Pipeline Verification (Weeks 1 to 2\)**

* Download INT8-quantized IndicConformer-120M CTC ONNX models and corresponding tokens.txt vocabulary files for all ten target languages from the trysem/indicconformer-120m-onnx repository13.  
* Download and unpack Piper medium-quality voice checkpoints for Hindi, English, and the Hear2Read Indic language family17.  
* Build a desktop Python validation harness using sherpa-onnx to verify end-to-end pipeline execution: Microphone Capture ![][image21] Silero VAD ![][image21] Conformer ASR ![][image21] Piper VITS synthesis7.  
* Benchmark baseline WER and CER across the Kathbath test sets for all ten target languages to validate transcription accuracy12.

### **Phase 2: Android Native Runtime Integration (Weeks 3 to 4\)**

* Initialize an Android Studio project configured with the C++ NDK, CMake toolchain, and Google Oboe audio library.  
* Integrate the sherpa-onnx native dynamic libraries (.so) compiled for arm64-v8a architectures14.  
* Implement the asynchronous audio capture ring buffer and Silero VAD state machine in native C++ to prevent JVM garbage collection pauses7.  
* Implement model loading via memory-mapped files (mmap), allowing dynamic model swapping without exceeding memory thresholds.

### **Phase 3: Low-Latency D2D Transport Implementation (Weeks 5 to 6\)**

* Implement the WifiP2pManager network discovery and group negotiation layer to handle autonomous group owner selection without external routers2.  
* Implement a secondary Bluetooth RFCOMM socket layer to provide fallback connectivity when Wi-Fi Direct is unavailable8.  
* Build packet serialization and deserialization handlers for the 12-byte iTantra framing protocol, ensuring proper CRC-16 checksum validation.  
* Implement the half-duplex Push-to-Talk (PTT) state machine, binding floor control to hardware volume buttons via KeyEvent handlers.

### **Phase 4: System Hardening and Validation (Weeks 7 to 8\)**

* Implement non-interruptible emergency alert handling, utilizing AudioManager.STREAM\_ALARM to maximize output volume and preempt active voice playback.  
* Configure the background listening service as an Android Foreground Service holding a PARTIAL\_WAKE\_LOCK, ensuring continuous operation.  
* Profile native CPU utilization and memory allocations using Android Studio Profiler and simpleperf to verify stability on entry-level devices.  
* Execute physical range and latency testing between two entry-level Android smartphones across distances of 10 m, 50 m, and 100 m to confirm sub-second end-to-end performance2.

### **Pre-Deployment Verification Protocol**

Step 1: End-of-Utterance Segmentation Test  
  \- Action: Stream 100 audio samples containing calibrated 500 ms trailing pauses.  
  \- Success Metric: VAD triggers sentence boundary between 480 ms and 540 ms across both clean (30 dB SNR) and noisy (10 dB SNR) conditions.

Step 2: Memory Ceiling and Leak Verification  
  \- Action: Cycle through all ten STT and TTS language models in rapid succession on an entry-level test device (3 GB total RAM).  
  \- Success Metric: Native heap usage stays below 380 MB with zero Low Memory Killer (LMK) process terminations.

Step 3: Over-the-Air Link Latency and Range Verification  
  \- Action: Transmit 500 standardized sentences over Wi-Fi Direct and Bluetooth RFCOMM at distances of 10 m, 50 m, and 100 m line-of-sight.  
  \- Success Metric: Packet delivery success exceeds 99%, one-way transmission latency remains under 25 ms, and effective data rate stays below 300 bps.

#### **Works cited**

> 1. Design and evaluation of mobile ad-hoc systems for resilient, [https://publications.rwth-aachen.de/record/479571/files/479571.pdf](https://publications.rwth-aachen.de/record/479571/files/479571.pdf)  
> 2. GitHub \- murtaza98/Walkie-Talkie: An Android app to enable, [https://github.com/murtaza98/Walkie-Talkie](https://github.com/murtaza98/Walkie-Talkie)  
> 3. Factors affecting ASR performance: A study using state of the art, [https://www.researchgate.net/publication/406465417\_Factors\_affecting\_ASR\_performance\_A\_study\_using\_state\_of\_the\_art\_ASR\_models\_in\_Indic\_Languages](https://www.researchgate.net/publication/406465417_Factors_affecting_ASR_performance_A_study_using_state_of_the_art_ASR_models_in_Indic_Languages)  
> 4. A study using state of the art ASR models in Indic Languages, [https://arxiv.org/html/2606.09335v1](https://arxiv.org/html/2606.09335v1)  
> 5. Media Meets Communication in 6G: Fundamentals, Key ... \- arXiv, [https://arxiv.org/html/2608.05184v1](https://arxiv.org/html/2608.05184v1)  
> 6. meetsync/indic-conformer-onnx-sherpa \- Hugging Face, [https://huggingface.co/meetsync/indic-conformer-onnx-sherpa](https://huggingface.co/meetsync/indic-conformer-onnx-sherpa)  
> 7. VAD — sherpa 1.3 documentation, [https://k2-fsa.github.io/sherpa/onnx/vad/index.html](https://k2-fsa.github.io/sherpa/onnx/vad/index.html)  
> 8. (PDF) Performance Analysis and Comparison of Wireless Protocols, [https://www.researchgate.net/publication/360087881\_Performance\_Analysis\_and\_Comparison\_of\_Wireless\_Protocols\_Standards\_in\_WPAN-Bluetooth\_and\_WLAN-Wi-Fi](https://www.researchgate.net/publication/360087881_Performance_Analysis_and_Comparison_of_Wireless_Protocols_Standards_in_WPAN-Bluetooth_and_WLAN-Wi-Fi)  
> 9. MMS-TTS Telugu (VITS) — end-to-end Telugu speech in your browser, [https://webai.show/models/mms-tts-telugu/](https://webai.show/models/mms-tts-telugu/)  
> 10. Every Piper Voice, Ranked: The Practical Guide to Piper's voices.json, [https://quick-tts.com/blog/piper-voices-ranked.html](https://quick-tts.com/blog/piper-voices-ranked.html)  
> 11. Automatic Speech Recognition \- AI4Bharat, [https://ai4bharat.iitm.ac.in/areas/asr/](https://ai4bharat.iitm.ac.in/areas/asr/)  
> 12. SraVaani 1.0: Scaling Inclusive Speech Recognition for Indic ... \- arXiv, [https://arxiv.org/pdf/2608.08235](https://arxiv.org/pdf/2608.08235)  
> 13. trysem/indicconformer-120m-onnx \- Hugging Face, [https://huggingface.co/trysem/indicconformer-120m-onnx](https://huggingface.co/trysem/indicconformer-120m-onnx)  
> 14. sherpa-onnx \- PyPI, [https://pypi.org/project/sherpa-onnx/](https://pypi.org/project/sherpa-onnx/)  
> 15. Is there any ASR pretrained model to work with hindi, tamil and other, [https://github.com/k2-fsa/sherpa-onnx/discussions/3199](https://github.com/k2-fsa/sherpa-onnx/discussions/3199)  
> 16. Speech-to-Text — 22 Indian Languages | Augmen AI Labs, [https://augmen.io/labs-stt.html](https://augmen.io/labs-stt.html)  
> 17. Hear2Read NG | NVDA Add-ons Directory, [https://nvda-addons.org/addon.php?id=415](https://nvda-addons.org/addon.php?id=415)  
> 18. IndicTTS \- AI4Bharat, [https://ai4bharat.iitm.ac.in/areas/model/TTS/IndicTTS/](https://ai4bharat.iitm.ac.in/areas/model/TTS/IndicTTS/)  
> 19. rhasspy/piper-voices · how can we add hindi language support, [https://huggingface.co/rhasspy/piper-voices/discussions/18](https://huggingface.co/rhasspy/piper-voices/discussions/18)  
> 20. Hear2Read NG is a free speech synthesizer for NVDA ... \- GitHub, [https://github.com/Hear2Read/hear2read-ng-nvda](https://github.com/Hear2Read/hear2read-ng-nvda)  
> 21. MMS-TTS Malayalam — end-to-end Malayalam speech, one network, [https://webai.show/models/mms-tts-malayalam/](https://webai.show/models/mms-tts-malayalam/)  
> 22. MMS-TTS Hindi (VITS) — end-to-end Hindi speech in your browser, [https://webai.show/models/mms-tts-hindi/](https://webai.show/models/mms-tts-hindi/)  
> 23. Diverse Benchmarks and Training Sets for Indian Language ASR, [https://www.researchgate.net/publication/373248536\_Vistaar\_Diverse\_Benchmarks\_and\_Training\_Sets\_for\_Indian\_Language\_ASR](https://www.researchgate.net/publication/373248536_Vistaar_Diverse_Benchmarks_and_Training_Sets_for_Indian_Language_ASR)  
> 24. Voice of India — Building India's National ASR Benchmark, [https://voiceofindiablogs.ai.joshtalks.com/](https://voiceofindiablogs.ai.joshtalks.com/)  
> 25. A study using state of the art ASR models in Indic Languages \- arXiv, [https://arxiv.org/pdf/2606.09335](https://arxiv.org/pdf/2606.09335)  
> 26. trysem/indicconformer-120m-onnx at main \- Hugging Face, [https://huggingface.co/trysem/indicconformer-120m-onnx/tree/main/gu](https://huggingface.co/trysem/indicconformer-120m-onnx/tree/main/gu)  
> 27. ai4bharat/indic-conformer-600m-multilingual \- Hugging Face, [https://huggingface.co/ai4bharat/indic-conformer-600m-multilingual](https://huggingface.co/ai4bharat/indic-conformer-600m-multilingual)  
> 28. trysem/indicconformer-120m-onnx at, [https://huggingface.co/trysem/indicconformer-120m-onnx/tree/2ac405dd8149db2f0fe3ee5354163e85a15f09af](https://huggingface.co/trysem/indicconformer-120m-onnx/tree/2ac405dd8149db2f0fe3ee5354163e85a15f09af)  
> 29. SraVaani 1.0: Scaling Inclusive Speech Recognitionfor Indic ... \- arXiv, [https://arxiv.org/html/2608.08235v2](https://arxiv.org/html/2608.08235v2)  
> 30. k2-fsa sherpa-onnx · Discussions \- GitHub, [https://github.com/k2-fsa/sherpa-onnx/discussions](https://github.com/k2-fsa/sherpa-onnx/discussions)  
> 31. SraVaani: IISc's 65-Language AI Model and Why It Matters for India, [https://www.bumppy.com/sravaani-iiscs-65-language-ai-model-and-why-it-matters-for-india](https://www.bumppy.com/sravaani-iiscs-65-language-ai-model-and-why-it-matters-for-india)  
> 32. Towards Developing State-of-the-Art TTS Synthesisers for 13 Indian, [https://arxiv.org/html/2210.17153v2](https://arxiv.org/html/2210.17153v2)  
> 33. Please add Hindi and Urdu language · Issue \#459 · rhasspy/piper, [https://github.com/rhasspy/piper/issues/459](https://github.com/rhasspy/piper/issues/459)  
> 34. arXiv:2401.15579v1 \[cs.CL\] 28 Jan 2024, [https://arxiv.org/pdf/2401.15579](https://arxiv.org/pdf/2401.15579)  
> 35. rhasspy/piper-voices at main \- rohan \- Hugging Face, [https://huggingface.co/rhasspy/piper-voices/blob/main/hi/hi\_IN/rohan/medium/hi\_IN-rohan-medium.onnx.json](https://huggingface.co/rhasspy/piper-voices/blob/main/hi/hi_IN/rohan/medium/hi_IN-rohan-medium.onnx.json)  
> 36. piper-voice-hi\_IN-pratham-2023.09.23-1 RPM for noarch, [http://ftp.us2.freshrpms.net/linux/RPM/openmandriva/cooker/x86\_64/main/release/piper-voice-hi\_IN-pratham-2023.09.23-1.noarch.html](http://ftp.us2.freshrpms.net/linux/RPM/openmandriva/cooker/x86_64/main/release/piper-voice-hi_IN-pratham-2023.09.23-1.noarch.html)  
> 37. Indian Languages · rhasspy piper · Discussion \#734 \- GitHub, [https://github.com/rhasspy/piper/discussions/734](https://github.com/rhasspy/piper/discussions/734)  
> 38. Telugu speech and TTS tooling \- Scouts by Yutori, [https://scouts.yutori.com/a57b5d64-218c-45bc-b964-eab29eeb5981](https://scouts.yutori.com/a57b5d64-218c-45bc-b964-eab29eeb5981)  
> 39. Hear2Read Text to Speech Open Source Software, [https://hear2read.org/](https://hear2read.org/)  
> 40. Xenova/mms-tts-hin \- Hugging Face, [https://huggingface.co/Xenova/mms-tts-hin](https://huggingface.co/Xenova/mms-tts-hin)  
> 41. Releases · AI4Bharat/Indic-TTS \- GitHub, [https://github.com/AI4Bharat/Indic-TTS/releases](https://github.com/AI4Bharat/Indic-TTS/releases)  
> 42. AI4Bharat/Indic-TTS: Text-to-Speech for languages of India \- GitHub, [https://github.com/AI4Bharat/Indic-TTS](https://github.com/AI4Bharat/Indic-TTS)  
> 43. arXiv:2211.09536v3 \[cs.CL\] 17 Feb 2023, [https://arxiv.org/pdf/2211.09536](https://arxiv.org/pdf/2211.09536)  
> 44. arXiv:1612.03371v1 \[cs.NI\] 11 Dec 2016, [https://arxiv.org/pdf/1612.03371](https://arxiv.org/pdf/1612.03371)  
> 45. wifi-direct · GitHub Topics, [https://github.com/topics/wifi-direct?l=kotlin\&o=asc\&s=stars](https://github.com/topics/wifi-direct?l=kotlin&o=asc&s=stars)  
> 46. BhasaAnuvaad: A Speech Translation Dataset for 13 Indian ... \- arXiv, [https://arxiv.org/html/2411.04699v2](https://arxiv.org/html/2411.04699v2)  
> 47. sherpa-onnx download | SourceForge.net, [https://sourceforge.net/projects/sherpa-onnx.mirror/](https://sourceforge.net/projects/sherpa-onnx.mirror/)  
> 48. Development of End-to-End Speech Translation Models for Indian, [https://aclanthology.org/2026.eacl-srw.41.pdf](https://aclanthology.org/2026.eacl-srw.41.pdf)  
> 49. NoBugNinja/Smart-India-Hackathon-SIH-2026-Problem-Statements, [https://github.com/NoBugNinja/Smart-India-Hackathon-SIH-2026-Problem-Statements](https://github.com/NoBugNinja/Smart-India-Hackathon-SIH-2026-Problem-Statements)  
> 50. SeamlessM4T: Massively Multilingual & Multimodal Machine ... \- arXiv, [https://arxiv.org/html/2308.11596v3](https://arxiv.org/html/2308.11596v3)  
> 51. BanglaKontho: Closing the Long-Form Gap in Bangla Text-to-Speech, [https://openreview.net/pdf?id=OLwJbUHEQA](https://openreview.net/pdf?id=OLwJbUHEQA)  
> 52. A Unified Framework for Collecting Text-to-Speech Synthesis, [https://arxiv.org/html/2410.14197v1](https://arxiv.org/html/2410.14197v1)  
> 53. The Development and Experimental Evaluation of a Multilingual, [https://www.mdpi.com/2076-3417/15/24/12880](https://www.mdpi.com/2076-3417/15/24/12880)

[image1]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACwAAAAZCAYAAABKM8wfAAAC6klEQVR4XsVVv6uPURh/nhgUN4lIWGRRymA1GJQMDDIoSjHczaCkTBb/wN0o6Q5SXBnkTgayMTCwmL4MlLIoSuLr+ZznnPM+57znnPf9ftX1qc+973mez/Pj/PwSzQvODf8Fpgt82qZq3zlavpHgZo7M6YaJrRndx4zyQfTyzdvcDNLRuzMH9gtvCt8IP/r/t4W3pJKQLgq3dnJaL7zk/AzNIC/4mOOJvR57RbiBgHSiZqSfSDgR7uocDvuEn4RTP14Uvvf2gL3Cz8KTxgY8Ei6Z8TXSPC+EC/0W6CWl+iZuCFckFKthsUBaAIUwczRxKlFoo1OpisYtlkknGBp6QppnCZbCSYH+cm5MoEG8Uf48FV5N7Q5h9VAIx+excHPndsCq+AklbdwTHg0DjnnY74SvHgTa8IncWEJo6rA1+phzpM38JD2P16PAge0O5HhAOkkFOw3q2J3AZM/6avg+aHxV4DhMJWSPxO2U7yPCM8IfwvuSbLcVZ7OXYq6RSWK1UP0mUt2q8LTna+Er4ZYgHQFGomekycJNxSvxQXgegiiNiCZ83CGNXYnuHpweKw3dF9LXCNTzXKigtpJDE30VfrdG1nP6m3ChynHANuE70sLNy8J6MZPjILbn5OxdgXqpDm5LWQvngH05N5qkh0gn+ouy818AVhKvhL6xilW2Z3wYjLODM4TG0LgFVgI3Gm9nDTgGWN271F4cPzGOk2qJA0qasELfqH878RyhGffUsf5a4fmzmJBqFovZO4SLuSN3zAr5GWSs7lv5Tl8CaZh9w76XA8Lt0ctua/+QPnfHhOuiz7kjcBcekubqvQbteVa9VQfOV3iC7E+xRx5nx7kPCLaCz5gSVUGqqDrWAK3aJV+cSck5D7iw1u3lSjBO1UJhu+I4NwRUHUDwtTRriGavI9AIbyxdESoa2t26q+6ZA/+arB4/6wGo6Hh4gUuVikKDUswgMrEbFhJ0poKTcitGZV3PnCzEUFzFZ/AX9Ot8zFh1jRQAAAAASUVORK5CYII=>

[image2]: <data:image/png;base64,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>

[image3]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAEQAAAAZCAYAAACIA4ibAAAD4ElEQVR4XsVXS6hOURReOxSRZ5EueWSCmFwGVwZGJtwyoLyiGJDMJBMDZSApI6+UbpJ0dWOkW5LEQDG7KRJ1KQoZkKsueXzfv/Y5Z599zj6P/57736++zv7XWnvttdfee+39i3QSxhdIvqyjmPAAxg/VplbNqhnYsTo55MShjVlW75JYBvpMhWIBvlNiiYGM8hQCvUPySBxQjwcmgQvBHtuug2ngPvAV+A/8AP4Er4GLwAdgb2wt8t5jP3jV4Q5wemwdgk3ODHBxSpGLWqlcLzqZR9IKyHzCd6tUd/JSNBGXJYmNSd0NDhnVuQnZDh4HR0V1/O2Ssm9SIwYarQUH0XgrukLtgr4uebLJ4ABUA7YdwnzwBXhawoFT7ieE4G58Z3U+utBp2CbyloqqnBnVcUW+gifBea7a7VvghjvuLgySM6+4LrrVi7buCdGgl+vP4CgjUi8hc8DnojrGkAJHMemxMgMzEUwIE3PR05WBO4ADMwEzrYz+nolOWHLGI9ygtWDmmrVAuzghnIwpTki30SRSd1hFrvOobfwxMxHw6BwA74CrJMcggD+SnNtd4E1wEJzlGnlg3WHhdCYUHO4IuDItMqmEeD0fW/kVqVQOguPGYH25D74GN3s6yXHAnXED/CsaCMkdp8fIZOwJrnhk2w7chLDt8g34WXRx0jeeH4r/2wM6my34PoXhBv1dCcPgKdGtzyCiifLWCBXVjeBvcROSBMdE8i0ST9LoF0XYRDEVHZklootJ3VlPVwksfAfBj6LHxYamn5Ik8qboE/chpT4YzCg691CQ46NLNJG089VrwCHRN8YPURt+n4iOR8QJ8TtbHJUoBg8B+xgsgHw3nBcdpBgtbymXO0VX2wVX8YxoQIc8XQQ6uSBqw90QQreoTa83k6IdQmSPZEkmlom+BLkKRVdjGXiT8DUZw47LiYyYcEKI1eAXcK+vcJAkhEgmVZIQs0f8hAQwG6xTH4KwtZI3Cc/rCkc1F3wo+oz23yc+6OWX6DXJl6kbF4v1beGkTOYdwsLPwskJx/9zjLbvWTmTvc7RJY0ksSX7pj3w8YNJGb4Kj4HfwXNS6cprgVc8r+moVjCR/Qg18tMHsthH0UcrHyJLAItp9C6ycOauzSQ1DaeF7paC28D9UlwTisBX7yaQ253Xffgd400g9TM4ucy2CFo6KLeJLcpNJc8oKylGyz7UKSSvAbrwHzJF5KpZ6Oj1YqhnPVa4eyA9coNxNOW4vGeBRYEq0eUZZWWOhM2swYRiHMKp4jLXJlfYAYxl3PK+5RZh5PXNk7WLJn0168xHfedRiarfM4yQr5C8afwHvQ2x6C1ILesAAAAASUVORK5CYII=>

[image4]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAGwAAAAaCAYAAABSHbkRAAAFiUlEQVR4Xu1Ya6hmUxh+Vy6Z3I1r+EEkITJFSTo0boliqNEo4oeSRtFQlI7kr0RGRDOjRO7SIfJjXNI0FD9MyqWGRJREmUJmPM9+19p77ffstfba3+U4xnnqOfvs9b7r9t7W2p+Ihwv/1PAt8wWDMGb36WCsRY3VOYkxRs13zUuXsGBIOiIpGAFmrEkO/b/DQhqvZK4SnXmoOpX1LNMyGNppqP4IOBl8HPwU/NY/nwKf8LwJXF5ri+wJrnWNvI83+j6Xdsi6uA7cR2K0jDCiRcrGOAg8EzwR3MvISnEMOAseFbVx/4dG7wGxTjGOBq8G7wJ3gvf6d/JubO4bPP8E13r948EfsOW/pTHyRvAX2GGLqIPXgA+Du8A5UQecBj7q29j3HmnmIR/wsmckY9GsSAoSLi07EvwR3AS+Bv4M7tHS6AedvAH8HVwRtdMxtOM70thsi1NbjwxmwHZRB0ZwJ+DP96LGJG4GvwDZHlA5EbwiaiNekcpxLtgJAVCN8z64f6NWY6uoo7MINk/bfjAOFDXmYVHbKvCO6D0PXcx1ovvrcFgV+JQFMgmGBkSNfUUXzCyzCM7gJCyfr4tuMEbIpnYpE3kWXBm9h3GsYwMY3ZfbxgRKNkudPr9yzawCf1mB6FpZLbLwE+wH3iaaSd5h9dTMMB41E0NwyrlWIE3U/CF6Hs22pJopzBifgS37vCDq5ADqcB7OF0BnB6Pw/9MjWRd4RjwvTZQ+BB7Q0mjAqnGZbTQI5YpGtuAcT9rGDnDTd4LHSsthNQodZmIrE2qMeC7OHow09teihrnGyAKow3rP868PnGOzaDQSR/j32Kk5wFnuMzyfBs8BLwHfFp6fItfGih6PQL8rCGPQsDRwymHhDDZoWfMs0aCNnd/lMAbPevRcL4lKkvZRWxIOfEYIB58BV0Nnh2g0M6pTYHaw73bTbkEnUe8NaS4an4AfgQdTIb3YGreAN7i2Kl8vwPMrUSOE2x2D4S1pgiOFPodtlvwYPB7msIoLJe+wL8H7RG+gM6KJMM+uBTaoM8QumAvhbS513hDMyG2YhRu73QoNOI4th+/6doPksnkh4hXZoL7U0FmniDqODitB3mGODnMph50tmuHL/QJSDusCdXeiH6vEIIQM2WYFou2bbGOEsFke2H2lh+fTnGuXF2ZbaTkM4EWC5XAj+CB4Uktao7Ig9cgcVkA17bCuDAvx5OgsR6cFeIe5Eofx7Of4vDkPAg9Vdtxg2mnYPofxih+cbc+/GCx5LH32FkpjDvlApe5johegV0XHZBXAWdV58eD6VibzVdF36cjtn3N/J/qDAxk+f8ifROdmRbhftIrFCEdEbvw2XGNIdmyur7pDlq6+CHhRVKfnY7fOxHYWmh65ATyuAp8DD4mUeQ68LHrxWGbGeE/6S6P/pHFdlybujU5Pgc5Wuup5Mchzf4fT/xn0ISA41t6ho+i62NZ3lLSwTrQTb168gcUyfj9B5kJWnAoeHsm5GG6S0c7Fpb6LeBa+JDpPdbkYAwwq+w0Yg7cwBs/HouWyQkEg0OmfSzV2rc1Sx0+GqLvjHphVM01bC+eLd5j/P2AV+Gb0zkrBXzv4KVCwPP3W+U2a9N3l9HlcpLNMZe5DPK8XvbbqJSPqZ2g/Mq08cDbSWSw4Q/SXFu6BUc+MZfbF+BX8QDRrLBjYdp/h6k6n3Cpa/ugoJshYv3SkwAtBuILHP0V55IMjLzUYpDw5mGnPE3UY98tPnMmgmeRK0fEvks5KMcAIA1R3X0zcCBMfsAT/yqQdWCzriLAYllStIbeQnMxggGqFofoTw4JMPGCSctVyzSVMG5EvxnLLWJ0XEvMXOr9lCbstFtTZU5/MTDDqfKP2K8VUx5/q4COid029CtNC18Rdbf9N/AMxHSkOgYt6QQAAAABJRU5ErkJggg==>

[image5]: <data:image/png;base64,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>

[image6]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAD0AAAAZCAYAAACCXybJAAADo0lEQVR4XsVYTahNURReOxRRjxS90CN/E2XwyhsTA+VN/JSRmZQyMhBTEzKQkaK83kARMylJelEmBiZMlAGFZGDEQPGs76y97/5be5/9bvfmq++ec9f69tp7nbX2vuc9ohaY1JAjl+QWDZoqtpnUMAQaAzTKGpFHgyWyZhJDJrP9Z4xlQaOO2RTPiZrEHpq8HCq31FGONBr4uFsCq0XSkIb28Oc+5prQmGAD8yXzE3OBIm2GGeYtRyPX68z1lrgf+C1nshlNvIrSfYiNzGvM32UJrWJeZT5n3md+Y56NFB7QHmbeof6kNzHPMX8yF5mvScYihosDG3zQQIsxOpIaeaPHMuZakkUtsBOBPWQDryB5uphw2o6fJb+IGqYpSzpbETDJ/EgS80LiA+aN+KCBNkMWNTPksEl3gVMcYv5hPmau7CyGVvPnReZpL1OhJK0iSlpZ7zz1JD0MgqSzKec6u6FLqaMBkrSpJ20aKk1B0tkKFUDTp6tV+h2J/RRJZX8x3zAPUBC3MEGXtPGVvklyuDEN4u619kkO0Jy0tR3kGH+tPWXUDYW1VZN2B8wTkoMFWEdyuHx3ogLC9saWwOLROR2CxfhKG7rL1+MJX3S+IGkee5QvD5nLyZ870AziK4jy1w8ygUvatnc3DhNhwsXumw0V3tsbl/RO5jPmFZIFpmhub+OrCJ3THiM5d94zp6xNUCozBZVWNF9JJsSTDeEWIoebDiT9haSloX1L8hueQk8ai5EFDZImnzTW7LbND0u8PzSj1t5uT+NnKoRdiNEPKVkskkanHGFeJomDNkyhJ+2hJe2AFxj4ztvvKMJWuQ1KqFSzVuk5kqBnAhtk2HvaQwqBpLFgjJgw0uJow84QYNikZS+b7kG6bQN/+mA8gmmnSE5kBE73HB7IU9Z+oMET7F4dUUGtahTs7v3MRySHGICXodskB9MOJ2TsZn4mmR9vezEMPSA5b6DZbq1oZdfWDttIYuvdZwEngkU0cp0N/qLazHxF8rNyg2Sie8yJgSIGP2njKueIas3a2I4LzBOJDnQVDTqgS9oRY9A1qR2nP661cyZFUPscqBLa9SRzF0ur4qVCD6ZbR4qx/K3cA3VK1ejR4x4FylOUPYI+fwt6Y/QKBJ0MH436AJURFVcEVacax4elT9cyoqap+RqgDQ9tmj/EOPZwX8iiP3AUNQWU9JG9JPLoV0QYWl4amP80pN//P1paxklMPQHx6f8G1mwhSm5n/we7xdZfm1QBEAAAAABJRU5ErkJggg==>

[image7]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABoAAAAZCAYAAAAv3j5gAAACFElEQVR4Xp1UrU6dQRCdDVzRhARaajDIJlUIHAKFKALRGgQP0DfgPkEfo2kQNQSJQBFSCbYNSHgATBMQFdAzO/Ptzs7s5X7hhHN35+fszs63C1EPyTsEaYY/YGxehFH6RbztMCcssEmjT/MqhMWDYxw6shVwC1wHJy7mENSDdiIxYc5yqcfgLbzfMZ6C99nbJDmFmO+oaIm1rNstOQ6fwK/O9x48A9/M/j6JdU/E2prDuj9JtcVLUtcPcMcVvERS3cfBoY2wYB1vtFPblXUXKK5oB9Vb8ArcVNviGdzzTgPWPRBr2xqOsHzQroF3adio/SacPNW5DQy4Ax+KtgIbcZFJtCrlaqSquNgzHtDUPiKXwbqsdX7diKa13Yk28PuXwkZ5bk7UBeuajXQ8wiRoc+soHp/a1rFZJzrllms3SohRTlT81FwGlcvAv5z8RfNME4pRL0MFR37qiYqWsQieUHhkia9p70PbBrOOF9w1Ncr1jgVkrIK/wGUxs2pfWSFV5mvL9yOJ7pKyNi3rZqz5p2MX1+A5eAB+g+YRwoVafp78Bm/AD4OX5FFa7SNyDzEuSNicX6f8T3SbcnL6TFJtgJGZeZrAUC3rbNYLyM8mFmIQPcHn1ogYgon/ZmfOj3QzUn383Xgfc4vO8BneHoFGkjvQ2mZonT3kkFnEptb5CwuMQbsoW90yC2a4O7DHD6rgmA8j+Q8VA1L54+Cu4AAAAABJRU5ErkJggg==>

[image8]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABoAAAAZCAYAAAAv3j5gAAAB6klEQVR4XpVUPS+GMRTtjRgkEoLFLhImic1gMjBYLAY/wD/g14gYLGKxmeSNkZUwskosEgYSnNvbPr1tbx+Pk5y37blfvX3bx7keEIVRCd1cQTRSjgW83jK2DRkyL14MC+tB/67+Rh6qV92BFAqP7ShbMEDitoDfsSgMxDS4Bo6XhhJcYxnjBfgEzufmJmbAMycxR+AruKUdPMLN4l1MgqvgO/jshhXaBL/BfdX4HHgPXiL7BAvJ5mf+pyrEavpv8hE4dlJoo1PIb3jkpLMlkUpQKkSqULDpIeLWsb/EaZyAP+C20iQ0JKgKda9WQSncOfsXhSgWOpRlZvSojq708qsksa8vVOTKCxmoCsUEOpFq8o3MjgYVourWGZ17jeSIU6HgSKqQFcsoOmq5BVC4DHlHHHTqpNBOFMpU1dGVCJ3EwHMnCfUDjdcbeYhPSJmcX7LCD/AL0xeMK4YTJwUpXttZ8Aa8BqeCtgt+hjEhpOLdhyQZR052GHGHgEeMi0rjR/kAXoF74Ad4AMq3skPcNLekGsieT9GYAf6ErTspxF22kGdS/0E32rVs1cQ/XAW6sNG18SEpUDkkoTJVaHm09IGI3QxN47uk7AZUExPRWnrla1k1zyWvZW2ise7QSJ3yNSMrsGfpbXWjZ783fFDsU4uslwAAAABJRU5ErkJggg==>

[image9]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAEEAAAAZCAYAAABuKkPfAAAEDElEQVR4Xs1XW4hOURReOzMPMnKNlBo8kEukCVGSQiReUIooHlySB8otD0revYyHkTQ8KEVeJkKZGjVE8eBSpFBI8qCQS+L7zjr7nH322ec/5z9D46vvP2evvdfae62z9tr7F6kJ4wssMh2meJxFPKB0XBD1tMIosFUgroXmbTWvUY4aNr2PmkENcxWUSgf4aIPGeDxb/Q4Pw8EhfCmeocDbvEJeMoig4y/Ay+A9rG2212+xCOwDJ7LxFzyosMczMIz+BHAhG15nEVrAsZKMTx7+194M4az4fTH4ARzDhjPRIfATuDYV+ai6rAAqqC4Bn4MPwC7wMTgvMyIMBu0V+FlUrwtz3cHzuzNmhKhsWNxm4M6Dd8E34GvwrWhgTkql5VZB+l24yBmpLGifi3oHTnFk08GP4AWj/Yq8Ou0/EQ3AKXCDpM5adIBfPNnBTMvIOPwuSJu1kFPjnrsKPgVXeH0+6DD2qYyyAqMpzmzwg+ODQXgo6miEQKypTzsuTugjGsggdxo2cm40iVif+3o12C9aZKIqW4I1YC/Y5sj4TtlvcJUj95ELQgBDwWuiVZ+g7Utpt6wDewbqP8EU3I5IcG+d8zvdCQKTMTV7pTgIOxy5DxuE+aLbgWRQW72MYLGjs5QcADtVbCaJ1oa52m6AwMKJyeBZ0b3r78MSZCw2CIJhELL7Nwt+3RuiDlosBX9K1l4IO8Ezkt4b9ov6wgK5yw4KQ3fOdfAZuFEKLx8FocsjFwSTzYRGQQjBnhgr/Q4Pt8H2eJV80J920dOkJxlVgJHgS9EUq7Lny5ALQvxOWRyEUECd5btPzY4+NI8kkjzoqJs9vEO4wcZdxbCWFEAnmyZ6+2IN2OZ210DDwmiifjcIides6sdFU3hT2p/odjsyFzRwWNyjV/W5DgueTtGtMYvQx9DUY5E5KvENLEJ4bBHmiB6HnNiCd3xccw0d5BHqIDFu057ZciXpTnRlnyNzgRuj6VcziS1mgRsEBrIj70Ze4oO1gTWCdwPeE4IaeWEk+SX6dexZzXfKlseDWHhvijrMwjU1ljOleR8ZHbe5Bl6cOM6byrQY/WA8HfxlLJPsKcTAJ/eWIHwLHlgneE+4L3pnqFI3doNfwdMwvj5+3yLZqbjIb+AFSbcO+/eA70WdfwT+APcmvekjug+gwXqQhYnsXRQNIodzm9VENFuybtaNbjSPWUEJuJ2YklsNv3zWVgkMtwD39QrRohfCLXCmL4yg0/C04D9IkgEZHIRcriqrAHtjLAIzlv8hyCrZ2wg1l2hRS72RUqO+gSJjOzxRWFoRnnJ9W3U1Y73o0YyN0NiQbDDxP62nQiL93witOSSrjCaVmxweo3mtpndCHiFtKwv1/YPsqOpF6ZB0wB8/y4uFPonC9gAAAABJRU5ErkJggg==>

[image10]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAADoAAAAZCAYAAABggz2wAAADg0lEQVR4Xs1WTahOQRh+pksRIuRG5KeQkiiFFCXEgiwVIQt2FOmmKKW7YaekbkpXWZEdShZfLCxYiUg2yk8IWbBQXO8778w583/Pud37uU8935nvfZ93Zp45c+YcgKH0b3WViw2OFur+RqNnr4/8nwClnEWkiQIOMjk3nFzclvD6aNhDStZsobIJlHP/AyOajyrWSSotCG5m1B47DDtKRpAJO4lJxJWGU6s0I1sLk9M/c4gbzDWsmEWcEsR6EOs8cNHcMJhCqZcgR4Oqr3S9SRwkcnuiLyliM/ENpPY18Qqqej1SH/EPccDwAfE3cYJo8uDV2EJ8SnwMO+8Wzhz0Uuol5I66+A65uxqZ8jUQ3VXzn2/AW+IQ8agVQYzehZjk+AInl4E/Iv9bBzHMxnkBnFQGfmo3ZGJhhZ6sF/MFfDfY4F/iVhNjxR7iOeL0OkRGlTZboTC7GrWoaq0i3odsm71Rtox+5I0O6lba7RLiR+I7is2v84g6gtxRx2jCQRmRbDnxNvEwcXKQy4HNaKMBONZBeDDVY+6EaJ4Q1xO/ET8RTyEe2xq9QOW8ffcjPpyaIDJ8BnqlcRLBoRIo2UQHrYxW4ImzhrfuDidud0i1syBbd8AZeynxM+RgrRHZqBBl+DC4TDwOb8UinQVr+AQcijRKjCrXqC+xRnlB3a1r43ecWAi7wO5iNMJi4jXiB8i2bYNGW9f1aNonwBqFFxSZ7aR36bicvhkoPshuES+FmQz0kPRcqmeQ067Nu8+iX+WN3oDr0b+j9hl9RJzmxI1RZY2uhZi+HuwZXmBm2K8HTtmTll8tPQWtA6PyxGof8kblpEx3bk9dNuF+wPB7kmv5kGLYrfxF/5O+zNZV/DwbOIOY5jyIyfTw7aGoo/OQ97HFIsij4O6Qn3DNC7ZBvnhOm//87nwIec0tNDH+JOS7vl1PWOl5H4L0JV9GwzupFalWC/Ch9J54jMqPQD7nZvoS3CP+IG60ASWDHSD+otZZuj6HfGWtsBozn9XEV5Avow5kcS76EufaFl6dWc4ClkFOQdrKits1jKM8FL8m+BHYhPw5wfGDkGe4N8iNZxjrpRVILW5QNlw5P/weVdVWbnxGVdUUqckl0UTDqHVNK1qj1HEp1z10eRbecF0eO4/kRJLBMUIwlv3rhhtMpygprXyxsIJRNROPTzSbe1lVzmKE69ROzfgHTjOGcQECv+QAAAAASUVORK5CYII=>

[image11]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACUAAAAZCAYAAAC2JufVAAACnElEQVR4Xq1WO2uVQRCdJVoIAR8RISCmE4L4gHQWVhZaKKiFRX6A/gJFrG39ByIWNmppYSEaLLUSFG0EI2gh2ASSwsLrmZ2db2f2cV/cwz3f7jz27Ox8Dy7RHAih9EyJEH9jUcWdo4pS22fgw8HYOmsINFwFrNACMatonW9PVZ6w7EXLqwi9QBe99EPgefBEN8MEOn09Dt4n0UoY8rI+0X4fUnjHM/A7+Ah8CV520enAGz0Gt8HVYoMj5PX/2GALl8Bbhe8z+Aq6B6JVnijapZM+wvWPhqIGsD777ZKjxPok+uq2I5/uYrIVWySnWWcj3yoZXTkhRtGlcI+kIFuU6pdFLZPT95qHYX7AuGF8jCfgCLwiZl5iv1NmegNco7oo6BPr7xoFnRh9j9XAIkGKMptoUXfFbFeSDC7mNUkhrLWN3sWigvpQVLGQYfQ9uJjdNFoURXVxFXxO8mxoAbZToh/iHiW6+mfBHZqrqMCv/1uc/1TsQchFpQ4xoB9YfyjK9GsEo6GfhWYtirUfgreNz3dKdje3j8o72NXXB3GjWPGUZNF16zQ5vNk32L8w/kjkOa8ZBZmfJvOg68IEFmroC/aBL4g/lsN+cbJF7WdNsQQeIylOyY/Cz0Se88dU9bmAhPgJWQ5O37eQ7RVc34EHjfcveNPY/OrGLqR5Qyecw/V34hkTYf335PVZ2+q3EL5gkzeYbGL+AOMdkm4o+LX/BH4FTxq/whattF1exxmSPrH+HvZZcudiozgot/oCyaJrPpRRNmccGrlWf6VRQ0KK+H+T7dSxmGOJol46eHKoTpodi9BYLCZVxHfGGjWSsxnzmCLFYHK2zehn9yMOU6YlzJZdoap8cCxQeCxM4uQ1ksHXzv/yLNIJZ/iEVvp/DY91EzUas6MAAAAASUVORK5CYII=>

[image12]: <data:image/png;base64,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>

[image13]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAC8AAAAaCAYAAAAnkAWyAAAC7ElEQVR4XrVWO4vWQBS9g6woruBjUYQtRAQRZBux8w9Y2IhgYSNYaGGl4DaChZWV4KOxEQsL/4NFwNLWRRAEBUUsLLURH+fMI/PITHKzxMN3viR3zr1zMrnf5BMJMPazTWw/cy7+00zaslqdGhMFJ4YzZFpdok4lUahOCJidUMFkjUQwqY1YAZ8qeQc8MKe6XtnCeIVTEPzE8aXQoLEm/4C/wUfgRfA2SM1X8FhInItxGyWormQUoU2xq5nhL/gG3J/ELjNm8pirZQY165gpatb2Aay6vMijhuZonmZjWOQC+LCPzcFgdqnHNEjyroDX46XFafA7eLKI3wSvFbEMuR+du5qqjGXX7QsL2x5StMcozLDIcmjWtm2SnNuvZ1Jpj75Es9ZyyBw1kY66+2DL/JBhyyyFB+J2sQ5cBTcw6RPMu152Q9V4NRjBnuaPVd8yGuSTcnfrwFXjfkec71ymGEX9DkLLsNg8FPXiZXWi3ry4FyS3aSscqJt1h1gDt2TaPCc9XAbHUEyarnw5RjB0UNw8I4iZFN4VZ/xtH81xAvwmfNQu77PwPeHO94ozFcDzT/78EvgOPApyX7ovceXXEWKd8xRibAsCvnsCboC7y1sMV1zBD+JMFzR8Cmt5ntmJrx04cmW+iJ9UnBFv3iZsGmd+D/gKfO51dkyi+SPidKHOa3ELIb7OGfCsH6uAmvzGWliB7haOH437gwbzpmLeIqx8MNebx8pizHRSN9+ZvF24+4Ux99ziRXI+javiWuW4v+afNLbEhp3QmurhzBtDI53QvJ3LfjXM27Hy5chVH1l5PfjiSh6r/BL31+KesD2iebp4LLHnqaEpthp3F+5onWTmwxM07/OeN6zJhRlDZbQSEhdt7QQ0dsgfd4H78mHGDH4v1kwtPyCZo26iFV4GvvjkHKkgE9cyQ4xHe54GlNAY87VHNbNRqTa8+YpIBV3eUJVEhoMKTCU1x/OBpqyJMqO8HoNKqxKJXrcM/gGQM2YZGZ2IMwAAAABJRU5ErkJggg==>

[image14]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAEUAAAAaCAYAAADhVZELAAADtElEQVR4XtVXTahOQRh+J5Ti5urqStgIdUvd/KSUHcLiKkopP+lauAsrFmSldBe2spKNLCwoScpC+srC34aFFYpbkoWUsrDw8z5n3jlnzpx3zpnznc8XTz33zHl/Z977zsz5iAYAEwqGjL7zpzuKZZKDb5TkQOl2gpbmMXQKU+/cpmA6Orj2hWHnC6Cn16UxtLMG2nukYz7zqk8TvAecrs6mLKioffjKWsMGtPFtYytYw/zJvEvFwr8yfzOfMk8wLzMfi+y+dfs/kVqfk8y1gewT2QLs82QLmHfIFqgDEltFU6XK2iKIMcHv94rXXOs6YqEnwza7ydzpBH8PkZVGxIPGNPNCIBsxtijnA/li5i3mRNPkGtRUZ2E1gT5urqONfaLtBPMXRToiMYYHz6O982ARyR8RlxSHme+ZK53ARL0GhXYJMmul1nVRuqxhGfM187SSAgftOeaxUFGg5LOF+Yx5hbmtoq6EjyDVTtDS3EPcczPzO3O7YrOc+Y75MFTAtjDPRnz+mB7Zrptkrs/VKqrJUpDklWIkNjFTXM84ZNExmtEoZTeSQ6kaPlYw37BSPZcA3c0hro1rBgSbIE+DK/c22aL0n9t6oigvyXaerxkje4vJq4ZcPs6jeb6mAdje6GaJnwMxxotXL29sCgL89w+SvXV+UHwyuKZ7Ml7F/MC8Ju/XhQCKAt2UvOOc2iNjFOaQjN12PcV8RFlMc5afD0R/nOxX9RKyufGlvVVWM8dPV/QjLFlNdt43mK/IxsZ235BZGHpL7mwLENbGHazoDo04E3xgwj0MTLHwA6JDQXDeLKJqUfCFvEnkoCskJv6ZcIZZuPlcErvdZOPgXEJu/NQYkVV8Y+4lm68454oV4sv7OXMd2VhPKLtAOiOsoVoUt3AUBTq0L+tMrmPbj/yYFB8QZxOAoszJE3AxL8oYxJbA1nC53fZAhyG+8wmB+aAQ+AnjYuECUJalocYoUOVFocaiQGemxP8L2Y/CEGFRlrLPC9J/X2W5TVAUee8Rplqe7CzZrssujRhqll4Pz7G2KKZSlNKZMuMFOipPWxRTOpDPkD0TkBgdgrME3eJ1ShZIOiUbzxA6wsbHX/hsJLttd2VSov3MHTKm0qrcsO8KeegjBrbNaO6nBPBEuDFQFM1MA2xzHw/4mB1zURJjDQjDzaYiNoWY/B+F0WesyXRhGQkmOiJdVBHkiGuGDTeT7Ckv7WcXKYAiEUQVAWJ2qTNtNBg6uvyK9tA9SjRCVNESDXF8tRv/ATgphzhTzhaRAAAAAElFTkSuQmCC>

[image15]: <data:image/png;base64,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>

[image16]: <data:image/png;base64,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>

[image17]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACEAAAAWCAYAAABOm/V6AAACVklEQVR4XqVWO2tWQRCdIQoJWkg0BDHgDzCVwRSaMm1SJBYaf4F/wIBVGhsbwUrExkqwEkSbhBBIEVKl0k5IglikFNLEx+eZ2dl7Z/fu/e794oGzu/M4u7OP70E0FBxa6dCwDvLof2DYBLpm7hwVbRNEv48nuW1CQx0uzVT2ZIcXYL4qNHoRvs3QXpuic62SrwFJMmp+h6gjnMIn1+N+U/hieilKSX121IV4QImjE72SRkOYstk2F2uUXEZnCvfIcRiaWwWZVsETjI7BE/h/65jpGONvSHhciUbB0NVTzCN33+U/AN/XJt0Dd8FDcA/cMUrBm+BVyxM8x8Kv0a+BE1RrZSOHJDpW7VcK+kp7G7xp4wvgO/CpWulOjsDrzr4LHoAzZi+BU+Ad0kXps/kFb1n1yYSij1oHphsUdrySRQR5EcjndbRz0I2j/whetoic5t8qD0WAR4XbmXO+OOSXFHYxnUeoKkK/iFf8gzJcdMnLVCiCQrJs8FXjvZh9Df0X9J/AcVdY7AZof1C4yzMLpgip8hbkVLZqlxYxgBG1YhexAP4Cn+UBm0h2Eq9j1np5Q7fAMbMl9QmFAibdbuNJCGbhfmEhaDlqVb3OUm24zwLYF3HFenlYG2Go066Cb8BLwROrYF+EaOOb29D/KHWxcew97lK48TAnwW1Eluzneh58CN4HH4EfQpoGfREGFr18onph0e7SvwnhH/CU5NOBnWNHcgWSE/lTtfUbGrCOWezvphdtG9LT6IWixDmL8XMhmylfo/Q3quBKfe2G2SVfZueuXkhF/wBSlV0RsGfPewAAAABJRU5ErkJggg==>

[image18]: <data:image/png;base64,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>

[image19]: <data:image/png;base64,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>

[image20]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAGwAAAAaCAYAAABSHbkRAAAFhUlEQVR4Xu1YXchmUxReu6HI+B2/IUyjacSMjFKS5kIkceGnFFJcuJuLEQrpu3HhRqJJRIyS8pvERC4+EUJxQWqiPhJREkUhxvOctfd71tlnn3P2ft/zvt835anne99v7bXXXmuv/bP2KzIEFwtmQKetzoapkGOt0slRzIW3lTKZkgX0tc0dYwy+EBuDCm0UdSlSHsBYtkrt9Ov3t3Zj2n6z9OzHvOxmwA5d4EaB6lpB0uWk0GJQYSHYAj4GfgZ+6z+fBB/3vBXcMNEWOQjcadqHeIvvc3miDXSx7A7wECnCtBPZ6ncUeB54JniwbWhppnEqeAS/dOhb+4Ux1jgZvBYD3IXPf8H7+L/n3eA34F/gTu/ERvAH8B+pJ/lp8BfwQ9EE3wA+DO4HXxd17hxwt5ex7z1Sj0Pe79ueBV1HwA3k6OTDnYg/P4J7wFfBn8F1BYOcIDpPV8YNHseA+0Q3A+3/AR7a0CgEd8CKaAItNsHn70Unk7gN3AfZJqMTkniVkRGviCYugAuAdt4FDzfygI+kqT8+0gk4EnwbPM7IrgFvN/+ncDz4vugCZAIYWyph68E3hQugBufvUdHTxyPtXAqHiTrMXRYjJIPObIHN12CYAVqE3RRv8+fAS8z/wY5JrHdSP7i6UwEbpIMyUn7dio+9+HZpLe4EfeYp8HfcIOorT4scrHeThLV85Lz+FAuB38BtsTAHISkXxQ3AjaKO/Cl6Hy01WnWncMeEHTgB3H5B9I4M2O90nI0mKCY7TAq/b2sF3Pi3NRkWV4CfgB+AF0ZtXThJ9Nj/PWGaMT0RCzvAXdS1wxgf27YaGUdbFu2XRtufCbjiafDYWlRpc7K/Fj2br6vbGraog/Pe8f4bAsdYlsrJygLPff5vk5oN4wNPCN6dz4Cba3EWtguTpYxBf7H7XHxypNCXsFPAL0XbHwGPFvX3fKtUgnDhs8rhitsBXi/VueyeFx2wC2H1rETyBpwPCJ9vSF1ofAp+LBrANGD1+oBosfBg1JaLoYQtS7wL0iu/L2HEuaLtgYx9Kvgdog4bX3hP8TKNCwkL7sgvRB3YFYTpeCo7/jic4B0vzwVNXwC+JVpxNUrvNDq8qZFOmHZLJyyNvoRxLnk9sBI9S7S4oi53Xd9mkJT/YYdw4mNQvicWGmx3Gigv7NT9Z8H7KZT4AdxtJcch3zFM1oqUJboP6YQpxkgYZ/wh0aslgOX8naL6S0aeRpQzXqrs+FRTXE3sUMJY4odkm/tP4kF45PHoi6tQFgYZu6QF3lMvixZBM71lxBYdbaTjby96yroSdgb4HRR47cRgIRcv4l6EieRAcfnKo4tyvp268KKoTvXYjdoswioe2oWl2C16nN8rzV9jsuHqJ02qaGJsXJQ56EpYiP3qhlRna1lKEub0ZyAO8rm0zlLH9xPbwq44W/ShGMBBGCRXyWXQt49CC57fL4naahYXfSkuB3cq74S9ouXzxHrGMKGKs+9L3pU8ymx3xsB7fYeRBZwm2n6TTE6Nqiv/8PjjtWFtcb44JvsNgo81Pto4gCW3r4fjUUMZX/I3i761bJGRYrxL4/bAJaOTM6G5WOf0mOU7jO+xrkWUAqs4FgOMgQUUf2rj7rP4FXwPDvMYJbirlqUdX2AAE8hTgHPIE4sV7Vfg6UZnFLAg0BLcNX6K8vBTPeKMj4jN8GspFiqCw5HjTi4WTRhj5hOnBx02+sFfXmifT6aSYmsxKAqlgdZELhSTg2xqzNS5AweKzbWIRpzzDtrNf4g2Fj9iC9NOcoZqhsoBhNWIZjXG/B/DGMzLoEJaJSWbHcHqDNZtV/3elnQh2Zrbva+tD77fCKHPHbP7mNUzS2kAY9goxWqMOUdU4bjSsCLtss4LxhpybkxXvK3/AMXGFXiVwUloAAAAAElFTkSuQmCC>

[image21]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABUAAAAZCAYAAADe1WXtAAABPElEQVR4Xo1UQUoEQQxMQEFREMTLIt68+AHf4C8EP6F/ELz5AN/gjzz6CU1PuqczleqeLajZSVUlk94dVoRCURgg5lhP08BbS9YjVR54c9CmImreZZMljUTKQwIURQyj71BuMA3RM5Cu5VaNVbk/5gkLxsHJGJf7ta9EGu6M78az8cYBKLd686lyZddvu3+oMmCdgpsG4JMcr5UyOk4HNeOem8Cj8b6/qRmXxgOnHqwva84fq1+M52z0m7EEOhVq7v0Zf43PZcjuN3EELowfwrZEIdboBZwYP6VF2IbMSSFZf5Iy8Mv4FF2Odcpkste3Ut5TlRu0HUktAgxNGTk1XgtzGtxhvua/skXlgIOy47ab7o2GOXQvID2DwVJXTdkxIvJevB7D3wznrAu8WXRBOMUOQoo1MI1jkBzIDP9zdRdp4JQEMgAAAABJRU5ErkJggg==>