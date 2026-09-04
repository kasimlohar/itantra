package com.itantra.domain.model

/**
 * PTT floor states per PRD US-01 / FR-08.
 * Pure domain, no Android deps.
 */
enum class PttState {
    Idle,
    Holding,
    Busy
}
