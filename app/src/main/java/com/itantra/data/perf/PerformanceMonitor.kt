package com.itantra.data.perf

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Environment
import android.os.StatFs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Snapshot of device performance metrics, sampled at a fixed interval.
 * pipelineLatencyMs is overlaid from ViewModel state (not measured here).
 */
data class DevicePerf(
    val ramUsedMb: Long,
    val ramTotalMb: Long,
    /** App-only RSS memory in MB (from /proc/self/status VmRSS). */
    val appRamMb: Long,
    val cpuPercent: Float,          // kept for compatibility, not shown in HUD
    val storageFreeGb: Float,
    val storageTotalGb: Float,
    /** App-only storage used (files + cache dirs) in MB. */
    val appStorageUsedMb: Long,
    /** Total pipeline latency VAD→STT→TX→TTS in ms; -1 = not yet measured. Injected from ViewModel. */
    val pipelineLatencyMs: Long = -1L,
    val lastAsrRtf: Float = -1f     // kept for backward compat
)

/**
 * PerformanceMonitor — samples RAM, CPU, and internal storage on a regular interval
 * and emits [DevicePerf] values via a cold Flow.
 *
 * All reads are purely local — no internet, no IPC beyond the kernel /proc filesystem.
 */
@Singleton
class PerformanceMonitor @Inject constructor(
    private val context: Context
) {
    /** Emits an updated [DevicePerf] every [intervalMs] milliseconds. */
    fun perfFlow(intervalMs: Long = 1000L): Flow<DevicePerf> = flow {
        var prevIdle = 0L
        var prevTotal = 0L
        while (true) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            val totalRam = memInfo.totalMem / (1024L * 1024L)
            val freeRam  = memInfo.availMem  / (1024L * 1024L)
            val usedRam  = totalRam - freeRam

            // App-only RSS from /proc/self/status (VmRSS line)
            val appRamMb = readAppRssKb() / 1024L

            val (cpuPercent, newIdle, newTotal) = readCpuUsage(prevIdle, prevTotal)
            prevIdle  = newIdle
            prevTotal = newTotal

            val statFs = StatFs(Environment.getDataDirectory().path)
            val freeBytes  = statFs.availableBlocksLong * statFs.blockSizeLong
            val totalBytes = statFs.blockCountLong       * statFs.blockSizeLong

            // App-only storage: sum of files dir + cache dir
            val appStorageUsedMb = measureAppStorageMb()

            emit(
                DevicePerf(
                    ramUsedMb         = usedRam,
                    ramTotalMb        = totalRam,
                    appRamMb          = appRamMb,
                    cpuPercent        = cpuPercent,
                    storageFreeGb     = freeBytes  / 1_073_741_824f,
                    storageTotalGb    = totalBytes / 1_073_741_824f,
                    appStorageUsedMb  = appStorageUsedMb
                )
            )
            delay(intervalMs)
        }
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private data class CpuSample(val percent: Float, val idle: Long, val total: Long)

    private fun readCpuUsage(prevIdle: Long, prevTotal: Long): CpuSample {
        return try {
            val line = BufferedReader(FileReader("/proc/stat")).use { it.readLine() }
            // cpu  user nice system idle iowait irq softirq steal guest guest_nice
            val parts = line.trim().split("\\s+".toRegex()).drop(1).map { it.toLong() }
            val idle  = parts[3] + parts[4]   // idle + iowait
            val total = parts.sum()
            val diffIdle  = idle  - prevIdle
            val diffTotal = total - prevTotal
            val percent = if (diffTotal == 0L) 0f
                          else (1f - diffIdle.toFloat() / diffTotal.toFloat()) * 100f
            CpuSample(percent.coerceIn(0f, 100f), idle, total)
        } catch (_: Exception) {
            CpuSample(0f, prevIdle, prevTotal)
        }
    }

    /** Read app process RSS from /proc/self/status in KB. Falls back to Debug API. */
    private fun readAppRssKb(): Long {
        return try {
            BufferedReader(FileReader("/proc/self/status")).useLines { lines ->
                lines.firstOrNull { it.startsWith("VmRSS:") }
                    ?.split("\\s+".toRegex())
                    ?.getOrNull(1)
                    ?.toLongOrNull() ?: 0L
            }
        } catch (_: Exception) {
            // Fallback: native heap + Java heap via Debug API
            val nativeKb = Debug.getNativeHeapAllocatedSize() / 1024L
            val javaKb   = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } / 1024L
            nativeKb + javaKb
        }
    }

    /** Measure app-only storage: files dir + cache dir in MB. */
    private fun measureAppStorageMb(): Long {
        fun dirSizeBytes(dir: File?): Long {
            if (dir == null || !dir.exists()) return 0L
            return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
        val bytes = dirSizeBytes(context.filesDir) + dirSizeBytes(context.cacheDir)
        return bytes / (1024L * 1024L)
    }
}
