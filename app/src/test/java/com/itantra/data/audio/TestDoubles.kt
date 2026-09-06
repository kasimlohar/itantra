package com.itantra.data.audio

class FakeAlertAudioManager : AlertAudioManager {
    override fun acquireAlarmFocus(): Boolean = true
    override fun vibrate(pattern: LongArray) {}
    override fun release() {}
}

class FakeVoiceTransceiver : VoiceTransceiver {
    var isListening: Boolean = false
        private set
    var lastSpokenText: String? = null
        private set

    override fun startListening(
        langCode: String,
        onPartial: (String) -> Unit,
        onRms: (Float) -> Unit,
        onResult: (String) -> Unit
    ) {
        isListening = true
    }

    override fun stopListening() {
        isListening = false
    }

    override fun speak(text: String, langCode: String) {
        lastSpokenText = text
    }

    override fun stopSpeaking() {}

    override fun isAvailable(): Boolean = true

    override fun release() {
        isListening = false
    }
}
