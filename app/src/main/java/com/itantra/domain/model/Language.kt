package com.itantra.domain.model

/**
 * Supported STT/TTS languages per PRD Appendix B + US-06.
 * Code values are authoritative: Hindi=01 … English=0A.
 */
enum class Language(val code: Byte) {
    HINDI(0x01),
    GUJARATI(0x02),
    MARATHI(0x03),
    KANNADA(0x04),
    MALAYALAM(0x05),
    TAMIL(0x06),
    TELUGU(0x07),
    ODIA(0x08),
    BENGALI(0x09),
    ENGLISH(0x0A);

    companion object {
        fun fromCode(code: Byte): Language? = entries.find { it.code == code }
        fun fromCodeOrThrow(code: Byte): Language =
            fromCode(code) ?: throw IllegalArgumentException("Unknown language code: 0x%02X".format(code))
    }
}
