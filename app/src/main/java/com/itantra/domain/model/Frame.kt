package com.itantra.domain.model

/**
 * Pure domain frame per PRD FR-06 / Appendix B.
 * Codec is responsible for 12-byte minimal framing validation; this model holds semantic fields.
 */
data class Frame(
    val mode: TransmitMode,
    val isAlert: Boolean,
    val isStream: Boolean,
    val pttPressed: Boolean,
    val srcLang: Language,
    val dstLang: Language,
    val seqId: Int,
    val payloadText: String,
    /** True when this frame carries a siren-locate signal (FLAG_SIREN bit4). */
    val isSiren: Boolean = false,
) {
    init {
        require(seqId in 0..65535) { "seqId must be 0..65535, got $seqId" }
    }
}
