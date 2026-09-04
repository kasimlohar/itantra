package com.itantra.data.tts

import com.itantra.domain.model.Language
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SherpaTtsEngineRealTest {
    private fun modelDir(): String {
        val candidates = listOf(
            "app/src/main/assets/models/tts/hi",
            "src/main/assets/models/tts/hi",
            "D:/SIH 2026/itantra/app/src/main/assets/models/tts/hi"
        )
        return candidates.firstOrNull { java.io.File(it).exists() } ?: "app/src/main/assets/models/tts/hi"
    }

    @Test
    fun isRealInference_falseOnHost_mockIsolated() {
        val e = SherpaTtsEngine(modelDir(), forceMock = true)
        val load = e.loadVoice(Language.HINDI)
        assertThat(load.isSuccess).isTrue()
        assertThat(e.isRealInference()).isFalse()
        assertThat(e.isMock()).isTrue()
        val r = e.synthesize("नमस्ते", Language.HINDI)
        assertThat(r.isSuccess).isTrue()
        assertThat(r.getOrThrow().pcm.isNotEmpty()).isTrue()
        assertThat(r.getOrThrow().sampleRate).isEqualTo(22050)
        e.unload()
    }

    @Test
    fun forceMock_stillEnforcesHindiOnly() {
        val e = SherpaTtsEngine(modelDir(), forceMock = true)
        val r = e.loadVoice(Language.BENGALI)
        assertThat(r.isFailure).isTrue()
    }
}
