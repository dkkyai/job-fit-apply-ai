package com.jd.jsearch.cli

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("Main.parse")
class MainParseTest {
    private fun parse(vararg args: String) = Main.parse(arrayOf(*args))

    @Test fun onceFlag() = assertEquals(Command.Once, parse("--once"))
    @Test fun noArgsIsUsage() = assertEquals(Command.Usage, parse())
    @Test fun unknownIsUsage() = assertEquals(Command.Usage, parse("--nope"))
    @Test fun onceAmongOthers() = assertEquals(Command.Once, parse("--verbose", "--once"))
}
