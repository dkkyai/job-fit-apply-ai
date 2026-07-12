package com.jd.jsearch.config

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Config is a singleton object loaded once per JVM from dotenv/env with hardcoded fallback
 * defaults. This test run has no .env file and none of these vars set in the environment
 * (verified before writing this test), so the fallback defaults are what load — asserting on
 * them exercises Config's `get()` null-coalescing chain end to end.
 */
@DisplayName("Config")
class ConfigTest {

    @Test
    @DisplayName("JD_BRIDGE_URL defaults to the local bridge")
    fun bridgeUrlDefault() {
        assertEquals("http://127.0.0.1:8765", Config.JD_BRIDGE_URL)
    }

    @Test
    @DisplayName("JSEARCH_API_KEY defaults to empty when unset")
    fun apiKeyDefaultsEmpty() {
        assertEquals("", Config.JSEARCH_API_KEY)
    }

    @Test
    @DisplayName("JSEARCH_INTERVAL_MS defaults to 24h in millis")
    fun intervalDefault() {
        assertEquals(86_400_000L, Config.JSEARCH_INTERVAL_MS)
    }

    @Test
    @DisplayName("STATE_FILE defaults to the mounted volume path")
    fun stateFileDefault() {
        assertEquals("/state/jsearch.json", Config.STATE_FILE)
        assertTrue(Config.STATE_FILE.isNotBlank())
    }
}
