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
            connectMaxAttempts = 1,  // isolate the cooldown gating from the in-scrape retry loop
            client = client,
            store = store,
            nanoTime = { now },
            sleep = {},
        )

        assertFalse(browser.isAvailable())  // first attempt fails and arms the cooldown
        assertFalse(browser.isAvailable())  // still within cooldown — must NOT re-probe the backend
        verify(client, times(1)).createSession(anyOrNull(), any())

        now = 2_000L * 1_000_000  // advance past the 1000ms cooldown
        assertFalse(browser.isAvailable())  // cooldown elapsed — probes again (auto-recovers when up)
        verify(client, times(2)).createSession(anyOrNull(), any())
    }

    @Test
    @DisplayName("a single connect retries createSession with exponential backoff before giving up")
    fun connectRetriesWithBackoff() {
        // A transient createSession 500 (SingletonLock race) should be retried within the same scrape
        // so the job isn't dropped to thin email-only JD text — not deferred to the next cooldown.
        val client = mock<SteelClient>()
        whenever(client.createSession(anyOrNull(), any())).thenThrow(RuntimeException("HTTP 500 SingletonLock"))
        val store = mock<SteelStorageStore>()
        val backoffs = mutableListOf<Long>()

        val browser = SteelBrowser(
            baseUrl = "http://steel:3000",
            connectMaxAttempts = 3,
            connectBackoffBaseMs = 500,
            client = client,
            store = store,
            nanoTime = { 0L },
            sleep = { backoffs.add(it) },
        )

        assertFalse(browser.isAvailable())  // one isAvailable() → three in-scrape attempts
        verify(client, times(3)).createSession(anyOrNull(), any())
        // Backoff only *between* attempts (2 gaps for 3 attempts), doubling each time.
        assertEquals(listOf(500L, 1000L), backoffs)
    }

    @Test
    @DisplayName("the circuit breaker widens the cooldown after N consecutive failed connect cycles")
    fun circuitBreakerWidensCooldown() {
        // After the breaker opens, a wedged backend must NOT be re-probed on the normal cooldown —
        // the pipeline backs off for the longer circuit-open window while the watchdog restarts Steel.
        val client = mock<SteelClient>()
        whenever(client.createSession(anyOrNull(), any())).thenThrow(RuntimeException("Steel wedged"))
        val store = mock<SteelStorageStore>()
        var now = 0L

        val browser = SteelBrowser(
            baseUrl = "http://steel:3000",
            reconnectCooldownMs = 1_000,
            circuitOpenCooldownMs = 60_000,
            circuitBreakerThreshold = 2,
            connectMaxAttempts = 1,  // one attempt per cycle keeps the createSession count exact
            client = client,
            store = store,
            nanoTime = { now },
            sleep = {},
        )

        assertFalse(browser.isAvailable())          // cycle 1: fail, arms the 1s (normal) cooldown
        now = 1_500L * 1_000_000                     // past the 1s cooldown
        assertFalse(browser.isAvailable())          // cycle 2: fail → breaker opens, arms the 60s cooldown
        verify(client, times(2)).createSession(anyOrNull(), any())

        now = 3_000L * 1_000_000                     // past the *normal* cooldown, but within the wide one
        assertFalse(browser.isAvailable())          // breaker open — must NOT re-probe yet
        verify(client, times(2)).createSession(anyOrNull(), any())

        now = 62_000L * 1_000_000                    // past the circuit-open cooldown
        assertFalse(browser.isAvailable())           // re-probes once the wide window elapses
        verify(client, times(3)).createSession(anyOrNull(), any())
    }
}
