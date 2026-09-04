package com.itantra.domain.model

/**
 * Pure domain item for priority routing.
 * Wraps [Frame] and exposes routing-relevant fields.
 * No Android dependencies — test surface for PriorityRouter (PRD US-05 / FR-09).
 */
data class PlaybackItem(val frame: Frame) {
    val isAlert: Boolean get() = frame.isAlert
    val seqId: Int get() = frame.seqId
    val text: String get() = frame.payloadText
}
