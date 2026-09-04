package com.itantra.data.transport

/** Thrown when a frame fails Appendix B validation (magic, version, CRC, length, lang). */
class FrameCodecException(message: String, cause: Throwable? = null) : Exception(message, cause)
