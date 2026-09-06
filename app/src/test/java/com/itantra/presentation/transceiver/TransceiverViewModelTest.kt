package com.itantra.presentation.transceiver
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlinx.coroutines.test.runTest
import com.itantra.domain.model.Language
import com.itantra.domain.model.TransmitMode
import com.itantra.data.transport.TransportManager
import com.itantra.data.transport.TransportState
class TransceiverViewModelTest {
  private fun fakeManager() = TransportManager(FakeWifi(), FakeBt())
  class FakeWifi: com.itantra.data.transport.TransportConnection {
    override suspend fun send(frame: com.itantra.domain.model.Frame)=Result.success(Unit)
    override val incomingFrames=kotlinx.coroutines.flow.emptyFlow<com.itantra.domain.model.Frame>()
    override fun disconnect(){}
    override val isConnected=false
  }
  class FakeBt: com.itantra.data.transport.TransportConnection {
    override suspend fun send(frame: com.itantra.domain.model.Frame)=Result.success(Unit)
    override val incomingFrames=kotlinx.coroutines.flow.emptyFlow<com.itantra.domain.model.Frame>()
    override fun disconnect(){}
    override val isConnected=true
  }
  @Test fun initialStateIsIdleDisconnectedHalfDuplex() = runTest {
    val vm=TransceiverViewModel(fakeManager(), com.itantra.data.ptt.PttStateMachine(), com.itantra.data.router.PriorityRouter())
    assertThat(vm.state.value.pttUiState).isEqualTo(PttUiState.IDLE)
    assertThat(vm.state.value.connectionState).isEqualTo(TransportState.DISCONNECTED)
    assertThat(vm.state.value.channelMode).isEqualTo(TransmitMode.HALF_DUPLEX)
  }
  @Test fun floorRequestWhenIdleTransitionsToListening() = runTest {
    val vm=TransceiverViewModel(fakeManager(), com.itantra.data.ptt.PttStateMachine(), com.itantra.data.router.PriorityRouter())
    vm.process(TransceiverIntent.FloorRequest)
    assertThat(vm.state.value.pttUiState).isEqualTo(PttUiState.LISTENING)
  }
  @Test fun floorRequestWhenBusyStaysBusy() = runTest {
    val ptt=com.itantra.data.ptt.PttStateMachine()
    val vm=TransceiverViewModel(fakeManager(), ptt, com.itantra.data.router.PriorityRouter())
    vm.process(TransceiverIntent.FloorRequest)
    vm.process(TransceiverIntent.FloorRequest)
    assertThat(vm.state.value.pttUiState).isEqualTo(PttUiState.LISTENING)
  }
  @Test fun toggleModeSwitchesHalfDuplexToDuplex() = runTest {
    val vm=TransceiverViewModel(fakeManager(), com.itantra.data.ptt.PttStateMachine(), com.itantra.data.router.PriorityRouter())
    assertThat(vm.state.value.channelMode).isEqualTo(TransmitMode.HALF_DUPLEX)
    vm.process(TransceiverIntent.ToggleMode)
    assertThat(vm.state.value.channelMode).isEqualTo(TransmitMode.DUPLEX)
  }
  @Test fun alertPreemptionSetsAlertActive() = runTest {
    val vm=TransceiverViewModel(fakeManager(), com.itantra.data.ptt.PttStateMachine(), com.itantra.data.router.PriorityRouter())
    vm.process(TransceiverIntent.SendAlert("help"))
    assertThat(vm.state.value.isAlertActive).isTrue()
    assertThat(vm.state.value.alertTranscript).isEqualTo("help")
  }
  @Test fun selectLanguageUpdatesState() = runTest {
    val vm=TransceiverViewModel(fakeManager(), com.itantra.data.ptt.PttStateMachine(), com.itantra.data.router.PriorityRouter())
    vm.process(TransceiverIntent.SelectLanguage(Language.TAMIL, Language.HINDI))
    assertThat(vm.state.value.srcLang).isEqualTo(Language.TAMIL)
    assertThat(vm.state.value.dstLang).isEqualTo(Language.HINDI)
  }
  @Test fun remoteControlFrameLocksAndUnlocksFloor() = runTest {
    val vm=TransceiverViewModel(fakeManager(), com.itantra.data.ptt.PttStateMachine(), com.itantra.data.router.PriorityRouter())
    val lockFrame = com.itantra.domain.model.Frame(
      mode = TransmitMode.HALF_DUPLEX,
      isAlert = false,
      isStream = false,
      pttPressed = true,
      srcLang = Language.HINDI,
      dstLang = Language.HINDI,
      seqId = 1,
      payloadText = ""
    )
    vm.process(TransceiverIntent.OnFrameReceived(lockFrame))
    assertThat(vm.state.value.isFloorLocked).isTrue()
    assertThat(vm.state.value.floorHolderId).isEqualTo("Remote Peer")
    assertThat(vm.state.value.pttUiState).isEqualTo(PttUiState.BUSY)

    // Attempting local press while floor locked remains BUSY
    vm.process(TransceiverIntent.FloorRequest)
    assertThat(vm.state.value.isFloorLocked).isTrue()
    assertThat(vm.state.value.pttUiState).isEqualTo(PttUiState.BUSY)

    // Remote release unlocks floor
    val releaseFrame = lockFrame.copy(pttPressed = false)
    vm.process(TransceiverIntent.OnFrameReceived(releaseFrame))
    assertThat(vm.state.value.isFloorLocked).isFalse()
    assertThat(vm.state.value.floorHolderId).isNull()
    assertThat(vm.state.value.pttUiState).isEqualTo(PttUiState.IDLE)
  }
  @Test fun localPttPressAndReleaseSendsControlFrames() = runTest {
    var currentTime = 1000L
    val ptt = com.itantra.data.ptt.PttStateMachine(clock = { currentTime })
    val capturing = CapturingConnection()
    val mgr = TransportManager(capturing, capturing)
    val vm = TransceiverViewModel(mgr, ptt, com.itantra.data.router.PriorityRouter())
    vm.process(TransceiverIntent.FloorRequest)
    Thread.sleep(50)
    assertThat(capturing.sentFrames).isNotEmpty()
    assertThat(capturing.sentFrames.first().pttPressed).isTrue()
    assertThat(capturing.sentFrames.first().payloadText).isEmpty()

    // Advance clock past 80ms debounce window
    currentTime += 100L
    vm.process(TransceiverIntent.FloorRelease)
    Thread.sleep(50)
    assertThat(capturing.sentFrames.size).isAtLeast(2)
    val releaseFrame = capturing.sentFrames.find { !it.pttPressed && it.payloadText.isEmpty() }
    assertThat(releaseFrame).isNotNull()
  }
}

class CapturingConnection : com.itantra.data.transport.TransportConnection {
  val sentFrames = mutableListOf<com.itantra.domain.model.Frame>()
  override suspend fun send(frame: com.itantra.domain.model.Frame): Result<Unit> {
    sentFrames.add(frame)
    return Result.success(Unit)
  }
  override val incomingFrames = kotlinx.coroutines.flow.emptyFlow<com.itantra.domain.model.Frame>()
  override fun disconnect() {}
  override val isConnected = true
}
