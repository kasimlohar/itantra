package com.itantra.data.vad

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SileroVadTest {

    private fun modelPath(): String {
        // Check multiple candidates for CI vs local
        val candidates = listOf(
            "app/src/main/assets/models/vad/silero_vad.onnx",
            "../app/src/main/assets/models/vad/silero_vad.onnx",
            "D:/SIH 2026/itantra/app/src/main/assets/models/vad/silero_vad.onnx"
        )
        return candidates.firstOrNull { java.io.File(it).exists() } ?: candidates[0]
    }

    @Test
    fun loadsModelSuccessfully() {
        val vad = SileroVad()
        assertThat(vad.load(modelPath())).isTrue()
        assertThat(vad.isLoaded()).isTrue()
    }

    @Test
    fun predictReturnsProbabilityIn0_1() {
        val vad = SileroVad()
        vad.load(modelPath())
        val pcm = ShortArray(512) { 0 }
        val p = vad.predict(pcm)
        assertThat(p).isAtLeast(0.0f)
        assertThat(p).isAtMost(1.0f)
    }

    @Test
    fun silenceVsSpeechLike() {
        val vad = SileroVad()
        vad.load(modelPath())
        val silence = ShortArray(512) { 0 }
        val pSilence = vad.predict(silence)
        assertThat(pSilence).isLessThan(0.35f)
        val speech = ShortArray(512) { (10000 * sin(2 * PI * 440 * it / 16000)).toInt().toShort() }
        val pSpeech = vad.predict(speech)
        assertThat(pSpeech).isAtLeast(0.0f)
        assertThat(pSpeech).isAtMost(1.0f)
        // distinct
        assertThat(pSilence).isNotEqualTo(pSpeech)
    }

    @Test
    fun unloadReset() {
        val vad = SileroVad()
        vad.load(modelPath())
        assertThat(vad.isLoaded()).isTrue()
        vad.reset()
        assertThat(vad.isLoaded()).isTrue()
        vad.unload()
        assertThat(vad.isLoaded()).isFalse()
    }

    @Test
    fun integrationWithVadFsm() {
        val vad = SileroVad()
        vad.load(modelPath())
        val fsm = VadFsm()
        val silence = ShortArray(512) { 0 }
        val pSil = vad.predict(silence)
        val s = fsm.onFrame(pSil, silence)
        assertThat(s).isEqualTo(com.itantra.domain.model.VadState.Idle)
        val speech = ShortArray(512) { (10000 * sin(2 * PI * 440 * it / 16000)).toInt().toShort() }
        val p = vad.predict(speech)
        // feed to fsm, at least not crash
        val s2 = fsm.onFrame(p, speech)
        assertThat(s2).isIn(listOf(com.itantra.domain.model.VadState.Idle, com.itantra.domain.model.VadState.Speaking, com.itantra.domain.model.VadState.Pause, com.itantra.domain.model.VadState.Eou))
    }

    @Test
    fun handles480PaddedTo512() {
        val vad = SileroVad()
        vad.load(modelPath())
        val pcm480 = ShortArray(480) { 0 }
        val p = vad.predict(pcm480)
        assertThat(p).isAtLeast(0.0f)
        assertThat(p).isAtMost(1.0f)
    }
}
