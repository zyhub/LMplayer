package com.lm.player.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerLibraryAccelerationResolverTest {

    @Test
    fun usesDefaultTtlWhenUrlHasNoExpiry() {
        val now = System.currentTimeMillis()
        assertEquals(10 * 60 * 1000L, ServerLibraryAccelerationResolver.effectiveTtlMs("https://cdn.example.com/a.mp3", now))
    }

    @Test
    fun capsTtlBySecondExpiry() {
        val now = System.currentTimeMillis()
        val expiresSeconds = (now + 10 * 60 * 1000L) / 1000L
        val ttl = ServerLibraryAccelerationResolver.effectiveTtlMs(
            "https://cdn.example.com/a.mp3?expires=$expiresSeconds",
            now
        )
        // 过期前 1 分钟，允许秒级取整误差
        assertTrue("ttl=$ttl", ttl in 538_000L..540_000L)
    }

    @Test
    fun clampsExpiredUrlToMinimumTtl() {
        val now = System.currentTimeMillis()
        val expiresSeconds = (now - 60 * 60 * 1000L) / 1000L
        assertEquals(
            60_000L,
            ServerLibraryAccelerationResolver.effectiveTtlMs(
                "https://cdn.example.com/a.mp3?expire=$expiresSeconds",
                now
            )
        )
    }

    @Test
    fun ignoresNonNumericExpiry() {
        val now = System.currentTimeMillis()
        assertEquals(
            10 * 60 * 1000L,
            ServerLibraryAccelerationResolver.effectiveTtlMs(
                "https://cdn.example.com/a.mp3?expires=not-a-time",
                now
            )
        )
    }
}
