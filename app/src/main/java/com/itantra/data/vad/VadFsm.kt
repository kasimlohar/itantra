package com.itantra.data.vad

import com.itantra.domain.model.VadState

/**
 * Pure FSM for PRD US-03 / FR-05.
 * Thresholds: onset p≥0.6 for 3 consecutive 30 ms frames, sustain p≥0.35,
 * hangover configurable (default 450 ms PTT, 550 ms phone), pre-roll 200 ms.
 *
 * Interface is the test surface for later C++ Oboe path.
 * Silero inference is stubbed: caller supplies prob per 30 ms frame.
 */
class VadFsm(
    hangoverMs: Int = 450,
    private val frameMs: Int = 30,
    private val preRollMs: Int = 200,
    private val sampleRate: Int = 16000
) {
    var state: VadState = VadState.Idle
        private set

    private var hangoverMsInternal: Int = hangoverMs
    private var hangoverFrames: Int = hangoverMsInternal / frameMs
    private val preRollFrames: Int = (preRollMs + frameMs - 1) / frameMs // ceil 200/30=7

    private var onsetCounter: Int = 0
    private var pauseCounter: Int = 0

    private val preRoll: ArrayDeque<ShortArray> = ArrayDeque()
    private val utterance: MutableList<Short> = mutableListOf()

    // buffer for onset frames while counting
    private val onsetBuffer: MutableList<ShortArray> = mutableListOf()

    fun setHangoverMs(ms: Int) {
        hangoverMsInternal = ms
        hangoverFrames = hangoverMsInternal / frameMs
    }

    fun getUtterance(): List<Short> = utterance.toList()

    fun reset() {
        state = VadState.Idle
        onsetCounter = 0
        pauseCounter = 0
        preRoll.clear()
        utterance.clear()
        onsetBuffer.clear()
    }

    /**
     * @param prob speech probability [0..1] from Silero (stubbed)
     * @param pcm 16 kHz mono PCM for this 30 ms frame (480 samples). Nullable for prob-only tests.
     *             When null, a zero-filled frame is synthesized.
     * @return new state after processing this frame
     */
    fun onFrame(prob: Float, pcm: ShortArray? = null): VadState {
        val frame: ShortArray = pcm ?: ShortArray(frameSamples) { 0 }
        // Ensure correct length (pad/truncate) – tests use 480
        val normalized = if (frame.size == frameSamples) frame else ShortArray(frameSamples) { i -> if (i < frame.size) frame[i] else 0 }

        when (state) {
            VadState.Idle -> handleIdle(prob, normalized)
            VadState.Speaking -> handleSpeaking(prob, normalized)
            VadState.Pause -> handlePause(prob, normalized)
            VadState.Eou -> {
                // stay in Eou until reset; ignore further frames
            }
        }
        return state
    }

    private val frameSamples: Int = sampleRate * frameMs / 1000 // 480

    private fun handleIdle(prob: Float, pcm: ShortArray) {
        if (prob >= 0.6f) {
            onsetCounter++
            onsetBuffer.add(pcm)
            if (onsetCounter >= 3) {
                // Flush preRoll + onsetBuffer into utterance
                for (fr in preRoll) {
                    for (s in fr) utterance.add(s)
                }
                for (fr in onsetBuffer) {
                    for (s in fr) utterance.add(s)
                }
                preRoll.clear()
                onsetBuffer.clear()
                state = VadState.Speaking
                pauseCounter = 0
            }
            // else stay Idle, keep buffering onset but do not yet push to preRoll
        } else {
            // Not speech – push to preRoll, reset onset
            onsetCounter = 0
            onsetBuffer.clear()
            preRoll.addLast(pcm)
            if (preRoll.size > preRollFrames) {
                preRoll.removeFirst()
            }
        }
    }

    private fun handleSpeaking(prob: Float, pcm: ShortArray) {
        if (prob >= 0.35f) {
            // sustain
            for (s in pcm) utterance.add(s)
        } else {
            // transition to Pause, do NOT append silence (trim)
            state = VadState.Pause
            pauseCounter = 1
        }
    }

    private fun handlePause(prob: Float, pcm: ShortArray) {
        if (prob >= 0.35f) {
            // resume speaking, append this frame
            for (s in pcm) utterance.add(s)
            state = VadState.Speaking
            pauseCounter = 0
        } else {
            pauseCounter++
            if (pauseCounter >= hangoverFrames) {
                state = VadState.Eou
                // do not append trailing silence
            } else {
                state = VadState.Pause
            }
        }
    }
}
