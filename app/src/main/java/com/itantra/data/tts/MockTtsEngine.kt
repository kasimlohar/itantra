package com.itantra.data.tts

import com.itantra.domain.model.Language

/**
 * Pure mock for TtsEngine per PRD FR-04.
 * No real Piper model — deterministic stub for TDD.
 * Mock PCM: text.length*100 samples, 22.05 kHz, first-buffer latency mocked as instant.
 */
class MockTtsEngine : TtsEngine {
    private val loaded = mutableSetOf<Language>()

    override fun loadVoice(language: Language): Result<Unit> {
        loaded.add(language)
        return Result.success(Unit)
    }

    override fun unload() {
        loaded.clear()
    }

    override fun isReady(): Boolean = loaded.isNotEmpty()

    override fun isLoaded(language: Language): Boolean = language in loaded

    override fun synthesize(text: String, language: Language): Result<SpeechBuffer> {
        if (!isReady() || !isLoaded(language)) {
            return Result.failure(IllegalStateException("MissingVoice: $language not loaded"))
        }
        if (text.isBlank()) {
            return Result.failure(IllegalArgumentException("EmptyText"))
        }
        // Mock synthesis: deterministic PCM, size proportional to text length, fast (<1ms)
        val pcm = ShortArray(text.length * 100) { (it % 100).toShort() }
        return Result.success(SpeechBuffer(pcm, 22050))
    }
}
