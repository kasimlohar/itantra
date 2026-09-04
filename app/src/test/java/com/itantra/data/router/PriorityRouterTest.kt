package com.itantra.data.router

import com.itantra.domain.model.Frame
import com.itantra.domain.model.Language
import com.itantra.domain.model.PlaybackItem
import com.itantra.domain.model.TransmitMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PriorityRouterTest {

    private fun frame(seq: Int, alert: Boolean = false, text: String = "hi-$seq"): Frame =
        Frame(
            mode = TransmitMode.HALF_DUPLEX,
            isAlert = alert,
            isStream = false,
            pttPressed = false,
            srcLang = Language.HINDI,
            dstLang = Language.ENGLISH,
            seqId = seq,
            payloadText = text
        )

    // 1. Standard when idle -> PlayNow
    @Test
    fun standard_frame_enqueued_fifo_whenIdle_playsNow() {
        val r = PriorityRouter()
        val d = r.route(frame(1, false))
        assertThat(d).isInstanceOf(RouteDecision.PlayNow::class.java)
        assertThat(r.current()?.seqId).isEqualTo(1)
        assertThat(r.current()?.isAlert).isFalse()
        assertThat(r.pending()).isEmpty()
    }

    // 2. Second standard preserves FIFO
    @Test
    fun second_standard_enqueued_preservesFifo() {
        val r = PriorityRouter()
        r.route(frame(1, false))
        val d2 = r.route(frame(2, false))
        assertThat(d2).isInstanceOf(RouteDecision.Enqueue::class.java)
        assertThat(r.current()?.seqId).isEqualTo(1)
        assertThat(r.pending().map { it.seqId }).isEqualTo(listOf(2))
    }

    // 3. Three standards in order
    @Test
    fun threeStandards_inOrder_pendingFifo() {
        val r = PriorityRouter()
        r.route(frame(10, false))
        r.route(frame(5, false))
        r.route(frame(3, false))
        assertThat(r.current()?.seqId).isEqualTo(10)
        assertThat(r.pending().map { it.seqId }).isEqualTo(listOf(5, 3))
    }

    // 4. Alert immediately becomes sole active (idle)
    @Test
    fun alert_immediatelyBecomesSoleActiveItem_playNow() {
        val r = PriorityRouter()
        val d = r.route(frame(99, true))
        assertThat(d).isInstanceOf(RouteDecision.PlayNow::class.java)
        assertThat(r.current()?.seqId).isEqualTo(99)
        assertThat(r.current()?.isAlert).isTrue()
        assertThat(r.pending()).isEmpty()
    }

    // 5. Alert preempts ongoing standard and clears pending
    @Test
    fun alert_preemptsOngoingStandard_andClearsPending() {
        val r = PriorityRouter()
        r.route(frame(1, false)) // current 1
        r.route(frame(2, false)) // pending [2]
        assertThat(r.pending().size).isEqualTo(1)
        val d = r.route(frame(99, true))
        assertThat(d).isInstanceOf(RouteDecision.PlayNow::class.java)
        val playNow = d as RouteDecision.PlayNow
        assertThat(playNow.preempted?.seqId).isEqualTo(1)
        assertThat(r.current()?.seqId).isEqualTo(99)
        assertThat(r.current()?.isAlert).isTrue()
        assertThat(r.pending()).isEmpty() // pending cleared
    }

    // 6. Alert arriving while standard is playing forces preemption
    @Test
    fun alert_arrivingWhileStandardPlaying_forcesPreemption() {
        val r = PriorityRouter()
        r.route(frame(10, false))
        assertThat(r.current()?.seqId).isEqualTo(10)
        val d = r.route(frame(20, true))
        assertThat(d).isInstanceOf(RouteDecision.PlayNow::class.java)
        assertThat(r.current()?.seqId).isEqualTo(20)
        assertThat(r.pending()).isEmpty()
    }

    // 7. After alert finishes, pending standards not restored
    @Test
    fun afterAlertFinishes_pendingStandardsNotRestored() {
        val r = PriorityRouter()
        r.route(frame(1, false))
        r.route(frame(2, false)) // pending [2]
        r.route(frame(99, true)) // preempts, clears pending
        assertThat(r.pending()).isEmpty()
        assertThat(r.current()?.seqId).isEqualTo(99)
        r.onPlaybackFinished() // alert done
        assertThat(r.current()).isNull()
        assertThat(r.pending()).isEmpty() // not restored
    }

    // 8. Empty queue behaviour
    @Test
    fun emptyQueue_behaviour_currentNull_pendingEmpty() {
        val r = PriorityRouter()
        assertThat(r.current()).isNull()
        assertThat(r.pending()).isEmpty()
        r.onPlaybackFinished() // should not crash
        assertThat(r.current()).isNull()
        assertThat(r.pending()).isEmpty()
    }

    // 9. Clear removes current and pending
    @Test
    fun clear_removesCurrentAndPending() {
        val r = PriorityRouter()
        r.route(frame(1, false))
        r.route(frame(2, false))
        r.route(frame(3, true)) // alert preempts? Actually will preempt, pending cleared, current 3
        // Now route another standard to have pending
        // Reset to known state: clear first then add
        r.clear()
        assertThat(r.current()).isNull()
        assertThat(r.pending()).isEmpty()
        r.route(frame(10, false))
        r.route(frame(11, false))
        assertThat(r.pending().size).isEqualTo(1)
        r.clear()
        assertThat(r.current()).isNull()
        assertThat(r.pending()).isEmpty()
    }

    // 10. Sequence-id not reordered – arrival order preserved
    @Test
    fun sequenceIdNotReordered_routerPreservesArrivalOrder() {
        val r = PriorityRouter()
        r.route(frame(5, false))
        r.route(frame(3, false))
        r.route(frame(10, false))
        // current 5, pending [3,10] arrival order, not sorted by seqId
        assertThat(r.current()?.seqId).isEqualTo(5)
        assertThat(r.pending().map { it.seqId }).isEqualTo(listOf(3, 10))
    }

    // 11. Second alert while alert playing enqueues behind current (non-interruptible)
    @Test
    fun secondAlert_whileAlertPlaying_enqueuesBehindCurrent() {
        val r = PriorityRouter()
        r.route(frame(100, true)) // current alert 100
        val d2 = r.route(frame(101, true))
        assertThat(d2).isInstanceOf(RouteDecision.Enqueue::class.java)
        assertThat(r.current()?.seqId).isEqualTo(100)
        assertThat(r.pending().map { it.seqId }).isEqualTo(listOf(101))
        assertThat(r.pending().first().isAlert).isTrue()
        // first alert finishes, second should become current
        r.onPlaybackFinished()
        assertThat(r.current()?.seqId).isEqualTo(101)
        assertThat(r.pending()).isEmpty()
    }

    // 12. onPlaybackFinished advances from pending FIFO
    @Test
    fun onPlaybackFinished_advancesFromPendingFifo() {
        val r = PriorityRouter()
        r.route(frame(1, false)) // current 1
        r.route(frame(2, false)) // pending [2]
        r.route(frame(3, false)) // pending [2,3]
        r.onPlaybackFinished() // finish 1
        assertThat(r.current()?.seqId).isEqualTo(2)
        assertThat(r.pending().map { it.seqId }).isEqualTo(listOf(3))
        r.onPlaybackFinished() // finish 2
        assertThat(r.current()?.seqId).isEqualTo(3)
        assertThat(r.pending()).isEmpty()
        r.onPlaybackFinished() // finish 3
        assertThat(r.current()).isNull()
        assertThat(r.pending()).isEmpty()
    }

    // 13. route returns correct decision type playNow vs enqueue
    @Test
    fun routeReturnsCorrectDecisionType_playNowVsEnqueue() {
        val r = PriorityRouter()
        val d1 = r.route(frame(1, false))
        assertThat(d1).isInstanceOf(RouteDecision.PlayNow::class.java)
        val d2 = r.route(frame(2, false))
        assertThat(d2).isInstanceOf(RouteDecision.Enqueue::class.java)
        val d3 = r.route(frame(99, true))
        assertThat(d3).isInstanceOf(RouteDecision.PlayNow::class.java)
        assertThat((d3 as RouteDecision.PlayNow).preempted?.seqId).isEqualTo(1)
    }

    // Extra: alert with empty pending still PlayNow
    @Test
    fun alert_withNoPending_stillPlayNow() {
        val r = PriorityRouter()
        r.route(frame(1, false))
        r.clear()
        val d = r.route(frame(50, true))
        assertThat(d).isInstanceOf(RouteDecision.PlayNow::class.java)
        assertThat(r.current()?.seqId).isEqualTo(50)
    }
}
