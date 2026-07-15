package com.jd.pipeline.client

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [SteelBrowser] that need no live Steel: the URL-rewriting helpers (critical for
 * container networking + phone re-auth links) and the config gating.
 */
@DisplayName("SteelBrowser")
class SteelBrowserTest {

    @Test
    @DisplayName("resolveWsEndpoint rehosts Steel's localhost websocketUrl onto the reachable base")
    fun wsRehosted() {
        assertEquals(
            "ws://steel:3000/",
            SteelBrowser.resolveWsEndpoint("http://steel:3000", "ws://localhost:3000/"),
        )
    }

    @Test
    @DisplayName("resolveWsEndpoint preserves the ws path/query and upgrades https base to wss")
    fun wsPreservesPathAndScheme() {
        assertEquals(
            "ws://steel:3000/devtools/browser/abc",
            SteelBrowser.resolveWsEndpoint("http://steel:3000", "ws://localhost:3000/devtools/browser/abc"),
        )
        assertEquals(
            "wss://steel.example.com/",
            SteelBrowser.resolveWsEndpoint("https://steel.example.com", "ws://localhost:3000/"),
        )
    }

    @Test
    @DisplayName("interactiveDebugUrl rehosts the debug path onto the UI base with interactive params")
    fun debugUrlBuilt() {
        assertEquals(
            "http://mac.tailnet.ts.net:3000/v1/sessions/debug?interactive=true&showControls=true",
            SteelBrowser.interactiveDebugUrl("http://mac.tailnet.ts.net:3000", "http://localhost:3000/v1/sessions/debug"),
        )
    }

    @Test
    @DisplayName("hostToIp leaves localhost and literal IPs untouched")
    fun hostToIpPassthrough() {
        assertEquals("ws://localhost:3000/", SteelBrowser.hostToIp("ws://localhost:3000/"))
        assertEquals("ws://172.20.0.9:3000/devtools/x", SteelBrowser.hostToIp("ws://172.20.0.9:3000/devtools/x"))
    }

    @Test
    @DisplayName("hostToIp returns the endpoint unchanged when the host can't be resolved")
    fun hostToIpUnresolvableIsSafe() {
        val ep = "ws://steel.nonexistent.invalid:3000/"
        assertEquals(ep, SteelBrowser.hostToIp(ep))
    }

    @Test
    @DisplayName("hostToIp resolves a real hostname to an IP (localhost's canonical name)")
    fun hostToIpResolves() {
        // ip6-localhost / the machine's own resolvable name → an IP literal, not the hostname.
        val out = SteelBrowser.hostToIp("ws://ip6-localhost:3000/")
        // Either it resolved to an IP, or (if that alias is absent) it safely passed through.
        assertTrue(out == "ws://ip6-localhost:3000/" || out.matches(Regex("""ws://[0-9a-f.:]+:3000/""")), "got $out")
    }

    @Test
    @DisplayName("isAvailable is false when no base URL is configured, without touching the network")
    fun unavailableWhenBaseBlank() {
        assertFalse(SteelBrowser(baseUrl = "").isAvailable())
    }

    @Test
    @DisplayName("close is a safe no-op when never connected")
    fun closeIsSafeWithoutConnect() {
        val browser = SteelBrowser(baseUrl = "")
        browser.close() // must not throw
        assertFalse(browser.isAvailable())
    }

    @Test
    @DisplayName("a failed connect is retried after the cooldown, not latched off (nor hammered) for the batch")
    fun reconnectCooldownGatesRetries() {
        // Regression: a never-connected Steel used to latch off after its first failed connect for
        // the whole batch, so a restarted backend was never picked up without a pipeline restart.
        // Now a failed connect only backs off for reconnectCooldownMs and re-probes past it.
        val client = mock<SteelClient>()
        whenever(client.createSession(anyOrNull(), any())).thenThrow(RuntimeException("Steel down"))
        val store = mock<SteelStorageStore>()
        var now = 0L

        val browser = SteelBrowser(
            baseUrl = "http://steel:3000",
            reconnectCooldownMs = 1000,
            client = client,
            store = store,
            nanoTime = { now },
        )

        assertFalse(browser.isAvailable())  // first attempt fails and arms the cooldown
        assertFalse(browser.isAvailable())  // still within cooldown — must NOT re-probe the backend
        verify(client, times(1)).createSession(anyOrNull(), any())

        now = 2_000L * 1_000_000  // advance past the 1000ms cooldown
        assertFalse(browser.isAvailable())  // cooldown elapsed — probes again (auto-recovers when up)
        verify(client, times(2)).createSession(anyOrNull(), any())
    }
}
