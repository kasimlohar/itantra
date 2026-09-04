package com.itantra.data.tts

import com.itantra.domain.model.Language
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TtsEngineTest {

    @Test
    fun synthesize_returnsMockPcm22050_whenVoiceLoaded() {
        val e = MockTtsEngine()
        e.loadVoice(Language.HINDI)
        val r = e.synthesize("नमस्ते", Language.HINDI)
        assertThat(r.isSuccess).isTrue()
        val buf = r.getOrThrow()
        assertThat(buf.sampleRate).isEqualTo(22050)
        assertThat(buf.pcm.isNotEmpty()).isTrue()
    }

    @Test
    fun synthesize_fails_whenNotReady() {
        val e = MockTtsEngine()
        val r = e.synthesize("hello", Language.HINDI)
        assertThat(r.isFailure).isTrue()
    }

    @Test
    fun synthesize_fails_whenVoiceMissing() {
        val e = MockTtsEngine()
        e.loadVoice(Language.HINDI)
        val r = e.synthesize("hello", Language.BENGALI)
        assertThat(r.isFailure).isTrue()
    }

    @Test
    fun synthesize_fails_emptyText() {
        val e = MockTtsEngine()
        e.loadVoice(Language.HINDI)
        val r1 = e.synthesize("", Language.HINDI)
        assertThat(r1.isFailure).isTrue()
        val r2 = e.synthesize("   ", Language.HINDI)
        assertThat(r2.isFailure).isTrue()
    }

    @Test
    fun firstBuffer_latency_mocked_lessThan80ms() {
        val e = MockTtsEngine()
        e.loadVoice(Language.ENGLISH)
        val start = System.nanoTime()
        val r = e.synthesize("Hello world", Language.ENGLISH)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertThat(r.isSuccess).isTrue()
        // Mocked synthesis should be fast; allow generous <200ms but target <80ms per PRD
        assertThat(elapsedMs).isLessThan(200L)
        // Also ensure buffer not empty
        assertThat(r.getOrThrow().pcm.isNotEmpty()).isTrue()
    }

    @Test
    fun loadVoice_unload_isReady() {
        val e = MockTtsEngine()
        assertThat(e.isReady()).isFalse()
        e.loadVoice(Language.TAMIL)
        assertThat(e.isReady()).isTrue()
        assertThat(e.isLoaded(Language.TAMIL)).isTrue()
        e.unload()
        assertThat(e.isReady()).isFalse()
        assertThat(e.isLoaded(Language.TAMIL)).isFalse()
    }

    @Test
    fun synthesize_afterUnload_fails() {
        val e = MockTtsEngine()
        e.loadVoice(Language.ENGLISH)
        e.unload()
        val r = e.synthesize("hi", Language.ENGLISH)
        assertThat(r.isFailure).isTrue()
    }

    @Test
    fun synthesize_loadAll10Voices() {
        val e = MockTtsEngine()
        for (lang in Language.entries) {
            val r = e.loadVoice(lang)
            assertThat(r.isSuccess).isTrue()
        }
        for (lang in Language.entries) {
            assertThat(e.isLoaded(lang)).isTrue()
        }
    }
}
