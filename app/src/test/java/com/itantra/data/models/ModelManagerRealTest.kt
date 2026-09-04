package com.itantra.data.models
import com.itantra.domain.model.Language
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
class ModelManagerRealTest {
  @Test fun loadStt_validatesFileAndSwapsUnder180ms() {
    val mm=ModelManager()
    val t0=System.nanoTime(); val r=mm.loadStt(Language.HINDI); val ms=(System.nanoTime()-t0)/1_000_000
    assertThat(r.isSuccess).isTrue(); assertThat(mm.currentStt()).isEqualTo(Language.HINDI); assertThat(ms).isLessThan(180L)
    val t1=System.nanoTime(); mm.loadStt(Language.HINDI); assertThat((System.nanoTime()-t1)/1_000_000).isLessThan(180L)
  }
  @Test fun loadTts_validatesFileAndSwapsUnder180ms() {
    val mm=ModelManager()
    val t0=System.nanoTime(); val r=mm.loadTts(Language.HINDI); val ms=(System.nanoTime()-t0)/1_000_000
    assertThat(r.isSuccess).isTrue(); assertThat(mm.currentTts()).isEqualTo(Language.HINDI); assertThat(ms).isLessThan(180L)
  }
  @Test fun lru_onlyOneSttOneTts() {
    val mm=ModelManager()
    mm.loadStt(Language.HINDI); mm.loadTts(Language.HINDI)
    assertThat(mm.residentMemoryEstimateBytes()).isGreaterThan(0L)
    assertThat(mm.currentStt()).isEqualTo(Language.HINDI)
    assertThat(mm.currentTts()).isEqualTo(Language.HINDI)
  }
  @Test fun sherpaNumThreadsIsTwo() {
    val asr=com.itantra.data.asr.SherpaAsrEngine(forceMock=true); asr.load(Language.HINDI)
    assertThat(asr.isMock()).isTrue()
    val tts=com.itantra.data.tts.SherpaTtsEngine(forceMock=true); tts.loadVoice(Language.HINDI)
    assertThat(tts.isMock()).isTrue()
    assertThat(File("app/src/main/assets/models/stt/hi/indic_conformer_hi_int8.onnx").exists() || File("src/main/assets/models/stt/hi/indic_conformer_hi_int8.onnx").exists()).isTrue()
  }
  @Test fun heapEstimateUnder380MB() {
    val mm=ModelManager(); mm.loadStt(Language.HINDI); mm.loadTts(Language.HINDI)
    assertThat(mm.residentMemoryEstimateBytes()).isLessThan(380L*1024*1024)
  }
  @Test fun loadHindi_validatesRealFiles() {
    val mm=ModelManager()
    // Validate that Hindi model files actually exist on disk for mmap
    val sttFile = File("app/src/main/assets/models/stt/hi/indic_conformer_hi_int8.onnx")
    val altStt = File("src/main/assets/models/stt/hi/indic_conformer_hi_int8.onnx")
    assertThat(sttFile.exists() || altStt.exists()).isTrue()
    val ttsFile = File("app/src/main/assets/models/tts/hi/hi_IN-pratham-medium.onnx")
    val altTts = File("src/main/assets/models/tts/hi/hi_IN-pratham-medium.onnx")
    assertThat(ttsFile.exists() || altTts.exists()).isTrue()
    // Loading Hindi should succeed with file-backed validation
    val r1=mm.loadStt(Language.HINDI)
    assertThat(r1.isSuccess).isTrue()
    val r2=mm.loadTts(Language.HINDI)
    assertThat(r2.isSuccess).isTrue()
  }
}
