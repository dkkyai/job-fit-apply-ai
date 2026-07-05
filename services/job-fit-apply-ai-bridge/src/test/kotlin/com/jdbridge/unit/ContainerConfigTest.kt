package com.jdbridge.unit

import com.jdbridge.resolveBindHosts
import com.jdbridge.resolveStoreDir
import java.nio.file.Paths
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Unit tests for the container-vs-host configuration that the Docker migration added:
 * the bind-host list (Application.kt) and the store-root resolution (Store.kt).
 */
class ContainerConfigTest {

    // ── Bind hosts ──────────────────────────────────────────────────────────────

    @Test
    fun `0_0_0_0 binds a single wildcard connector (no double-bind)`() {
        // Regression guard: 0.0.0.0 must NOT also add 127.0.0.1, or the port double-binds.
        assertEquals(listOf("0.0.0.0"), resolveBindHosts("0.0.0.0"))
    }

    @Test
    fun `null host binds loopback only`() {
        assertEquals(listOf("127.0.0.1"), resolveBindHosts(null))
    }

    @Test
    fun `explicit 127_0_0_1 is not duplicated`() {
        assertEquals(listOf("127.0.0.1"), resolveBindHosts("127.0.0.1"))
    }

    @Test
    fun `a resolved host (e_g_ Tailscale IP) binds loopback plus that host`() {
        assertEquals(listOf("127.0.0.1", "100.111.66.8"), resolveBindHosts("100.111.66.8"))
    }

    // ── Store dir ───────────────────────────────────────────────────────────────

    @Test
    fun `store dir uses the env value when set (container volume)`() {
        assertEquals(Paths.get("/data"), resolveStoreDir("/data"))
    }

    @Test
    fun `store dir falls back to the host default when unset or blank`() {
        val default = Paths.get(System.getProperty("user.home"), ".openclaw", "jd-bridge")
        assertEquals(default, resolveStoreDir(null))
        assertEquals(default, resolveStoreDir(""))
        assertEquals(default, resolveStoreDir("   "))
    }
}
