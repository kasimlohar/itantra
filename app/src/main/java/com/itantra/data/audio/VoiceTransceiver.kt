package com.itantra.data.audio

interface VoiceTransceiver {
  fun startListening(
    langCode: String = "hi-IN",
    onPartial: (String) -> Unit,
    onRms: (Float) -> Unit,
    onResult: (String) -> Unit
  )
  fun stopListening()
  fun speak(text: String, langCode: String = "hi-IN")
  fun isAvailable(): Boolean
  fun release()
}
