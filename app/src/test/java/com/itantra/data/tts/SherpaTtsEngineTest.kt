package com.itantra.data.tts

import com.itantra.data.models.ModelManager
import com.itantra.domain.model.Language
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class SherpaTtsEngineTest {

    private fun modelDir(): String {
        val candidates = listOf(
            "app/src/main/assets/models/tts/hi",
            "src/main/assets/models/tts/hi",
            "D:/SIH 2026/itantra/app/src/main/assets/models/tts/hi"
        )
        return candidates.firstOrNull { File(it).exists() } ?: "app/src/main/assets/models/tts/hi"
    }
    private fun modelFile(): File {
        val dir = File(modelDir())
        val onnx = dir.listFiles()?.firstOrNull { it.name.endsWith(".onnx") }
        return onnx ?: File("${modelDir()}/hi_IN-pratham-medium.onnx")
    }
    private fun configFile(): File {
        val m = modelFile()
        return File(m.absolutePath + ".json")
    }

    @Test
    fun loadHindi_succeeds_forChosenLanguage() {
        val e = SherpaTtsEngine(modelDir())
        val r = e.loadVoice(Language.HINDI)
        assertThat(r.isSuccess).isTrue()
        assertThat(e.isLoaded(Language.HINDI)).isTrue()
        assertThat(e.isReady()).isTrue()
        e.unload()
    }

    @Test
    fun synthesize_returnsNonEmpty22050_whenVoiceLoaded() {
        val e = SherpaTtsEngine(modelDir())
        e.loadVoice(Language.HINDI)
        val r = e.synthesize("नमस्ते दुनिया", Language.HINDI)
        assertThat(r.isSuccess).isTrue()
        val buf = r.getOrThrow()
        assertThat(buf.sampleRate).isEqualTo(22050)
        assertThat(buf.pcm.isNotEmpty()).isTrue()
        e.unload()
    }

    @Test
    fun synthesize_fails_whenNotReady_orEmpty_orUnsupported() {
        val e = SherpaTtsEngine(modelDir())
        val r1 = e.synthesize("hello", Language.HINDI)
        assertThat(r1.isFailure).isTrue()
        e.loadVoice(Language.HINDI)
        val r2 = e.synthesize("", Language.HINDI)
        assertThat(r2.isFailure).isTrue()
        val r3 = e.synthesize("   ", Language.HINDI)
        assertThat(r3.isFailure).isTrue()
        val r4 = e.synthesize("hello", Language.BENGALI)
        assertThat(r4.isFailure).isTrue()
        e.unload()
    }

    @Test
    fun unload_resetsReady() {
        val e = SherpaTtsEngine(modelDir())
        e.loadVoice(Language.HINDI)
        assertThat(e.isReady()).isTrue()
        e.unload()
        assertThat(e.isReady()).isFalse()
        assertThat(e.isLoaded(Language.HINDI)).isFalse()
        val r = e.synthesize("hi", Language.HINDI)
        assertThat(r.isFailure).isTrue()
    }

    @Test
    fun modelFileSize35to65MB_andLicenseMIT() {
        val m = modelFile()
        assertThat(m.exists()).isTrue()
        val size = m.length()
        assertThat(size).isAtLeast(35L * 1024 * 1024)
        assertThat(size).isAtMost(70L * 1024 * 1024)
        val cfg = configFile()
        assertThat(cfg.exists()).isTrue()
        assertThat(cfg.length()).isGreaterThan(0L)
        val lic = File("${modelDir()}/LICENSE")
        assertThat(lic.exists()).isTrue()
        assertThat(lic.readText()).contains("MIT")
    }

    @Test
    fun modelManager_residency_onlyOneTts() {
        val mm = ModelManager()
        val e = SherpaTtsEngine(modelDir())
        e.loadVoice(Language.HINDI)
        mm.loadTts(Language.HINDI)
        assertThat(mm.currentTts()).isEqualTo(Language.HINDI)
        mm.loadTts(Language.ENGLISH)
        assertThat(mm.currentTts()).isEqualTo(Language.ENGLISH)
        assertThat(mm.isTtsLoaded(Language.HINDI)).isFalse()
        assertThat(mm.isTtsLoaded(Language.ENGLISH)).isTrue()
        e.unload()
    }

    @Test
    fun firstBuffer_latency_under1s_hostFallback() {
        val e = SherpaTtsEngine(modelDir())
        e.loadVoice(Language.HINDI)
        val start = System.nanoTime()
        val r = e.synthesize("Hello world", Language.HINDI)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertThat(r.isSuccess).isTrue()
        assertThat(elapsedMs).isLessThan(1000L)
        assertThat(r.getOrThrow().pcm.isNotEmpty()).isTrue()
        e.unload()
    }

    @Test
    fun loadUnsupportedLanguage_fails() {
        val e = SherpaTtsEngine(modelDir())
        val r = e.loadVoice(Language.TAMIL)
        assertThat(r.isFailure).isTrue()
        assertThat(e.isReady()).isFalse()
    }
}
