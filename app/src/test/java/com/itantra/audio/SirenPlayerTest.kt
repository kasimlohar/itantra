package com.itantra.audio

import com.itantra.data.audio.SirenPlayer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for SirenPlayer frequency-to-distance mapping logic.
 * No Android dependencies — uses only the pure mapping function.
 */
class SirenPlayerTest {

    private val player = SirenPlayer()

    @Test
    fun testMappingForDistance_closerThan2m_returns1200Hz() {
        val (freq, interval) = player.mappingForDistance(1.0f)
        assertEquals(1200, freq)
        assertEquals(0L, interval)
    }

    @Test
    fun testMappingForDistance_farThan20m_returns440Hz() {
        val (freq, interval) = player.mappingForDistance(25f)
        assertEquals(440, freq)
        assertEquals(1200L, interval)
    }

    @Test
    fun testMappingForDistance_unknownNegative_returns440Hz() {
        val (freq, _) = player.mappingForDistance(-1f)
        assertEquals(440, freq)
    }

    @Test
    fun testMappingForDistance_5to10m_returns800Hz() {
        val (freq, interval) = player.mappingForDistance(7f)
        assertEquals(800, freq)
        assertEquals(500L, interval)
    }

    @Test
    fun testMappingForDistance_2to5m_returns1000Hz() {
        val (freq, interval) = player.mappingForDistance(3f)
        assertEquals(1000, freq)
        assertEquals(250L, interval)
    }

    @Test
    fun testMappingForDistance_10to20m_returns600Hz() {
        val (freq, interval) = player.mappingForDistance(15f)
        assertEquals(600, freq)
        assertEquals(800L, interval)
    }
}
