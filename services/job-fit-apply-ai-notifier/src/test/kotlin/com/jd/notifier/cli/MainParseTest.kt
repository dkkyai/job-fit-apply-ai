package com.jd.notifier.cli

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("Main.parse")
class MainParseTest {
    private fun parse(vararg a: String) = Main.parse(arrayOf(*a))

    @Test fun poll() = assertEquals(Command.Poll, parse("--poll"))
    @Test fun once() = assertEquals(Command.Once, parse("--once"))
    @Test fun health() = assertEquals(Command.Health, parse("--health"))
    @Test fun noArgs() = assertEquals(Command.Usage, parse())
    @Test fun unknown() = assertEquals(Command.Usage, parse("--x"))
    @Test fun healthWinsOverPoll() = assertEquals(Command.Health, parse("--poll", "--health"))
}
