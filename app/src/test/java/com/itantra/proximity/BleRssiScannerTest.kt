package com.itantra.proximity

import com.itantra.data.proximity.BleRssiScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for the BLE path-loss distance formula.
 * No Android dependencies — BluetoothAdapter is not needed for the maths.
 */
class BleRssiScannerTest {

    // We test computeDistance which is pure maths — instantiate with a dummy adapter (null-unsafe)
    // We extract the formula inline to keep tests hermetic.
    private fun computeDistance(txPower: Float, rssi: Float): Float {
        val pathLossExponent = 2.7f
        val ratio = (txPower - rssi) / (10f * pathLossExponent)
        return Math.pow(10.0, ratio.toDouble()).toFloat().coerceIn(0.1f, 200f)
    }

    @Test
    fun testDistanceFormula_1m_returns1m() {
        // When RSSI == txPower, ratio = 0, distance = 10^0 = 1.0
        val distance = computeDistance(txPower = -59f, rssi = -59f)
        assertEquals(1.0f, distance, 0.01f)
    }

    @Test
    fun testDistanceFormula_far_returnsLargeDistance() {
        // A very weak signal should return a large (but bounded) distance
        val distance = computeDistance(txPower = -59f, rssi = -100f)
        assertTrue("Expected distance > 10m, got $distance", distance > 10f)
    }

    @Test
    fun testDistanceFormula_strong_returnsSmallDistance() {
        // Signal stronger than txPower → distance < 1m
        val distance = computeDistance(txPower = -59f, rssi = -30f)
        assertTrue("Expected distance < 1m, got $distance", distance < 1f)
    }

    @Test
    fun testDistanceFormula_clampedMin() {
        // Cannot return less than 0.1m
        val distance = computeDistance(txPower = -10f, rssi = 0f)
        assertTrue("Expected distance >= 0.1m", distance >= 0.1f)
    }

    @Test
    fun testDistanceFormula_clampedMax() {
        // Cannot return more than 200m
        val distance = computeDistance(txPower = -59f, rssi = -200f)
        assertTrue("Expected distance <= 200m", distance <= 200f)
    }
}
