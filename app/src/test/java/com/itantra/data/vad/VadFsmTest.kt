package com.itantra.data.vad

import com.itantra.domain.model.VadState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * TDD RED tests for VadFsm per PRD US-03 / FR-05 and Architecture §3.
 * Thresholds: onset p≥0.6 for 3 consecutive 30 ms frames, sustain p≥0.35,
 * hangover 450 ms default (PTT) / 550 ms phone, pre-roll 200 ms, trim trailing silence.
 */
class VadFsmTest {

    private fun pcm(frameId: Int = 0, frames: Int = 1, value: Short = frameId.toShort()): ShortArray {
        // 16 kHz * 30 ms = 480 samples per frame; fill with frameId for pre-roll checks
        return ShortArray(480 * frames) { value }
    }

    private fun pcmDistinct(id: Int): ShortArray = ShortArray(480) { id.toShort() }

    // 1. Idle stays Idle when prob below onset
    @Test
    fun idle_staysIdle_whenProbBelowOnset() {
        val fsm = VadFsm()
        assertThat(fsm.onFrame(0.5f, pcm())).isEqualTo(VadState.Idle)
        assertThat(fsm.state).isEqualTo(VadState.Idle)
    }

    // 2. Idle -> Speaking after 3 consecutive p≥0.6
    @Test
    fun idle_transitions_toSpeaking_afterThreeConsecutive_0_6() {
        val fsm = VadFsm()
        assertThat(fsm.onFrame(0.65f, pcm(1))).isEqualTo(VadState.Idle)
        assertThat(fsm.onFrame(0.70f, pcm(2))).isEqualTo(VadState.Idle)
        assertThat(fsm.onFrame(0.65f, pcm(3))).isEqualTo(VadState.Speaking)
        assertThat(fsm.state).isEqualTo(VadState.Speaking)
    }

    // 3. Two frames insufficient
    @Test
    fun idle_doesNotTrigger_onTwoFramesOnly() {
        val fsm = VadFsm()
        fsm.onFrame(0.65f, pcm(1))
        fsm.onFrame(0.65f, pcm(2))
        assertThat(fsm.state).isEqualTo(VadState.Idle)
        // third low breaks sequence
        fsm.onFrame(0.1f, pcm(3))
        assertThat(fsm.state).isEqualTo(VadState.Idle)
        assertThat(fsm.getUtterance().isEmpty()).isTrue()
    }

    // 4. Speaking sustains when p≥0.35
    @Test
    fun speaking_sustains_whenProbAbove_0_35() {
        val fsm = VadFsm()
        repeat(3) { fsm.onFrame(0.65f, pcm()) }
        assertThat(fsm.state).isEqualTo(VadState.Speaking)
        assertThat(fsm.onFrame(0.40f, pcm())).isEqualTo(VadState.Speaking)
        assertThat(fsm.onFrame(0.35f, pcm())).isEqualTo(VadState.Speaking)
    }

    // 5. Speaking -> Pause when p<0.35
    @Test
    fun speaking_transitionsToPause_whenProbBelow_0_35() {
        val fsm = VadFsm()
        repeat(3) { fsm.onFrame(0.65f, pcm()) }
        assertThat(fsm.onFrame(0.20f, pcm())).isEqualTo(VadState.Pause)
        assertThat(fsm.state).isEqualTo(VadState.Pause)
    }

    // 6. Pause returns to Speaking when prob resumes within hangover
    @Test
    fun pause_returnsToSpeaking_whenProbResumesWithinHangover() {
        val fsm = VadFsm(hangoverMs = 450)
        repeat(3) { fsm.onFrame(0.65f, pcm()) }
        fsm.onFrame(0.2f, pcm()) // Pause 1
        assertThat(fsm.state).isEqualTo(VadState.Pause)
        fsm.onFrame(0.1f, pcm()) // Pause 2
        assertThat(fsm.onFrame(0.60f, pcm())).isEqualTo(VadState.Speaking)
        assertThat(fsm.state).isEqualTo(VadState.Speaking)
    }

    // 7. Pause triggers Eou after hangover 450 (PTT default)
    @Test
    fun pause_triggersEou_afterHangover_450() {
        val fsm = VadFsm(hangoverMs = 450) // 450/30 =15 frames
        repeat(3) { fsm.onFrame(0.65f, pcm()) }
        var state: VadState = VadState.Speaking
        repeat(15) { state = fsm.onFrame(0.10f, pcm()) }
        assertThat(state).isEqualTo(VadState.Eou)
        assertThat(fsm.state).isEqualTo(VadState.Eou)
    }

    // 8. Pause triggers Eou after hangover 550 (phone mode)
    @Test
    fun pause_triggersEou_afterHangover_550_phoneMode() {
        val fsm = VadFsm(hangoverMs = 550) // 550/30 ≈18 frames (int division)
        repeat(3) { fsm.onFrame(0.65f, pcm()) }
        // 17 frames -> still Pause
        repeat(17) { fsm.onFrame(0.10f, pcm()) }
        assertThat(fsm.state).isEqualTo(VadState.Pause)
        // 1 more -> Eou (18)
        assertThat(fsm.onFrame(0.10f, pcm())).isEqualTo(VadState.Eou)
    }

    // 9. Hangover boundary within 60 ms tolerance (450 case)
    @Test
    fun hangover_boundary_within60ms_tolerance() {
        val fsm = VadFsm(hangoverMs = 450)
        repeat(3) { fsm.onFrame(0.65f, pcm()) }
        // 13 frames =390 ms (450-60) should still be Pause
        repeat(13) { fsm.onFrame(0.10f, pcm()) }
        assertThat(fsm.state).isEqualTo(VadState.Pause)
        // 2 more =15 total 450 ms -> Eou
        fsm.onFrame(0.10f, pcm())
        assertThat(fsm.onFrame(0.10f, pcm())).isEqualTo(VadState.Eou)
    }

    // 10. Pre-roll retains 200 ms of prior frames on onset
    @Test
    fun preRoll_retains200ms_ofPriorFrames_onOnset() {
        val fsm = VadFsm(preRollMs = 200)
        // Feed 7 distinct pre-roll frames while Idle (p low)
        for (i in 1..7) {
            fsm.onFrame(0.10f, pcmDistinct(i))
        }
        // Now trigger onset with 3 high frames 8,9,10
        fsm.onFrame(0.65f, pcmDistinct(8))
        fsm.onFrame(0.65f, pcmDistinct(9))
        fsm.onFrame(0.65f, pcmDistinct(10))
        assertThat(fsm.state).isEqualTo(VadState.Speaking)
        val utterance = fsm.getUtterance()
        // Expected: 7 pre-roll frames (1..7) + 3 onset frames (8..10) =10 frames *480 =4800 samples
        assertThat(utterance.size).isEqualTo(480 * 10)
        // Check first sample values correspond to frame ids
        assertThat(utterance[0]).isEqualTo(1.toShort())
        assertThat(utterance[480]).isEqualTo(2.toShort())
        assertThat(utterance[6 * 480]).isEqualTo(7.toShort())
        assertThat(utterance[7 * 480]).isEqualTo(8.toShort())
        assertThat(utterance[9 * 480]).isEqualTo(10.toShort())
    }

    // 11. Utterance trims trailing silence before Eou
    @Test
    fun utterance_trimsTrailingSilence_beforeEou() {
        val fsm = VadFsm(hangoverMs = 450)
        repeat(3) { fsm.onFrame(0.65f, pcmDistinct(1)) }
        fsm.onFrame(0.60f, pcmDistinct(2)) // speaking frame
        val sizeBeforePause = fsm.getUtterance().size
        // Now silence for hangover
        repeat(15) { fsm.onFrame(0.10f, pcmDistinct(99)) }
        assertThat(fsm.state).isEqualTo(VadState.Eou)
        val utterance = fsm.getUtterance()
        // Trailing silence (99) must NOT be appended
        assertThat(utterance.size).isEqualTo(sizeBeforePause)
        assertThat(utterance.last()).isNotEqualTo(99.toShort())
    }

    // 12. State resets to Idle after Eou and getUtterance
    @Test
    fun state_resets_toIdle_afterEou_and_getUtterance() {
        val fsm = VadFsm(hangoverMs = 450)
        repeat(3) { fsm.onFrame(0.65f, pcm()) }
        repeat(15) { fsm.onFrame(0.10f, pcm()) }
        assertThat(fsm.state).isEqualTo(VadState.Eou)
        val utt = fsm.getUtterance()
        assertThat(utt.isNotEmpty()).isTrue()
        fsm.reset()
        assertThat(fsm.state).isEqualTo(VadState.Idle)
        assertThat(fsm.getUtterance().isEmpty()).isTrue()
    }

    // 13. Single frame spike does not trigger
    @Test
    fun short_noiseSpike_singleFrameAbove0_6_doesNotTrigger() {
        val fsm = VadFsm()
        fsm.onFrame(0.65f, pcm())
        assertThat(fsm.state).isEqualTo(VadState.Idle)
        fsm.onFrame(0.10f, pcm())
        assertThat(fsm.state).isEqualTo(VadState.Idle)
        fsm.onFrame(0.65f, pcm())
        assertThat(fsm.state).isEqualTo(VadState.Idle)
        // Still need 3 consecutive
        fsm.onFrame(0.65f, pcm())
        fsm.onFrame(0.65f, pcm())
        assertThat(fsm.state).isEqualTo(VadState.Speaking)
    }

    // 14. Rapid on/off does not leak frames, hangover resets
    @Test
    fun rapidOnOff_doesNotLeakFrames() {
        val fsm = VadFsm(hangoverMs = 450)
        repeat(3) { fsm.onFrame(0.65f, pcmDistinct(1)) }
        fsm.onFrame(0.10f, pcmDistinct(99)) // Pause 1
        fsm.onFrame(0.60f, pcmDistinct(2)) // back to Speaking, resets pause
        assertThat(fsm.state).isEqualTo(VadState.Speaking)
        // Now silence again but need full hangover
        repeat(14) { fsm.onFrame(0.10f, pcmDistinct(99)) }
        assertThat(fsm.state).isEqualTo(VadState.Pause)
        assertThat(fsm.onFrame(0.10f, pcmDistinct(99))).isEqualTo(VadState.Eou)
    }

    // 15. Eou then immediate new utterance starts fresh
    @Test
    fun eou_thenImmediateNewUtterance_startsFresh() {
        val fsm = VadFsm(hangoverMs = 450)
        repeat(3) { fsm.onFrame(0.65f, pcmDistinct(1)) }
        repeat(15) { fsm.onFrame(0.10f, pcmDistinct(99)) }
        assertThat(fsm.state).isEqualTo(VadState.Eou)
        val firstUtt = fsm.getUtterance().toList()
        fsm.reset()
        // New utterance with different frame ids
        for (i in 10..16) fsm.onFrame(0.10f, pcmDistinct(i)) // pre-roll
        repeat(3) { fsm.onFrame(0.65f, pcmDistinct(20)) }
        assertThat(fsm.state).isEqualTo(VadState.Speaking)
        val secondUtt = fsm.getUtterance()
        assertThat(secondUtt == firstUtt).isFalse()
        assertThat(secondUtt.isNotEmpty()).isTrue()
    }

    // 16. Hangover configurable at runtime
    @Test
    fun hangover_configurable_switch450to550_atRuntime() {
        val fsm = VadFsm(hangoverMs = 450)
        fsm.setHangoverMs(550)
        repeat(3) { fsm.onFrame(0.65f, pcm()) }
        repeat(15) { fsm.onFrame(0.10f, pcm()) }
        // 15 frames with 550 hangover (needs 18) should still be Pause
        assertThat(fsm.state).isEqualTo(VadState.Pause)
        repeat(3) { fsm.onFrame(0.10f, pcm()) }
        assertThat(fsm.state).isEqualTo(VadState.Eou)
    }

    // Edge: hangover boundary ±60 ms using 500 ms nominal (Architecture) – verify 480-540 window
    @Test
    fun hangover_nominal500_within480to540() {
        val fsm = VadFsm(hangoverMs = 500) // 500/30=16 frames (480 ms)
        repeat(3) { fsm.onFrame(0.65f, pcm()) }
        repeat(16) { fsm.onFrame(0.10f, pcm()) }
        assertThat(fsm.state).isEqualTo(VadState.Eou)
        // Reset and test that 15 frames (450 ms) not yet Eou if hangover 540 would need 18 frames
        fsm.reset()
        fsm.setHangoverMs(540)
        repeat(3) { fsm.onFrame(0.65f, pcm()) }
        repeat(16) { fsm.onFrame(0.10f, pcm()) }
        assertThat(fsm.state).isEqualTo(VadState.Pause)
        fsm.onFrame(0.10f, pcm())
        assertThat(fsm.onFrame(0.10f, pcm())).isEqualTo(VadState.Eou) // 18 frames =540 ms
    }
}
