package com.jd.poller.cli

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
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
}
