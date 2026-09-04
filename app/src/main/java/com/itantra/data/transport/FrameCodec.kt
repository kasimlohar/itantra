package com.itantra.data.transport

import com.itantra.domain.model.Frame
import com.itantra.domain.model.Language
import com.itantra.domain.model.TransmitMode

/**
 * Pure Kotlin codec for PRD Appendix B framing.
 *
 * Wire format (all multi-byte big-endian):
 * [0] 0x49, [1] 0x54 (magic "IT")
 * [2] ver(lo 4b = 0x01) | mode(hi 4b 0=HALF 1=DUPLEX)
 * [3] flags: bit7 ALERT | bit6 STREAM | bit5 PTT | bits4-0 RSV=0
 * [4] srcLang 0x01..0x0A
 * [5] dstLang 0x01..0x0A
 * [6-7] seqId BE uint16
 * [8-9] payloadLen BE uint16 (0..2048; 0 allowed for PTT control, FR-06 text = 1..2048)
 * [10..10+len-1] UTF-8 payload
 * [10+len .. 11+len] CRC-16-CCITT BE over header(10)+payload
 *
 * Pure, no Android deps – interface is test surface for transport (PRD 4.1.1).
 */
object FrameCodec {
    private const val MAGIC_0: Byte = 0x49
    private const val MAGIC_1: Byte = 0x54
    private const val VERSION: Int = 0x01
    private const val HEADER_SIZE = 10
    private const val CRC_SIZE = 2
    private const val MIN_FRAME_SIZE = HEADER_SIZE + CRC_SIZE // 12, payload 0
    private const val MAX_PAYLOAD = 2048

    private val VALID_LANG_CODES: Set<Byte> = Language.entries.map { it.code }.toSet()

    fun encode(frame: Frame): ByteArray {
        val payload = frame.payloadText.toByteArray(Charsets.UTF_8)
        if (payload.size > MAX_PAYLOAD) {
            throw IllegalArgumentException("payload too large: ${payload.size} > $MAX_PAYLOAD")
        }
        // Header 10 bytes
        val out = ByteArray(HEADER_SIZE + payload.size + CRC_SIZE)
        out[0] = MAGIC_0
        out[1] = MAGIC_1
        val modeBit = frame.mode.bit and 0x0F
        out[2] = ((modeBit shl 4) or (VERSION and 0x0F)).toByte()
        var flags = 0
        if (frame.isAlert) flags = flags or 0x80
        if (frame.isStream) flags = flags or 0x40
        if (frame.pttPressed) flags = flags or 0x20
        // bits 4-0 reserved zero
        out[3] = flags.toByte()
        out[4] = frame.srcLang.code
        out[5] = frame.dstLang.code
        out[6] = ((frame.seqId shr 8) and 0xFF).toByte()
        out[7] = (frame.seqId and 0xFF).toByte()
        out[8] = ((payload.size shr 8) and 0xFF).toByte()
        out[9] = (payload.size and 0xFF).toByte()
        System.arraycopy(payload, 0, out, HEADER_SIZE, payload.size)
        val crc = Crc16.compute(out, 0, HEADER_SIZE + payload.size)
        out[HEADER_SIZE + payload.size] = ((crc shr 8) and 0xFF).toByte()
        out[HEADER_SIZE + payload.size + 1] = (crc and 0xFF).toByte()
        return out
    }

    fun decode(bytes: ByteArray): Frame {
        if (bytes.size < MIN_FRAME_SIZE) {
            throw FrameCodecException("frame too short: ${bytes.size} < $MIN_FRAME_SIZE")
        }
        if (bytes[0] != MAGIC_0 || bytes[1] != MAGIC_1) {
            throw FrameCodecException("bad magic: expected 0x49 0x54, got 0x%02X 0x%02X".format(bytes[0], bytes[1]))
        }
        val version = bytes[2].toInt() and 0x0F
        if (version != VERSION) {
            throw FrameCodecException("unsupported version: $version != $VERSION")
        }
        val modeBit = (bytes[2].toInt() ushr 4) and 0x0F
        val mode = try {
            TransmitMode.fromBit(modeBit)
        } catch (e: IllegalArgumentException) {
            throw FrameCodecException("invalid mode bit: $modeBit", e)
        }

        val flags = bytes[3].toInt() and 0xFF
        val isAlert = (flags and 0x80) != 0
        val isStream = (flags and 0x40) != 0
        val pttPressed = (flags and 0x20) != 0
        // reserved bits are ignored on decode (but encode zeroes them)

        val srcCode = bytes[4]
        val dstCode = bytes[5]
        if (srcCode !in VALID_LANG_CODES) {
            throw FrameCodecException("unknown srcLang code: 0x%02X".format(srcCode))
        }
        if (dstCode !in VALID_LANG_CODES) {
            throw FrameCodecException("unknown dstLang code: 0x%02X".format(dstCode))
        }
        val srcLang = Language.fromCode(srcCode)!!
        val dstLang = Language.fromCode(dstCode)!!

        val seqId = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
        val payloadLen = ((bytes[8].toInt() and 0xFF) shl 8) or (bytes[9].toInt() and 0xFF)

        if (payloadLen > MAX_PAYLOAD) {
            throw FrameCodecException("payload length exceeds $MAX_PAYLOAD: $payloadLen")
        }
        val expectedSize = HEADER_SIZE + payloadLen + CRC_SIZE
        if (bytes.size != expectedSize) {
            throw FrameCodecException(
                "length mismatch: header declares payloadLen=$payloadLen => expected total $expectedSize, got ${bytes.size}"
            )
        }

        val computed = Crc16.compute(bytes, 0, HEADER_SIZE + payloadLen)
        val stored = ((bytes[HEADER_SIZE + payloadLen].toInt() and 0xFF) shl 8) or
            (bytes[HEADER_SIZE + payloadLen + 1].toInt() and 0xFF)
        if (computed != stored) {
            throw FrameCodecException("CRC mismatch: computed 0x%04X != stored 0x%04X".format(computed, stored))
        }

        val payloadBytes = if (payloadLen == 0) ByteArray(0) else bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + payloadLen)
        val text = try {
            String(payloadBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            throw FrameCodecException("invalid UTF-8 payload", e)
        }

        return Frame(
            mode = mode,
            isAlert = isAlert,
            isStream = isStream,
            pttPressed = pttPressed,
            srcLang = srcLang,
            dstLang = dstLang,
            seqId = seqId,
            payloadText = text,
        )
    }
}
