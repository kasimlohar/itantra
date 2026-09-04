package com.itantra.data.harness

import com.itantra.data.asr.MockAsrEngine
import com.itantra.data.asr.SherpaAsrEngine
import com.itantra.data.tts.MockTtsEngine
import com.itantra.data.tts.SherpaTtsEngine
import com.itantra.domain.model.Language
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OfflineLoopHarnessTest {
    private fun pcm1Sec(): ShortArray = ShortArray(16000) { (8000 * kotlin.math.sin(2*Math.PI*200*it/16000)).toInt().toShort() }
    private fun pcm2Sec(): ShortArray = ShortArray(32000) { (5000 * kotlin.math.sin(2*Math.PI*150*it/16000)).toInt().toShort() }

    @Test fun harness_canAcceptPcm_andProducesNonEmptyTranscription() {
        val asr = MockAsrEngine(); asr.load(Language.HINDI)
        val tts = MockTtsEngine(); tts.loadVoice(Language.HINDI)
        val h = OfflineLoopHarness(asr, tts)
        val r = h.run(pcm1Sec())
        assertThat(r.isSuccess).isTrue()
        val v = r.getOrThrow()
        assertThat(v.transcription.isNotBlank()).isTrue()
        assertThat(v.transcriptionLength).isGreaterThan(0)
    }

    @Test fun harness_producesNonEmptySpeechBufferAndPositiveRtf() {
        val asr = MockAsrEngine(); asr.load(Language.HINDI)
        val tts = MockTtsEngine(); tts.loadVoice(Language.HINDI)
        val h = OfflineLoopHarness(asr, tts)
        val r = h.run(pcm1Sec())
        val v = r.getOrThrow()
        assertThat(v.speechBuffer.pcm.isNotEmpty()).isTrue()
        assertThat(v.speechBuffer.sampleRate).isEqualTo(22050)
        assertThat(v.sttRtf).isGreaterThan(0.0)
        assertThat(v.ttsRtf).isGreaterThan(0.0)
        assertThat(v.sttTimeMs).isAtLeast(0L)
        assertThat(v.ttsTimeMs).isAtLeast(0L)
        assertThat(v.audioDurationSec).isWithin(0.001).of(1.0)
    }

    @Test fun harness_reportsCerWhenReferenceProvided() {
        val asr = MockAsrEngine(); asr.load(Language.HINDI)
        val tts = MockTtsEngine(); tts.loadVoice(Language.HINDI)
        val h = OfflineLoopHarness(asr, tts)
        val pcm = pcm1Sec()
        val r = h.run(pcm, reference = "नमस्ते")
        val v = r.getOrThrow()
        assertThat(v.cer).isNotNull()
        assertThat(v.cer!!).isAtLeast(0.0)
        assertThat(v.cer!!).isAtMost(1.0)
    }

    @Test fun harness_failsWhenAsrNotReady() {
        val asr = MockAsrEngine() // not loaded
        val tts = MockTtsEngine(); tts.loadVoice(Language.HINDI)
        val h = OfflineLoopHarness(asr, tts)
        val r = h.run(pcm1Sec())
        assertThat(r.isFailure).isTrue()
    }

    @Test fun harness_failsWhenTtsNotReady() {
        val asr = MockAsrEngine(); asr.load(Language.HINDI)
        val tts = MockTtsEngine() // not loaded
        val h = OfflineLoopHarness(asr, tts)
        val r = h.run(pcm1Sec())
        assertThat(r.isFailure).isTrue()
    }

    @Test fun harness_worksWithSherpaMockPath() {
        val asr = SherpaAsrEngine(forceMock=true); asr.load(Language.HINDI)
        val tts = SherpaTtsEngine(forceMock=true); tts.loadVoice(Language.HINDI)
        val h = OfflineLoopHarness(asr, tts)
        val r = h.run(pcm2Sec())
        assertThat(r.isSuccess).isTrue()
        assertThat(r.getOrThrow().audioDurationSec).isWithin(0.001).of(2.0)
    }

    @Test fun harness_computesRtfReasonably() {
        val asr = MockAsrEngine(); asr.load(Language.HINDI)
        val tts = MockTtsEngine(); tts.loadVoice(Language.HINDI)
        val h = OfflineLoopHarness(asr, tts)
        val r = h.run(pcm2Sec())
        val v = r.getOrThrow()
        assertThat(v.sttRtf).isLessThan(5.0)
        assertThat(v.ttsRtf).isLessThan(5.0)
    }
}
