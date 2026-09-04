package com.itantra.data.audio

import com.itantra.domain.model.VadState

/**
 * Thin Kotlin control surface over C++ AudioCapture + VadPipeline.
 * Owns native bridge via handle (0 = not created). No UI, no STT/TTS.
 */
class NativeAudioBridge : AutoCloseable {
    private var handle: Long = 0L

    init {
        try {
            System.loadLibrary("itantra-native")
        } catch (_: UnsatisfiedLinkError) {
            // Host unit test without .so (e.g., JVM testDebugUnitTest) — handle stays 0, methods fallback to no-op
        }
        handle = try {
            createBridge()
        } catch (_: UnsatisfiedLinkError) {
            0L
        } catch (_: Exception) {
            0L
        }
    }

    fun startCapture(): Boolean {
        if (handle == 0L) return false
        return try { startCapture(handle) } catch (_: UnsatisfiedLinkError) { false }
    }

    fun stopCapture(): Boolean {
        if (handle == 0L) return false
        return try { stopCapture(handle) } catch (_: UnsatisfiedLinkError) { false }
    }

    fun isCapturing(): Boolean {
        if (handle == 0L) return false
        return try { isCapturing(handle) } catch (_: UnsatisfiedLinkError) { false }
    }

    /**
     * Pump pipeline: pop 480 from ring → Silero → VadFsm. Returns frames processed.
     * Explicit call from Kotlin thread (not audio thread) to keep audio callback lean.
     */
    fun process(): Int {
        if (handle == 0L) return 0
        return try { process(handle) } catch (_: UnsatisfiedLinkError) { 0 }
    }

    fun getVadState(): VadState {
        if (handle == 0L) return VadState.Idle
        return try {
            val ord = getVadState(handle)
            VadState.entries.getOrElse(ord) { VadState.Idle }
        } catch (_: UnsatisfiedLinkError) { VadState.Idle }
    }

    fun clear() {
        if (handle == 0L) return
        try { clear(handle) } catch (_: UnsatisfiedLinkError) {}
    }

    override fun close() {
        if (handle != 0L) {
            try { destroyBridge(handle) } catch (_: UnsatisfiedLinkError) {}
            handle = 0L
        }
    }

    @Suppress("unused")
    protected fun finalize() {
        close()
    }

    private external fun createBridge(): Long
    private external fun destroyBridge(handle: Long)
    private external fun startCapture(handle: Long): Boolean
    private external fun stopCapture(handle: Long): Boolean
    private external fun isCapturing(handle: Long): Boolean
    private external fun process(handle: Long): Int
    private external fun getVadState(handle: Long): Int
    private external fun clear(handle: Long)
}
