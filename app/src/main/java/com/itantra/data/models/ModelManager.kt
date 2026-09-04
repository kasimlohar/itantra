package com.itantra.data.models

import com.itantra.domain.model.Language

/**
 * Pure ModelManager per PRD FR-02 / §4.1.1 ModelManager seam.
 * Simulates mmap LRU <180 ms, enforces at most one STT + one TTS resident.
 * No real file I/O, no android.* deps, deterministic for TDD.
 * Real file mmap deferred to later phase.
 */
class ModelManager {
    private var stt: Language? = null
    private var tts: Language? = null

    // Mock per-language footprints per PRD §3.1 (STT 120–188 MB, TTS 35–55 MB)
    // Use conservative 120 MB STT + 45 MB TTS for estimate, well under 380 MB NFR-02
    private fun sttBytes(@Suppress("UNUSED_PARAMETER") language: Language): Long = 120L * 1024 * 1024
    private fun ttsBytes(@Suppress("UNUSED_PARAMETER") language: Language): Long = 45L * 1024 * 1024

    fun loadStt(language: Language): Result<Unit> {
        // Simulate mmap load <180 ms (mock 5 ms)
        try {
            Thread.sleep(5)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        stt = language
        return Result.success(Unit)
    }

    fun loadTts(language: Language): Result<Unit> {
        try {
            Thread.sleep(5)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        tts = language
        return Result.success(Unit)
    }

    fun unloadStt() {
        stt = null
    }

    fun unloadTts() {
        tts = null
    }

    fun clear() {
        stt = null
        tts = null
    }

    fun currentStt(): Language? = stt

    fun currentTts(): Language? = tts

    fun isSttLoaded(language: Language): Boolean = stt == language

    fun isTtsLoaded(language: Language): Boolean = tts == language

    fun residentMemoryEstimateBytes(): Long {
        var total = 0L
        stt?.let { total += sttBytes(it) }
        tts?.let { total += ttsBytes(it) }
        return total
    }
}
