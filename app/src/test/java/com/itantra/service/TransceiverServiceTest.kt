package com.itantra.service
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
class TransceiverServiceTest {
  private fun readSrc(path: String): String {
    val cands = listOf(
      path,
      "app/$path",
      "src/main/java/com/itantra/service/TransceiverService.kt",
      "D:/SIH 2026/itantra/app/src/main/java/com/itantra/service/TransceiverService.kt",
      "D:/SIH 2026/itantra/$path"
    )
    for (p in cands) {
      val f = File(p)
      if (f.exists()) return f.readText()
    }
    return File(path).readText()
  }
  private fun readMan(): String {
    val cands = listOf(
      "app/src/main/AndroidManifest.xml",
      "src/main/AndroidManifest.xml",
      "D:/SIH 2026/itantra/app/src/main/AndroidManifest.xml"
    )
    for (p in cands) {
      val f = File(p)
      if (f.exists()) return f.readText()
    }
    return File(cands[0]).readText()
  }
  private fun readNav(): String {
    val cands = listOf(
      "app/src/main/java/com/itantra/presentation/navigation/NavGraph.kt",
      "src/main/java/com/itantra/presentation/navigation/NavGraph.kt",
      "D:/SIH 2026/itantra/app/src/main/java/com/itantra/presentation/navigation/NavGraph.kt"
    )
    for (p in cands) {
      val f = File(p)
      if (f.exists()) return f.readText()
    }
    return File(cands[0]).readText()
  }
  @Test fun serviceExistsAndHasNotificationText() {
    val src=readSrc("app/src/main/java/com/itantra/service/TransceiverService.kt")
    assertThat(src).contains("startForeground")
    assertThat(src).contains("PARTIAL_WAKE_LOCK")
    assertThat(src).contains("iTantra Walkie-Talkie Active")
  }
  @Test fun manifestHasServiceEntry() {
    val man=readMan()
    assertThat(man).contains("TransceiverService")
    assertThat(man).contains("foregroundServiceType")
  }
  @Test fun navGraphHasThreeDestinations() {
    val nav=readNav()
    assertThat(nav).contains("transceiver")
    assertThat(nav).contains("connection")
    assertThat(nav).contains("languagePicker")
  }
}
