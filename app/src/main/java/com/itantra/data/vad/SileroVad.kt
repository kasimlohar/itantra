package com.itantra.data.vad

import java.io.File
import kotlin.math.sqrt

/**
 * Kotlin wrapper for Silero VAD v5 ONNX per PRD §3.1.
 * Minimal real impl for this slice: checks file exists and does energy-based heuristic.
 * Full ONNX Runtime inference deferred to next phase (sherpa-onnx).
 * Mirrors C++ itantra::vad::SileroVad.
 */
class SileroVad {
    private var loaded = false
    private var modelPath: String? = null
    // mock LSTM states
    private var h = FloatArray(128)
    private var c = FloatArray(128)

    fun load(path: String): Boolean {
        return try {
            val f = File(path)
            if (!f.exists() || f.length() < 100_000) return false
            // Simulate mmap load <5ms
            loaded = true
            modelPath = path
            h.fill(0f); c.fill(0f)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun isLoaded(): Boolean = loaded

    fun predict(pcm: ShortArray): Float {
        if (!loaded || pcm.isEmpty()) return 0.0f
        val n = minOf(pcm.size, 512)
        var sumSq = 0.0
        for (i in 0 until n) {
            val v = pcm[i] / 32768.0
            sumSq += v * v
        }
        val rms = sqrt(sumSq / n)
        val prob = when {
            rms < 0.01 -> 0.05
            rms < 0.05 -> 0.2 + (rms - 0.01) * 10 // 0.2-0.6
            else -> 0.6 + minOf(rms * 1.2, 0.35) // up to 0.95
        }
        return prob.coerceIn(0.0, 1.0).toFloat()
    }

    fun reset() {
        h.fill(0f); c.fill(0f)
    }

    fun unload() {
        loaded = false
        modelPath = null
        h.fill(0f); c.fill(0f)
    }
}
