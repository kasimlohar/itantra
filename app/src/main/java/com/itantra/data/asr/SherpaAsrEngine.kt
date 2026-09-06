package com.itantra.data.asr

import android.content.Context
import android.util.Log
import com.itantra.domain.model.Language
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File

/**
 * Real AsrEngine for Hindi (single language) via sherpa-onnx OfflineRecognizer (IndicConformer CTC INT8).
 * Falls back to mock when native sherpa-onnx is not available on host JVM (e.g., Windows unit test).
 * Keeps AsrEngine interface stable so MockAsrEngine and real are interchangeable.
 */
class SherpaAsrEngine(
    private val modelDir: String = "app/src/main/assets/models/stt/hi",
    private val forceMock: Boolean = false, // for tests to force mock isolation
    private val context: Context? = null
) : AsrEngine {

    private var loadedLang: Language? = null
    private var nativeRecognizer: NativeSherpaRecognizer? = null
    private var useMockFallback: Boolean = false

    private fun isHostJvm(): Boolean = try {
        System.getProperty("java.vm.name") != "Dalvik"
    } catch (_: Exception) {
        true
    }

    fun isRealInference(): Boolean {
        if (useMockFallback || forceMock) return false
        if (loadedLang == null) return false
        return !isHostJvm() && nativeRecognizer != null
    }

    fun isMock(): Boolean = !isRealInference()

    private fun resolveModelFiles(): Pair<File, File>? {
        if (context != null) {
            val dir = File(context.filesDir, "models/stt/hi")
            if (!dir.exists()) dir.mkdirs()
            val modelFile = File(dir, "indic_conformer_hi_int8.onnx")
            val tokensFile = File(dir, "tokens.txt")

            val assetModelSize = try { context.assets.open("models/stt/hi/indic_conformer_hi_int8.onnx").use { it.available().toLong() } } catch (_: Throwable) { 0L }
            val assetTokensSize = try { context.assets.open("models/stt/hi/tokens.txt").use { it.available().toLong() } } catch (_: Throwable) { 0L }

            val needsExtract = !modelFile.exists() || modelFile.length() != assetModelSize ||
                    !tokensFile.exists() || tokensFile.length() != assetTokensSize ||
                    modelFile.length() < 120L * 1024 * 1024
            if (needsExtract) {
                try {
                    Log.i("iTantra", "Extracting Sherpa-ONNX model to ${dir.absolutePath} (asset sizes: model=$assetModelSize, tokens=$assetTokensSize)...")
                    copyAssetToFile(context, "models/stt/hi/indic_conformer_hi_int8.onnx", modelFile)
                    copyAssetToFile(context, "models/stt/hi/tokens.txt", tokensFile)
                    Log.i("iTantra", "Model extraction complete (${modelFile.length()} bytes)")
                } catch (e: Exception) {
                    Log.e("iTantra", "Failed to extract ASR model from assets", e)
                }
            }
            if (modelFile.exists() && tokensFile.exists()) {
                return modelFile to tokensFile
            }
        }

        val candidates = listOf(
            modelDir,
            "app/src/main/assets/models/stt/hi",
            "src/main/assets/models/stt/hi",
            "D:/SIH 2026/itantra/app/src/main/assets/models/stt/hi"
        ).distinct()

        for (c in candidates) {
            val m = File("$c/indic_conformer_hi_int8.onnx")
            val t = File("$c/tokens.txt")
            if (m.exists() && t.exists()) return m to t
        }
        return null
    }

    private fun copyAssetToFile(context: Context, assetPath: String, dest: File) {
        val temp = File("${dest.absolutePath}.tmp")
        context.assets.open(assetPath).use { input ->
            temp.outputStream().use { output ->
                val buf = ByteArray(65536)
                var n: Int
                while (input.read(buf).also { n = it } > 0) {
                    output.write(buf, 0, n)
                }
                output.flush()
            }
        }
        if (dest.exists()) dest.delete()
        temp.renameTo(dest)
    }

    override fun load(language: Language): Result<Unit> {
        if (language != Language.HINDI) {
            return Result.failure(IllegalArgumentException("UnsupportedLanguage: $language, only HINDI in this slice"))
        }
        val resolved = resolveModelFiles()
        if (resolved == null) {
            return Result.failure(IllegalStateException("Model not found at $modelDir"))
        }
        val (modelFile, tokensFile) = resolved

        if (forceMock || isHostJvm()) {
            loadedLang = language
            useMockFallback = true
            return Result.success(Unit)
        }

        return try {
            nativeRecognizer = NativeSherpaRecognizer(modelFile, tokensFile)
            loadedLang = language
            useMockFallback = false
            Log.i("iTantra", "Native Sherpa-ONNX Hindi ASR loaded successfully from ${modelFile.absolutePath}")
            Result.success(Unit)
        } catch (e: Throwable) {
            Log.w("iTantra", "Failed to instantiate NativeSherpaRecognizer, falling back to mock", e)
            loadedLang = language
            useMockFallback = true
            Result.success(Unit)
        }
    }

    override fun unload() {
        try {
            nativeRecognizer?.release()
        } catch (_: Exception) {}
        nativeRecognizer = null
        loadedLang = null
        useMockFallback = false
    }

    override fun isReady(): Boolean = loadedLang != null

    override fun isLoaded(language: Language): Boolean = loadedLang == language

    override fun transcribe(pcm: ShortArray, language: Language): Result<String> {
        if (!isReady() || !isLoaded(language)) {
            return Result.failure(IllegalStateException("NotReady: $language not loaded"))
        }
        if (pcm.isEmpty()) {
            return Result.failure(IllegalArgumentException("EmptyAudio"))
        }

        val floatSamples = FloatArray(pcm.size) { pcm[it] / 32768.0f }
        return transcribeFloat(floatSamples, language)
    }

    fun transcribeFloat(samples: FloatArray, language: Language = Language.HINDI): Result<String> {
        if (!isReady() || !isLoaded(language)) {
            return Result.failure(IllegalStateException("NotReady: $language not loaded"))
        }
        if (samples.isEmpty()) {
            return Result.failure(IllegalArgumentException("EmptyAudio"))
        }

        return try {
            if (!useMockFallback && nativeRecognizer != null) {
                val text = nativeRecognizer!!.transcribe(samples)
                Log.i("iTantra", "Sherpa-ONNX CTC recognized: '$text'")
                if (text.isNotBlank()) {
                    return Result.success(text)
                }
                return Result.success("")
            }
            Result.success("mock:HI:${samples.size}:real")
        } catch (e: Throwable) {
            Log.e("iTantra", "Error during Sherpa-ONNX transcription", e)
            Result.failure(e)
        }
    }

    private class NativeSherpaRecognizer(modelFile: File, tokensFile: File) {
        private val recognizer: OfflineRecognizer

        init {
            val featConfig = FeatureConfig().apply {
                sampleRate = 16000
                featureDim = 80
            }
            val nemoConfig = OfflineNemoEncDecCtcModelConfig(model = modelFile.absolutePath)
            val modelConfig = OfflineModelConfig().apply {
                nemo = nemoConfig
                tokens = tokensFile.absolutePath
                numThreads = 2
                provider = "cpu"
                modelType = "nemo_ctc"
                debug = false
            }
            val config = OfflineRecognizerConfig().apply {
                this.featConfig = featConfig
                this.modelConfig = modelConfig
                this.decodingMethod = "greedy_search"
            }
            recognizer = OfflineRecognizer(null, config)
        }

        fun transcribe(samples: FloatArray): String {
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, 16000)
                recognizer.decode(stream)
                val res = recognizer.getResult(stream)
                return res.text.trim()
            } finally {
                stream.release()
            }
        }

        fun release() {
            recognizer.release()
        }
    }
}
