package com.itantra.presentation.transceiver
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import com.itantra.data.router.PriorityRouter
import com.itantra.domain.model.Frame
import com.itantra.domain.model.Language
import com.itantra.domain.model.TransmitMode
import com.itantra.data.transport.TransportManager
import com.itantra.data.transport.TransportState
import com.itantra.data.audio.AlertAudioManager
class AlertHandlerTest {
  class FakeAudio: AlertAudioManager {
    var focus=false; var vib=false
    override fun acquireAlarmFocus():Boolean { focus=true; return true }
    override fun vibrate(pattern:LongArray){ vib=true }
    override fun release(){}
  }
  private fun fakeManager(): TransportManager {
    val wifi = object: com.itantra.data.transport.TransportConnection {
      override suspend fun send(frame: Frame)=Result.success(Unit)
      override val incomingFrames=kotlinx.coroutines.flow.emptyFlow<Frame>()
      override fun disconnect(){}
      override val isConnected=false
    }
    val bt = object: com.itantra.data.transport.TransportConnection {
      override suspend fun send(frame: Frame)=Result.success(Unit)
      override val incomingFrames=kotlinx.coroutines.flow.emptyFlow<Frame>()
      override fun disconnect(){}
      override val isConnected=true
    }
    return TransportManager(wifi, bt)
  }
  @Test fun alertPreemptsAndAcquiresAlarmFocus() {
    val audio=FakeAudio(); val router=PriorityRouter()
    val vm=TransceiverViewModel(fakeManager(), com.itantra.data.ptt.PttStateMachine(), router)
    val handler=AlertHandler(audio, router, vm)
    val alertFrame=Frame(mode=TransmitMode.HALF_DUPLEX, isAlert=true, isStream=false, pttPressed=false, srcLang=Language.HINDI, dstLang=Language.HINDI, seqId=1, payloadText="alert")
    handler.onAlertFrame(alertFrame)
    assertThat(audio.focus).isTrue()
    assertThat(audio.vib).isTrue()
  }
}
