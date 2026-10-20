package com.lm.player.core.source

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceAccelerationPolicyTest {

    @After
    fun tearDown() = SourceAccelerationPolicy.reset()

    @Test
    fun serverLostKeepsLocalAccelerationEnabled() {
        SourceAccelerationPolicy.reset()
        SourceAccelerationPolicy.onServerLost()
        assertTrue(SourceAccelerationPolicy.isEnabled())
        assertEquals("服务器离线，本地音源接管", SourceAccelerationPolicy.state.value.reason)
    }

    @Test
    fun resetDisablesAcceleration() {
        SourceAccelerationPolicy.onServerLost()
        SourceAccelerationPolicy.reset()
        assertFalse(SourceAccelerationPolicy.isEnabled())
    }
}
