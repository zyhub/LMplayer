package com.lm.player.core.network

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerLatencyMonitorTest {
    @After
    fun tearDown() = ServerLatencyMonitor.clear()

    @Test
    fun recordsLatencyAndRecentSuccess() {
        ServerLatencyMonitor.record("lm.example.com:8099", 42L, true)
        assertEquals(42L, ServerLatencyMonitor.latencyMs("lm.example.com"))
        assertTrue(ServerLatencyMonitor.isRecentlyOnline("lm.example.com"))
    }

    @Test
    fun failureKeepsLastSuccessButRecordsFailure() {
        ServerLatencyMonitor.record("lm.example.com", 30L, true)
        ServerLatencyMonitor.record("lm.example.com", 200L, false)
        val sample = ServerLatencyMonitor.get("lm.example.com")!!
        assertTrue(sample.lastSuccessAt > 0L)
        assertTrue(sample.lastFailureAt > 0L)
        assertEquals(2, sample.sampleCount)
    }
}
