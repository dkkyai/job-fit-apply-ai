package com.jd.pipeline.client

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
}
