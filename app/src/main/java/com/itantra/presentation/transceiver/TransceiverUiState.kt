package com.itantra.presentation.transceiver
import com.itantra.data.transport.TransportState
import com.itantra.domain.model.Language
import com.itantra.domain.model.TransmitMode
import com.itantra.domain.model.Frame
enum class PttUiState { IDLE, LISTENING, SENDING, SENT, BUSY }
data class MessageItem(val frame: Frame, val isAlert: Boolean, val timestamp: Long, val durationSec: Double)
data class TransceiverUiState(
  val connectionState: TransportState = TransportState.DISCONNECTED,
  val channelMode: TransmitMode = TransmitMode.HALF_DUPLEX,
  val pttUiState: PttUiState = PttUiState.IDLE,
  val srcLang: Language = Language.HINDI,
  val dstLang: Language = Language.HINDI,
  val volumeLevel: Float = 0f,
  val currentTranscript: String = "",
  val messageHistory: List<MessageItem> = emptyList(),
  val isAlertActive: Boolean = false,
  val alertTranscript: String? = null,
  val isFloorLocked: Boolean = false,
  val floorHolderId: String? = null
)
