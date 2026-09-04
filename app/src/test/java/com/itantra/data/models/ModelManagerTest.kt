package com.itantra.data.models

import com.itantra.domain.model.Language
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModelManagerTest {

    @Test
    fun loadStt_success_and_isLoaded() {
        val m = ModelManager()
        val r = m.loadStt(Language.HINDI)
        assertThat(r.isSuccess).isTrue()
        assertThat(m.isSttLoaded(Language.HINDI)).isTrue()
        assertThat(m.currentStt()).isEqualTo(Language.HINDI)
    }

    @Test
    fun loadTts_success_and_isLoaded() {
        val m = ModelManager()
        val r = m.loadTts(Language.BENGALI)
        assertThat(r.isSuccess).isTrue()
        assertThat(m.isTtsLoaded(Language.BENGALI)).isTrue()
        assertThat(m.currentTts()).isEqualTo(Language.BENGALI)
    }

    @Test
    fun onlyOneSttResident_loadingSecondUnloadsFirst() {
        val m = ModelManager()
        m.loadStt(Language.HINDI)
        m.loadStt(Language.BENGALI)
        assertThat(m.currentStt()).isEqualTo(Language.BENGALI)
        assertThat(m.isSttLoaded(Language.HINDI)).isFalse()
        assertThat(m.isSttLoaded(Language.BENGALI)).isTrue()
    }

    @Test
    fun onlyOneTtsResident_loadingSecondUnloadsFirst() {
        val m = ModelManager()
        m.loadTts(Language.HINDI)
        m.loadTts(Language.TAMIL)
        assertThat(m.currentTts()).isEqualTo(Language.TAMIL)
        assertThat(m.isTtsLoaded(Language.HINDI)).isFalse()
        assertThat(m.isTtsLoaded(Language.TAMIL)).isTrue()
    }

    @Test
    fun sttAndTtsIndependent_oneEachCanCoexist() {
        val m = ModelManager()
        m.loadStt(Language.HINDI)
        m.loadTts(Language.BENGALI)
        assertThat(m.isSttLoaded(Language.HINDI)).isTrue()
        assertThat(m.isTtsLoaded(Language.BENGALI)).isTrue()
        assertThat(m.currentStt()).isEqualTo(Language.HINDI)
        assertThat(m.currentTts()).isEqualTo(Language.BENGALI)
        // Loading second TTS should not affect STT
        m.loadTts(Language.TAMIL)
        assertThat(m.isSttLoaded(Language.HINDI)).isTrue()
        assertThat(m.isTtsLoaded(Language.BENGALI)).isFalse()
    }

    @Test
    fun unloadStt_clearsCurrent() {
        val m = ModelManager()
        m.loadStt(Language.GUJARATI)
        assertThat(m.currentStt()).isEqualTo(Language.GUJARATI)
        m.unloadStt()
        assertThat(m.currentStt()).isNull()
        assertThat(m.isSttLoaded(Language.GUJARATI)).isFalse()
    }

    @Test
    fun unloadTts_clearsCurrent() {
        val m = ModelManager()
        m.loadTts(Language.KANNADA)
        m.unloadTts()
        assertThat(m.currentTts()).isNull()
        assertThat(m.isTtsLoaded(Language.KANNADA)).isFalse()
    }

    @Test
    fun clear_resetsBoth() {
        val m = ModelManager()
        m.loadStt(Language.HINDI)
        m.loadTts(Language.ENGLISH)
        m.clear()
        assertThat(m.currentStt()).isNull()
        assertThat(m.currentTts()).isNull()
        assertThat(m.isSttLoaded(Language.HINDI)).isFalse()
        assertThat(m.isTtsLoaded(Language.ENGLISH)).isFalse()
        assertThat(m.residentMemoryEstimateBytes()).isEqualTo(0L)
    }

    @Test
    fun isSttLoaded_falseAfterUnload() {
        val m = ModelManager()
        m.loadStt(Language.MARATHI)
        assertThat(m.isSttLoaded(Language.MARATHI)).isTrue()
        m.unloadStt()
        assertThat(m.isSttLoaded(Language.MARATHI)).isFalse()
    }

    @Test
    fun simulatedLoadLatency_lessThan180ms() {
        val m = ModelManager()
        val s = System.nanoTime()
        m.loadStt(Language.HINDI)
        val e = (System.nanoTime() - s) / 1_000_000
        assertThat(e).isLessThan(180L)
        val s2 = System.nanoTime()
        m.loadTts(Language.TAMIL)
        val e2 = (System.nanoTime() - s2) / 1_000_000
        assertThat(e2).isLessThan(180L)
    }

    @Test
    fun tenLanguageCycle_doesNotAccumulateResidents() {
        val m = ModelManager()
        for (lang in Language.entries) {
            m.loadStt(lang)
            assertThat(m.currentStt()).isEqualTo(lang)
            // only one STT resident at a time
            var sttCount = 0
            for (l in Language.entries) if (m.isSttLoaded(l)) sttCount++
            assertThat(sttCount).isEqualTo(1)
        }
        for (lang in Language.entries) {
            m.loadTts(lang)
            assertThat(m.currentTts()).isEqualTo(lang)
            var ttsCount = 0
            for (l in Language.entries) if (m.isTtsLoaded(l)) ttsCount++
            assertThat(ttsCount).isEqualTo(1)
        }
        // After full cycles, still only one each
        assertThat(m.residentMemoryEstimateBytes()).isGreaterThan(0L)
        assertThat(m.residentMemoryEstimateBytes()).isLessThan(400L * 1024 * 1024) // <400MB per NFR-02
    }

    @Test
    fun currentSttAndTts_queries() {
        val m = ModelManager()
        assertThat(m.currentStt()).isNull()
        assertThat(m.currentTts()).isNull()
        m.loadStt(Language.ODIA)
        assertThat(m.currentStt()).isEqualTo(Language.ODIA)
        assertThat(m.currentTts()).isNull()
        m.loadTts(Language.MALAYALAM)
        assertThat(m.currentTts()).isEqualTo(Language.MALAYALAM)
    }

    @Test
    fun residentMemoryEstimate_reflectsLoadedPair() {
        val m = ModelManager()
        assertThat(m.residentMemoryEstimateBytes()).isEqualTo(0L)
        m.loadStt(Language.HINDI)
        val afterStt = m.residentMemoryEstimateBytes()
        assertThat(afterStt).isGreaterThan(0L)
        m.loadTts(Language.ENGLISH)
        val afterBoth = m.residentMemoryEstimateBytes()
        assertThat(afterBoth).isGreaterThan(afterStt)
        assertThat(afterBoth).isLessThan(380L * 1024 * 1024)
    }
}
