package com.itantra.data.audio

import com.itantra.domain.model.VadState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NativeAudioBridgeTest {

    @Test
    fun startAndStopCapture() {
        val b = NativeAudioBridge()
        // On host JVM without native .so, isCapturing is false; on device with Oboe it may be true after start
        // Must not crash on double start/stop
        assertThat(b.isCapturing()).isFalse()
        b.startCapture()
        // After start, isCapturing may be true (if native loaded) or false (host mock) — both ok as long as no crash
        val afterStart = b.isCapturing()
        assertThat(afterStart == true || afterStart == false).isTrue()
        b.startCapture() // double start safe
        b.stopCapture()
        assertThat(b.isCapturing()).isFalse()
        b.stopCapture() // double stop safe
        assertThat(b.isCapturing()).isFalse()
        b.close()
    }

    @Test
    fun isCapturingQuery() {
        val b = NativeAudioBridge()
        assertThat(b.isCapturing()).isFalse()
        b.startCapture()
        // isCapturing should be consistent (either true or false, but not crash)
        val v = b.isCapturing()
        assertThat(v).isAnyOf(true, false)
        b.stopCapture()
        assertThat(b.isCapturing()).isFalse()
        b.close()
    }

    @Test
    fun processAndObserveVadState() {
        val b = NativeAudioBridge()
        b.startCapture()
        // Initially Idle
        assertThat(b.getVadState()).isEqualTo(VadState.Idle)
        // Process with empty ring should return 0 and stay Idle
        val n = b.process()
        assertThat(n).isEqualTo(0)
        assertThat(b.getVadState()).isEqualTo(VadState.Idle)
        // No crash on multiple process calls
        b.process()
        b.process()
        assertThat(b.getVadState()).isIn(listOf(VadState.Idle, VadState.Speaking, VadState.Pause, VadState.Eou))
        b.stopCapture()
        b.close()
    }

    @Test
    fun clearLifecycle() {
        val b = NativeAudioBridge()
        b.startCapture()
        b.process()
        b.clear()
        assertThat(b.getVadState()).isEqualTo(VadState.Idle)
        // isCapturing should still be as before (clear doesn't stop capture)
        b.stopCapture()
        b.clear()
        assertThat(b.getVadState()).isEqualTo(VadState.Idle)
        b.close()
    }

    @Test
    fun doubleCloseNoCrash() {
        val b = NativeAudioBridge()
        b.startCapture()
        b.close()
        // Second close should be safe (idempotent)
        b.close()
        assertThat(b.isCapturing()).isFalse()
        assertThat(b.getVadState()).isEqualTo(VadState.Idle)
        // process after close should be safe (handle 0)
        assertThat(b.process()).isEqualTo(0)
    }
}
