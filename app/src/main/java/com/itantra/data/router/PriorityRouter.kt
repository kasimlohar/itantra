package com.itantra.data.router

import com.itantra.domain.model.PlaybackItem

/**
 * Pure priority router per PRD US-05 AC-05.1 / AC-05.2 and FR-09.
 *
 * Policy (documented for verification):
 * - Standard frames (isAlert=false) are FIFO. If nothing is playing, PlayNow; else Enqueue to pendingStandard.
 * - Alert frames (isAlert=true) preempt any ongoing or queued standard items:
 *   - If idle (current==null): PlayNow alert.
 *   - If current is standard: Drop current standard, clear all pendingStandard, set current=alert, PlayNow(preempted=oldCurrent).
 *   - If current is alert (non-interruptible): Enqueue alert to pendingAlert FIFO, do not preempt current.
 * - After alert finishes via onPlaybackFinished(), router advances to next pendingAlert if any, else next pendingStandard if any, else idle. Previously cleared standard pending is NOT restored.
 * - clear() resets current and both pending queues.
 * - pending() exposes combined view: pendingAlert (first) + pendingStandard (in arrival order). Exposed for tests; UI layer may filter.
 * - No Android dependencies; defer AudioManager/STREAM_ALARM to later layer.
 */
class PriorityRouter {
    private var current: PlaybackItem? = null
    private val pendingStandard = mutableListOf<PlaybackItem>()
    private val pendingAlert = mutableListOf<PlaybackItem>()

    fun route(frame: com.itantra.domain.model.Frame): RouteDecision {
        val item = PlaybackItem(frame)
        return if (item.isAlert) {
            handleAlert(item)
        } else {
            handleStandard(item)
        }
    }

    private fun handleAlert(item: PlaybackItem): RouteDecision {
        if (current == null) {
            current = item
            return RouteDecision.PlayNow(item, null)
        }
        val cur = current
        return if (cur != null && cur.isAlert) {
            // current alert is non-interruptible; enqueue behind
            pendingAlert.add(item)
            RouteDecision.Enqueue(item)
        } else {
            // preempt standard (current + pendingStandard)
            val preempted = current
            pendingStandard.clear()
            current = item
            RouteDecision.PlayNow(item, preempted)
        }
    }

    private fun handleStandard(item: PlaybackItem): RouteDecision {
        if (current == null) {
            current = item
            return RouteDecision.PlayNow(item, null)
        }
        pendingStandard.add(item)
        return RouteDecision.Enqueue(item)
    }

    fun onPlaybackFinished() {
        current = when {
            pendingAlert.isNotEmpty() -> pendingAlert.removeAt(0)
            pendingStandard.isNotEmpty() -> pendingStandard.removeAt(0)
            else -> null
        }
    }

    fun current(): PlaybackItem? = current

    fun pending(): List<PlaybackItem> {
        // Alerts have priority in pending view as well (FIFO within each bucket)
        return pendingAlert.toList() + pendingStandard.toList()
    }

    fun clear() {
        current = null
        pendingStandard.clear()
        pendingAlert.clear()
    }
}
