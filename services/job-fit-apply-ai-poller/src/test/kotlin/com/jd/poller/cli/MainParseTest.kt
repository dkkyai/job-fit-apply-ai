package com.jd.poller.cli

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("Main.parse")
class MainParseTest {

    private fun parse(vararg args: String) = Main.parse(arrayOf(*args))

    @Test fun pollFlag() = assertEquals(PollerCommand.Poll, parse("--poll"))
    @Test fun healthFlag() = assertEquals(PollerCommand.Health, parse("--health"))
    @Test fun reauthFlag() = assertEquals(PollerCommand.Reauth, parse("--reauth"))
    @Test fun checkTokenFlag() = assertEquals(PollerCommand.CheckToken, parse("--check-token"))
    @Test fun noArgsIsUsage() = assertEquals(PollerCommand.Usage, parse())
    @Test fun unknownIsUsage() = assertEquals(PollerCommand.Usage, parse("--nope"))

    @Test fun healthBeatsPollButNotToken() {
        // Precedence: check-token/reauth/token-from-url win over health, which wins over poll.
        assertEquals(PollerCommand.Health, parse("--poll", "--health"))
        assertEquals(PollerCommand.CheckToken, parse("--health", "--check-token"))
    }

    @Test
    fun tokenFromUrlCapturesValue() {
        val cmd = parse("--token-from-url", "http://localhost/?code=abc123")
        assertTrue(cmd is PollerCommand.TokenFromUrl)
        assertEquals("http://localhost/?code=abc123", (cmd as PollerCommand.TokenFromUrl).redirectUrl)
    }

    @Test
    fun tokenFromUrlWinsOverReauth() {
        // redirectUrl is checked first in the precedence chain.
        assertTrue(parse("--reauth", "--token-from-url", "http://localhost/?code=x") is PollerCommand.TokenFromUrl)
    }

    // ── Main.main() / Main.healthy() ──────────────────────────────────────────
    // Only branches with no network I/O, no exitProcess(), and no infinite loop are safe to drive
    // end-to-end here. --poll/--reauth/--check-token/--token-from-url/--health(unhealthy) either
    // touch Gmail/OAuth or call kotlin.system.exitProcess(), which would kill the test JVM.

    @Test
    @DisplayName("no args prints usage and returns normally (no exitProcess, no I/O)")
    fun mainWithNoArgsPrintsUsageAndReturns() {
        val originalOut = System.out
        val captured = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(captured))
        try {
            Main.main(arrayOf())
        } finally {
            System.setOut(originalOut)
        }
        val output = captured.toString()
        assertTrue(output.contains("Usage: poller"))
        assertTrue(output.contains("--poll"))
        assertTrue(output.contains("--health"))
    }

    @Test
    @DisplayName("healthy() is false for a now() far enough in the past that no real heartbeat could match")
    fun healthyIsFalseForAncientTimestamp() {
        // now=0 (the Unix epoch) is always outside any real heartbeat's freshness window; this is a
        // read-only check against Heartbeat/PollerConfig.HEARTBEAT_FILE, so it never writes to disk.
        assertFalse(Main.healthy(now = 0L))
    }
}
