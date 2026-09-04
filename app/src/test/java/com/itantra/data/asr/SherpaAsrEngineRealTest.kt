package com.itantra.data.asr

import com.itantra.domain.model.Language
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SherpaAsrEngineRealTest {
    @Test
    fun isRealInference_falseOnHost_mockIsolated() {
        val e = SherpaAsrEngine("src/main/assets/models/stt/hi", forceMock = true)
        e.load(Language.HINDI)
        assertThat(e.isRealInference()).isFalse()
        assertThat(e.isMock()).isTrue()
        val pcm = ShortArray(16000) { (1000 * kotlin.math.sin(2 * Math.PI * 100 * it / 16000)).toInt().toShort() }
        val r = e.transcribe(pcm, Language.HINDI)
        assertThat(r.isSuccess).isTrue()
        assertThat(r.getOrNull()).contains("mock:")
    }
}
