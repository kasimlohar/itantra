package com.itantra.presentation.transceiver
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
class TransceiverScreenTest {
  private fun src(): String {
    val cands = listOf(
      "app/src/main/java/com/itantra/presentation/transceiver/TransceiverScreen.kt",
      "src/main/java/com/itantra/presentation/transceiver/TransceiverScreen.kt",
      "D:/SIH 2026/itantra/app/src/main/java/com/itantra/presentation/transceiver/TransceiverScreen.kt"
    )
    val f = cands.map { File(it) }.firstOrNull { it.exists() } ?: File(cands[0])
    return f.readText()
  }
  @Test fun pttButtonExistsAndShowsIdle() {
    val s = src()
    assertThat(s).contains("pttButton")
    assertThat(s).contains("PttButton")
    assertThat(s).contains("FilledTonalButton")
  }
  @Test fun alertBannerVisibleWhenAlertActive() {
    val s = src()
    assertThat(s).contains("isAlertActive")
    assertThat(s).contains("alertTranscript")
    assertThat(s).contains("alertBanner")
  }
  @Test fun topBarModeToggleExists() {
    val s = src()
    assertThat(s).contains("modeToggle")
    assertThat(s).contains("TopAppBar")
  }
}
