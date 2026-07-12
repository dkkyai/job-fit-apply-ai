package com.jd.notifier.cli

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("Main.parse")
class MainParseTest {
    private fun parse(vararg a: String) = Main.parse(arrayOf(*a))

    @Test fun poll() = assertEquals(Command.Poll, parse("--poll"))
    @Test fun once() = assertEquals(Command.Once, parse("--once"))
    @Test fun health() = assertEquals(Command.Health, parse("--health"))
    @Test fun noArgs() = assertEquals(Command.Usage, parse())
    @Test fun unknown() = assertEquals(Command.Usage, parse("--x"))
    @Test fun healthWinsOverPoll() = assertEquals(Command.Health, parse("--poll", "--health"))

    // These two go through Main.main() itself (not just parse()) — safe because the Usage branch
    // only prints and returns; it never touches NotificationClient/NotifierBridgeClient/Heartbeat,
    // so no network or process-exit side effects occur.
    @Test
    @DisplayName("main() with no args prints usage")
    fun mainNoArgsPrintsUsage() {
        val out = captureStdout { Main.main(arrayOf()) }
        assertTrue(out.contains("Usage: notifier"))
    }

    @Test
    @DisplayName("main() with an unknown flag prints usage")
    fun mainUnknownFlagPrintsUsage() {
        val out = captureStdout { Main.main(arrayOf("--bogus")) }
        assertTrue(out.contains("--poll"))
        assertTrue(out.contains("--once"))
        assertTrue(out.contains("--health"))
    }

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString()
    }
}
