package com.itantra.data.harness
import com.itantra.data.asr.MockAsrEngine
import com.itantra.data.tts.MockTtsEngine
import com.itantra.domain.model.Language
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
class HarnessCsvGateTest {
  private fun pcmSec(s:Double=1.0):ShortArray { val n=(16000*s).toInt(); return ShortArray(n){ (5000*Math.sin(2*Math.PI*200*it/16000)).toInt().toShort()} }
  @Test fun csv_headerAndRow() {
    val h="lang,audioSec,sttRtf,ttsRtf,cer,wer,transcription"
    assertThat(HarnessCsv.header()).isEqualTo(h)
    val asr=MockAsrEngine().apply{load(Language.HINDI)}; val tts=MockTtsEngine().apply{loadVoice(Language.HINDI)}
    val res=OfflineLoopHarness(asr,tts).run(pcmSec(), "नमस्ते").getOrThrow()
    val row=HarnessCsv.row(Language.HINDI, res)
    assertThat(row).contains("hi"); assertThat(row).contains(res.transcription)
    assertThat(row.split(",").size).isAtLeast(7)
  }
  @Test fun csv_writeCreatesFile() {
    val tmp=File.createTempFile("harness",".csv"); tmp.deleteOnExit()
    tmp.delete()
    val asr=MockAsrEngine().apply{load(Language.HINDI)}; val tts=MockTtsEngine().apply{loadVoice(Language.HINDI)}
    val res=OfflineLoopHarness(asr,tts).run(pcmSec(), "नमस्ते").getOrThrow()
    HarnessCsv.write(tmp, Language.HINDI, res)
    assertThat(tmp.exists()).isTrue(); val lines=tmp.readLines(); assertThat(lines[0]).isEqualTo(HarnessCsv.header()); assertThat(lines.size).isEqualTo(2)
    HarnessCsv.write(tmp, Language.HINDI, res); assertThat(tmp.readLines().size).isEqualTo(3)
  }
  @Test fun gate_passesForFastMockRtf() {
    val asr=MockAsrEngine().apply{load(Language.HINDI)}; val tts=MockTtsEngine().apply{loadVoice(Language.HINDI)}
    val res=OfflineLoopHarness(asr,tts).run(pcmSec(), "नमस्ते").getOrThrow()
    val g=HarnessGate.check(res); assertThat(g.pass).isTrue(); assertThat(g.sttPass).isTrue(); assertThat(g.ttsPass).isTrue()
  }
  @Test fun gate_failsForHighRtf() {
    val slow=HarnessResult("hi",2, com.itantra.data.tts.SpeechBuffer(ShortArray(22050),22050), 900,900, 0.90,0.80, 1.0, 0.1)
    val g=HarnessGate.check(slow); assertThat(g.pass).isFalse(); assertThat(g.sttPass).isFalse(); assertThat(g.ttsPass).isFalse()
  }
  private fun wavPath():String {
    val cands=listOf("app/src/test/resources/hi_sample.wav","src/test/resources/hi_sample.wav","D:/SIH 2026/itantra/app/src/test/resources/hi_sample.wav")
    return cands.firstOrNull{File(it).exists()} ?: "app/src/test/resources/hi_sample.wav"
  }
  private fun txtPath():String {
    val cands=listOf("app/src/test/resources/hi_sample.txt","src/test/resources/hi_sample.txt","D:/SIH 2026/itantra/app/src/test/resources/hi_sample.txt")
    return cands.firstOrNull{File(it).exists()} ?: "app/src/test/resources/hi_sample.txt"
  }
  @Test fun wavSample_passesGate() {
    val asr=MockAsrEngine().apply{load(Language.HINDI)}; val tts=MockTtsEngine().apply{loadVoice(Language.HINDI)}
    val pcm=WavLoader.loadPcm16Mono16k(wavPath())
    val ref=File(txtPath()).readText(Charsets.UTF_8).trim()
    val res=OfflineLoopHarness(asr,tts).run(pcm, ref).getOrThrow()
    val g=HarnessGate.check(res); assertThat(g.pass).isTrue()
    val row=HarnessCsv.row(Language.HINDI, res); assertThat(row).contains("hi")
  }
}
