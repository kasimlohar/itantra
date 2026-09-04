package com.itantra.data.harness

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal WAV loader for 16-bit PCM mono 16 kHz.
 * Pure Kotlin, offline, host-runnable, no Android deps.
 */
object WavLoader {
    data class WavInfo(val sampleRate: Int, val channels: Int, val frames: Int, val durationSec: Double)

    fun info(path: String): WavInfo {
        val file = resolveFile(path)
        require(file.exists()) { "WAV not found: $path" }
        FileInputStream(file).use { fis ->
            val all = fis.readBytes()
            require(all.size >= 44) { "invalid wav header: too short" }
            require(String(all, 0, 4) == "RIFF" && String(all, 8, 4) == "WAVE") { "not RIFF/WAVE" }
            // Find fmt chunk
            var fmtOffset = -1
            var dataOffset = -1
            var dataSize = -1
            // Search for chunks
            var pos = 12
            var audioFormat = -1
            var channels = -1
            var sampleRate = -1
            var bitsPerSample = -1
            val bbAll = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
            while (pos + 8 <= all.size) {
                val id = String(all, pos, 4)
                val size = bbAll.getInt(pos + 4)
                if (id == "fmt ") {
                    fmtOffset = pos
                    audioFormat = bbAll.getShort(pos + 8).toInt() and 0xFFFF
                    channels = bbAll.getShort(pos + 10).toInt() and 0xFFFF
                    sampleRate = bbAll.getInt(pos + 12)
                    bitsPerSample = bbAll.getShort(pos + 22).toInt() and 0xFFFF
                } else if (id == "data") {
                    dataOffset = pos + 8
                    dataSize = size
                    break
                }
                pos += 8 + size
                // pad byte if odd
                if (size % 2 == 1) pos += 1
            }
            require(fmtOffset >= 0) { "fmt chunk not found" }
            require(dataOffset >= 0) { "data chunk not found" }
            require(audioFormat == 1) { "only PCM supported, was $audioFormat" }
            require(bitsPerSample == 16) { "only 16-bit supported, was $bitsPerSample" }
            val frames = dataSize / (channels * 2)
            val dur = frames.toDouble() / sampleRate
            return WavInfo(sampleRate, channels, frames, dur)
        }
    }

    fun loadPcm16Mono16k(path: String): ShortArray {
        val file = resolveFile(path)
        require(file.exists()) { "WAV not found: $path" }
        FileInputStream(file).use { fis ->
            val all = fis.readBytes()
            // Find data chunk
            var dataOffset = -1
            var dataSize = -1
            var pos = 12
            val bbAll = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
            while (pos + 8 <= all.size) {
                val id = String(all, pos, 4)
                val size = bbAll.getInt(pos + 4)
                if (id == "data") {
                    dataOffset = pos + 8
                    dataSize = size
                    break
                }
                pos += 8 + size
                if (size % 2 == 1) pos += 1
            }
            require(dataOffset >= 0) { "data chunk not found" }
            val pcmBytes = all.copyOfRange(dataOffset, dataOffset + dataSize)
            val totalShorts = pcmBytes.size / 2
            val shorts = ShortArray(totalShorts)
            ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
            val info = info(file.absolutePath)
            // Handle stereo -> mono by averaging
            val mono = if (info.channels == 1) {
                shorts
            } else {
                val frames = info.frames
                val monoArr = ShortArray(frames)
                for (i in 0 until frames) {
                    var sum = 0
                    for (ch in 0 until info.channels) {
                        sum += shorts[i * info.channels + ch].toInt()
                    }
                    monoArr[i] = (sum / info.channels).toShort()
                }
                monoArr
            }
            require(info.sampleRate == 16000) { "only 16k supported, was ${info.sampleRate}" }
            return mono
        }
    }

    private fun resolveFile(path: String): File {
        val direct = File(path)
        if (direct.exists()) return direct
        // Try with app prefix for test runner working dir differences
        val withApp = File("app/$path")
        if (withApp.exists()) return withApp
        // If path contains hi_sample, try known locations
        if (path.contains("hi_sample")) {
            val candidates = listOf(
                "app/src/test/resources/hi_sample.wav",
                "src/test/resources/hi_sample.wav",
                "D:/SIH 2026/itantra/app/src/test/resources/hi_sample.wav"
            )
            for (c in candidates) {
                val f = File(c)
                if (f.exists()) return f
            }
            // try classloader resource
            try {
                val stream = this::class.java.classLoader.getResourceAsStream("hi_sample.wav")
                if (stream != null) {
                    stream.use { s ->
                        val tmp = File.createTempFile("hi_sample", ".wav")
                        tmp.deleteOnExit()
                        tmp.outputStream().use { it.write(s.readBytes()) }
                        if (tmp.length() > 0) return tmp
                    }
                }
            } catch (_: Exception) {}
        }
        return direct
    }

    fun loadPcmFromResource(name: String): ShortArray {
        val stream = this::class.java.classLoader.getResourceAsStream(name)
            ?: throw IllegalArgumentException("resource not found: $name")
        val tmp = File.createTempFile("wav", ".wav")
        tmp.deleteOnExit()
        tmp.outputStream().use { it.write(stream.readBytes()) }
        return loadPcm16Mono16k(tmp.absolutePath)
    }
}
