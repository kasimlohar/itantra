package com.itantra.data.tts

import com.itantra.domain.model.Language

/**
 * Pure TTS seam per PRD FR-04 / §4.1.1 tts-engine.
 * Mock implementation only for this slice; real Piper VITS deferred.
 */
interface TtsEngine {
    fun loadVoice(language: Language): Result<Unit>
    fun unload()
    fun isReady(): Boolean
    fun isLoaded(language: Language): Boolean
    fun synthesize(text: String, language: Language): Result<SpeechBuffer>
}
