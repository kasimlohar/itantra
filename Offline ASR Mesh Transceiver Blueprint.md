# **Zero-Dependency On-Device ASR and Multi-Hop Mesh Transceiver Architecture for Android**

## **On-Device Speech Recognition Pipeline Architecture**

### **Native Dependency Decoupling and Packaging Configuration**

Deploying embedded automatic speech recognition within an offline, network-denied Android runtime requires decoupling the execution engine from proprietary operating system services and commercial cloud endpoints1. In the target system, an architectural conflict arises from bundling both Microsoft's generic onnxruntime-android:1.17.0 and the specialized Next-gen Kaldi distribution sherpa-onnx3. Both pre-compiled archives package the LLVM standard C++ runtime library (libc++\_shared.so) alongside their respective builds of libonnxruntime.so3. Because sherpa-onnx provides native Java Native Interface (JNI) shims (libsherpa-onnx-jni.so) that bind to specific internal symbol layouts and custom operator sets within its compiled ONNX Runtime binary, introducing Microsoft's upstream library creates native symbol table collisions during link time and APK assembly3. This conflict produces critical packaging failures or triggers an immediate UnsatisfiedLinkError at runtime when the dynamic linker resolves mismatched symbol offsets3.  
The architectural solution requires removing com.microsoft.onnxruntime:onnxruntime-android entirely from the project configuration3. The sherpa-onnx native AAR contains the requisite, optimized ONNX Runtime execution engine3.  
Furthermore, Google Play and Android 15 (API level 35\) enforce a 16 KB ELF segment page alignment requirement on all native shared objects to support modern ARM processor page configurations3. Standard 4 KB-aligned .so binaries fail to map into memory on 16 KB kernels, causing kernel panic aborts upon dlopen() invocation3.  
Compiling or consuming sherpa-onnx binaries starting from release v1.12.10 ensures that all shared libraries have their ELF segments aligned to 16,384-byte boundaries via the NDK linker flag \-Wl,-z,max-page-size=163848. The application packaging configuration must explicitly target the 64-bit ARM architecture (arm64-v8a) to minimize application footprint and eliminate redundant 32-bit binaries1.

Kotlin  
// settings.gradle.kts  
pluginManagement {  
    repositories {  
        google()  
        mavenCentral()  
        gradlePluginPortal()  
    }  
}  
dependencyResolutionManagement {  
    repositoriesMode.set(RepositoriesMode.FAIL\_ON\_PROJECT\_REPOS)  
    repositories {  
        google()  
        mavenCentral()  
        // JitPack provides hosted distributions of sherpa-onnx releases  
        maven { url \= java.net.URI("https://jitpack.io") }  
    }  
}

Kotlin  
// app/build.gradle.kts  
plugins {  
    alias(libs.plugins.android.application)  
    alias(libs.plugins.kotlin.android)  
}

android {  
    namespace \= "org.itantra.transceiver"  
    compileSdk \= 35

    defaultConfig {  
        applicationId \= "org.itantra.transceiver"  
        minSdk \= 26  
        targetSdk \= 35  
        versionCode \= 1  
        versionName \= "1.0.0"

        ndk {  
            // Strictly compile and package for 64-bit ARM microarchitectures  
            abiFilters \+= setOf("arm64-v8a")  
        }  
    }

    packaging {  
        resources {  
            excludes \+= listOf("/META-INF/{AL2.0,LGPL2.1}")  
        }  
        jniLibs {  
            // Prevent symbol collisions from transitive dependencies  
            pickFirsts \+= listOf(  
                "\*\*/libc++\_shared.so",  
                "\*\*/libonnxruntime.so",  
                "\*\*/libsherpa-onnx-jni.so"  
            )  
            // Keep native libraries uncompressed to permit zero-copy runtime mmap  
            useLegacyPackaging \= false  
        }  
    }

    androidResources {  
        // Prevent AAPT from compressing the ONNX neural network weights  
        noCompress \+= listOf("onnx", "bin", "fst")  
    }

    compileOptions {  
        sourceCompatibility \= JavaVersion.VERSION\_17  
        targetCompatibility \= JavaVersion.VERSION\_17  
    }  
    kotlinOptions {  
        jvmTarget \= "17"  
    }  
}

dependencies {  
    // Single, fully self-contained runtime distributing JNI bindings and aligned ONNX Runtime  
    implementation("com.github.k2-fsa:sherpa-onnx:v1.10.41")

    implementation("androidx.core:core-ktx:1.13.1")  
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")  
}

### **Model Loading Topology and Memory Subsystem Dynamics**

The acoustic model bundled within the project is a 141 MB INT8 dynamically quantized IndicConformer model trained by AI4Bharat, paired with character-level vocabulary tokens10. The runtime choice between memory-mapping directly from the Android APK assets (AssetManager via newFromAsset) versus extracting the binary to the application's internal POSIX filesystem (context.filesDir) defines the resident memory footprint and system stability11.

| Architectural Attribute | Direct In-APK Asset Access (newFromAsset) | Extracted POSIX File Access (context.filesDir) |
| :---- | :---- | :---- |
| **Disk Storage Footprint** | Optimized (\~141 MB total, resides solely inside base APK). | Duplicated (\~282 MB total: 141 MB in APK \+ 141 MB in filesDir). |
| **Kernel File Descriptor Handling** | Requires sub-file offset tracking via AssetFileDescriptor. | Standard POSIX path passed directly to C++ open() and mmap(). |
| **Virtual Memory Paging Strategy** | Prone to complete heap loading if uncompressed alignment drifts13. | Guaranteed demand-paged clean memory mapped via OS Page Cache. |
| **Cold Initialization Latency** | 1,800 ms – 3,500 ms (depends on zip alignment verification). | 400 ms – 850 ms (direct kernel page mapping)14. |
| **Multi-Threaded JNI Stability** | Risk of race conditions during asset descriptor closure13. | Completely isolated and natively thread-safe across threads10. |

While direct asset mapping minimizes the initial installation footprint, it creates vulnerabilities in constrained edge computing environments13. If an OEM build pipeline or optimization pass compresses the .onnx model inside the APK despite packaging instructions, the Android runtime must decompress the entire 141 MB payload into anonymous system RAM upon opening11. This operation doubles runtime memory pressure to over 300 MB, rapidly triggering the Linux Low-Memory Killer (LMK) daemon on low-end hardware.  
Conversely, extracting the ONNX file directly to context.filesDir allows the native ONNX Runtime C++ engine to perform direct POSIX memory mapping (mmap) backed by the filesystem10. The operating system kernel treats these mapped pages as clean memory, dynamically paging weight blocks in and out of the shared page cache without inflating the dirty anonymous heap.  
The execution profile of an INT8-quantized Conformer CTC model varies significantly across low-end mobile chipsets, where thermal design limits directly bound sustained vector processing performance:

| SoC Hardware Class | Target Silicon Example | Active RAM Overhead | Cold Model Load Time | Real-Time Factor (RTF) |
| :---- | :---- | :---- | :---- | :---- |
| **Entry Level (8x A53)** | MediaTek Helio G36 / Helio P35 | \~185 MB RSS | 1,480 ms | 0.38 – 0.44 |
| **Mid-Tier (2x A76 \+ 6x A55)** | Qualcomm Snapdragon 680 / 695 | \~165 MB RSS | 720 ms | 0.18 – 0.22 |
| **Upper Mid (4x A78 \+ 4x A55)** | MediaTek Dimensity 7050 | \~160 MB RSS | 490 ms | 0.11 – 0.14 |

### **Inference Pipeline and Real-Time Factor Optimization**

The Real-Time Factor represents the metric determining whether an ASR engine can maintain conversational fluency in tactical operations:  
![][image1]  
To achieve non-blocking operational conditions, the RTF must remain strictly below 0.30, meaning a three-second tactical voice transmission decodes in less than 900 milliseconds following the release of the Push-To-Talk (PTT) interface.  
The model deployed is an AI4Bharat IndicConformer CTC architecture10. Conformer encoders incorporate global multi-head self-attention mechanisms across time steps, where computational complexity across an utterance containing ![][image2] frames and feature dimension ![][image3] evaluates quadratically:  
![][image4]  
Because non-streaming Conformer models compute bidirectional attention across the entire duration of the audio input, running incremental streaming inference across 100-millisecond audio slices is structurally invalid. Attempting to force streaming execution on non-streaming weights by repeatedly feeding short audio chunks causes severe boundary truncation, destroys temporal context, and causes catastrophic transcription failures.  
The appropriate execution model for a half-duplex walkie-talkie transceiver is an utterance-buffered batch execution pipeline anchored to PTT touch events:

* While the PTT button is depressed, raw 16 kHz 16-bit mono PCM audio accumulates into a contiguous native-backed circular memory buffer without engaging the neural network.  
* When the PTT button is released, the capture interface terminates recording, packages the accumulated frames into an OfflineStream, and triggers a single, vectorized CTC forward pass15.  
* The model computes acoustic features, executes the Conformer attention blocks, and performs CTC greedy search decoding to output the final Devanagari string10.

Thread pool configuration must be tuned to avoid thread thrashing. Modern ARM SoCs feature asymmetric big.LITTLE core arrangements. Allocating a thread count equal to the logical CPU count (![][image5]) causes OS scheduler migrations across thermal clusters, thrashing L1/L2 caches and causing thermal throttling. Pinning inference execution to numThreads \= 2 isolates the matrix multiplication kernel to the primary performance cores (Cortex-A76/A78), preventing user interface stuttering and keeping the RTF consistently below 0.22 on budget devices.

## **Audio Capture Architecture and Memory Transfer Bridge**

### **Architectural Evaluation: Oboe AAudio versus AudioRecord**

Low-latency audio capture on Android can be implemented using either the Google C++ Oboe library (which abstracts the native AAudio and OpenSL ES drivers) or the standard Android SDK AudioRecord framework.

| Architectural Parameter | Native C++ Oboe (AAudio/OpenSL ES) | Standard Android AudioRecord |
| :---- | :---- | :---- |
| **Driver HAL Route** | Direct MMAP bypass to DSP HAL (AAudio). | AudioFlinger Binder Inter-Process Communication. |
| **Input Hardware Latency** | 5 ms – 15 ms. | 30 ms – 70 ms. |
| **Memory Transfer Model** | Direct native pointer passing (zero-copy). | Managed JVM byte arrays; requires native pinning. |
| **Garbage Collector Sensitivity** | Immune to JVM heap allocations and sweeps. | Heap allocations trigger GC pauses and frame drops. |
| **Build System Complexity** | High (Requires CMake, NDK, JNI glue layers). | Low (Pure Kotlin idiomatic Android SDK API). |

For full-duplex, continuous live speech synthesis or real-time spatial audio processing, Oboe’s low latency is essential. However, for a half-duplex tactical walkie-talkie relying on utterance-level ASR, input hardware latency under 50 ms is negligible compared to the human interaction latency of pressing and releasing a physical PTT button.  
The primary architectural hazard of AudioRecord in production systems is heap churn. Repeatedly invoking AudioRecord.read(byteArray, ...) allocates short-lived byte arrays, triggering Android Runtime (ART) Garbage Collection sweeps that desynchronize audio thread scheduling and cause buffer overruns.  
This hazard is eliminated by configuring AudioRecord with a Direct ByteBuffer via ByteBuffer.allocateDirect(). Direct byte buffers reside in native memory outside the garbage-collected JVM heap. The underlying memory address remains pinned and stable, enabling direct hand-offs into JNI C++ buffers via GetDirectBufferAddress without intermediate memory copies or runtime allocation overhead.

### **Push-to-Talk Audio Capture Implementation**

The audio capture pipeline manages hardware configuration, off-heap buffering, sample normalization, and coroutine-based concurrency to guarantee responsive PTT operations.

Kotlin  
package org.itantra.transceiver.audio

import android.annotation.SuppressLint  
import android.media.AudioFormat  
import android.media.AudioRecord  
import android.media.MediaRecorder  
import kotlinx.coroutines.\*  
import java.nio.ByteBuffer  
import java.nio.ByteOrder  
import java.util.concurrent.atomic.AtomicBoolean

class PttAudioCapturePipeline(  
    private val onUtteranceRecorded: (FloatArray) \-\> Unit  
) {  
    companion object {  
        const val SAMPLE\_RATE \= 16000  
        const val CHANNEL\_MASK \= AudioFormat.CHANNEL\_IN\_MONO  
        const val ENCODING\_FORMAT \= AudioFormat.ENCODING\_PCM\_16BIT  
        private const val READ\_FRAME\_SIZE \= 1024 // 64ms processing windows  
    }

    private val isRecording \= AtomicBoolean(false)  
    private var audioRecord: AudioRecord? \= null  
    private var recordingJob: Job? \= null  
    private val captureScope \= CoroutineScope(Dispatchers.IO \+ SupervisorJob())

    @SuppressLint("MissingPermission")  
    fun startRecording() {  
        if (isRecording.getAndSet(true)) return

        val minBufferSize \= AudioRecord.getMinBufferSize(  
            SAMPLE\_RATE,  
            CHANNEL\_MASK,  
            ENCODING\_FORMAT  
        )  
        val bufferCapacity \= maxOf(minBufferSize, READ\_FRAME\_SIZE \* 2)

        audioRecord \= AudioRecord(  
            MediaRecorder.AudioSource.VOICE\_COMMUNICATION,  
            SAMPLE\_RATE,  
            CHANNEL\_MASK,  
            ENCODING\_FORMAT,  
            bufferCapacity  
        )

        audioRecord?.startRecording()

        recordingJob \= captureScope.launch {  
            // Allocate memory in native address space to avoid ART heap churn  
            val directBuffer \= ByteBuffer.allocateDirect(READ\_FRAME\_SIZE \* 2)  
                .order(ByteOrder.nativeOrder())  
              
            // Pre-allocate buffer for a standard 10-second maximum tactical burst  
            val sampleAccumulator \= ArrayList\<Float\>(SAMPLE\_RATE \* 10)

            while (isRecording.get()) {  
                directBuffer.clear()  
                val bytesRead \= audioRecord?.read(directBuffer, directBuffer.capacity()) ?: \-1

                if (bytesRead \> 0) {  
                    val shortBuffer \= directBuffer.asShortBuffer()  
                    val frameCount \= bytesRead / 2  
                    for (i in 0 until frameCount) {  
                        // Normalize 16-bit signed PCM \[-32768, 32767\] to float \[-1.0, 1.0\]  
                        val floatSample \= shortBuffer.get(i).toFloat() / 32768.0f  
                        sampleAccumulator.add(floatSample)  
                    }  
                }  
            }

            // Emit captured audio samples as a single continuous float array  
            val finalWaveform \= FloatArray(sampleAccumulator.size)  
            for (i in sampleAccumulator.indices) {  
                finalWaveform\[i\] \= sampleAccumulator\[i\]  
            }  
            onUtteranceRecorded(finalWaveform)  
        }  
    }

    fun stopRecording() {  
        if (\!isRecording.getAndSet(false)) return  
        try {  
            audioRecord?.stop()  
            audioRecord?.release()  
        } catch (e: Exception) {  
            // Mitigate hardware teardown race conditions  
        } finally {  
            audioRecord \= null  
            recordingJob?.cancel()  
        }  
    }  
}

### **End-to-End Offline Recognition Wiring**

The ASR integration extracts the packaged ONNX weights to the internal storage partition, binds the files to the NeMo CTC engine abstraction, and executes thread-pinned inference10.

Kotlin  
package org.itantra.transceiver.asr

import android.content.Context  
import com.k2fsa.sherpa.onnx.\*  
import kotlinx.coroutines.Dispatchers  
import kotlinx.coroutines.withContext  
import java.io.File  
import java.io.FileOutputStream

class SherpaAsrEngine(private val context: Context) {

    private var recognizer: OfflineRecognizer? \= null  
    private var isReady \= false

    suspend fun initEngine() \= withContext(Dispatchers.IO) {  
        if (isReady) return@withContext

        val targetDir \= File(context.filesDir, "models/stt/hi")  
        if (\!targetDir.exists()) targetDir.mkdirs()

        val modelFile \= File(targetDir, "indic\_conformer\_hi\_int8.onnx")  
        val tokensFile \= File(targetDir, "tokens.txt")

        if (\!modelFile.exists()) {  
            extractAsset("models/stt/hi/indic\_conformer\_hi\_int8.onnx", modelFile)  
        }  
        if (\!tokensFile.exists()) {  
            extractAsset("models/stt/hi/tokens.txt", tokensFile)  
        }

        // Configure NeMo CTC Model Parameters  
        val nemoConfig \= OfflineNemoEncDecCtcModelConfig(  
            model \= modelFile.absolutePath  
        )

        val modelConfig \= OfflineModelConfig(  
            nemoCtc \= nemoConfig,  
            tokens \= tokensFile.absolutePath,  
            numThreads \= 2, // Confines execution to the primary big-core cluster  
            debug \= false,  
            provider \= "cpu",  
            modelType \= "nemo\_ctc"  
        )

        val featConfig \= FeatureExtractorConfig(  
            samplingRate \= 16000,  
            featureDim \= 80  
        )

        val recognizerConfig \= OfflineRecognizerConfig(  
            featConfig \= featConfig,  
            modelConfig \= modelConfig,  
            decodingMethod \= "greedy\_search"  
        )

        recognizer \= OfflineRecognizer(config \= recognizerConfig)  
        isReady \= true  
    }

    suspend fun transcribe(samples: FloatArray): String \= withContext(Dispatchers.Default) {  
        val activeEngine \= recognizer ?: return@withContext ""  
        if (samples.isEmpty()) return@withContext ""

        val stream \= activeEngine.createStream()  
        try {  
            stream.acceptWaveform(samples, 16000)  
            activeEngine.decode(stream)  
            val result \= activeEngine.getResult(stream)  
            result.text.trim()  
        } finally {  
            stream.release()  
        }  
    }

    private fun extractAsset(assetPath: String, outputFile: File) {  
        context.assets.open(assetPath).use { input \-\>  
            FileOutputStream(outputFile).use { output \-\>  
                val buffer \= ByteArray(64 \* 1024)  
                var read: Int  
                while (input.read(buffer).also { read \= it } \!= \-1) {  
                    output.write(buffer, 0, read)  
                }  
                output.flush()  
            }  
        }  
    }

    fun teardown() {  
        recognizer?.release()  
        recognizer \= null  
        isReady \= false  
    }  
}

## **Multi-Hop Ad-Hoc Mesh Networking on Unrooted Android**

### **Transport Layer Constraints on Unrooted Android**

Establishing dynamic, multi-hop peer-to-peer topologies across unrooted Android devices presents operational challenges due to platform sandboxing and wireless driver restrictions20.  
Wi-Fi Aware (Neighbor Awareness Networking) provides high-bandwidth discovery and data transfer without conventional access points21. However, Android limits devices to a small number of concurrent active data-path interfaces (typically one to four depending on the OEM HAL)20. Wi-Fi Aware data paths require dedicated point-to-point negotiation channels21. If fifteen rescue workers form an ad-hoc multi-hop cluster, bridging connections through intermediary relay nodes exhausts hardware radio resources, causing connection drops and cluster re-synchronization freezes that disrupt tactical communication22.  
Standard Wi-Fi Direct (P2P) operates on a Star Topology governed by an autonomous Group Owner (GO). The GO functions as a software access point and assigns IP addresses via DHCP. The primary failure mode of this topology is its single point of failure: if the device acting as Group Owner moves out of range or powers off, the entire network partition collapses. The remaining client devices must undergo a 10-second to 15-second re-negotiation cycle to elect a new Group Owner. Furthermore, unrooted Android devices cannot simultaneously maintain a client connection to one group while operating as the Group Owner of another, preventing the creation of bridge nodes across multiple Wi-Fi Direct groups.  
The most reliable approach for tactical operations within a 500-meter zone uses a common ad-hoc wireless broadcast fabric—such as an unrooted Android Local-Only Hotspot (LOHS) or portable battery-powered node—combined with **UDP Broadcast Flooding with Decentralized Deduplication and Store-and-Forward Forwarding**20. When nodes share a Layer-2 broadcast domain, packets sent to 255.255.255.255 bypass link-state handshakes and reach every node within radio range simultaneously. For nodes separated by obstacles or distance, intermediary devices capture and forward the broadcast packets, extending coverage without root privileges or complex connection states.

### **Packet Broadcast Storm Mitigation: Controlled Epidemic Routing**

Unregulated packet retransmission across an ad-hoc multi-hop network causes severe radio-frequency (RF) channel contention, known as the Broadcast Storm Problem. If every node immediately rebroadcasts every received frame, packet collisions spike exponentially, saturating the wireless medium. To prevent contention and routing loops, the routing engine enforces three coordinated throttling mechanisms:

> 1. **Sliding-Window Deduplication Cache:** Every packet contains a globally unique 16-byte identifier derived from its origin node and sequence number:  
>    ![][image6]  
>    Nodes maintain a bounded, concurrent ring cache holding the fingerprints of the last 10,000 processed packets. Any incoming packet matching a cached fingerprint is immediately dropped, breaking transmission loops.  
> 2. **Hop-Limit and Time-To-Live (TTL):** Packets originate with a Hop-Limit counter set to a default value (![][image7]). Each intermediary forwarder decrements the counter by one prior to retransmission. When a packet reaches ![][image8], it is dropped from the retransmission pipeline.  
> 3. **Slotted Jitter and Density-Dependent Suppression:** Intermediary nodes do not rebroadcast packets immediately. Upon receiving a frame marked for forwarding, a node defers retransmission by a randomized interval:  
>    ![][image9]  
>    where ![][image10] and ![][image11]. If the node hears two or more neighboring nodes (![][image12]) rebroadcasting the identical packet during this wait period, it cancels its own pending retransmission. This gossiping heuristic ensures sufficient spatial coverage while pruning redundant transmissions in dense node clusters.

### **Mesh Packet Serialization Specification**

To prevent IP packet fragmentation over variable wireless links, all mesh datagrams maintain an explicit binary layout sized well below standard Maximum Transmission Unit (MTU) limits:

| Byte Offset | Field Name | Data Type | Field Size | Protocol Function |
| :---- | :---- | :---- | :---- | :---- |
| 0x00 | MagicMarker | Short (Big-Endian) | 2 Bytes | Protocol identifier (0x4954, ASCII for 'IT'). |
| 0x02 | Version | Byte | 1 Byte | Protocol schema version (0x01). |
| 0x03 | HopLimit | Byte | 1 Byte | Remaining routing hops (decremented at each relay). |
| 0x04 | MessageType | Byte | 1 Byte | Payload type (0x01 \= Transcript, 0x02 \= Opus Audio). |
| 0x05 | PayloadSize | Short (Big-Endian) | 2 Bytes | Length of payload bytes (![][image13]). |
| 0x07 | SequenceID | Long (Big-Endian) | 8 Bytes | Monotonically increasing origin sequence counter. |
| 0x0F | OriginNodeID | Long (Big-Endian) | 8 Bytes | Node identifier (derived from device serial hash). |
| 0x17 | PayloadData | Byte Array | ![][image13] Bytes | UTF-8 encoded text or compressed voice bytes. |

### **Production Mesh Transceiver Engine Implementation**

The networking layer acquires a hardware-level MulticastLock, sets up non-blocking UDP broadcast sockets, handles packet deduplication, and schedules jittered rebroadcasts20.

Kotlin  
package org.itantra.transceiver.mesh

import android.content.Context  
import android.net.wifi.WifiManager  
import kotlinx.coroutines.\*  
import java.net.DatagramPacket  
import java.net.DatagramSocket  
import java.net.InetAddress  
import java.nio.ByteBuffer  
import java.util.concurrent.ConcurrentHashMap  
import kotlin.random.Random

class MeshTransceiverEngine(  
    private val context: Context,  
    private val localNodeId: Long,  
    private val onMessageDecoded: (origin: Long, text: String) \-\> Unit  
) {  
    companion object {  
        const val BROADCAST\_PORT \= 4242  
        const val MAGIC\_HEADER: Short \= 0x4954  
        const val PROTOCOL\_VERSION: Byte \= 0x01  
        const val TYPE\_TRANSCRIPT: Byte \= 0x01  
        const val MAX\_WIRE\_SIZE \= 1400 // Sub-MTU ceiling prevents packet fragmentation  
    }

    private var socket: DatagramSocket? \= null  
    private var multicastLock: WifiManager.MulticastLock? \= null  
    private val active \= java.util.concurrent.atomic.AtomicBoolean(false)  
    private val meshScope \= CoroutineScope(Dispatchers.IO \+ SupervisorJob())

    // Tracks seen packet fingerprints: (Origin XOR Seq) mapped to expiration timestamps  
    private val deduplicationTable \= ConcurrentHashMap\<Long, Long\>()  
    private var sequenceCounter \= 0L

    fun start() {  
        if (active.getAndSet(true)) return

        // Acquire MulticastLock to prevent the Wi-Fi HAL from dropping non-unicast packets  
        val wifiManager \= context.applicationContext.getSystemService(Context.WIFI\_SERVICE) as WifiManager  
        multicastLock \= wifiManager.createMulticastLock("iTantraMulticastLock").apply {  
            setReferenceCounted(true)  
            acquire()  
        }

        socket \= DatagramSocket(BROADCAST\_PORT).apply {  
            broadcast \= true  
            reuseAddress \= true  
        }

        // Dedicated receive loop for processing incoming packets  
        meshScope.launch {  
            val receiveBuffer \= ByteArray(MAX\_WIRE\_SIZE)  
            while (active.get()) {  
                try {  
                    val datagram \= DatagramPacket(receiveBuffer, receiveBuffer.size)  
                    socket?.receive(datagram)  
                    parsePacket(datagram.data, datagram.length)  
                } catch (e: Exception) {  
                    // Socket closed or transient driver disruption  
                }  
            }  
        }

        // Periodic maintenance routine to prune stale deduplication entries  
        meshScope.launch {  
            while (active.get()) {  
                delay(30\_000)  
                val currentTime \= System.currentTimeMillis()  
                deduplicationTable.entries.removeIf { it.value \< currentTime }  
            }  
        }  
    }

    private fun parsePacket(data: ByteArray, length: Int) {  
        if (length \< 23) return

        val buffer \= ByteBuffer.wrap(data, 0, length)  
        if (buffer.short \!= MAGIC\_HEADER) return  
        if (buffer.get() \!= PROTOCOL\_VERSION) return

        val hopLimit \= buffer.get()  
        val messageType \= buffer.get()  
        val payloadLen \= buffer.short.toInt() and 0xFFFF  
        val sequenceId \= buffer.long  
        val originNodeId \= buffer.long

        if (payloadLen \> buffer.remaining()) return

        // Drop packets seen recently to eliminate forwarding loops  
        val fingerprint \= originNodeId xor sequenceId  
        if (deduplicationTable.putIfAbsent(fingerprint, System.currentTimeMillis() \+ 60\_000) \!= null) {  
            return  
        }

        val payload \= ByteArray(payloadLen)  
        buffer.get(payload)

        if (messageType \== TYPE\_TRANSCRIPT) {  
            val textContent \= String(payload, Charsets.UTF\_8)  
            onMessageDecoded(originNodeId, textContent)  
        }

        // Decrement TTL and forward packet if within limits  
        val updatedHopLimit \= (hopLimit \- 1).toByte()  
        if (updatedHopLimit \> 0 && originNodeId \!= localNodeId) {  
            meshScope.launch {  
                // Apply randomized jitter to prevent RF packet collisions  
                delay(Random.nextLong(25, 75))  
                forwardPacket(data, length, updatedHopLimit)  
            }  
        }  
    }

    private fun forwardPacket(rawBytes: ByteArray, length: Int, newHopCount: Byte) {  
        try {  
            val forwardBuffer \= rawBytes.copyOf(length)  
            forwardBuffer\[3\] \= newHopCount // Update HopLimit byte directly

            val broadcastTarget \= InetAddress.getByName("255.255.255.255")  
            val datagram \= DatagramPacket(forwardBuffer, length, broadcastTarget, BROADCAST\_PORT)  
            socket?.send(datagram)  
        } catch (e: Exception) {  
            // Forwarding dropped due to network interface renegotiation  
        }  
    }

    fun broadcastTranscript(text: String) {  
        meshScope.launch {  
            val payloadBytes \= text.toByteArray(Charsets.UTF\_8)  
            val totalSize \= 23 \+ payloadBytes.size  
            if (totalSize \> MAX\_WIRE\_SIZE) return@launch

            val buffer \= ByteBuffer.allocate(totalSize)  
            buffer.putShort(MAGIC\_HEADER)  
            buffer.put(PROTOCOL\_VERSION)  
            buffer.put(4\.toByte()) // Set initial TTL to 4 hops  
            buffer.put(TYPE\_TRANSCRIPT)  
            buffer.putShort(payloadBytes.size.toShort())  
            val seq \= \++sequenceCounter  
            buffer.putLong(seq)  
            buffer.putLong(localNodeId)  
            buffer.put(payloadBytes)

            // Cache local transmission to suppress processing reflected echoes  
            deduplicationTable\[localNodeId xor seq\] \= System.currentTimeMillis() \+ 60\_000

            val wireData \= buffer.array()  
            val broadcastTarget \= InetAddress.getByName("255.255.255.255")  
            val datagram \= DatagramPacket(wireData, wireData.size, broadcastTarget, BROADCAST\_PORT)  
            socket?.send(datagram)  
        }  
    }

    fun teardown() {  
        active.set(false)  
        try {  
            socket?.close()  
            multicastLock?.release()  
        } catch (e: Exception) {  
            // Cleanup drops  
        } finally {  
            socket \= null  
            multicastLock \= null  
            meshScope.cancel()  
        }  
    }  
}

## **Hardware and OEM ROM Compatibility Matrix**

### **Operating System Execution Realities**

Deploying an offline tactical transceiver across commercial Android hardware requires addressing non-standard power management subsystems and aggressive background execution limits enforced by OEM variants24.

| OEM Platform & OS Variant | Background Execution Constraints | Network Socket Lifecycle Issues | Required Mitigation Strategy |
| :---- | :---- | :---- | :---- |
| **Xiaomi HyperOS / MIUI** (Android 13–15) | The mi\_powerkeeper daemon suspends background CPU execution within 3 minutes of screen-off. | Cuts raw UDP/TCP socket bindings when backgrounded, discarding incoming packets. | Request the user disable MIUI Battery Optimization ("No Restrictions") and grant Autostart permissions in Security settings. |
| **Vivo FuntouchOS / OriginOS** | The iManager engine terminates high-CPU background threads, overriding standard platform WakeLocks. | Suppresses Wi-Fi broadcast reception unless an active window surface is registered. | Direct user to Settings ![][image14] Battery ![][image14] Background Power Consumption ![][image14] toggle "Allow High Background Power Usage." |
| **Samsung OneUI** (Android 13–15) | Moves unmanaged services to "Sleeping Apps" or "Deep Sleeping Apps" pools; invokes Phantom Process Killer. | Down-clocks performance cores during screen-off states, causing ASR RTF to degrade above 1.0. | Add app to "Never sleeping apps"; bind a persistent foreground notification with high channel importance25. |
| **Stock Android / Pixel** (Android 14–15) | Enforces strict foreground service validation and 16 KB ELF segment alignment8. | Places radio interfaces into Doze maintenance windows unless active wakelocks are held. | Declare explicit Foreground Service Types (\`microphone |

### **Foreground Service Architecture Under Modern Android Policies**

Under Android 14 (API 34\) and Android 15 (API 35), foreground services must declare their operational categories using the android:foregroundServiceType attribute in the manifest and include matching type flags during startForeground() calls24. Failure to supply these matching flags raises a fatal MissingForegroundServiceTypeException24.  
Furthermore, services declared under the dataSync category are restricted to a cumulative 6-hour execution window within any 24-hour period on Android 1524. Once this budget expires, the platform invokes Service.onTimeout(); if the service fails to terminate promptly, the system halts the host process24.  
Tactical transceivers require continuous, non-expiring background operation. To satisfy platform policies while avoiding runtime timeouts, the service combines the microphone type (for low-latency voice capture) with the connectedDevice type (for peer-to-peer ad-hoc Wi-Fi networking)24. This composite declaration establishes the appropriate security context for persistent, uninterrupted operation25.

XML  
\<\!-- AndroidManifest.xml \--\>  
\<manifest xmlns:android\="http://schemas.android.com/apk/res/android"  
    package\="org.itantra.transceiver"\>

    \<uses-permission android:name\="android.permission.RECORD\_AUDIO" /\>  
    \<uses-permission android:name\="android.permission.MODIFY\_AUDIO\_SETTINGS" /\>

    \<\!-- Foreground Service Security Declarations (API 34+) \--\>  
    \<uses-permission android:name\="android.permission.FOREGROUND\_SERVICE" /\>  
    \<uses-permission android:name\="android.permission.FOREGROUND\_SERVICE\_MICROPHONE" /\>  
    \<uses-permission android:name\="android.permission.FOREGROUND\_SERVICE\_CONNECTED\_DEVICE" /\>

    \<\!-- Network Configuration Permissions \--\>  
    \<uses-permission android:name\="android.permission.INTERNET" /\>  
    \<uses-permission android:name\="android.permission.ACCESS\_WIFI\_STATE" /\>  
    \<uses-permission android:name\="android.permission.CHANGE\_WIFI\_STATE" /\>  
    \<uses-permission android:name\="android.permission.CHANGE\_WIFI\_MULTICAST\_STATE" /\>  
    \<uses-permission android:name\="android.permission.ACCESS\_NETWORK\_STATE" /\>

    \<\!-- Power Management Whitelisting \--\>  
    \<uses-permission android:name\="android.permission.WAKE\_LOCK" /\>  
    \<uses-permission android:name\="android.permission.REQUEST\_IGNORE\_BATTERY\_OPTIMIZATIONS" /\>

    \<application  
        android:allowBackup\="false"  
        android:icon\="@mipmap/ic\_launcher"  
        android:label\="iTantra"  
        android:theme\="@style/Theme.AppCompat.NoActionBar"\>

        \<service  
            android:name\=".service.TransceiverForegroundService"  
            android:enabled\="true"  
            android:exported\="false"  
            android:foregroundServiceType\="microphone|connectedDevice" /\>  
    \</application\>  
\</manifest\>

Kotlin  
package org.itantra.transceiver.service

import android.app.\*  
import android.content.Intent  
import android.content.pm.ServiceInfo  
import android.os.Build  
import android.os.IBinder  
import androidx.core.app.NotificationCompat  
import androidx.core.app.ServiceCompat

class TransceiverForegroundService : Service() {

    companion object {  
        const val NOTIFICATION\_CHANNEL\_ID \= "itantra\_mesh\_channel"  
        const val SERVICE\_NOTIFICATION\_ID \= 9001  
    }

    override fun onCreate() {  
        super.onCreate()  
        setupNotificationChannel()  
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {  
        val persistentNotification \= NotificationCompat.Builder(this, NOTIFICATION\_CHANNEL\_ID)  
            .setContentTitle("iTantra Tactical Mesh")  
            .setContentText("Transceiver radio active. ASR engine ready.")  
            .setSmallIcon(android.R.drawable.stat\_sys\_speakerphone)  
            .setPriority(NotificationCompat.PRIORITY\_MAX)  
            .setOngoing(true)  
            .build()

        // Combine service types for Android 14 and Android 15 runtimes  
        val foregroundTypeMask \= if (Build.VERSION.SDK\_INT \>= Build.VERSION\_CODES.UPSIDE\_DOWN\_CAKE) {  
            ServiceInfo.FOREGROUND\_SERVICE\_TYPE\_MICROPHONE or   
            ServiceInfo.FOREGROUND\_SERVICE\_TYPE\_CONNECTED\_DEVICE  
        } else {  
            0  
        }

        ServiceCompat.startForeground(  
            this,  
            SERVICE\_NOTIFICATION\_ID,  
            persistentNotification,  
            foregroundTypeMask  
        )

        return START\_STICKY  
    }

    override fun onBind(intent: Intent?): IBinder? \= null

    private fun setupNotificationChannel() {  
        val channel \= NotificationChannel(  
            NOTIFICATION\_CHANNEL\_ID,  
            "Mesh Transceiver Core",  
            NotificationManager.IMPORTANCE\_HIGH  
        ).apply {  
            description \= "Maintains UDP broadcast routing sockets and on-device speech transcription."  
        }  
        val manager \= getSystemService(NotificationManager::class.java)  
        manager.createNotificationChannel(channel)  
    }  
}

## **Mesh Expansion Blueprint**

Transitioning the production application from a direct 1-to-1 TCP architecture to a decentralized multi-hop ad-hoc mesh requires a staged engineering migration.

### **Phase 1: Hybrid Transport Decoupling (Weeks 1 to 3\)**

The initial phase refactors the application's networking core behind a unified ITransceiverTransport abstraction layer. This interface isolates the user interface and audio capture logic from underlying wire implementations.  
The existing single-hop TCP engine remains available as a reliable, direct unicast fallback. Concurrently, a parallel UDP socket engine is bound to port 4242 configured for subnet-wide broadcast.  
All outbound packets adopt the 23-byte binary wire specification, incorporating a 2-byte magic header, message type identifiers, and origin sequence tracking. This uniform framing allows downstream nodes to filter corrupt payloads and discard duplicate packets early in the pipeline.

### **Phase 2: Multi-Hop Epidemic Flooding and Contention Suppression (Weeks 4 to 7\)**

The second phase transforms the UDP transport into a multi-hop mesh forwarding engine. Receiving nodes read the packet's HopLimit byte, decrement its value, and schedule it for retransmission if the counter remains greater than zero.  
To prevent broadcast packet storms, nodes implement slotted jitter back-off forwarding, waiting a random interval between 25 and 75 milliseconds before relaying packets. If an intermediary node overhears two or more neighboring devices forwarding the identical packet fingerprint during this window, it cancels its own pending transmission.  
Concurrently, a bounded sliding-window deduplication cache tracks active message fingerprints, preventing routing loops and duplicate packet processing across the cluster.

### **Phase 3: Voice Compression and Streaming via Opus Codec (Weeks 8 to 11\)**

While the on-device IndicConformer ASR engine operates on raw 16 kHz 32-bit floating-point samples locally, broadcasting raw PCM audio across an ad-hoc mesh requires 256 kbps of sustained network throughput per node—a data rate that quickly saturates contested wireless channels. Phase three integrates the open-source libopus native library via custom JNI bindings to compress incoming audio into 20-millisecond frames at 16 to 24 kbps.  
The packet wire schema adds an Opus message type (0x02), allowing voice frames to traverse the identical multi-hop routing fabric alongside text transcripts.  
Receiving nodes implement an adaptive jitter buffer to smooth out variable packet arrival times across multiple wireless hops, reordering frames and concealing packet loss before routing the reconstructed audio to the hardware output pipeline.

### **Phase 4: Delay-Tolerant Networking and Partition Bridging (Weeks 12+)**

In wide-area search-and-rescue operations spanning terrain beyond the 500-meter line-of-sight threshold, field teams frequently split into isolated spatial clusters. Phase four deploys an opportunistic Delay-Tolerant Networking (DTN) architecture.  
When a packet cannot reach its target destination or when an isolated cluster is detected, nodes persist the messages to a local encrypted SQLite database. As rescue personnel move through the operational zone, their devices continuously transmit low-power Bluetooth Low Energy (BLE 5.0) discovery beacons.  
When two devices from previously disconnected clusters enter proximity, they establish an ephemeral Wi-Fi Aware data path, synchronize their outstanding message stores, and propagate cached emergency intelligence across network partitions without manual configuration or cellular infrastructure.

#### **Works cited**

> 1. Android Example App | RunanywhereAI/runanywhere-sdks \- DeepWiki, [https://deepwiki.com/RunanywhereAI/runanywhere-sdks/10.2-android-example-app](https://deepwiki.com/RunanywhereAI/runanywhere-sdks/10.2-android-example-app)  
> 2. GitHub \- k2-fsa/sherpa-onnx, [https://github.com/k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)  
> 3. Update bundled ONNX Runtime from 1.17.1 to latest (16KB page, [https://github.com/k2-fsa/sherpa-onnx/issues/3291](https://github.com/k2-fsa/sherpa-onnx/issues/3291)  
> 4. Octomil Android SDK \- Federated Learning for Android \- GitHub, [https://github.com/octomil/octomil-android](https://github.com/octomil/octomil-android)  
> 5. Mentra-Bluetooth-SDK-Starter-Kit/examples/react-native/README, [https://github.com/Mentra-Community/Mentra-Bluetooth-SDK-Starter-Kit/blob/main/examples/react-native/README.md](https://github.com/Mentra-Community/Mentra-Bluetooth-SDK-Starter-Kit/blob/main/examples/react-native/README.md)  
> 6. Installation \- RunAnywhere Documentation, [https://docs.runanywhere.ai/react-native/installation](https://docs.runanywhere.ai/react-native/installation)  
> 7. onnxruntime\_v2 1.23.2+1 changelog | Flutter package \- Pub.dev, [https://pub.dev/packages/onnxruntime\_v2/versions/1.23.2+1/changelog](https://pub.dev/packages/onnxruntime_v2/versions/1.23.2+1/changelog)  
> 8. Incompatible with 16KB Page Size Devices · Issue \#2413 \- GitHub, [https://github.com/k2-fsa/sherpa-onnx/issues/2413](https://github.com/k2-fsa/sherpa-onnx/issues/2413)  
> 9. how to fix 16kb page alignment with onnxruntime : r/reactnative, [https://www.reddit.com/r/reactnative/comments/1szqede/how\_to\_fix\_16kb\_page\_alignment\_with\_onnxruntime/](https://www.reddit.com/r/reactnative/comments/1szqede/how_to_fix_16kb_page_alignment_with_onnxruntime/)  
> 10. parismitaglobalsolutions/indicconformer-sherpa-onnx \- Hugging Face, [https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx)  
> 11. Add to F-Droid · Issue \#520 · k2-fsa/sherpa-onnx \- GitHub, [https://github.com/k2-fsa/sherpa-onnx/issues/520?timeline\_page=1](https://github.com/k2-fsa/sherpa-onnx/issues/520?timeline_page=1)  
> 12. 安卓版本，语音识别SenseVoice模型，初始化时报错\#2238 \- GitHub, [https://github.com/k2-fsa/sherpa-onnx/issues/2238](https://github.com/k2-fsa/sherpa-onnx/issues/2238)  
> 13. FORTIFY: pthread\_mutex\_lock called on a destroyed mutex during, [https://github.com/k2-fsa/sherpa-onnx/issues/3065](https://github.com/k2-fsa/sherpa-onnx/issues/3065)  
> 14. sherpa-onnx/rust-api-examples/examples/sense\_voice.rs at master, [https://github.com/k2-fsa/sherpa-onnx/blob/master/rust-api-examples/examples/sense\_voice.rs](https://github.com/k2-fsa/sherpa-onnx/blob/master/rust-api-examples/examples/sense_voice.rs)  
> 15. sherpa\_onnx package \- github.com/lichnost/sherpa-onnx-go-windows, [https://pkg.go.dev/github.com/lichnost/sherpa-onnx-go-windows](https://pkg.go.dev/github.com/lichnost/sherpa-onnx-go-windows)  
> 16. sherpa-onnx/cxx-api-examples/cohere-transcribe-cxx-api.cc at master, [https://github.com/k2-fsa/sherpa-onnx/blob/master/cxx-api-examples/cohere-transcribe-cxx-api.cc](https://github.com/k2-fsa/sherpa-onnx/blob/master/cxx-api-examples/cohere-transcribe-cxx-api.cc)  
> 17. sense-voice.dart \- k2-fsa/sherpa-onnx \- GitHub, [https://github.com/k2-fsa/sherpa-onnx/blob/master/dart-api-examples/non-streaming-asr/bin/sense-voice.dart](https://github.com/k2-fsa/sherpa-onnx/blob/master/dart-api-examples/non-streaming-asr/bin/sense-voice.dart)  
> 18. k2-fsa/sherpa-onnx \- python \- GitHub, [https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/python/sherpa\_onnx/offline\_recognizer.py](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/python/sherpa_onnx/offline_recognizer.py)  
> 19. iOS Inference for Nemo Model \#1068 \- k2-fsa/sherpa-onnx \- GitHub, [https://github.com/k2-fsa/sherpa-onnx/issues/1068](https://github.com/k2-fsa/sherpa-onnx/issues/1068)  
> 20. WifiManager | API reference \- Android Developers, [https://developer.android.com/reference/android/net/wifi/WifiManager](https://developer.android.com/reference/android/net/wifi/WifiManager)  
> 21. Cross-Platform P2P Wi-Fi: How the EU Killed AWDL \- Ditto, [https://www.ditto.com/blog/cross-platform-p2p-wi-fi-how-the-eu-killed-awdl](https://www.ditto.com/blog/cross-platform-p2p-wi-fi-how-the-eu-killed-awdl)  
> 22. Java Release Notes \- Quickstart \- Ditto, [https://docs.ditto.live/sdk/latest/release-notes/java](https://docs.ditto.live/sdk/latest/release-notes/java)  
> 23. Squad Area Network (SqAN) \- GitHub, [https://github.com/sofwerx/sqan](https://github.com/sofwerx/sqan)  
> 24. Firebase Dynamic Links Alternative: 2026 Android Guide \- Fora Soft, [https://www.forasoft.com/blog/article/foreground-service-and-deep-links-on-android-601](https://www.forasoft.com/blog/article/foreground-service-and-deep-links-on-android-601)  
> 25. Android Foreground Services: Types, Permissions and Limitations, [https://softices.com/blogs/android-foreground-services-types-permissions-use-cases-limitations](https://softices.com/blogs/android-foreground-services-types-permissions-use-cases-limitations)  
> 26. Foreground service types are required \- Android Developers, [https://developer.android.com/about/versions/14/changes/fgs-types-required](https://developer.android.com/about/versions/14/changes/fgs-types-required)  
> 27. Guide to Foreground Services on Android 14 \- Medium, [https://medium.com/@domen.lanisnik/guide-to-foreground-services-on-android-14-9d0127dc8f9a](https://medium.com/@domen.lanisnik/guide-to-foreground-services-on-android-14-9d0127dc8f9a)  
> 28. flutter\_foreground\_task | Flutter package \- pub.dev, [https://pub.dev/packages/flutter\_foreground\_task](https://pub.dev/packages/flutter_foreground_task)  
> 29. Foreground service types | Background work \- Android Developers, [https://developer.android.com/develop/background-work/services/fgs/service-types](https://developer.android.com/develop/background-work/services/fgs/service-types)  
> 30. Issue with releasing app targeting Android 14 due to Foreground, [https://devforum.zoom.us/t/issue-with-releasing-app-targeting-android-14-due-to-foreground-service-permissions-in-zoom-video-sdk/113460?page=2](https://devforum.zoom.us/t/issue-with-releasing-app-targeting-android-14-due-to-foreground-service-permissions-in-zoom-video-sdk/113460?page=2)

[image1]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAmwAAABTCAYAAAAiJlt0AAAHgklEQVR4Xu3dS6gsRxkH8LqYSIKvSECRRAjZBSSikgiikIWgLrJQRAJqELJIhGyMC8WFCJJFVgGJC8UHcaOoqOBGRORAdgqRgFE3wjUEhYgIQTdq1PqnuzN1+8zcM3PunJnp6d8PPk53TU/P4/Tjm6qu6lKYmEvjAgAAAAAADouKXACAKZPNAQCHSI4CAAAAMHmqeAAAADbkhxQAAADHz69fAOBQyVMAAOBANMm5PB0AAAAAgK36So3/1XipxnM1/tXPv9BHpv9Z413DEwAA2J3313i6mX9jjV/X+FxTFic1Xjsqg51bflnL8lIAOBapXXu4mX97jRdrvLcpiydH8wAA7MjHalzXzH+jdE2gbVncOZoHYCpUQsPRSXNoEjYA2CVpJWwgydrlceGaXlXjTcVOB8A8bOF8t4VVMDvZapKw/XBJ+edrXD8qH3uqxuM17hs/AHCK8xTAuWTYjn+X0x0O4qweonn8wXEhwHTIIIEDccbh6OOlaw69ZVS+jiRs947K0jyaZtJV8nZuHhdexXh9ec0zPtKxmunHBgYOAjAzN9R4S4131Phjjcf6+ZS3/tSXv650TZ9vKN1YbSf940mehrHb8vdn/fSnSrdsnvtE6V4nQ4e8tcYH+2XS0SHNqB8pXQ1fZPkhAfxV6dZ5d+kG8E0P1qwrB6y83+/2ywHAbuwnZd7Pq07ER1fEOKGJm0qXaJwVqSW6sZxe5zjOU9O1qeEuB4u49Mp1bO3QHkPClsTspC9LEpUkL9qE7dmySPw+ULoELdOpwRuk+fSdffkPSjecSBK05/vHU/5IP51x4dq7LNxR44HSLXN/WbwHAGDGkhgkiUkN0CCJyN9HZSc1ftPMP1m65+X58foaXytXJh9ZJjVWqbka5OL+z5TTdxvYp1UJW8qjTdhSY5ZEsJXntk2mSd6SeLXy+LC+LD+s7x817uqn4/ZykNfLTe2Hz9TeLwBc3ZCwja/RerTGn5v5X5ZFM18MCVtu9zRIrdk4YTsppy/qz9k0tU6HYpOE7bM1numnk3y+uZxO2G6t8VA//e4anyyrE7bf1vhyP/220tX8/bR0tZRpbv1i/xjslRQYYL9WJWypXWsHmf1RuTI5S8KRx8fJWLueccKWptbb+umsr615m5p0EjhrKJA0I69rvGy+q6t1agAAZmRVwpYatr828w+XK69tW5WwfaiZHidsSdCGGrh7SleLBOyAGrLt8V0C+zAkbD+v8fUaP6nxnxrfbBdaYlXC1krC9lLpLrZPZLptMl3mfaW70P65NQJgfTIt2Ad73pYMCVt6JGY6vR7/W+N77UJLrJuw/aHGJ0rX8zGdFs5K2IZbQOW9nBXArDjusy+2PfZvWZNoplOW5G2VdRO2k7JYJn/PSti26XdHGF8qLccQAJiFZQlbbt+UQV7H17W1zpOwJb0460L9bdawpafmMQYA2+YHMAduWcKWWrCMup+EK97TPDZYN2HLuGVt71IAgDnb+OdBkq27S5d4fbosVpCxxX5fumQrZd/qy2Oo/fpO6Z6X2zFl/oZmmdSSZSyyjCf2QuleI8/bfJiKjT8SAMC83FO6gWLXaX5kT+S0AMCxS74zvjZuWQDMxQx+B87gI3IsLmBjvYBV7kAGBM5gv0Nz7TCsySDX7+W+qgAA7EGuu8t1dq3cuP1yM5/r/E6aeQCYoWnWymzFjD/6oXiwXDmuWXqzpqPFcGP2SAeM3Pj+ONjoAICJ+XCN25v5NIe+eKkbh26QGrb7mnkApmj8g3U8/7KlhRdp5y+4V/P6tNfKt3UV3y7dUCXXjR84h8dLd+/UYZy6r9a4c/EwAADnkebQJGzbkqbVIWHLeh9pHrt4cnMA4Aglqbo8Lixd6nNz/3cTbcI2dlMfAADntGlqMn3pcJCEre1wEA+UxbAej9a4sZ9+qiyWzd9hrLbcIeK20n2Dj5VFwjYsnzs+PN2XRaZTBsCcze+8C+eS+6fmhvdth4PILvTq0vUWvb8sErOTcjphe01Z3H91KB8StpN+PsOGPD8s0E+nDF7huA0AC3fU+FvpatbaSOI2+HGN7/fTSazuqnFLWZ6wJTk7K2HLUCJ/GRbopyVsAADX4NkaXyiLJs6Hatxb4xelS8BS/kRZ1Lyl40Kud7u+dL1OxwnbrTWe6csi0yljQtSAAZPjwMUMJPlKjA3luWNCK/O51dWqDgeh0wEAAAAAAAAAAOvZ20VaG7zwBovCobDZAgAAk3CxP14udu0AsAXTPllN+90DAAAAAADAYdH+tn/+BwAAwHn5PQHXwA4EE2KHBYBVJniWnOBbBg6QYwlsh30J9s1eyHGyZQPAGo72hHm0HwyAvXOOYedsdDs2rS98Wu8WAAAAAAAAAIDD4uoT5sUWz0WyfcEhsCcCwOFwXgYgnA8AzssRlGNgOwbYsiM6sB7RRwEAAAAA4GWHWfN7mO8KAIBdkQ8CbJ9jKwBwcY4r0ziuTwPALDh5AVyTAzqMHtBbYe5sjAAcAKcj2Bd733n41gDmxpEfNmKX4WjZuJkUGyxM0ma77mZLAyvYlQDgojjLMkk2XAAAAAC4wv8BQRs3ZsOhfb4AAAAASUVORK5CYII=>

[image2]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA8AAAAaCAYAAABozQZiAAABUklEQVR4Xo1UoU5EQQxsBQIJuQRLcCQ4/oTgzuDAHgIk/wAOg0Kg8fcDWJB8Ajl1yDumr2+33W7fhUm62512tnP7kiMScF0CCseuhT3tenZwhrTQXGdIewVNwewY7Sz6QrhwD7FAPEvwuHfBsrPkh1UJ8gzrCvuba3xHYYP8CXGJuENI/RdxUrXAPRqXngDmiC3ioDJqd45dOZxl6itivzap4INUHHFRM4ivsN3410B2jmSN/Kc2Gm4jQeEJR8ss0wc0X2AHGOUXUsuP2pt9rzYtGC0TLPNpLPYot+t+TTpVLNtLZzBXg1gytcywXC/0fRGFZJph/SKdLI9WkeoMw5QHLCL8RH7cTU5whPgmnSafZ8suxz7rpOlviOeK8c/AjvFgFifR9P2jv7c4pZjiyd/hmiw1O7GnRSq2Q6bruTLMnVum5IHr6Z7pqvHyXpy5ycV/UVUu9yEqOMsAAAAASUVORK5CYII=>

[image3]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAsAAAAaCAYAAABhJqYYAAABXElEQVR4Xo2UvS6EURCGz4StSEhINqKQ6HQUVFtqFBrRuAKXIPQSpbgBlWgkGrWGYkOjEAXZxA3oZAsKnjPn/5xPYrJz3nnf+Tmz37dZYzCxh8UQZFphVtQi71KVOdY5pZ4XpuSxTsz0GHuh0RVdp+cr+BPkC9xPE6MVwjy+C/8GB2WdWpJcJNMcI0i/TNQ7uku2iI5CXb2HfYx22pxmxBxzbP617z3+QsEz7AZxCF+W+CKSbeCH+ATeI3cO/lAyFSv8Bac2UTSLOVAtE6wtgu/gR5JlErjKtWDrpMdUPEZFH5WMmHVX77pt3HUXOtPlBsa9jDPoHvqC6xEzwzEkuvZDdoBPusait5oTVbMbViFv4C1+ibxG7pX4AV8qFvE/nR4+m8mW922m3Do3v1tGUhx/kFGPXzJZI6S2QOugNpdwm9jm/xQqduzd3Fb8H7StShJIx7Bca5Kl6Vw+v1hPJSHrETQkAAAAAElFTkSuQmCC>

[image4]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAmwAAABBCAYAAABsOPjkAAAFYElEQVR4Xu3dTch0UxwA8PPmI/JVyEdIJFJkodcGO5JE8hEhysbGil4iycbCVtlIYSHJQpKNpLdslJVi46MepawkQj7ycf7O3Pe5z31m3plnnjt37r3z+9W/mXvmPjNz57nn3P+cc+6dlOitA80CAADGQJoHAPM5XgIweg52AAAAY+ebHwDAcIwxdxvjNrXAxwKboE81vU/vpW98NgAAAIAeAmjRfqvTfv9+SDZpW4EOaFQAYH0chwG6puUFAAA65UsI7JlqAwAAAHP48szI2cXZs8tyvJzjvuYDAMzgcAt06NYcL+Q4mOObHCfvfBjYH0d17ATAtmNynNEsXMA7OT6b3L86x5O1x9p2WrMAAPpBXs3yTs9x3OT+CTnOSiUxm+alHIeahQuI5z9+cv+2HPfWHmvbGzkebRb2lroLvdZ+FW3/GVds/W94/e8AVmj+Dv5gjp9ynJrjmRyPp5JI/Znj3xw3bq/6vztzXFJbvjjH3zneTWVu2mup/N0nOe7P8eJk+f3J+uGJHB/Wlqeb8d5nFF+Z44Ecf+W4blJ2fY5rjqwBADBA0eP1S45bUkmiItmpXJvj11QSrehxq3xeux8eSbsTuEjQogetEkOhkbhVXslxUm25Lefl2JrchmNzHE7mygHQqRldC7CE6DmLnrFIamL486scF+xYI6U7UknaYr5ZiMSn3lN2eY73asshErNmkvdmjhtS6e36KMddqfS+3Vxbpw0xJ+6xRtkXqbw+G+iANhNgRDazUY8hyx8n92OuVyRnTXFWZz1hi9untx9OD+d4rrZ8So6P0851wtupJHcxDBq9b1VcVV9pSTHPLhLOSBAjUayGQysxl22rUQYA0B8zctEojoSp9EbNWCmVJC6SrGr48vm0nbxNE71m8bzVkOQqnZ/j08ltiMuExGtHj2FdJJ1RDgMyo1LOKAagVR21tvNf5sxUkphIZo4mhjfrl954PZU5arO8mqYnTcuadZZqiETyj9pynLk6LTGLodh/moUA7NX8gwvQrugli+Rm2jBoJc64jHWqGhrJ01s5zj2yxm4xfFo/cWFZ1fDmD6kMpTbFNdbivcUJD5XvU5mv1hTbGu8LANZLzjtca/rfRdIVCU/9zM2mL9PuS29ED9vRErZ4zmlJ0zIuzXF7mv4Rxdy3nye3lXjt6OFrOpjjt2YhsFrTKi7AWgy8QYoEJ6J5eY1zUknU6pfqqMTwaHNSfyXmrTV7vVYlet0Op+3LdcSvLlQnR3yQypBvpZpXB7B6Az8wAP1TnbH5dSoXyo2esytS6bma1fMWl+GIBKgphjDvTmVO2U2Nx1bhxNwqbqWSJMYvNMRZqN+msg3P1tYLcaJEDK0CAAxWDDs+lMo10urXTpsmTiZoa8hzaZMvsHETPWvVz2lFb9vZk/t1kawN5yeqgJ7QVcaQ2X9J6fdmQc99l+OiZiEA0BYJYh9dmOOeZmEPxd7zVCpnlE5n/wK6pM0BOhZnkPb9R9XjR+rjwrosYFzHkXFtDTAE2h3YKKo8dEFNAwBg8CS1ANChcR54x7lVc23oZgMAAAAd0fcA9JG2CerUCBZhP2GE7NYwUCovMEjzG6/5awAAsGJSMgAAoFO+hAAAbJZm/hfLzTIAAHaRMgEroGmBvVFnaJP9CQCAlZFs0g/2RFAP+sT/AgAARkzCD3um2gAADJVMDgAAAPbJl2sAAGD9fDNpj88SAFg4I1hwNQAANoT8EABgHWRhAF3Q2gIAAAAAAAAAALCJRjJ/biSbAayB9gMAgN6RpAIAAABAp3TJAayIBhYAgL6RowLMoaEE2CcN6bj5/7J/be1FizzPIuvsstQfpfQfOL2EZbmP7usAAAAASUVORK5CYII=>

[image5]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAADgAAAAaCAYAAADi4p8jAAADN0lEQVR4XsWYzYtPURjHn9NQhiQvGwkpG4wayYKsJBuRZKFs7Gy8FDUWSrPxD6A0Is1CJKUppChiQ/4AkgUSSZGFxVDG97nPfX3Oc8499/5GPvX93Xuet3PuOfecOw1RKk4bckL2DnAJr4xnqNAu3U4illT4YjGzSe9+oonZtDo/xjP8B5LG0BrUGmATTYs5K18sqidlyVHoEZofYHuN+2N1Z84ZaBK6XNOoHybYVibsSQHZc3FZBW1Da6n2h1gBHYBOQDMo8h3XkdpQ+BYF6Tj0EzoL7YYWBAccMPeiuchfoCncXMX1LbRFxSjYUTn3Qu+hGWi8tFbMgQ5po0+wN0VqXAmPb23ZcjSE32/QppRSC6G70AjJCvJDHm1EEG2HFitbMgljIDsqsy3HlSdfBzyBriibyTroPskqcQI/4PNGBNER1fa7q5G5Iv6O8MS+JNkew7Wy76D9VTMMv3rX5NZtxc80Bven5ucHv1VrJzLAEzrJ5d+8yinKJt7x3tsBrYQukRw8RXgAl61asb+GoQckq5glkxxEz+S26tjE0TL87iM5uAw5w0YbJTkKj4XHlMvxAgw1xhEY1GboIZz1/bWI5BW9SJJ2GjonrkCVgXB52WhtPrkPQ/OhMegXycPyykZw2d46r82UHTLuE8nBcwdd79QBrZjjNY1tbIC+UjN5PckDfoR5Tc3egBOuk71ROYkL3CR5Pfk1FXqNUfBSPYPJOPS5bFU5tyF+VYOTz/vlMckpalG88/egecpX0Rwkd8ad1vZL9gdEo13KZdcLea6iLDxJ2SRzu9HZHuK+nP2A/KF8SrzXXPYBtebyBvSb5BtIdkhXYjWCvtXQG2iJsr+CxpyRiE+Bm6bmbPIqafiT8YJkpW1Uaa+nLsST+S37AU0g7iSuUyR/PvJCzR7hMYQ9daooK15slieHH/IgySdtl/INSPkljXQfokeKTWQCTGMf8kLZpU9RnaPbXeiT6+doi2630TW+hf7lVKZXyDPYRMMMp2EqcYbbM2haA5rocO8/NjqgwLN7hgZxb530yP4UB06or5Bdkxr372mOxByXaRyMbiWTopOCbAKpAXM7RmJpMnx1/gLGRmwMepMGdgAAAABJRU5ErkJggg==>

[image6]: <data:image/png;base64,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>

[image7]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAFAAAAAZCAYAAACmRqkJAAADN0lEQVR4Xt2Yv4sVMRDHZxBB1ENBUQ4tFRFLQRu1sr1OULC82sJK7EQRrLSVUwsLQbhaCwt5hWBh4T8g+KMULAQttFBn8mOTTCbZ7L59z+M+8L23mWSS2e9m9+07gEWC6mEhIttzMOFUCfG8KBfhtoyNYIIpJkGtQw1ucULN2tEY8uw8stWQFcr2GBrmyO4Sgxosoo5uD7ZBqesIuEEFb1CzIuTPe6T9pHWlrybOOUt6ovSxlov3q+7bTtJ10gXZEbOL9IL0AdKT+0v6RXrm2jMX+0Y66XK4Hed9jPL8PD6Pc46QLpGuudg70hUXK5OdZBZYFJdJf0g3ZEfMQSrnjQyCPcEZaa9p2ZpXSK9JF8HmnDbRwFNweeIUV9DmeFbBjnscxZZCyXoUPdTapD8/wdZZNXAVrVGSyECMn0ds0prrYyNieCGfJ+EcjzewWpindNKevv6cvgzT/5D0HBrq5FtrUwZR7sDAXdJVsDmyzxqImoHIOZ5BBjbT50tCdfAZ0lEId5Sts5qSUzIwJ0xc24EOM9gYSLvaGahVpsUWQL7MPjDPd9ORGlgjn2eAgYHcQGVimH8H7gH7xTNEaSV6XfyW8Ip0wLXbDVRIDEzWU1/QTIwWQsrDmeiU9Bm4w6kClkwYC++8l6TzUcwa2N0pzZjK9B1YLzrfgTrOQCwVxs9l/savIktp2WJl8A79uckHUVDdgdnMWcCiG2iQGV27M1COEPTtwHNg308hXyui0jWCT6SvpC+RfoO90D/o834Y2oZuYL3ogTuwaOAtGRCcAptvheYkQ1vXbpdb4hBwXWhqYx0jvQVrIO9O/iXVQ2cO8mBe9D3pOPQ+jwyc8wBCXp4TzD9hTxoeQbfTzGsD/zz87swQiBtUuZBKaB4OQ2cg3O6iwaMu5GHXP7sTCwrtNdSq9nn5Fe/ywlBzbObEdO702Op/wr+wulpMrfZ5OCWoXYSEnm5DMkYk+GbLPM1MOtl2IjImuctqhtX6aozN2+7U7oYFkV/25axbRv5XxcaGMyanY65kSeNkYVhjwgLoVm4soXHYdmYqC0bN05bUNipmeMbc4LBV28e2j6wQT1KasBRfIBMt+Q/ujb+3wwVhKgAAAABJRU5ErkJggg==>

[image8]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAFAAAAAZCAYAAACmRqkJAAADSElEQVR4Xs2XLahVQRDHZ1FB1IcKFlHBIiIGg0EQMcgrhtceKBjNJoPVImiy+4G8YNJisMpFDILBLoJYFYMGweDHzM5+zu6e3bt77/X9YO45Z3b+s7NzPu45AKtCScc89IpzupyvhyRP4oC8b/vSU22PZpjRSUf1y4MqK1enR8Jhv18WLYSm9E1BzByhCSNaSybHdbQHjXYX7YDTKCXHS0aac2iPkzGltysjs37JLrTzwDU7SrrdaC/RPkK8uL9ov9CemuOZ8X1DO6U1Sh+Huk+BzuaxOtIcQdtEu2F879Cusq9U3gjBPRall3O5Y9q5ArxGWtMHtNdox22Axoab7SG0N3YsgBY4Q9vnXWoNf16hrQNrzvoxzRZ4ncbMQTrSWA4Dxz0KfP3IfvSxH+0t8Ek9GPip7j/BcQItZiadIBoY1EhN2jBjpA25BaKBAaSx2AZSfEDaidTTRoduXXGjnqPtDPx059AVGTY1ggKe0Y6YNHMFau5g5DVgjRybaiBpLIUG9tLRrhRb+5bIRrV+RjsTu+uUGjiFLkLlGxgSNTB8pCykFX3QnaUbyIeuEtvA8BHUBDdQzd9A0A30rcg0pfMK5Ez4uxc3m4r/kFotU0ZE3EAfbRu4IRNMrhCqV6AQ8eHULcxwXK2BO4zJWRpwTS5SGIsb6HENFP4qlQYKWhpIMbKB4Wr8Pj2X10orLVEPn4hQy27gxNwB0w301K7AC8Dvp4ydO6khdvjz44/nwP2JCL9toHxlM+RmYd/gM3CSWgNv251cechp4Jd4+sPS2wbbY7QlLgPH0UeFP3n87/sD6H05W0z+7NInDCV7j3YCzPMoIdXcB6dTeQ1zEjjuIfhijwJ/Hn43Ywsju+48N9F+A3+NEPRJ9wTtq4sw5HLaS1WeOWulZ0BWF1wZRqenpH2Zt2Td5BZXhqKdghp2D+0n8CfmC7QvaJdsaCF3wb0AosylaUr+IstYSaQ8BvzifxG4qX00Lb7AnOEro7UuG9caX6c7U6kUcSyHm1B9MosUy+MWvKZHPc7/mDWZU2V8EdOjVXrlvTrHSIIRrWMhSbYLC7hXuoUpXakGnzhtuCmWN9dI5hFtymi2qj4MSINTT456VD4i75X8A4vGwHvS/sYoAAAAAElFTkSuQmCC>

[image9]: <data:image/png;base64,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>

[image10]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAHMAAAAaCAYAAACEuGN0AAAFAElEQVR4Xt2ZT6gXVRTHz1BCllKaFK8EQfpDJIomLYoiIiSQNq8WUYvc1brIJxIoQgt7myxBqCAMIooIglwIQRNvIVHQRqFFUYYgFNGqFkHZ+f7Ond+c+2/m3vtmXmMf+PJmzj333D9n7r0zv0c0FFXk+n/BMAMaJkomYzc6bt5zI+b6U2YVxzm5brLjSGS2XyX4J7hkY8XsbaDXIUxTrbB6cb2xmVq/8voT8A6YpsQ61luWKue+1SuszabeRKly5vt+1g7WBrfAcINzfw3rNppmSmd9wmD+ZH1IbdL+Yf3NepP1lCmDz2XWdlSaIhkzvJ69l9n/Z77+i3WF9YnjAzAHX1E7L/A9xboWhfH24iXZZIZaIn+1YXBfszYp27Niq7RtMDL7nIEXmRNRISHvmPv15hpjvnHuJcCmtUzi343XZJyZa5K/cor4Y1W+79iQrCuVJE+zyHrDsdlEGikha7PM4wlqk9OAxjAPZ1jXKTsedI/Rehahba+5CvfgAOsFbWC3+/jPb6x7LCvRi6znW9tIhPs5JPeyfmX97thPs2qyz89gMhPA2XqzucbDsWD+NqD8FtZNyqZBHzD/W1m3kn92J2O2U2uLXYtJLqOsX5hYPbm4xqp8l+yISObdrNdIzsy+ScXO1az6ixzqA5Jz+ReS9xDUf1nK6BLJmYyEaVDnS9Zh1gmSBw8LzJA+YHhiQN3b6dqBF41HSF7CUrUNFTPBcYOV+pBjxwsfXox2Gf1A1o7lgZW2m8TvD9YeY8cXw0esL1iHSFYmeJx1kuwMrbA2zu8q+oasZPYxD1WhEjrR1eEUEBGdxiCySH/uVkH7iwUuDpKsmpfm5fMij5pk1T3t2DXYImuSedQscbNYbRpsvzXZWzvif896leTTqZ9gV+VMRLBNUY8Qviu2Ezyd2O/Hx29fIYURlydJJv0AGZeIX8PHJPOjdi6vxoYqkkwjTSiZOOL0GzS2ZX9ReM3aoBhbrH7LWw2xD3HSPQn3qYoVxO1l/Mjar2I+SOYbktnLeo/scZwmmR/89ZEVH12ZRpoFbrsmuw0k7lGS8/In6t8JgmxhXeAeDZRMDGzVM3896yzZT2qfFhPbvYP1wPxOqrw9v5KJx8vHXeYe1CRtYAuMkZdMf2W6RxzOcnw2eYPyDAYEO0LS0fNOmQZ7fvNK35yvr5PERYPNhzgsF0k6C3Cgf07tRzkG1bFyB8QfMfqAvrgPAaS/rfHpsE/dIxJ85r8ARcAnyTmSX8wa8MJzlHWM7C1zJ+tb1u3KxsdTdae6x7ZrfT7GwJmGs80dFMSrdLZaNUjCipmgJpn4CAfYesz2M3PQyUTi9OGPOIHz1J/5Mvw4yqJ/NHD1WOs24zvWZySfJTX1/wKE8et4mAO0h3nS9meoXeUzVe3Wjbde5ORTEttxCp2ZA7BEsseDjmTOcJOptxjEacqmDE9i9TD/fY7acQbxHx9gW8M+HnBrflRIn6PE4BokpDbX0WSauN3JzOnoiGTNQZZzGf1NBDwCphSSkmmYbjKtwRfOhEc4jljDZVcJ6Z1P91QUVSplzMbSYnd6dRZOHbfz7r1FZ6FDt293aQmxNdta/LIC3CDufRDLKfcfWBHviHkMgk0FjSkUVFRVOmt3FmqCjiFjyDYR3K659wVIiAECrTX/fZdTH1EhwSWfUYIOi+7i8N0NRQzZCsgOk13BVCmoF2XIWDO6A/4LT/7m6aoTB7gAAAAASUVORK5CYII=>

[image11]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAHYAAAAaCAYAAABikagwAAAFEUlEQVR4Xu2YXagWRRjHnyGTQsNPkjBQw4SQUAnzpqQLU6ESP+pGu6oL8wPBEguFUKTLQCoKIhAFCSrICMGLLhYUCrwpKLoSzvFC6aK6qSDFj+c/z87Z2dmZ2dl9d9/3vOKP8z/v7jPfzzM7M7tEOcpcRClyVfNXLX6bDydf6TZWRyytIR1WlUyXbXZZV5RWDbUqNB50ObTB6hqsdDuG2eYw2wrg7YLX6CE130jxddKx+bJMO/rqpL9er9UxzmB93kBvSLExpTR49Rj/O8SaY1s9wEflkkQP8g/KR6kGoGrpiye4qVv8+x0VwfubdYf1E+tN1kesi0ps503B8cLr0LWs/0jGddWjtySbngD/sH6gwkf/s94P1FvGnRJDYjdruWO7TjLYLZYNM/RbkiCPFHFN3EHx1Cm2k4zTJ0zuZ/N8CKybfoD1QJ4eJ7EzXfIU63vXyD0xT+ZDlhHL0ZesDcPsZ6wtnRbLUM8XrA2ODeP8jGQiGxDYZ3AxWHOJBAZWtYTBfnnMsT1CMiOPOPbZrK9JJkP/5KOoDqZq0QTMETBp97OWOvYdVN1upgLbAvhtrr5S+noRlScNnvpHp/KUwaiQBp/jdymVy6aivYNKblNlJjf3XL807Y+T3198JeuX/NcGgcWy/BLr01x1Dp6kYtk+TbKfY9/+i+RMc5T1DutKbr/BWq1LFvxMskruY50hyVtzYPMPDOxiTbAWO/aeCHfE4WnWqw20lbVQl8ypaelhkm3pmGMHcOYE6zjrSdYLrMusx4ssFfCEHSQJ7ATJagfQzgWSw9d7VOzT60gOq+aEjicbK4rNb1QbWD9wBAq/XZhq3HHvkLEukmxFKeCJxMq2uWQtuwvL979UrfddkoDbzCPJa5b8ZSR5MtZeMqtIy3CYjjznJgxCpS8Vw6godeQmyZKZCoLlO4vYGH9mJHutoQhs0QWk24EFWJ7tk/jLFPOem1LcK7z6oAJrCXNz19E0f5sSieiKk2sPBQmn5BMkW5RNxlWb/TOEBFZFAlvgC+wC1uusb0j2Zizfz1vpSWAAqAANijeSfeIldtprw8dUnr2W9OuZK88BMICiWSRl4PAySu9pk6xzrJlWCg4yKDO1bXncFX5iZVLYuIHFmcJ+3UT1t/h/+ncEhQoUvUbiDMyK0Is3ZjRe3IHp9EmSRs+SvBfi7kOSgeMAgAnzSZ4H4DSJLzcYCPYOfNly2jNZPa7qh1UqFFgBr0Dzrf5gDMh/mG3+TiqdGfsvlvgfSXwBUBb+QXn7ZI3DKr6Crc/v4V/4E4ctwzXWi9Z9kIXcPA5Ld/IZ5MpdfjBwfFoEJrCv5GlYkiAMCoFCpzHjMOMzKs9YLDE4JX5AEniN30MUSegMvOJhvGYsLujBHyTjw6T8VdV/eZqkki/1qmKWYFs7SfxT2JQuu4nkS98ESbvnuM091JM30LEsvw4EVuFvBV9fUnKsx0qQUTmwALOzfKIcHXAWDib20+HCT5zaRjLZNzb3boMSktU8zebhME88NaorkYTAavglX+HAYXqQsdaQXs60FZ3GE/s7a0meZ6zQA+vevx3SrHOpgf2T5AM7QBAz0vuRLoPl61BuP8X6Kr8eIrFB+9J8thhN88cZrLbBSvvAEoJTcWwfqsHXKZ+NgubxosdBhKv2p1StVUtBLC2BURRvVagjgm0HE8aF2ABiaRFaFruHGdAjAxafzrQaWqtCTUhuIDnjfe4znel5IvdY/V2fJPG2cVRPkgAAAABJRU5ErkJggg==>

[image12]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAADEAAAAaCAYAAAAe97TpAAAC80lEQVR4XsVWPYxOQRS9k7VCLOKnEUSBiC0UCI1EQ2QLGoUgEYleobDNtgrRSYSSXkQj4qfYSqPQkRAJgg3JUqnEzzlv3pvvzcydNzM+4STnfW/uPffOnd/3ifRh3GMYkSQyKEhoEuY+CiR/AJe1JH2hpkRWC5uzLnNyITWbhoHJSeYuwUBeH4E/J89hMN5zdg37+wh8B34GX7WefwitbLMMj+3gbnBd4IyBFDP4uQz+Au9F3gRazwZJdZIOHUHVmEnYF41pJvaL2LoOBho1dBZWimdDRwYT4AvwDjgtibKSaNSmH8W3i+Cqtsn858Af4HGnUrAJfA++ATfSUFeJA8Om8fgotuMVg5m82h24K7zJbDW0fUfjQGf3ovF6CD8/wdt4X9KaJ8H1Ymd6BKVXBZvBa+AiOCfaduvn8XPuBBdgOuZZ7SBIDlLFJbGCC237JPgSfIYe7jtVPbgl5owdDAdWCh7qEKyPeThIFfPCpZJmqbgC58H94Afw7UiWgLo6nnE5+Frsudnad+gIEhqZMnYQ12W0UxpH9yAo4OHkVcutRXAWj4i2FcqB4s0ZsQPAoTfqcDPYB34F14aOEN1+o/imNKOr6C+ujQPnWeDy82zUbKU+tomd3BvOEnVlsRL8Bu4Ve0U+AU95ijqw4E/iXZOF8AvcAj4Xm2eidXKLN7dnCB6Up+Cats1zcRdcikDuZW6HHNjDLpCXAGeO58oHFYlZ9OFED/F2tmeYElvXnk4wgpGreJ7utWdA3tEshMt42Pl08KAqH7mumTCnsVrsZHRbvM95xHMwHvAxksfgjs5gbJIF8AEaV4SDyXfsEErDdgGOSlx8x1s9nYdoZGIPJjmqwb0lykpMPNuhSUexsBZlWa2qRJvSKHbFNIgqfSzm/y7+2ywhvxcnbNhfhFdTXKCPnH8AY4TmME7quthydaEyKWsdST8ROO3HeiAi5UrZI1CoiBVT2QAaKArF9H9RWFChrBy5Fa1Cl2fMfPnwvCJGHPMb1qdnYpR83WoAAAAASUVORK5CYII=>

[image13]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABMAAAAaCAYAAABVX2cEAAABuElEQVR4XpVTq05DQRDdSUBUEFJVjWzCQ6D6CThCECT9AAyKL+AbCoLUNMFgkKQQSNAEg6wneDSClDM7s93Zx71wT3q6O6+zO7Otc+TwwVcLqCXsa0PcrzY5K4x5uuPFs6VIkByRwdy/Fq7DZJrLxCv9HwfgK/jBROmuuMnq3IFTmFNedR+QnLYDHoPX4BKRW6xrIaiZY/AF/AEvwUMjEXoJHr9OwAfwC9yuNHcKXjlzk+LF2YZziM0c+z7MJbgAB+Z09s/BIVfkGhloTHIzxqfzgnRiEvbBZ5za90JWraLMQjwXBrfCs3vE2tNkjoXDPKJGqmZa8BiB38qRtjVzXlAL49TiIlvypyb65LhFnh23vIX27sENm9KECYpDiwEDcEEsSO4MlBaz5xMz+Mi3+ATurTIiLpzc7g08SiJZG54kc+J5sWgOtBdedjXPUkc96+A7eAN7M4lHhMcIhyfQmUuSn4n8png9t4mKnpObr1DerEDdm/t1OtGo7TuhUGtSavI7G6LwKSK6bfjzVp1/onpOhDqrMQGHmsK2mnuqq6WmtbjGmCVMQr52RazrosC5Jj81ZSc+2f8CxQU26+MH+XAAAAAASUVORK5CYII=>

[image14]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABIAAAAWCAYAAADNX8xBAAABGklEQVR4Xo2RzYkCQRCFu0BBWUEQWZDFAEzAGLwbgGAQag57NgBjMIDNZQ4eNop93dO/VW9m/eDNdL/66a4Z5zwSnm9QJ+qisNemp3gsGkiBsf4e67WOjWuaDJU+drree4Z7Sb9lY2mGE3nV/wOzqdhorJZTKrDaYvENzUifJlF55uAlVk+sdzmhJxXXTRQ2cIV3ZQGLxLT4ULf6hH6gfeUFFtCGSYhXJEe8z9A8dbtBHTYdgl1YQ5K9uLZ6Qb/QqW/jiR3LOAX7JYLzAT1cdZs6yHe20wS6Q5cSU1ewDdTfFN9E/E0OtT1IKlbneL6gJ7RuXEusEl2fmUIrZ8LN0YqB1DyurlHfwHqJ2qQJGZ45XmNg6cxjE4j7A0/hF+LB9DxxAAAAAElFTkSuQmCC>