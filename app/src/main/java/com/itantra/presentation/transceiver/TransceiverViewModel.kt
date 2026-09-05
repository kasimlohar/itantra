package com.itantra.presentation.transceiver
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.itantra.data.transport.TransportManager
import com.itantra.data.ptt.PttStateMachine
import com.itantra.data.router.PriorityRouter
import com.itantra.domain.model.TransmitMode
import com.itantra.domain.model.PttEvent

class TransceiverViewModel(
  private val transportManager: TransportManager,
  private val pttMachine: PttStateMachine,
  private val router: PriorityRouter
) : ViewModel() {
  constructor() : this(
    TransportManager(
      WifiDirectTransportStub(),
      BluetoothTransportStub()
    ),
    PttStateMachine(),
    PriorityRouter()
  )

  // Lightweight stubs for Activity default construction (host-runnable, no Android deps)
  private class WifiDirectTransportStub : com.itantra.data.transport.TransportConnection {
    override suspend fun send(frame: com.itantra.domain.model.Frame) = Result.success(Unit)
    override val incomingFrames = kotlinx.coroutines.flow.emptyFlow<com.itantra.domain.model.Frame>()
    override fun disconnect() {}
    override val isConnected = false
  }
  private class BluetoothTransportStub : com.itantra.data.transport.TransportConnection {
    override suspend fun send(frame: com.itantra.domain.model.Frame) = Result.success(Unit)
    override val incomingFrames = kotlinx.coroutines.flow.emptyFlow<com.itantra.domain.model.Frame>()
    override fun disconnect() {}
    override val isConnected = false
  }
  private val _state = MutableStateFlow(TransceiverUiState())
  val state: StateFlow<TransceiverUiState> = _state

  fun process(intent: TransceiverIntent) {
    when (intent) {
      is TransceiverIntent.FloorRequest -> {
        val ev = pttMachine.onLocalPress()
        _state.value = _state.value.copy(
          pttUiState = when (ev) {
            is PttEvent.SendControlFrame -> PttUiState.LISTENING
            is PttEvent.ChannelBusy -> PttUiState.BUSY
            is PttEvent.FloorDenied -> PttUiState.LISTENING
            is PttEvent.Debounced -> _state.value.pttUiState
            else -> PttUiState.LISTENING
          }
        )
      }
      is TransceiverIntent.FloorRelease -> {
        try { pttMachine.onLocalRelease() } catch (_: Exception) {}
        _state.value = _state.value.copy(pttUiState = PttUiState.IDLE)
      }
      is TransceiverIntent.ToggleMode -> {
        val newMode = if (_state.value.channelMode == TransmitMode.HALF_DUPLEX) TransmitMode.DUPLEX else TransmitMode.HALF_DUPLEX
        _state.value = _state.value.copy(channelMode = newMode)
      }
      is TransceiverIntent.SelectLanguage -> {
        _state.value = _state.value.copy(srcLang = intent.src, dstLang = intent.dst)
      }
      is TransceiverIntent.SendAlert -> {
        _state.value = _state.value.copy(isAlertActive = true, alertTranscript = intent.text)
      }
      else -> {}
    }
  }
}
