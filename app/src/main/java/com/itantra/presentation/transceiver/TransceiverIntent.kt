package com.itantra.presentation.transceiver
import com.itantra.domain.model.Language
import com.itantra.domain.model.Frame
sealed interface TransceiverIntent {
  data object FloorRequest : TransceiverIntent
  data object FloorRelease : TransceiverIntent
  data object ToggleMode : TransceiverIntent
  data class SelectLanguage(val src: Language, val dst: Language) : TransceiverIntent
  data class SendAlert(val text: String) : TransceiverIntent
  data class ConnectPeer(val peerId: String) : TransceiverIntent
  data object Disconnect : TransceiverIntent
  data class OnFrameReceived(val frame: Frame) : TransceiverIntent
  data class SendMessage(val text: String) : TransceiverIntent
}
