package com.itantra.data.asr

import com.itantra.domain.model.Language
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AsrEngineTest {

    @Test
    fun transcribe_returnsMockText_whenLoaded() {
        val e = MockAsrEngine()
        e.load(Language.HINDI)
        val r = e.transcribe(ShortArray(480) { 1 }, Language.HINDI)
        assertThat(r.isSuccess).isTrue()
        assertThat(r.getOrNull()).contains("HINDI")
        assertThat(r.getOrNull()).contains("480")
    }

    @Test
    fun transcribe_fails_whenNotReady() {
        val e = MockAsrEngine()
        // no load
        val r = e.transcribe(ShortArray(480) { 1 }, Language.HINDI)
        assertThat(r.isFailure).isTrue()
    }

    @Test
    fun transcribe_fails_whenLanguageNotLoaded() {
        val e = MockAsrEngine()
        e.load(Language.HINDI)
        val r = e.transcribe(ShortArray(480) { 1 }, Language.BENGALI)
        assertThat(r.isFailure).isTrue()
    }

    @Test
    fun transcribe_fails_emptyAudio() {
        val e = MockAsrEngine()
        e.load(Language.HINDI)
        val r = e.transcribe(ShortArray(0), Language.HINDI)
        assertThat(r.isFailure).isTrue()
    }

    @Test
    fun load_unload_isReady() {
        val e = MockAsrEngine()
        assertThat(e.isReady()).isFalse()
        e.load(Language.HINDI)
        assertThat(e.isReady()).isTrue()
        assertThat(e.isLoaded(Language.HINDI)).isTrue()
        e.unload()
        assertThat(e.isReady()).isFalse()
        assertThat(e.isLoaded(Language.HINDI)).isFalse()
    }

    @Test
    fun load_all10Languages_succeed() {
        val e = MockAsrEngine()
        for (lang in Language.entries) {
            val r = e.load(lang)
            assertThat(r.isSuccess).isTrue()
        }
        assertThat(e.isReady()).isTrue()
        for (lang in Language.entries) {
            assertThat(e.isLoaded(lang)).isTrue()
        }
    }

    @Test
    fun transcribe_afterUnload_fails() {
        val e = MockAsrEngine()
        e.load(Language.ENGLISH)
        e.unload()
        val r = e.transcribe(ShortArray(10) { 1 }, Language.ENGLISH)
        assertThat(r.isFailure).isTrue()
    }
}
