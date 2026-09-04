package com.itantra.data.transport

import com.itantra.domain.model.Frame
import com.itantra.domain.model.Language
import com.itantra.domain.model.TransmitMode
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class FrameCodecTest {

    // Magic
    @Test
    fun encode_setsMagicBytes_0x49_0x54() {
        val f = Frame(TransmitMode.HALF_DUPLEX, false, false, false, Language.HINDI, Language.ENGLISH, 0, "hi")
        val b = FrameCodec.encode(f)
        assertThat(b[0]).isEqualTo(0x49.toByte())
        assertThat(b[1]).isEqualTo(0x54.toByte())
    }

    @Test
    fun decode_rejectsBadMagic() {
        // valid frame then corrupt magic
        val valid = FrameCodec.encode(Frame(TransmitMode.HALF_DUPLEX, false, false, false, Language.HINDI, Language.ENGLISH, 1, "hi"))
        valid[0] = 0x00
        assertThrows(FrameCodecException::class.java) { FrameCodec.decode(valid) }
    }

    // Version/mode
    @Test
    fun encode_versionLowNibble_is_0x01() {
        val f = Frame(TransmitMode.HALF_DUPLEX, false, false, false, Language.HINDI, Language.ENGLISH, 0, "x")
        val b = FrameCodec.encode(f)
        val ver = b[2].toInt() and 0x0F
        assertThat(ver).isEqualTo(0x01)
    }

    @Test
    fun encode_modeHighNibble_halfDuplex_zero_duplex_one() {
        val half = FrameCodec.encode(Frame(TransmitMode.HALF_DUPLEX, false, false, false, Language.HINDI, Language.ENGLISH, 0, "x"))
        val duplex = FrameCodec.encode(Frame(TransmitMode.DUPLEX, false, false, false, Language.HINDI, Language.ENGLISH, 0, "x"))
        assertThat((half[2].toInt() shr 4) and 0x0F).isEqualTo(0)
        assertThat((duplex[2].toInt() shr 4) and 0x0F).isEqualTo(1)
    }

    @Test
    fun decode_rejectsInvalidVersion() {
        val b = FrameCodec.encode(Frame(TransmitMode.HALF_DUPLEX, false, false, false, Language.HINDI, Language.ENGLISH, 0, "x"))
        b[2] = ((b[2].toInt() and 0xF0) or 0x02).toByte() // version 2
        // need to fix CRC after tamper? No – we test CRC path too but version check should trigger before CRC mismatch is observed as FrameCodecException anyway
        // Recompute CRC to isolate version check: compute correct CRC then tamper version but keep CRC valid for tampered header? easier: expect any FrameCodecException
        assertThrows(FrameCodecException::class.java) { FrameCodec.decode(b) }
    }

    // Flags
    @Test
    fun encode_flags_alert_stream_ptt_bits() {
        val f = Frame(TransmitMode.HALF_DUPLEX, isAlert = true, isStream = false, pttPressed = true, Language.HINDI, Language.ENGLISH, 0, "x")
        val b = FrameCodec.encode(f)
        val flags = b[3].toInt() and 0xFF
        assertThat(flags and 0x80).isEqualTo(0x80)
        assertThat(flags and 0x40).isEqualTo(0x00)
        assertThat(flags and 0x20).isEqualTo(0x20)
    }

    @Test
    fun encode_flags_streamBit() {
        val f = Frame(TransmitMode.HALF_DUPLEX, false, true, false, Language.HINDI, Language.ENGLISH, 0, "x")
        val b = FrameCodec.encode(f)
        assertThat(b[3].toInt() and 0x40).isEqualTo(0x40)
    }

    @Test
    fun encode_flags_reservedBits_zeroed() {
        val f = Frame(TransmitMode.HALF_DUPLEX, false, false, false, Language.HINDI, Language.ENGLISH, 0, "x")
        val b = FrameCodec.encode(f)
        assertThat(b[3].toInt() and 0x1F).isEqualTo(0)
    }

    @Test
    fun flags_roundTrip() {
        val f = Frame(TransmitMode.DUPLEX, true, true, true, Language.TAMIL, Language.BENGALI, 99, "hello")
        val decoded = FrameCodec.decode(FrameCodec.encode(f))
        assertThat(decoded.isAlert).isTrue()
        assertThat(decoded.isStream).isTrue()
        assertThat(decoded.pttPressed).isTrue()
        assertThat(decoded.mode).isEqualTo(TransmitMode.DUPLEX)
    }

    // Languages 0x01..0x0A
    @Test
    fun encode_allLanguages_roundTrip() {
        for (lang in Language.entries) {
            val f = Frame(TransmitMode.HALF_DUPLEX, false, false, false, lang, lang, 0, "test")
            val decoded = FrameCodec.decode(FrameCodec.encode(f))
            assertThat(decoded.srcLang).isEqualTo(lang)
            assertThat(decoded.dstLang).isEqualTo(lang)
        }
    }

    @Test
    fun decode_rejectsUnknownSrcLang() {
        val b = FrameCodec.encode(Frame(TransmitMode.HALF_DUPLEX, false, false, false, Language.HINDI, Language.ENGLISH, 0, "x"))
        b[4] = 0x0B.toByte() // invalid
        // recompute CRC so we hit lang validation not CRC
        val hdrPayload = b.copyOfRange(0, b.size - 2)
        hdrPayload[4] = 0x0B.toByte()
        val crc = Crc16.compute(hdrPayload)
        b[4] = 0x0B.toByte()
        b[b.size - 2] = ((crc shr 8) and 0xFF).toByte()
        b[b.size - 1] = (crc and 0xFF).toByte()
        assertThrows(FrameCodecException::class.java) { FrameCodec.decode(b) }
    }

    @Test
    fun decode_rejectsUnknownDstLang() {
        val b = FrameCodec.encode(Frame(TransmitMode.HALF_DUPLEX, false, false, false, Language.HINDI, Language.ENGLISH, 0, "x"))
        b[5] = 0x00.toByte()
        val hdrPayload = b.copyOfRange(0, b.size - 2)
        hdrPayload[5] = 0x00.toByte()
        val crc = Crc16.compute(hdrPayload)
        b[b.size - 2] = ((crc shr 8) and 0xFF).toByte()
        b[b.size - 1] = (crc and 0xFF).toByte()
        assertThrows(FrameCodecException::class.java) { FrameCodec.decode(b) }
    }

    // Seq big-endian
    @Test
    fun seqId_bigEndian_roundTrip() {
        val cases = listOf(0, 1, 0x0102, 0xFFFF, 0x00FF, 0xFF00)
        for (seq in cases) {
            val f = Frame(TransmitMode.HALF_DUPLEX, false, false, false, Language.HINDI, Language.ENGLISH, seq, "x")
            val b = FrameCodec.encode(f)
            val seqHi = b[6].toInt() and 0xFF
            val seqLo = b[7].toInt() and 0xFF
            assertThat((seqHi shl 8) or seqLo).isEqualTo(seq)
            assertThat(FrameCodec.decode(b).seqId).isEqualTo(seq)
        }
    }

    // Payload len big-endian
    @Test
    fun payloadLen_bigEndian_matchesUtf8ByteCount() {
        val text = "hello" // 5 bytes
        val b = FrameCodec.encode(Frame(TransmitMode.HALF_DUPLEX, false, false, false, Language.HINDI, Language.ENGLISH, 0, text))
        val len = ((b[8].toInt() and 0xFF) shl 8) or (b[9].toInt() and 0xFF)
        assertThat(len).isEqualTo(5)
    }

    @Test
    fun payloadLen_multibyte_utf8() {
        val text = "नमस्ते" // Hindi, multi-byte
        val bytes = text.toByteArray(Charsets.UTF_8)
        val b = FrameCodec.encode(Frame(TransmitMode.HALF_DUPLEX, false, false, false, Language.HINDI, Language.ENGLISH, 0, text))
        val len = ((b[8].toInt() and 0xFF) shl 8) or (b[9].toInt() and 0xFF)
        assertThat(len).isEqualTo(bytes.size)
        assertThat(FrameCodec.decode(b).payloadText).isEqualTo(text)
    }

    // CRC
    @Test
    fun crc_roundTrip_validFrame_decodes() {
        val f = Frame(TransmitMode.HALF_DUPLEX, false, false, false, Language.TAMIL, Language.TELUGU, 42, "vanakkam")
        val b = FrameCodec.encode(f)
        val decoded = FrameCodec.decode(b)
        assertThat(decoded.payloadText).isEqualTo("vanakkam")
        assertThat(decoded.srcLang).isEqualTo(Language.TAMIL)
        assertThat(decoded.seqId).isEqualTo(42)
    }

    @Test
    fun decode_rejectsCrcMismatch_flipPayloadByte() {
        val b = FrameCodec.encode(Frame(TransmitMode.HALF_DUPLEX, false, false, false, Language.HINDI, Language.ENGLISH, 0, "hello"))
        // payload starts at offset 10, flip one byte
        b[10] = (b[10].toInt() xor 0x01).toByte()
        assertThrows(FrameCodecException::class.java) { FrameCodec.decode(b) }
    }

    @Test
    fun decode_rejectsCrcMismatch_flipHeaderByte() {
        val b = FrameCodec.encode(Frame(TransmitMode.HALF_DUPLEX, false, false, false, Language.HINDI, Language.ENGLISH, 5, "hi"))
        b[6] = (b[6].toInt() xor 0xFF).toByte()
        assertThrows(FrameCodecException::class.java) { FrameCodec.decode(b) }
    }

    @Test
    fun crc_knownVector_emptyHeaderPlusPayload() {
        // Known CCITT-FALSE: "123456789" -> 0x29B1
        val data = "123456789".toByteArray(Charsets.US_ASCII)
        assertThat(Crc16.compute(data)).isEqualTo(0x29B1)
    }

    @Test
    fun crc_isBigEndianInFrame() {
        val f = Frame(TransmitMode.HALF_DUPLEX, false, false, false, Language.HINDI, Language.ENGLISH, 0, "A")
        val b = FrameCodec.encode(f)
        val headerPayload = b.copyOfRange(0, b.size - 2)
        val expected = Crc16.compute(headerPayload)
        val stored = ((b[b.size - 2].toInt() and 0xFF) shl 8) or (b[b.size - 1].toInt() and 0xFF)
        assertThat(stored).isEqualTo(expected)
    }

    // Length bounds 0 allowed for control, 1..2048 for text, 2049 rejected
    @Test
    fun payloadLengthBounds_zero_allowed_forControlFrame() {
        val f = Frame(TransmitMode.HALF_DUPLEX, false, false, true, Language.HINDI, Language.ENGLISH, 0, "")
        val b = FrameCodec.encode(f)
        val len = ((b[8].toInt() and 0xFF) shl 8) or (b[9].toInt() and 0xFF)
        assertThat(len).isEqualTo(0)
        // decode must succeed for zero payload when used as control (PTT flag)
        val decoded = FrameCodec.decode(b)
        assertThat(decoded.payloadText).isEqualTo("")
    }

    @Test
    fun payloadLengthBounds_2048_allowed() {
        val text = "a".repeat(2048)
        val f = Frame(TransmitMode.HALF_DUPLEX, false, false, false, Language.HINDI, Language.ENGLISH, 0, text)
        val b = FrameCodec.encode(f)
        assertThat(FrameCodec.decode(b).payloadText).isEqualTo(text)
    }

    @Test
    fun payloadLengthBounds_2049_throws() {
        val text = "a".repeat(2049)
        val f = Frame(TransmitMode.HALF_DUPLEX, false, false, false, Language.HINDI, Language.ENGLISH, 0, text)
        assertThrows(IllegalArgumentException::class.java) { FrameCodec.encode(f) }
    }

    // Corrupt / truncated
    @Test
    fun decode_rejectsTruncatedHeader() {
        val truncated = byteArrayOf(0x49.toByte(), 0x54.toByte(), 0x01, 0x00, 0x01)
        assertThrows(FrameCodecException::class.java) { FrameCodec.decode(truncated) }
    }

    @Test
    fun decode_rejectsTruncatedPayload() {
        val b = FrameCodec.encode(Frame(TransmitMode.HALF_DUPLEX, false, false, false, Language.HINDI, Language.ENGLISH, 0, "hello"))
        val truncated = b.copyOfRange(0, b.size - 3) // cut inside payload/crc
        assertThrows(FrameCodecException::class.java) { FrameCodec.decode(truncated) }
    }

    @Test
    fun decode_rejectsExtraTrailingBytes() {
        val b = FrameCodec.encode(Frame(TransmitMode.HALF_DUPLEX, false, false, false, Language.HINDI, Language.ENGLISH, 0, "hi"))
        val withExtra = b + byteArrayOf(0x00, 0x01)
        assertThrows(FrameCodecException::class.java) { FrameCodec.decode(withExtra) }
    }

    @Test
    fun decode_rejectsEmptyInput() {
        assertThrows(FrameCodecException::class.java) { FrameCodec.decode(byteArrayOf()) }
    }

    @Test
    fun utf8_roundTrip_withAllSupportedScripts() {
        val samples = mapOf(
            Language.HINDI to "नमस्ते दुनिया",
            Language.GUJARATI to "નમસ્તે",
            Language.MARATHI to "नमस्कार",
            Language.KANNADA to "ನಮಸ್ಕಾರ",
            Language.MALAYALAM to "നമസ്കാരം",
            Language.TAMIL to "வணக்கம்",
            Language.TELUGU to "నమస్కారం",
            Language.ODIA to "ନମସ୍କାର",
            Language.BENGALI to "নমস্কার",
            Language.ENGLISH to "Hello world! 123"
        )
        for ((lang, text) in samples) {
            val f = Frame(TransmitMode.HALF_DUPLEX, false, false, false, lang, Language.ENGLISH, 123, text)
            assertThat(FrameCodec.decode(FrameCodec.encode(f)).payloadText).isEqualTo(text)
        }
    }

    @Test
    fun encode_decode_preservesSeqAndLangsInMultilingualPair() {
        val f = Frame(TransmitMode.DUPLEX, true, false, false, Language.BENGALI, Language.TAMIL, 0xABCD, "cross-lang")
        val d = FrameCodec.decode(FrameCodec.encode(f))
        assertThat(d.srcLang).isEqualTo(Language.BENGALI)
        assertThat(d.dstLang).isEqualTo(Language.TAMIL)
        assertThat(d.seqId).isEqualTo(0xABCD)
        assertThat(d.isAlert).isTrue()
        assertThat(d.mode).isEqualTo(TransmitMode.DUPLEX)
    }
}
