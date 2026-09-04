package com.itantra.data.router

import com.itantra.domain.model.PlaybackItem

/**
 * Pure decision returned by [PriorityRouter.route].
 * No Android types — defer STREAM_ALARM / AudioManager to later layer.
 */
sealed class RouteDecision {
    data class PlayNow(val item: PlaybackItem, val preempted: PlaybackItem? = null) : RouteDecision()
    data class Enqueue(val item: PlaybackItem) : RouteDecision()
    object Drop : RouteDecision()
}
