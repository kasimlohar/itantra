package com.itantra.data.harness

import com.itantra.data.asr.MockAsrEngine
import com.itantra.data.tts.MockTtsEngine
import com.itantra.domain.model.Language
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class OfflineLoopHarnessWavTest {
    private fun wavPath(): String {
        val cands = listOf(
            "app/src/test/resources/hi_sample.wav",
            "src/test/resources/hi_sample.wav",
            "D:/SIH 2026/itantra/app/src/test/resources/hi_sample.wav"
        )
        return cands.firstOrNull { File(it).exists() } ?: "app/src/test/resources/hi_sample.wav"
    }
    private fun txtPath(): String = wavPath().replace(".wav", ".txt")

    @Test fun wavLoader_loadsRealWavAndReports16kMono() {
        val info = WavLoader.info(wavPath())
        assertThat(info.sampleRate).isEqualTo(16000)
        assertThat(info.channels).isEqualTo(1)
        assertThat(info.durationSec).isAtLeast(0.9)
        assertThat(info.durationSec).isAtMost(3.5)
        val pcm = WavLoader.loadPcm16Mono16k(wavPath())
        assertThat(pcm.isNotEmpty()).isTrue()
        assertThat(pcm.size).isAtLeast(16000)
    }

    @Test fun harness_processesRealWav_producesNonEmptyTranscriptionAndRtf() {
        val asr = MockAsrEngine(); asr.load(Language.HINDI)
        val tts = MockTtsEngine(); tts.loadVoice(Language.HINDI)
        val h = OfflineLoopHarness(asr, tts)
        val pcm = WavLoader.loadPcm16Mono16k(wavPath())
        val ref = try { File(txtPath()).readText(Charsets.UTF_8).trim() } catch (_: Exception) { null }
        val r = h.run(pcm, ref)
        assertThat(r.isSuccess).isTrue()
        val v = r.getOrThrow()
        assertThat(v.transcription.isNotBlank()).isTrue()
        assertThat(v.speechBuffer.pcm.isNotEmpty()).isTrue()
        assertThat(v.speechBuffer.sampleRate).isEqualTo(22050)
        assertThat(v.sttRtf).isGreaterThan(0.0)
        assertThat(v.ttsRtf).isGreaterThan(0.0)
        assertThat(v.audioDurationSec).isAtLeast(0.9)
        if (ref != null) {
            assertThat(v.cer).isNotNull()
            assertThat(v.cer!!).isAtLeast(0.0); assertThat(v.cer!!).isAtMost(1.0)
        }
    }

    @Test fun harness_withSherpaMock_alsoProcessesRealWav() {
        val asr = com.itantra.data.asr.SherpaAsrEngine(forceMock=true); asr.load(Language.HINDI)
        val tts = com.itantra.data.tts.SherpaTtsEngine(forceMock=true); tts.loadVoice(Language.HINDI)
        val h = OfflineLoopHarness(asr, tts)
        val pcm = WavLoader.loadPcm16Mono16k(wavPath())
        val r = h.run(pcm)
        assertThat(r.isSuccess).isTrue()
    }

    @Test fun wavLoader_throwsOnMissingFile() {
        val res = try { WavLoader.loadPcm16Mono16k("no_such.wav"); false } catch (_: Exception) { true }
        assertThat(res).isTrue()
    }
}
