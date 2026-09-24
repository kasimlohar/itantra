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
  data object RefreshNetwork : TransceiverIntent
  /** Restart UDP peer discovery scan — used by the Radar Search Peers button. */
  data object RestartDiscovery : TransceiverIntent

  // ── Feature A: Find My Phone ─────────────────────────────────────────────
  /** Rescuer starts BLE RSSI homing toward the SOS sender. */
  data object StartFindMyPhone : TransceiverIntent
  data object StopFindMyPhone : TransceiverIntent

  // ── Feature B: Device Performance HUD ────────────────────────────────────
  /** Toggle the Performance HUD card expanded/collapsed. */
  data object TogglePerfHud : TransceiverIntent

  // ── Feature C: Locate via Siren ──────────────────────────────────────────
  /** Rescuer starts proximity siren (frequency increases as they get closer). */
  data object StartSirenLocate : TransceiverIntent
  data object StopSirenLocate : TransceiverIntent
  /** Victim sends a siren frame to trigger the rescuer's device to play a siren. */
  data object TriggerRemoteSiren : TransceiverIntent
  /** Sends a siren-stop command to the target device. */
  data object StopRemoteSiren : TransceiverIntent

  // ── Feature D: Language Auto-Detection ───────────────────────────────────
  /** Toggle automatic language detection on/off. */
  data object ToggleAutoLangDetect : TransceiverIntent
}

