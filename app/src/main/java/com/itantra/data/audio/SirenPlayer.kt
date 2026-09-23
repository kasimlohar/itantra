package com.itantra.data.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.sin

/**
 * SirenPlayer — generates and plays proximity-reactive tones using AudioTrack.
 * Uses USAGE_ALARM so it overrides media/call audio at maximum urgency.
 *
 * Frequency mapping:
 *   distance ≥ 20 m  → 440 Hz, beep every 1200 ms
 *   distance 10-20 m → 600 Hz, beep every  800 ms
 *   distance  5-10 m → 800 Hz, beep every  500 ms
 *   distance  2-5 m  → 1000 Hz, beep every 250 ms
 *   distance  < 2 m  → 1200 Hz, continuous tone
 */
@Singleton
class SirenPlayer @Inject constructor() {

    private val sampleRate = 22050
    private var collectJob: Job? = null
    private var beepJob: Job? = null
    private var audioTrack: AudioTrack? = null

    // Hot state — beep loop reads this; distance flow writes it
    private val _currentDistance = MutableStateFlow(-1f)

    /** Returns (freqHz, intervalMs) pair for the given distance. */
    internal fun mappingForDistance(metres: Float): Pair<Int, Long> = when {
        metres < 0f  -> Pair(440,  1200L)  // unknown — slow beep
        metres < 2f  -> Pair(1200, 0L)     // continuous — interval 0 = loop immediately
        metres < 5f  -> Pair(1000, 250L)
        metres < 10f -> Pair(800,  500L)
        metres < 20f -> Pair(600,  800L)
        else         -> Pair(440,  1200L)
    }

    /**
     * Starts continuous siren playback that adapts to [distanceFlow].
     * Two separate coroutines:
     *   1. Collector: reads distance, updates [_currentDistance]
     *   2. Beep loop: reads [_currentDistance] and plays the right tone on a loop
     * The beep loop continues even when BLE gives no updates, so the siren is
     * always heard while the feature is active.
     */
    fun start(scope: CoroutineScope, distanceFlow: Flow<Float>) {
        stop()

        // 1. Collect distance updates into hot state
        collectJob = scope.launch {
            try {
                distanceFlow.collect { metres ->
                    _currentDistance.value = metres
                }
            } catch (_: Throwable) {}
        }

        // 2. Independent beep loop — runs continuously regardless of BLE updates
        beepJob = scope.launch {
            while (true) {
                val metres = _currentDistance.value
                val (freqHz, intervalMs) = mappingForDistance(metres)
                val toneDurationMs = if (intervalMs == 0L) 600L else minOf(intervalMs / 2, 300L)
                try { playBeep(freqHz, toneDurationMs) } catch (_: Throwable) {}
                // Wait for the rest of the interval before the next beep
                val silenceMs = if (intervalMs == 0L) 50L else (intervalMs - toneDurationMs).coerceAtLeast(50L)
                delay(silenceMs)
            }
        }
    }

    fun stop() {
        collectJob?.cancel(); collectJob = null
        beepJob?.cancel();    beepJob    = null
        _currentDistance.value = -1f
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }

    private fun playBeep(freqHz: Int, durationMs: Long) {
        val numSamples = ((durationMs.coerceAtLeast(50L) * sampleRate) / 1000L).toInt()
        val buffer = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            buffer[i] = (Short.MAX_VALUE * sin(2.0 * PI * freqHz * t)).toInt().toShort()
        }
        val at = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(buffer.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        at.write(buffer, 0, buffer.size)
        at.play()
        // Release the previous track after starting the new one
        val old = audioTrack
        audioTrack = at
        old?.stop()
        old?.release()
    }
}
