package com.jd.pipeline.client

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse

/**
 * Config-gating guard: with no endpoint, the provider must report unavailable WITHOUT touching
 * Playwright, so the scraper takes the legacy launch path. (Live CDP behavior is verified
 * manually against a running debug Chrome — see README.)
 */
@DisplayName("ChromeCdpBrowser")
class ChromeCdpBrowserTest {

    @Test
    @DisplayName("isAvailable is false when no endpoint is configured")
    fun unavailableWhenEndpointBlank() {
        assertFalse(ChromeCdpBrowser(endpoint = "").isAvailable())
    }

    @Test
    @DisplayName("close is a safe no-op when never connected")
    fun closeIsSafeWithoutConnect() {
        val browser = ChromeCdpBrowser(endpoint = "")
        browser.close() // must not throw
        assertFalse(browser.isAvailable())
    }
}
