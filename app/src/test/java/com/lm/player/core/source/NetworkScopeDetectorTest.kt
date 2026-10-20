package com.lm.player.core.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class NetworkScopeDetectorTest {

    private fun isPrivate(ip: String): Boolean =
        NetworkScopeDetector.isPrivate(InetAddress.getByName(ip))

    @Test
    fun recognizesRfc1918AndLoopback() {
        assertTrue(isPrivate("10.0.0.1"))
        assertTrue(isPrivate("172.16.0.1"))
        assertTrue(isPrivate("172.31.255.254"))
        assertTrue(isPrivate("192.168.1.1"))
        assertTrue(isPrivate("127.0.0.1"))
    }

    @Test
    fun recognizesTailscaleCgnatRange() {
        assertTrue(isPrivate("100.64.0.1"))
        assertTrue(isPrivate("100.100.100.100"))
        assertTrue(isPrivate("100.127.255.254"))
        // 边界外仍然是公网
        assertFalse(isPrivate("100.63.255.255"))
        assertFalse(isPrivate("100.128.0.1"))
    }

    @Test
    fun keepsPublicAddressesPublic() {
        assertFalse(isPrivate("8.8.8.8"))
        assertFalse(isPrivate("223.5.5.5"))
    }
}
