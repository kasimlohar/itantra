package com.itantra.data.audio

import android.content.Context
import android.media.AudioManager
import android.os.Vibrator
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class AndroidAlertAudioManager @Inject constructor(
  @ApplicationContext private val context: Context
) : AlertAudioManager {

  private fun isHost(): Boolean = try {
    System.getProperty("java.vm.name") != "Dalvik"
  } catch (_: Exception) { true }

  override fun acquireAlarmFocus(): Boolean {
    if (isHost()) return true
    return try {
      val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
      am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
      val result = am.requestAudioFocus(
        null,
        AudioManager.STREAM_ALARM,
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
      )
      result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    } catch (_: Exception) {
      true
    }
  }

  override fun vibrate(pattern: LongArray) {
    if (isHost()) return
    try {
      val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
      vibrator.vibrate(pattern, -1)
    } catch (_: Exception) {}
  }

  override fun release() {
    try {
      val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
      am.abandonAudioFocus(null)
    } catch (_: Exception) {}
  }
}
