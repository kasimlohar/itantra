package com.itantra.data.asr

import com.itantra.domain.model.Language

/**
 * Pure mock for AsrEngine per PRD FR-03.
 * No real model, no sherpa-onnx — deterministic stub for TDD.
 */
class MockAsrEngine : AsrEngine {
    private val loaded = mutableSetOf<Language>()

    override fun load(language: Language): Result<Unit> {
        loaded.add(language)
        return Result.success(Unit)
    }

    override fun unload() {
        loaded.clear()
    }

    override fun isReady(): Boolean = loaded.isNotEmpty()

    override fun isLoaded(language: Language): Boolean = language in loaded

    override fun transcribe(pcm: ShortArray, language: Language): Result<String> {
        if (!isReady() || !isLoaded(language)) {
            return Result.failure(IllegalStateException("NotReady: $language not loaded"))
        }
        if (pcm.isEmpty()) {
            return Result.failure(IllegalArgumentException("EmptyAudio"))
        }
        // Deterministic mock: includes language and size, no real inference
        return Result.success("mock:${language.name}:${pcm.size}")
    }
}
