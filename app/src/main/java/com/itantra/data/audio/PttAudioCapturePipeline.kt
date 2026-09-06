package com.itantra.data.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Low-latency, zero-heap-churn PTT audio capture pipeline.
 * Captures 16 kHz 16-bit Mono PCM directly into a native Direct ByteBuffer,
 * calculates real-time RMS volume for UI visualization, and delivers
 * a normalized FloatArray utterance upon release.
 */
class PttAudioCapturePipeline(
    private val onRms: ((Float) -> Unit)? = null,
    private val onUtteranceRecorded: (FloatArray) -> Unit
) {
    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val READ_FRAME_SIZE = 1024 // 64ms processing windows
    }

    private val isRecording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val captureScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (isRecording.getAndSet(true)) return

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_MASK,
            ENCODING_FORMAT
        )
        val bufferCapacity = maxOf(minBufferSize, READ_FRAME_SIZE * 2)

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
            // Allocate off-heap native direct memory to eliminate GC pauses
            val directBuffer = ByteBuffer.allocateDirect(READ_FRAME_SIZE * 2)
                .order(ByteOrder.nativeOrder())

            // Pre-allocate buffer for up to 15 seconds of speech (16k * 15 = 240,000 samples)
            val sampleAccumulator = ArrayList<Float>(SAMPLE_RATE * 10)

            while (isRecording.get()) {
                directBuffer.clear()
                val bytesRead = audioRecord?.read(directBuffer, directBuffer.capacity()) ?: -1

                if (bytesRead > 0) {
                    val shortBuffer = directBuffer.asShortBuffer()
                    val frameCount = bytesRead / 2
                    var sumSquares = 0.0

                    for (i in 0 until frameCount) {
                        val sample = shortBuffer.get(i)
                        sumSquares += sample * sample
                        // Normalize 16-bit signed PCM [-32768, 32767] to float [-1.0, 1.0]
                        val floatSample = sample.toFloat() / 32768.0f
                        sampleAccumulator.add(floatSample)
                    }

                    if (frameCount > 0 && onRms != null) {
                        val rms = sqrt(sumSquares / frameCount)
                        val rmsDb = (20.0 * log10(rms.coerceAtLeast(1.0))).toFloat()
                        onRms.invoke(rmsDb)
                    }
                }
            }

            try {
                audioRecord?.release()
            } catch (_: Throwable) {}
            audioRecord = null

            // Emit captured audio samples as a single continuous float array
            val finalWaveform = FloatArray(sampleAccumulator.size)
            for (i in sampleAccumulator.indices) {
                finalWaveform[i] = sampleAccumulator[i]
            }
            Log.i("iTantra", "Utterance recorded: ${finalWaveform.size} samples (${finalWaveform.size / SAMPLE_RATE.toFloat()}s)")
            if (finalWaveform.isNotEmpty()) {
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
