package com.itantra.data.vad

import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * Real Silero VAD v5 per PRD §3.1 and FR-05.
 * On Android (Dalvik/ART), executes natively via sherpa-onnx Vad C++ JNI bridge,
 * eliminating external onnxruntime-android symbol collisions.
 * On Host JVM (desktop unit tests), delegates to HostSileroVad using ONNX Runtime Java API.
 */
class SileroVad {

    private var nativeVad: Vad? = null
    private var hostVad: HostSileroVad? = null
    private var loaded = false
    private var modelPath: String? = null

    private fun isHostJvm(): Boolean = try {
        System.getProperty("java.vm.name") != "Dalvik"
    } catch (_: Throwable) {
        true
    }

    fun load(path: String): Boolean {
        return try {
            val f = File(path)
            if (!f.exists() || f.length() < 100_000) {
                loaded = false
                return false
            }

            if (!isHostJvm()) {
                // Android runtime: use native Sherpa-ONNX Vad
                try {
                    nativeVad?.release()
                } catch (_: Throwable) {}

                val sileroConfig = SileroVadModelConfig(
                    model = path,
                    threshold = 0.5f,
                    minSilenceDuration = 0.5f,
                    minSpeechDuration = 0.25f,
                    windowSize = 512
                )
                val vadConfig = VadModelConfig(
                    sileroVadModelConfig = sileroConfig,
                    sampleRate = 16000,
                    numThreads = 1,
                    provider = "cpu",
                    debug = false
                )
                nativeVad = Vad(null, vadConfig)
                loaded = true
                modelPath = path
                Log.i("iTantra", "Native Sherpa Silero VAD loaded successfully from $path")
                true
            } else {
                // Host JVM (unit test runner): use ONNX Runtime Java API
                val h = HostSileroVad()
                val ok = h.load(path)
                if (ok) {
                    hostVad = h
                    loaded = true
                    modelPath = path
                    true
                } else {
                    loaded = false
                    false
                }
            }
        } catch (t: Throwable) {
            Log.e("iTantra", "SileroVad load failed for $path", t)
            unload()
            false
        }
    }

    fun isLoaded(): Boolean = loaded && (if (isHostJvm()) hostVad?.isLoaded() == true else nativeVad != null)

    fun predict(pcm: ShortArray): Float {
        if (!isLoaded() || pcm.isEmpty()) return 0.0f
        return try {
            if (!isHostJvm() && nativeVad != null) {
                val inputData = FloatArray(512)
                val n = minOf(pcm.size, 512)
                for (i in 0 until n) {
                    inputData[i] = pcm[i] / 32768.0f
                }
                val prob = nativeVad!!.compute(inputData)
                prob.coerceIn(0.0f, 1.0f)
            } else if (hostVad != null) {
                hostVad!!.predict(pcm)
            } else {
                0.0f
            }
        } catch (t: Throwable) {
            0.0f
        }
    }

    fun reset() {
        try {
            nativeVad?.reset()
            hostVad?.reset()
        } catch (_: Throwable) {}
    }

    fun unload() {
        try { nativeVad?.release() } catch (_: Throwable) {}
        nativeVad = null
        hostVad?.unload()
        hostVad = null
        loaded = false
        modelPath = null
    }

    /**
     * Fallback for host JVM unit tests where native Android .so cannot be loaded.
     * Uses desktop onnxruntime Java API.
     */
    private class HostSileroVad {
        private var env: ai.onnxruntime.OrtEnvironment? = null
        private var session: ai.onnxruntime.OrtSession? = null
        private var loaded = false
        private var state: Array<Array<FloatArray>> = Array(2) { Array(1) { FloatArray(128) } }

        fun load(path: String): Boolean {
            return try {
                val f = File(path)
                if (!f.exists() || f.length() < 100_000) return false
                session?.close()
                env?.close()
                env = ai.onnxruntime.OrtEnvironment.getEnvironment()
                val opts = ai.onnxruntime.OrtSession.SessionOptions()
                opts.setOptimizationLevel(ai.onnxruntime.OrtSession.SessionOptions.OptLevel.ALL_OPT)
                session = env!!.createSession(path, opts)
                val inputNames = session!!.inputNames
                if (!inputNames.contains("input") || !inputNames.contains("state") || !inputNames.contains("sr")) {
                    session?.close()
                    env?.close()
                    loaded = false
                    return false
                }
                loaded = true
                state = Array(2) { Array(1) { FloatArray(128) } }
                true
            } catch (e: Throwable) {
                try { session?.close() } catch (_: Throwable) {}
                try { env?.close() } catch (_: Throwable) {}
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
                val inputData = FloatArray(512)
                val n = minOf(pcm.size, 512)
                for (i in 0 until n) {
                    inputData[i] = pcm[i] / 32768.0f
                }
                val inputTensor = ai.onnxruntime.OnnxTensor.createTensor(
                    environment,
                    java.nio.FloatBuffer.wrap(inputData),
                    longArrayOf(1, 512)
                )
                val stateData = FloatArray(2 * 1 * 128)
                var idx = 0
                for (i in 0 until 2) for (j in 0 until 1) for (k in 0 until 128) stateData[idx++] = state[i][j][k]
                val stateTensor = ai.onnxruntime.OnnxTensor.createTensor(
                    environment,
                    java.nio.FloatBuffer.wrap(stateData),
                    longArrayOf(2, 1, 128)
                )
                val srTensor = ai.onnxruntime.OnnxTensor.createTensor(
                    environment,
                    java.nio.LongBuffer.wrap(longArrayOf(16000)),
                    longArrayOf(1)
                )

                val inputs = mapOf("input" to inputTensor, "state" to stateTensor, "sr" to srTensor)
                val outputs = sess.run(java.util.Collections.unmodifiableMap(inputs))
                val outputTensor = outputs[0] as ai.onnxruntime.OnnxTensor
                val prob = outputTensor.floatBuffer.get(0)
                val stateNTensor = outputs[1] as ai.onnxruntime.OnnxTensor
                val stateNBuffer = stateNTensor.floatBuffer
                stateNBuffer.rewind()
                idx = 0
                for (i in 0 until 2) for (j in 0 until 1) for (k in 0 until 128) {
                    state[i][j][k] = stateNBuffer.get(idx++)
                }
                inputTensor.close()
                stateTensor.close()
                srTensor.close()
                outputTensor.close()
                stateNTensor.close()
                return prob.coerceIn(0.0f, 1.0f)
            } catch (_: Throwable) {
                return 0.0f
            }
        }

        fun reset() {
            state = Array(2) { Array(1) { FloatArray(128) } }
        }

        fun unload() {
            try { session?.close() } catch (_: Throwable) {}
            try { env?.close() } catch (_: Throwable) {}
            session = null
            env = null
            loaded = false
            state = Array(2) { Array(1) { FloatArray(128) } }
        }
    }
}
