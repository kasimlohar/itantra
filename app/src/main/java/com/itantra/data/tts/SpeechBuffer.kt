package com.itantra.data.tts

/**
 * Mock PCM buffer for TTS per PRD FR-04.
 * 22.05 kHz mono PCM16, pure mock — real Piper VITS deferred.
 */
data class SpeechBuffer(
    val pcm: ShortArray,
    val sampleRate: Int = 22050
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SpeechBuffer
        if (!pcm.contentEquals(other.pcm)) return false
        if (sampleRate != other.sampleRate) return false
        return true
    }
    override fun hashCode(): Int {
        var result = pcm.contentHashCode()
        result = 31 * result + sampleRate
        return result
    }
}
