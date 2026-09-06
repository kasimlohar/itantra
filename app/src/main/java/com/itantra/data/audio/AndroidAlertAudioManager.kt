package com.itantra.data.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class AndroidAlertAudioManager @Inject constructor(
  @ApplicationContext private val context: Context
) : AlertAudioManager {

  private val mainHandler = Handler(Looper.getMainLooper())
  private var focusRequest: Any? = null
  private var toneGen: ToneGenerator? = null

  private fun isHost(): Boolean = try {
    System.getProperty("java.vm.name") != "Dalvik"
  } catch (_: Exception) { true }

  override fun acquireAlarmFocus(): Boolean {
    if (isHost()) return true
    return try {
      val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
      am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
      val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
          .setAudioAttributes(
            AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_ALARM)
              .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
              .build()
          )
          .build()
        focusRequest = req
        am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
      } else {
        @Suppress("DEPRECATION")
        am.requestAudioFocus(
          null,
          AudioManager.STREAM_ALARM,
          AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
        ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
      }
      mainHandler.removeCallbacksAndMessages(null)
      mainHandler.postDelayed({ release() }, 2500)
      granted
    } catch (_: Exception) {
      true
    }
  }

  override fun vibrate(pattern: LongArray) {
    if (isHost()) return
    // 1. Play audible emergency siren tone (sounds loud on phones and tablets without vibrator)
    try {
      toneGen?.release()
      toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
      toneGen?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1500)
    } catch (_: Exception) {}

    // 2. Vibrate if physical vibrator hardware is supported (e.g. phones)
    try {
      val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator ?: (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
      } else {
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
      }
      if (vibrator != null && vibrator.hasVibrator()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          val effect = VibrationEffect.createWaveform(pattern, -1)
          vibrator.vibrate(effect)
        } else {
          @Suppress("DEPRECATION")
          vibrator.vibrate(pattern, -1)
        }
      }
    } catch (_: Exception) {}
  }

  override fun playBusyTone() {
    if (isHost()) return
    try {
      val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
      tg.startTone(ToneGenerator.TONE_SUP_BUSY, 250)
      mainHandler.postDelayed({
        try { tg.release() } catch (_: Throwable) {}
      }, 350)
    } catch (_: Throwable) {}
  }

  override fun release() {
    try {
      toneGen?.stopTone()
      toneGen?.release()
      toneGen = null
    } catch (_: Exception) {}
    try {
      val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest is AudioFocusRequest) {
        am.abandonAudioFocusRequest(focusRequest as AudioFocusRequest)
        focusRequest = null
      } else {
        @Suppress("DEPRECATION")
        am.abandonAudioFocus(null)
      }
    } catch (_: Exception) {}
  }
}
