package com.itantra.data.proximity

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID
import kotlin.math.pow

/**
 * BLE RSSI proximity scanner and beacon transmitter for Rescue Beacon.
 * Uses log-distance path-loss model to estimate distance in metres.
 */
class BleRssiScanner(private val bluetoothAdapter: BluetoothAdapter) {

    companion object {
        /** 128-bit Service UUID identifying iTantra Rescue Beacons. */
        val BEACON_SERVICE_UUID: UUID = UUID.fromString("0000FEAA-0000-1000-8000-00805F9B34FB")

        /** RSSI at 1 m reference distance (dBm). Calibrate with two phones 1 m apart. */
        private const val TX_POWER_DEFAULT = -59

        /** Path-loss exponent n. Use 2.5 for typical indoor handheld use. */
        private const val PATH_LOSS_EXPONENT = 2.5f

        /** EMA smoothing factor (0 < alpha ≤ 1). */
        private const val EMA_ALPHA = 0.25f

        /** RSSI at or below this is treated as no signal / invalid. */
        private const val MIN_VALID_RSSI = -100

        /** Maximum believable distance. Clamp output to this. */
        private const val MAX_DISTANCE_METRES = 100f

        /** Minimum believable distance. Sub-cm readings are physically impossible. */
        private const val MIN_DISTANCE_METRES = 0.1f

        /**
         * Sentinel value meaning "RSSI not available yet / out of range".
         * UI should show "Searching…" when state holds this value.
         */
        const val DISTANCE_UNKNOWN = -1f

        /** Minimum milliseconds between emitted values to avoid UI churn. */
        private const val EMIT_INTERVAL_MS = 300L
    }

    private var advertiseCallback: AdvertiseCallback? = null

    /**
     * Broadcasts an iTantra Rescue Beacon over BLE so nearby rescuers can home in.
     */
    fun startBeacon() {
        if (!bluetoothAdapter.isEnabled) return
        val advertiser = try { bluetoothAdapter.bluetoothLeAdvertiser } catch (_: Throwable) { null } ?: return
        if (advertiseCallback != null) return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BEACON_SERVICE_UUID))
            .build()

        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                android.util.Log.i("BleRssiScanner", "iTantra BLE Beacon started")
            }
            override fun onStartFailure(errorCode: Int) {
                android.util.Log.w("BleRssiScanner", "BLE Beacon start failed: $errorCode")
            }
        }
        try {
            advertiser.startAdvertising(settings, data, cb)
            advertiseCallback = cb
        } catch (e: Throwable) {
            android.util.Log.w("BleRssiScanner", "Error starting BLE Beacon", e)
        }
    }

    /**
     * Stops broadcasting the iTantra Rescue Beacon.
     */
    fun stopBeacon() {
        val cb = advertiseCallback ?: return
        try {
            bluetoothAdapter.bluetoothLeAdvertiser?.stopAdvertising(cb)
        } catch (_: Throwable) {}
        advertiseCallback = null
    }

    /**
     * Main proximity scan flow.
     * Prioritizes iTantra Beacons, matches target name if available, and seamlessly
     * falls back to the nearest active BLE device so the screen never gets stuck on "Measuring…".
     */
    fun scanFlow(targetNameOrAddress: String? = null): Flow<Float> = callbackFlow {
        if (!bluetoothAdapter.isEnabled) {
            close(IllegalStateException("Bluetooth is turned off"))
            return@callbackFlow
        }
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: run {
            close(IllegalStateException("BLE scanner unavailable — Bluetooth may be off"))
            return@callbackFlow
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        val smoothedRssiByAddress = mutableMapOf<String, Float>()
        var beaconAddress: String? = null
        var lastEmitMs = 0L

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                try {
                    val rawRssi = result.rssi
                    val device = result.device ?: return
                    val address = device.address ?: return
                    val scanRecord = result.scanRecord

                    if (rawRssi <= MIN_VALID_RSSI) return

                    // Check if this advertisement is an iTantra Rescue Beacon
                    val isItantraBeacon = scanRecord?.serviceUuids?.any {
                        it.uuid == BEACON_SERVICE_UUID
                    } == true

                    val scanRecordName = scanRecord?.deviceName
                    val remoteName = try { device.name } catch (_: SecurityException) { null }

                    val isTargetMatch = if (!targetNameOrAddress.isNullOrBlank()) {
                        address.equals(targetNameOrAddress, ignoreCase = true) ||
                        (scanRecordName != null && scanRecordName.contains(targetNameOrAddress, ignoreCase = true)) ||
                        (remoteName != null && remoteName.contains(targetNameOrAddress, ignoreCase = true))
                    } else false

                    if (isItantraBeacon) {
                        beaconAddress = address
                    }

                    val prev = smoothedRssiByAddress[address] ?: rawRssi.toFloat()
                    val smoothed = EMA_ALPHA * rawRssi + (1f - EMA_ALPHA) * prev
                    smoothedRssiByAddress[address] = smoothed

                    val now = System.currentTimeMillis()
                    if (now - lastEmitMs >= EMIT_INTERVAL_MS) {
                        lastEmitMs = now

                        val bestSmoothed = when {
                            beaconAddress != null && smoothedRssiByAddress.containsKey(beaconAddress) ->
                                smoothedRssiByAddress[beaconAddress]
                            isTargetMatch ->
                                smoothed
                            smoothedRssiByAddress.isNotEmpty() ->
                                smoothedRssiByAddress.values.maxOrNull()
                            else -> null
                        } ?: return

                        val txPower = if (result.txPower != ScanResult.TX_POWER_NOT_PRESENT)
                            result.txPower else TX_POWER_DEFAULT
                        val distance = computeDistance(txPower.toFloat(), bestSmoothed)
                        trySend(distance)
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

    fun nearestDeviceFlow(): Flow<Float> = scanFlow(null)

    fun distanceFlow(targetNameOrAddress: String): Flow<Float> = scanFlow(targetNameOrAddress)

    /**
     * Log-distance path-loss model:
     *   distance = 10 ^ ((txPower - rssi) / (10 * n))
     *
     * @param txPower  RSSI at 1 m reference (dBm), from ad packet or TX_POWER_DEFAULT
     * @param rssi     EMA-smoothed measured RSSI (dBm)
     * @return         Estimated distance in metres, clamped to valid range
     */
    fun computeDistance(txPower: Float, rssi: Float): Float {
        val ratio = (txPower - rssi) / (10f * PATH_LOSS_EXPONENT)
        return 10f.pow(ratio).coerceIn(MIN_DISTANCE_METRES, MAX_DISTANCE_METRES)
    }
}
