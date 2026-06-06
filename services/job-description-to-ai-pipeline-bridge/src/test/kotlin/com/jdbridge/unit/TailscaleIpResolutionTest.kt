package com.jdbridge.unit

import com.jdbridge.resolveTailscaleHost
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TailscaleIpResolutionTest {

    @Test
    fun `resolveTailscaleHost returns same value when not tailscale sentinel`() {
        assertEquals("192.168.1.1", resolveTailscaleHost("192.168.1.1"))
        assertEquals("127.0.0.1",   resolveTailscaleHost("127.0.0.1"))
        assertEquals("localhost",    resolveTailscaleHost("localhost"))
    }

    @Test
    fun `resolveTailscaleHost returns null for blank override`() {
        assertNull(resolveTailscaleHost(""))
    }

    @Test
    fun `resolveTailscaleHost returns valid Tailscale IP or null when sentinel used`() {
        val result = resolveTailscaleHost("__tailscale__")
        if (result != null) {
            assertTrue(result.matches(Regex("""100\.\d+\.\d+\.\d+""")),
                "Expected a valid Tailscale IP (100.x.x.x), got: $result")
        }
        // null means Tailscale is unavailable — loopback-only mode, which is correct
    }
}
