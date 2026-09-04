package com.itantra.domain.model

/**
 * VadFsm states per PRD US-03 / Architecture §3 FSM.
 * Idle — monitoring pre-roll, waiting for 3× p≥0.6 onset.
 * Speaking — active speech, p≥0.35 sustain.
 * Pause — silence counter running, may return to Speaking or trigger Eou.
 * Eou — End-of-Utterance emitted after hangover (450/550 ms), awaiting reset.
 */
enum class VadState {
    Idle,
    Speaking,
    Pause,
    Eou
}
