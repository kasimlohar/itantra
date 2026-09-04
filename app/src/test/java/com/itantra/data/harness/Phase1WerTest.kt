package com.itantra.data.harness
import com.itantra.data.asr.MockAsrEngine
import com.itantra.data.tts.MockTtsEngine
import com.itantra.domain.model.Language
import com.google.common.truth.Truth.assertThat
import org.junit.Test
class Phase1WerTest {
  @Test fun harness_computesWer() {
    val asr=MockAsrEngine().apply{load(Language.HINDI)}; val tts=MockTtsEngine().apply{loadVoice(Language.HINDI)}
    val res=OfflineLoopHarness(asr,tts).run(ShortArray(16000){0}, "नमस्ते दुनिया").getOrThrow()
    assertThat(res.wer).isNotNull(); assertThat(res.wer!!).isAtLeast(0.0); assertThat(res.wer!!).isAtMost(1.0)
  }
  @Test fun csv_headerHasWer() {
    assertThat(HarnessCsv.header()).isEqualTo("lang,audioSec,sttRtf,ttsRtf,cer,wer,transcription")
    val asr=MockAsrEngine().apply{load(Language.HINDI)}; val tts=MockTtsEngine().apply{loadVoice(Language.HINDI)}
    val res=OfflineLoopHarness(asr,tts).run(ShortArray(16000){0}, "नमस्ते").getOrThrow()
    val row=HarnessCsv.row(Language.HINDI, res); assertThat(row.split(",").size).isAtLeast(7)
  }
  @Test fun phase1Report_has10LangsAndRendersTable() {
    val rep=Phase1Report()
    assertThat(rep.expectedLangs().size).isEqualTo(10)
    assertThat(rep.thresholdFor(Language.HINDI)).isWithin(0.01).of(0.082)
    val asr=MockAsrEngine().apply{load(Language.HINDI)}; val tts=MockTtsEngine().apply{loadVoice(Language.HINDI)}
    val res=OfflineLoopHarness(asr,tts).run(ShortArray(16000){0}, "नमस्ते").getOrThrow()
    rep.record(Language.HINDI, res)
    val md=rep.toMarkdown()
    assertThat(md).contains("hi"); assertThat(md).contains("WER"); assertThat(md.split("\n").size).isAtLeast(12)
  }
  @Test fun gate_tightThresholdStillPassesMock() {
    val asr=MockAsrEngine().apply{load(Language.HINDI)}; val tts=MockTtsEngine().apply{loadVoice(Language.HINDI)}
    val res=OfflineLoopHarness(asr,tts).run(ShortArray(16000){0}, "नमस्ते").getOrThrow()
    assertThat(HarnessGate.STT_THR).isWithin(0.001).of(0.28)
    assertThat(HarnessGate.TTS_THR).isWithin(0.001).of(0.18)
    assertThat(HarnessGate.check(res).pass).isTrue()
  }
}
