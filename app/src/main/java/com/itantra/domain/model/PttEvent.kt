package com.itantra.domain.model

/**
 * Pure events for PttStateMachine per PRD US-01 / FR-08.
 * No Android types — hardware VOLUME_DOWN binding deferred.
 */
sealed class PttEvent {
    data class SendControlFrame(val frame: Frame) : PttEvent()
    object FloorGranted : PttEvent()
    object FloorDenied : PttEvent()
    object ChannelBusy : PttEvent()
    data class FloorReleased(val frame: Frame) : PttEvent()
    object RemoteGranted : PttEvent()
    object RemoteReleased : PttEvent()
    object Debounced : PttEvent()
    object Ignored : PttEvent()
}
