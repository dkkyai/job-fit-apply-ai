package com.jd.pipeline.config

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [Config.hasApiKey] / [Config.require] — the two helpers not otherwise
 * exercised by usage of the individual `Config.*` properties.
 *
 * PATH is used as a stand-in for "a key guaranteed to be set" since it's inherited
 * by every JVM process from the shell/OS and isn't overridden by this project's .env.
 */
@DisplayName("ConfigTest")
class ConfigTest {

    @Test
    @DisplayName("hasApiKey is false for a key that is not set")
    fun hasApiKeyFalseWhenUnset() {
        assertFalse(Config.hasApiKey("JD_PIPELINE_TEST_NONEXISTENT_KEY_XYZ"))
    }

    @Test
    @DisplayName("hasApiKey is true for a key that is set in the environment")
    fun hasApiKeyTrueWhenSet() {
        assertTrue(Config.hasApiKey("PATH"))
    }

    @Test
    @DisplayName("require returns the value for a configured key")
    fun requireReturnsValueWhenSet() {
        val value = Config.require("PATH")
        assertEquals(System.getenv("PATH"), value)
    }

    @Test
    @DisplayName("require throws for a key that is not set")
    fun requireThrowsWhenUnset() {
        assertFailsWith<IllegalArgumentException> {
            Config.require("JD_PIPELINE_TEST_NONEXISTENT_KEY_XYZ")
        }
    }
}
