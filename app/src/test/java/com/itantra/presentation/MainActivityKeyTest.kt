package com.itantra.presentation
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
class MainActivityKeyTest {
  private fun src(): String {
    val cands = listOf(
      "app/src/main/java/com/itantra/MainActivity.kt",
      "src/main/java/com/itantra/MainActivity.kt",
      "D:/SIH 2026/itantra/app/src/main/java/com/itantra/MainActivity.kt"
    )
    val f = cands.map { File(it) }.firstOrNull { it.exists() } ?: File(cands[0])
    return f.readText()
  }
  @Test fun mainActivityHandlesVolumeDown() {
    val s = src()
    assertThat(s).contains("KEYCODE_VOLUME_DOWN")
    assertThat(s).contains("FloorRequest")
    assertThat(s).contains("80")
  }
  @Test fun mainActivityHandlesVolumeUpRelease() {
    val s = src()
    assertThat(s).contains("FloorRelease")
    assertThat(s).contains("onKeyUp")
  }
}
