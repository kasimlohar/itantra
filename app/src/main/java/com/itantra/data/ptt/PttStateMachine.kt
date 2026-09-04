package com.itantra.data.ptt

import com.itantra.domain.model.Frame
import com.itantra.domain.model.Language
import com.itantra.domain.model.PttEvent
import com.itantra.domain.model.PttState
import com.itantra.domain.model.TransmitMode

/**
 * Pure PTT floor controller per PRD US-01 / FR-08 and Architecture PTT §172-189.
 * Generates/consumes control Frames (FLAG_PTT, payload_len=0) via [Frame] model.
 * No Android deps, no KeyEvent — hardware binding deferred.
 *
 * Half-duplex: only one side may hold floor at a time. DUPLEX mode bypasses all floor logic.
 * Debounce 80 ms via injectable [clock] for testability.
 */
class PttStateMachine(
    private var mode: TransmitMode = TransmitMode.HALF_DUPLEX,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val debounceMs: Long = 80L
) {
    private var state: PttState = PttState.Idle
    private var lastEventMs: Long = -1000L
    private var seqCounter: Int = 0

    private fun nextSeq(): Int {
        val s = seqCounter and 0xFFFF
        seqCounter = (seqCounter + 1) and 0xFFFF
        return s
    }

    private fun controlFrame(ptt: Boolean): Frame {
        return Frame(
            mode = mode,
            isAlert = false,
            isStream = false,
            pttPressed = ptt,
            srcLang = Language.HINDI,
            dstLang = Language.ENGLISH,
            seqId = nextSeq(),
            payloadText = ""
        )
    }

    fun onLocalPress(): PttEvent {
        if (mode == TransmitMode.DUPLEX) return PttEvent.Ignored
        val now = clock()
        if (now - lastEventMs < debounceMs) return PttEvent.Debounced
        lastEventMs = now
        return when (state) {
            PttState.Idle -> {
                state = PttState.Holding
                val f = controlFrame(true)
                PttEvent.SendControlFrame(f)
            }
            PttState.Busy -> PttEvent.ChannelBusy
            PttState.Holding -> PttEvent.FloorDenied // already holding, second press without release
        }
    }

    fun onLocalRelease(): PttEvent {
        if (mode == TransmitMode.DUPLEX) return PttEvent.Ignored
        val now = clock()
        if (now - lastEventMs < debounceMs) return PttEvent.Debounced
        lastEventMs = now
        return when (state) {
            PttState.Holding -> {
                state = PttState.Idle
                val f = controlFrame(false)
                PttEvent.FloorReleased(f)
            }
            else -> PttEvent.Ignored
        }
    }

    fun onRemoteFrame(frame: Frame): PttEvent {
        if (mode == TransmitMode.DUPLEX) return PttEvent.Ignored
        // Control frames must have empty payload; text frames are ignored for PTT
        if (frame.payloadText.isNotEmpty()) return PttEvent.Ignored
        return if (frame.pttPressed) {
            // Remote requests/holds floor
            state = PttState.Busy
            PttEvent.RemoteGranted
        } else {
            // Remote releases
            if (state == PttState.Busy) {
                state = PttState.Idle
            }
            // If we were Idle or Holding, remote release does not change our local holding? Keep as Idle if was Busy else unchanged
            // For simplicity, if Holding and remote releases, stay Holding? But per test, remote release from Idle stays Idle.
            // We'll just set to Idle if was Busy, else leave state.
            PttEvent.RemoteReleased
        }
    }

    fun currentState(): PttState = state

    fun isFloorHeldLocally(): Boolean = state == PttState.Holding

    fun clear() {
        state = PttState.Idle
        lastEventMs = -1000L
        seqCounter = 0
    }

    // For testing mode switch (not required but useful)
    fun setMode(m: TransmitMode) {
        mode = m
        if (m == TransmitMode.DUPLEX) {
            // bypass: reset to Idle per spec that DUPLEX ignores PTT
            state = PttState.Idle
        }
    }
}
