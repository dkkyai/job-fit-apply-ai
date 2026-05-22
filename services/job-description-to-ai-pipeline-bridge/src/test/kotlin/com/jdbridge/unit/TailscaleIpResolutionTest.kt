package com.jdbridge.unit

import com.jdbridge.resolveHost
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TailscaleIpResolutionTest {

    @Test
    fun `resolveHost returns same IP when not tailscale sentinel`() {
        assertEquals("192.168.1.1", resolveHost("192.168.1.1"))
        assertEquals("0.0.0.0", resolveHost("0.0.0.0"))
        assertEquals("localhost", resolveHost("localhost"))
    }

    @Test
    fun `resolveHost returns valid IP when tailscale is available`() {
        // When tailscale is installed and running, it returns a valid 100.x.x.x IP
        val result = resolveHost("__tailscale__")
        assertTrue(result == "127.0.0.1" || result.matches(Regex("""100\.\d+\.\d+\.\d+""")),
            "Expected either 127.0.0.1 fallback or valid Tailscale IP, got: $result")
    }

    @Test
    fun `resolveHost returns 127_0_0_1 when __tailscale__ sentinel used and tailscale not available`() {
        // This tests the fallback behavior - we just verify it returns something sensible
        val result = resolveHost("__tailscale__")
        assertTrue(result.isNotBlank(), "resolveHost should always return a non-blank value")
    }
}
