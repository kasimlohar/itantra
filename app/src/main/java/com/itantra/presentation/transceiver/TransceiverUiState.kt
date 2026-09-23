package com.itantra.presentation.transceiver
import com.itantra.data.transport.DiscoveredPeer
import com.itantra.data.transport.TransportState
import com.itantra.data.perf.DevicePerf
import com.itantra.domain.model.Language
import com.itantra.domain.model.TransmitMode
import com.itantra.domain.model.Frame

enum class PttUiState { IDLE, LISTENING, SENDING, SENT, BUSY }

/** Connection status for an individual discovered peer. */
enum class PeerConnectionStatus {
    DISCOVERED,
    CONNECTING,
    CONNECTED,
    FAILED,
    DISCONNECTED
}

/** Transient SOS result shown in the Transmit zone for ~3 seconds, then auto-cleared. */
sealed class SosToastState {
    object Success : SosToastState()
    object Failure : SosToastState()
}

data class MessageItem(
    val frame: Frame,
    val isAlert: Boolean,
    /** True when the local user sent this message; false when received from a remote peer. */
    val isOutgoing: Boolean,
    val timestamp: Long,
    val durationSec: Double
)

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
  val floorHolderId: String? = null,
  val localIp: String = "127.0.0.1",
  val gatewayIp: String? = null,
  val discoveredPeers: List<DiscoveredPeer> = emptyList(),
  /** True while a user-initiated UDP discovery scan is actively running. */
  val isDiscoveryActive: Boolean = false,
  /** Non-null for ~3 seconds after SOS is triggered, then auto-cleared to null. */
  val sosToast: SosToastState? = null,

  // ── Feature A: Find My Phone ───────────────────────────────────────────────
  val findMyPhoneActive: Boolean = false,
  /** Estimated distance in metres from BLE RSSI; -1 = unknown. */
  val findMyPhoneDistanceMetres: Float = -1f,
  val findMyPhonePeerName: String = "",
  /** Non-null when BLE scan fails or device has no BLE support. */
  val findMyPhoneError: String? = null,

  // ── Feature B: Device Performance HUD ─────────────────────────────────────
  val devicePerf: DevicePerf? = null,
  val showPerfHud: Boolean = false,

  // ── Feature C: Locate via Siren ────────────────────────────────────────────
  val sirenLocateActive: Boolean = false,
  /** Estimated distance in metres from BLE RSSI during siren locate; -1 = unknown. */
  val sirenDistanceMetres: Float = -1f,
  val sirenFreqHz: Int = 440,

  // ── Feature D: Language Auto-Detection ─────────────────────────────────────
  /** When true, the ASR engine auto-detects language and updates [srcLang] on each result. */
  val isAutoLangDetect: Boolean = false,

  // ── Peer connection states ────────────────────────────────────────────────
  /** Independent connection status per discovered peer IP. */
  val peerConnectionStates: Map<String, PeerConnectionStatus> = emptyMap(),
) {
  fun peerConnectionStatus(peerIp: String): PeerConnectionStatus {
    return peerConnectionStates[peerIp] ?: PeerConnectionStatus.DISCOVERED
  }
}

