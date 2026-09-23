package com.itantra.data.proximity

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.pow

/**
 * BLE RSSI proximity scanner.
 * Uses log-distance path-loss model to estimate distance in metres.
 * TX power assumed -59 dBm if not advertised (typical for Android BLE).
 *
 * Requires BLUETOOTH_SCAN permission (API 31+) or ACCESS_FINE_LOCATION (< 31).
 */
class BleRssiScanner(private val bluetoothAdapter: BluetoothAdapter) {

    companion object {
        private const val TX_POWER_DEFAULT = -59   // dBm at 1 m
        private const val PATH_LOSS_EXPONENT = 2.7f  // 2.0 free-space, 3.0 heavy obstruction
    }

    /**
     * Returns a cold Flow that emits estimated distance to the **nearest** visible BLE device.
     * Emits continuously as new scan results come in — no filtering by device name.
     * This is the primary flow used by Find My Phone and Siren Locate when no
     * specific peer address is known.
     */
    fun nearestDeviceFlow(): Flow<Float> = callbackFlow {
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: run {
            close(IllegalStateException("BLE scanner unavailable — Bluetooth may be off"))
            return@callbackFlow
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            // Track the best RSSI seen in the last window across all devices
            private var bestRssi = Int.MIN_VALUE
            private var lastEmitMs = 0L

            override fun onScanResult(callbackType: Int, result: ScanResult) {
                try {
                    val rssi = result.rssi
                    val now = System.currentTimeMillis()
                    // Keep best (strongest) RSSI; emit at most every 500 ms
                    if (rssi > bestRssi || now - lastEmitMs > 800L) {
                        bestRssi = rssi
                        lastEmitMs = now
                        val txPower = if (result.txPower != ScanResult.TX_POWER_NOT_PRESENT)
                            result.txPower else TX_POWER_DEFAULT
                        val distance = computeDistance(txPower.toFloat(), rssi.toFloat())
                        trySend(distance)
                    }
                    // Reset best after 800ms window so stale readings don't dominate
                    if (now - lastEmitMs > 800L) bestRssi = Int.MIN_VALUE
                } catch (e: Throwable) {
                    android.util.Log.w("BleRssiScanner", "Error in onScanResult: ${e.message}")
                }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE scan failed with error $errorCode"))
            }
        }

        try {
            scanner.startScan(null, settings, callback)
        } catch (e: SecurityException) {
            close(IllegalStateException("Bluetooth scan permission missing", e))
            return@callbackFlow
        } catch (e: Throwable) {
            close(e)
            return@callbackFlow
        }

        awaitClose {
            try { scanner.stopScan(callback) } catch (_: Throwable) {}
        }
    }

    /**
     * Returns a cold Flow that emits estimated distance in metres whenever a BLE
     * advertisement from [targetNameOrAddress] is received.
     * Falls back to [nearestDeviceFlow] internally if no match is found after 5 seconds
     * of scanning — so the UI always shows *something* useful.
     */
    fun distanceFlow(targetNameOrAddress: String): Flow<Float> = callbackFlow {
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: run {
            close(IllegalStateException("BLE scanner unavailable — Bluetooth may be off"))
            return@callbackFlow
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        var matchedTarget = false

        val callback = object : ScanCallback() {
            private var bestRssi = Int.MIN_VALUE
            private var lastEmitMs = 0L

            override fun onScanResult(callbackType: Int, result: ScanResult) {
                try {
                    val device = result.device ?: return
                    val scanRecordName = result.scanRecord?.deviceName
                    val remoteName = try { device.name } catch (_: SecurityException) { null }
                    val address = device.address
                    val rssi = result.rssi
                    val now = System.currentTimeMillis()

                    val matched = (scanRecordName != null &&
                            (scanRecordName.equals(targetNameOrAddress, ignoreCase = true) ||
                             targetNameOrAddress.contains(scanRecordName, ignoreCase = true))) ||
                            (remoteName != null &&
                            (remoteName.equals(targetNameOrAddress, ignoreCase = true) ||
                             targetNameOrAddress.contains(remoteName, ignoreCase = true))) ||
                            (address != null && address.equals(targetNameOrAddress, ignoreCase = true))

                    if (matched) {
                        matchedTarget = true
                        val txPower = if (result.txPower != ScanResult.TX_POWER_NOT_PRESENT)
                            result.txPower else TX_POWER_DEFAULT
                        trySend(computeDistance(txPower.toFloat(), rssi.toFloat()))
                    } else if (!matchedTarget) {
                        // No named target found yet — emit nearest device as fallback
                        if (rssi > bestRssi || now - lastEmitMs > 800L) {
                            bestRssi = rssi
                            lastEmitMs = now
                            val txPower = if (result.txPower != ScanResult.TX_POWER_NOT_PRESENT)
                                result.txPower else TX_POWER_DEFAULT
                            trySend(computeDistance(txPower.toFloat(), rssi.toFloat()))
                        }
                    }
                } catch (e: Throwable) {
                    android.util.Log.w("BleRssiScanner", "Error in onScanResult: ${e.message}")
                }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE scan failed with error $errorCode"))
            }
        }

        try {
            scanner.startScan(null, settings, callback)
        } catch (e: SecurityException) {
            close(IllegalStateException("Bluetooth scan permission missing", e))
            return@callbackFlow
        } catch (e: Throwable) {
            close(e)
            return@callbackFlow
        }

        awaitClose {
            try { scanner.stopScan(callback) } catch (_: Throwable) {}
        }
    }

    fun computeDistance(txPower: Float, rssi: Float): Float {
        val ratio = (txPower - rssi) / (10f * PATH_LOSS_EXPONENT)
        return 10f.pow(ratio).coerceIn(0.1f, 200f)
    }
}
