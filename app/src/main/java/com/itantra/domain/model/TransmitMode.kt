package com.itantra.domain.model

/**
 * Channel mode encoded in header Byte 2 high nibble per Appendix B.
 * 0 = Walkie-Talkie Half-Duplex, 1 = Continuous Phone Duplex.
 */
enum class TransmitMode(val bit: Int) {
    HALF_DUPLEX(0),
    DUPLEX(1);

    companion object {
        fun fromBit(bit: Int): TransmitMode = when (bit) {
            0 -> HALF_DUPLEX
            1 -> DUPLEX
            else -> throw IllegalArgumentException("Invalid mode bit: $bit")
        }
    }
}
