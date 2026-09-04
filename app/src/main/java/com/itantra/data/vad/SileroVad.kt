package com.itantra.data.vad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.LongBuffer
import java.util.Collections

/**
 * Real Silero VAD v5 via ONNX Runtime (Java) per PRD §3.1.
 * Loads `silero_vad.onnx` (≈2.21 MB MIT) and runs actual neural inference.
 * Handles 480→512 padding, 16 kHz mono, sr=16000, state [2,1,128] h/c.
 * Mirrors C++ itantra::vad::SileroVad.
 */
class SileroVad {
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var loaded = false
    private var modelPath: String? = null
    private var state: Array<Array<FloatArray>> = Array(2) { Array(1) { FloatArray(128) } }

    fun load(path: String): Boolean {
        return try {
            val f = File(path)
            if (!f.exists() || f.length() < 100_000) return false
            // Close previous
            session?.close()
            env?.close()
            env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions()
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            session = env!!.createSession(path, opts)
            // Verify expected inputs/outputs exist (proves real ONNX, not just file size)
            val inputNames = session!!.inputNames
            if (!inputNames.contains("input") || !inputNames.contains("state") || !inputNames.contains("sr")) {
                session?.close()
                env?.close()
                loaded = false
                return false
            }
            loaded = true
            modelPath = path
            // reset state
            state = Array(2) { Array(1) { FloatArray(128) } }
            true
        } catch (e: Exception) {
            try { session?.close() } catch (_: Exception) {}
            try { env?.close() } catch (_: Exception) {}
            loaded = false
            false
        }
    }

    fun isLoaded(): Boolean = loaded && session != null

    fun predict(pcm: ShortArray): Float {
        if (!isLoaded() || pcm.isEmpty()) return 0.0f
        val sess = session ?: return 0.0f
        val environment = env ?: return 0.0f
        try {
            // Prepare input [1,512] float normalized
            val inputData = FloatArray(512)
            val n = minOf(pcm.size, 512)
            for (i in 0 until n) {
                inputData[i] = pcm[i] / 32768.0f
            }
            // Already zero-padded for remaining
            val inputTensor = OnnxTensor.createTensor(environment, java.nio.FloatBuffer.wrap(inputData), longArrayOf(1, 512))
            // state [2,1,128] flatten
            val stateData = FloatArray(2 * 1 * 128)
            var idx = 0
            for (i in 0 until 2) for (j in 0 until 1) for (k in 0 until 128) stateData[idx++] = state[i][j][k]
            val stateTensor = OnnxTensor.createTensor(environment, java.nio.FloatBuffer.wrap(stateData), longArrayOf(2, 1, 128))
            val srTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(longArrayOf(16000)), longArrayOf(1))

            val inputs = mapOf("input" to inputTensor, "state" to stateTensor, "sr" to srTensor)
            val outputs = sess.run(Collections.unmodifiableMap(inputs))
            // output is float[1,1]
            val outputTensor = outputs[0] as OnnxTensor
            val prob = (outputTensor.floatBuffer.get(0))
            // stateN is outputs[1]
            val stateNTensor = outputs[1] as OnnxTensor
            val stateNBuffer = stateNTensor.floatBuffer
            stateNBuffer.rewind()
            idx = 0
            for (i in 0 until 2) for (j in 0 until 1) for (k in 0 until 128) {
                state[i][j][k] = stateNBuffer.get(idx++)
            }
            // Clean up
            inputTensor.close()
            stateTensor.close()
            srTensor.close()
            outputTensor.close()
            stateNTensor.close()
            return prob.coerceIn(0.0f, 1.0f)
        } catch (e: Exception) {
            return 0.0f
        }
    }

    fun reset() {
        state = Array(2) { Array(1) { FloatArray(128) } }
    }

    fun unload() {
        try { session?.close() } catch (_: Exception) {}
        try { env?.close() } catch (_: Exception) {}
        session = null
        env = null
        loaded = false
        modelPath = null
        state = Array(2) { Array(1) { FloatArray(128) } }
    }
}
