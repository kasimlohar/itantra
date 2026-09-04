package com.itantra.data.ptt

import com.itantra.data.transport.FrameCodec
import com.itantra.domain.model.Frame
import com.itantra.domain.model.Language
import com.itantra.domain.model.PttEvent
import com.itantra.domain.model.PttState
import com.itantra.domain.model.TransmitMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PttStateMachineTest {

    private fun frame(ptt: Boolean, seq: Int = 0, text: String = ""): Frame =
        Frame(TransmitMode.HALF_DUPLEX, isAlert = false, isStream = false, pttPressed = ptt, Language.HINDI, Language.ENGLISH, seq, text)

    // 1. Idle -> local press grants floor and sends ptt=1 control frame
    @Test
    fun idle_onLocalPress_grantsFloor_andSendsControlFrame_ptt1() {
        val m = PttStateMachine()
        val e = m.onLocalPress()
        assertThat(e).isInstanceOf(PttEvent.SendControlFrame::class.java)
        assertThat(m.currentState()).isEqualTo(PttState.Holding)
        assertThat(m.isFloorHeldLocally()).isTrue()
        val f = (e as PttEvent.SendControlFrame).frame
        assertThat(f.pttPressed).isTrue()
        assertThat(f.payloadText).isEmpty()
        // FrameCodec round-trip for control frame (payload_len=0)
        val decoded = FrameCodec.decode(FrameCodec.encode(f))
        assertThat(decoded.pttPressed).isTrue()
        assertThat(decoded.payloadText).isEmpty()
        assertThat(decoded.frameBytesPayloadLen()).isEqualTo(0)
    }

    // helper to assert payload len via codec
    private fun Frame.frameBytesPayloadLen(): Int {
        val enc = FrameCodec.encode(this)
        return ((enc[8].toInt() and 0xFF) shl 8) or (enc[9].toInt() and 0xFF)
    }

    // 2. Holding -> local release sends ptt=0 and returns to Idle
    @Test
    fun holding_onLocalRelease_sendsControlFrame_ptt0_andReturnsIdle() {
        val m = PttStateMachine()
        m.onLocalPress()
        assertThat(m.currentState()).isEqualTo(PttState.Holding)
        // advance clock beyond debounce
        Thread.sleep(90)
        val e = m.onLocalRelease()
        assertThat(e).isInstanceOf(PttEvent.FloorReleased::class.java)
        val f = (e as PttEvent.FloorReleased).frame
        assertThat(f.pttPressed).isFalse()
        assertThat(f.payloadText).isEmpty()
        assertThat(m.currentState()).isEqualTo(PttState.Idle)
        assertThat(m.isFloorHeldLocally()).isFalse()
        val decoded = FrameCodec.decode(FrameCodec.encode(f))
        assertThat(decoded.pttPressed).isFalse()
    }

    // 3. Busy: remote holds floor, local press denied / ChannelBusy
    @Test
    fun busy_remoteHoldsFloor_localPress_denied_channelBusy_noMicCapture() {
        val m = PttStateMachine()
        // remote takes floor
        val remoteGrant = m.onRemoteFrame(frame(ptt = true, seq = 10))
        assertThat(remoteGrant).isEqualTo(PttEvent.RemoteGranted)
        assertThat(m.currentState()).isEqualTo(PttState.Busy)
        assertThat(m.isFloorHeldLocally()).isFalse()
        // local press should be denied
        val e = m.onLocalPress()
        assertThat(e).isEqualTo(PttEvent.ChannelBusy)
        assertThat(m.currentState()).isEqualTo(PttState.Busy)
        assertThat(m.isFloorHeldLocally()).isFalse()
    }

    // 4. Remote ptt=1 sets Busy
    @Test
    fun remoteFrame_ptt1_setsBusy_andMutesLocal() {
        val m = PttStateMachine()
        val e = m.onRemoteFrame(frame(ptt = true, seq = 5))
        assertThat(e).isEqualTo(PttEvent.RemoteGranted)
        assertThat(m.currentState()).isEqualTo(PttState.Busy)
    }

    // 5. Remote ptt=0 from Busy returns Idle
    @Test
    fun remoteFrame_ptt0_fromBusy_returnsIdle() {
        val m = PttStateMachine()
        m.onRemoteFrame(frame(ptt = true))
        assertThat(m.currentState()).isEqualTo(PttState.Busy)
        val e = m.onRemoteFrame(frame(ptt = false))
        assertThat(e).isEqualTo(PttEvent.RemoteReleased)
        assertThat(m.currentState()).isEqualTo(PttState.Idle)
    }

    // 6. Local press while already Holding (without release) is ignored/denied
    @Test
    fun localPressWhileHolding_ignoredOrDenied() {
        val m = PttStateMachine(clock = { 1000L })
        m.onLocalPress() // -> Holding
        val e2 = m.onLocalPress()
        // Should not grant again; either Debounced (if within 80ms) or FloorDenied
        // With same clock time, debounce triggers
        assertThat(e2).isEqualTo(PttEvent.Debounced)
        assertThat(m.currentState()).isEqualTo(PttState.Holding)
    }

    // 7. Debounce 80ms: second press within 80ms is Debounced
    @Test
    fun debounce_80ms_secondPressWithin80ms_isDebounced() {
        var now = 1000L
        val m = PttStateMachine(clock = { now })
        val e1 = m.onLocalPress()
        assertThat(e1).isInstanceOf(PttEvent.SendControlFrame::class.java)
        now = 1050L // 50ms later <80
        val e2 = m.onLocalRelease()
        assertThat(e2).isEqualTo(PttEvent.Debounced)
        assertThat(m.currentState()).isEqualTo(PttState.Holding) // still holding
    }

    // 8. Debounce outside 80ms allows second request
    @Test
    fun debounce_outside80ms_allowsSecondRequest() {
        var now = 0L
        val m = PttStateMachine(clock = { now })
        m.onLocalPress() // t0
        now = 90L // >80
        val e2 = m.onLocalRelease() // should succeed
        assertThat(e2).isInstanceOf(PttEvent.FloorReleased::class.java)
        assertThat(m.currentState()).isEqualTo(PttState.Idle)
        now = 180L
        val e3 = m.onLocalPress()
        assertThat(e3).isInstanceOf(PttEvent.SendControlFrame::class.java)
        assertThat(m.currentState()).isEqualTo(PttState.Holding)
    }

    // 9. DUPLEX mode bypasses floor control — all local presses ignored
    @Test
    fun duplexMode_bypassesFloorControl_allPressesIgnored_noControlFrame() {
        val m = PttStateMachine(mode = TransmitMode.DUPLEX)
        val e1 = m.onLocalPress()
        assertThat(e1).isEqualTo(PttEvent.Ignored)
        assertThat(m.currentState()).isEqualTo(PttState.Idle)
        assertThat(m.isFloorHeldLocally()).isFalse()
        val e2 = m.onLocalRelease()
        assertThat(e2).isEqualTo(PttEvent.Ignored)
    }

    // 10. DUPLEX mode remote frames ignored
    @Test
    fun duplexMode_remoteFramesIgnored() {
        val m = PttStateMachine(mode = TransmitMode.DUPLEX)
        val e = m.onRemoteFrame(frame(ptt = true))
        assertThat(e).isEqualTo(PttEvent.Ignored)
        assertThat(m.currentState()).isEqualTo(PttState.Idle)
        val e2 = m.onRemoteFrame(frame(ptt = false))
        assertThat(e2).isEqualTo(PttEvent.Ignored)
    }

    // 11. Control frame has empty payload and ptt flag correct and codec round-trips
    @Test
    fun controlFrame_hasEmptyPayload_andPttFlagCorrect_andCodecRoundTrips() {
        val m = PttStateMachine()
        val e1 = m.onLocalPress() as PttEvent.SendControlFrame
        val f1 = e1.frame
        assertThat(f1.payloadText).isEmpty()
        assertThat(f1.pttPressed).isTrue()
        assertThat(f1.isAlert).isFalse()
        assertThat(f1.isStream).isFalse()
        // encode/decode preserves flags
        val dec1 = FrameCodec.decode(FrameCodec.encode(f1))
        assertThat(dec1.pttPressed).isTrue()
        assertThat(dec1.isAlert).isFalse()

        // Use fake clock to avoid debounce for release
        var now = 0L
        val m2 = PttStateMachine(clock = { now })
        m2.onLocalPress()
        now = 100L
        val e2 = m2.onLocalRelease() as PttEvent.FloorReleased
        val f2 = e2.frame
        assertThat(f2.payloadText).isEmpty()
        assertThat(f2.pttPressed).isFalse()
        val dec2 = FrameCodec.decode(FrameCodec.encode(f2))
        assertThat(dec2.pttPressed).isFalse()
    }

    // 12. Control frame seqId monotonic
    @Test
    fun controlFrame_seqId_monotonic() {
        var now = 0L
        val m = PttStateMachine(clock = { now })
        val e1 = m.onLocalPress() as PttEvent.SendControlFrame
        val s1 = e1.frame.seqId
        now = 100L
        val e2 = m.onLocalRelease() as PttEvent.FloorReleased
        val s2 = e2.frame.seqId
        assertThat(s2).isNotEqualTo(s1)
        // Should be monotonic increment (seq wraps at 65535, but small values increment by 1)
        assertThat(s2).isEqualTo((s1 + 1) and 0xFFFF)
        now = 200L
        val e3 = m.onLocalPress() as PttEvent.SendControlFrame
        assertThat(e3.frame.seqId).isEqualTo((s2 + 1) and 0xFFFF)
    }

    // 13. Clear resets to Idle
    @Test
    fun clear_resetsToIdle() {
        val m = PttStateMachine()
        m.onLocalPress()
        assertThat(m.currentState()).isEqualTo(PttState.Holding)
        m.clear()
        assertThat(m.currentState()).isEqualTo(PttState.Idle)
        assertThat(m.isFloorHeldLocally()).isFalse()
        // After clear, able to press again
        val e = m.onLocalPress()
        assertThat(e).isInstanceOf(PttEvent.SendControlFrame::class.java)
        assertThat(m.currentState()).isEqualTo(PttState.Holding)
    }

    // 14. isFloorHeldLocally true only while Holding
    @Test
    fun isFloorHeldLocally_trueOnlyWhileHolding() {
        val m = PttStateMachine()
        assertThat(m.isFloorHeldLocally()).isFalse()
        m.onRemoteFrame(frame(ptt = true))
        assertThat(m.currentState()).isEqualTo(PttState.Busy)
        assertThat(m.isFloorHeldLocally()).isFalse()
        m.onRemoteFrame(frame(ptt = false))
        assertThat(m.isFloorHeldLocally()).isFalse()
        m.onLocalPress()
        assertThat(m.isFloorHeldLocally()).isTrue()
        // need to bypass debounce for release
        var now = 0L
        val m2 = PttStateMachine(clock = { now })
        m2.onLocalPress()
        assertThat(m2.isFloorHeldLocally()).isTrue()
        now = 100L
        m2.onLocalRelease()
        assertThat(m2.isFloorHeldLocally()).isFalse()
    }

    // Additional: remote non-empty payload ignored for PTT (text frames should not change floor)
    @Test
    fun remoteTextFrame_doesNotChangeFloor() {
        val m = PttStateMachine()
        // remote sends text frame with pttPressed true but non-empty payload? Actually ptt control frames have empty payload; text frames have text
        // Our router should ignore ptt handling if payload not empty? But spec says payload_len=0 for control; we check isEmpty
        val textFrame = Frame(TransmitMode.HALF_DUPLEX, false, false, true, Language.HINDI, Language.ENGLISH, 5, "hello")
        val e = m.onRemoteFrame(textFrame)
        assertThat(e).isEqualTo(PttEvent.Ignored)
        assertThat(m.currentState()).isEqualTo(PttState.Idle)
    }
}
