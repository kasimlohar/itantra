package com.itantra.data.audio
interface AlertAudioManager {
  fun acquireAlarmFocus(): Boolean
  fun vibrate(pattern: LongArray)
  fun playBusyTone() {}
  fun release()
}
