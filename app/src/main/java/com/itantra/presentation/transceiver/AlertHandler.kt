package com.itantra.presentation.transceiver

import com.itantra.data.audio.AlertAudioManager
import com.itantra.data.router.PriorityRouter
import com.itantra.domain.model.Frame

class AlertHandler(
  private val audioManager: AlertAudioManager,
  private val router: PriorityRouter,
  private val viewModel: TransceiverViewModel
) {
  fun onAlertFrame(frame: Frame) {
    // Route via PriorityRouter (should preempt)
    try { router.route(frame) } catch (_: Exception) {}
    // Acquire alarm focus and vibrate
    try { audioManager.acquireAlarmFocus() } catch (_: Exception) {}
    try { audioManager.vibrate(longArrayOf(0, 500, 500, 500)) } catch (_: Exception) {}
    // Update ViewModel state to show alert banner
    try { viewModel.process(TransceiverIntent.SendAlert(frame.payloadText)) } catch (_: Exception) {}
  }
}
