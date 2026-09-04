package com.itantra.data.transport

/**
 * CRC-16-CCITT (poly 0x1021, init 0xFFFF, no xorOut, no refin) – PRD Appendix B.
 * Known test vector: "123456789" → 0x29B1 (CCITT-FALSE).
 * Used over header+payload, stored BE.
 */
object Crc16 {
    private const val POLY = 0x1021
    private const val INIT = 0xFFFF

    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        require(offset >= 0 && length >= 0 && offset + length <= data.size)
        var crc = INIT
        for (i in offset until offset + length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) {
                    ((crc shl 1) xor POLY) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc and 0xFFFF
    }
}
