package com.itantra.data.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.itantra.data.vad.SileroVad
import com.itantra.data.vad.VadFsm
import com.itantra.domain.model.VadState
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Low-latency, zero-heap-churn PTT and Continuous Duplex audio capture pipeline.
 * Captures 16 kHz 16-bit Mono PCM, integrates neural Silero VAD inference
 * for real-time speech detection, and automatically triggers sentence boundary
 * detection on 500 ms speech pauses (PRD FR-05, US-02).
 */
class PttAudioCapturePipeline(
    private val context: Context? = null,
    private val continuousDuplex: Boolean = false,
    private val onRms: ((Float) -> Unit)? = null,
    private val onUtteranceRecorded: (FloatArray) -> Unit
) {
    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val VAD_FRAME_SIZE = 512 // 32ms @ 16 kHz
    }

    private val isRecording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val captureScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val sileroVad = SileroVad()
    // Hangover of 500 ms (~16 frames x 31.25 ms = 500 ms pause for FR-05 EOU)
    private val vadFsm = VadFsm(hangoverMs = 500, frameMs = 32, preRollMs = 200, sampleRate = SAMPLE_RATE)

    private fun ensureVadLoaded() {
        if (sileroVad.isLoaded()) return
        val candidates = mutableListOf<String>()
        if (context != null) {
            val dir = File(context.filesDir, "models/vad")
            if (!dir.exists()) dir.mkdirs()
            val dest = File(dir, "silero_vad.onnx")
            if (!dest.exists() || dest.length() < 100_000) {
                try {
                    context.assets.open("models/vad/silero_vad.onnx").use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (e: Throwable) {
                    Log.w("iTantra", "Failed to copy silero_vad.onnx from assets", e)
                }
            }
            if (dest.exists()) candidates.add(dest.absolutePath)
        }
        candidates.addAll(listOf(
            "app/src/main/assets/models/vad/silero_vad.onnx",
            "src/main/assets/models/vad/silero_vad.onnx",
            "D:/SIH 2026/itantra/app/src/main/assets/models/vad/silero_vad.onnx"
        ))
        for (c in candidates) {
            if (File(c).exists() && sileroVad.load(c)) {
                Log.i("iTantra", "Silero VAD loaded successfully from $c")
                break
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (isRecording.getAndSet(true)) return
        ensureVadLoaded()
        vadFsm.reset()
        sileroVad.reset()

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_MASK,
            ENCODING_FORMAT
        )
        val bufferCapacity = maxOf(minBufferSize, VAD_FRAME_SIZE * 4)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_MASK,
                ENCODING_FORMAT,
                bufferCapacity
            )
            audioRecord?.startRecording()
        } catch (e: Throwable) {
            Log.e("iTantra", "Failed to start AudioRecord", e)
            isRecording.set(false)
            return
        }

        recordingJob = captureScope.launch {
            val directBuffer = ByteBuffer.allocateDirect(VAD_FRAME_SIZE * 2)
                .order(ByteOrder.nativeOrder())
            val frameShorts = ShortArray(VAD_FRAME_SIZE)
            val rollingUtterance = ArrayList<Float>(SAMPLE_RATE * 10)

            while (isRecording.get()) {
                directBuffer.clear()
                val bytesRead = audioRecord?.read(directBuffer, directBuffer.capacity()) ?: -1

                if (bytesRead > 0) {
                    val shortBuffer = directBuffer.asShortBuffer()
                    val frameCount = bytesRead / 2
                    var sumSquares = 0.0

                    for (i in 0 until frameCount) {
                        val sample = shortBuffer.get(i)
                        frameShorts[i] = sample
                        sumSquares += sample * sample
                        val floatSample = sample.toFloat() / 32768.0f
                        rollingUtterance.add(floatSample)
                    }

                    if (frameCount > 0 && onRms != null) {
                        val rms = sqrt(sumSquares / frameCount)
                        val rmsDb = (20.0 * log10(rms.coerceAtLeast(1.0))).toFloat()
                        onRms.invoke(rmsDb)
                    }

                    // Run Silero VAD on 512-sample frame
                    if (frameCount == VAD_FRAME_SIZE && sileroVad.isLoaded()) {
                        val prob = sileroVad.predict(frameShorts)
                        val vadState = vadFsm.onFrame(prob, frameShorts)

                        if (vadState == VadState.Eou) {
                            // Automatic sentence boundary detected on 500 ms speech pause (FR-05)
                            Log.i("iTantra", "Silero VAD EOU (Sentence boundary) detected! Utterance size: ${rollingUtterance.size}")
                            val utterance = vadFsm.getUtterance()
                            val utteranceFloats = if (utterance.isNotEmpty()) {
                                FloatArray(utterance.size) { utterance[it] / 32768.0f }
                            } else {
                                rollingUtterance.toFloatArray()
                            }
                            if (utteranceFloats.isNotEmpty()) {
                                onUtteranceRecorded(utteranceFloats)
                            }
                            vadFsm.reset()
                            sileroVad.reset()
                            rollingUtterance.clear()

                            if (!continuousDuplex) {
                                break
                            }
                        }
                    }
                }
            }

            try {
                audioRecord?.release()
            } catch (_: Throwable) {}
            audioRecord = null

            // If stopped manually (PTT released) and utterance remaining, emit it
            if (rollingUtterance.isNotEmpty()) {
                val finalWaveform = rollingUtterance.toFloatArray()
                rollingUtterance.clear()
                Log.i("iTantra", "PTT release flush: ${finalWaveform.size} samples (${finalWaveform.size / SAMPLE_RATE.toFloat()}s)")
                onUtteranceRecorded(finalWaveform)
            }
        }
    }

    fun stopRecording() {
        if (!isRecording.getAndSet(false)) return
        try {
            audioRecord?.stop()
        } catch (e: Throwable) {
            Log.w("iTantra", "Exception stopping AudioRecord", e)
        }
    }
}
