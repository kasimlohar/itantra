package com.itantra.data.asr

import com.itantra.domain.model.Language

/**
 * Pure ASR seam per PRD FR-03 / §4.1.1 asr-engine.
 * Mock implementation only for this slice; real IndicConformer via sherpa-onnx deferred.
 */
interface AsrEngine {
    fun load(language: Language): Result<Unit>
    fun unload()
    fun isReady(): Boolean
    fun isLoaded(language: Language): Boolean
    fun transcribe(pcm: ShortArray, language: Language): Result<String>
}
