package com.jd.pipeline.cli

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [CommandParser] — pure logic, no I/O beyond temp files.
 *
 * Phase 1: Gmail commands (--reauth, --check-token, --test-gmail, --email, batch default) were
 * removed with the Gmail migration to the Poller. `--worker` is a deprecated alias for `--processor`.
 */
@DisplayName("CommandParserTest")
class CommandParserTest {

    // ── single flags ─────────────────────────────────────────────────────────

    @Test
    fun testFlag()   = assertEquals(Command.Test, parse("--test"))
    @Test
    fun testResumeFlag() = assertEquals(Command.TestResume, parse("--test-resume"))
    @Test
    fun testCoverLetterFlag() = assertEquals(Command.TestCoverLetter, parse("--test-coverletter"))
    @Test
    fun testSupabaseFlag() = assertEquals(Command.TestSupabase, parse("--test-supabase"))
    @Test
    fun signedInFlag() = assertEquals(Command.SignedIn, parse("--signed-in"))

    @Test
    fun resumeGenFlag() {
        val cmd = parse("--resume-gen", "path/to/file.docx")
        assertTrue(cmd is Command.ResumeGen)
        assertEquals("path/to/file.docx", cmd.path)
    }

    @Test
    fun initProfileFlag() {
        val cmd = parse("--init-profile", "path/to/file.pdf")
        assertTrue(cmd is Command.InitProfile)
        assertEquals("path/to/file.pdf", cmd.path)
    }

    // ── processor (formerly worker) ────────────────────────────────────────────

    @Test
    fun processorFlag() = assertEquals(Command.Processor, parse("--processor"))

    @Test
    fun workerFlagIsDeprecatedAliasForProcessor() = assertEquals(Command.Processor, parse("--worker"))

    @Test
    fun noArgsIsUsage() = assertEquals(Command.Usage, parse())

    @Test
    fun unknownFlagIsUsage() = assertEquals(Command.Usage, parse("--nonsense"))

    @Test
    fun notifyTimeoutFlag() {
        val cmd = parse("--notify-timeout", "120")
        assertTrue(cmd is Command.NotifyTimeout)
        assertEquals(120, cmd.minutes)
    }

    // ── tuner flags ───────────────────────────────────────────────────────────

    @Test
    fun scrapeJdTunerFlag() {
        val cmd = parse("--scrapetuner")
        assertTrue(cmd is Command.ScrapeJdTuner)
        assertNull(cmd.file)
        assertEquals(5, cmd.maxIterations)
    }

    @Test
    fun scrapeJdTunerWithFile() {
        val cmd = parse("--scrapetuner", "jd_data.json", "--max-iterations", "8")
        assertTrue(cmd is Command.ScrapeJdTuner)
        assertEquals("jd_data.json", cmd.file)
        assertEquals(8, cmd.maxIterations)
    }

    // ── priority / precedence ─────────────────────────────────────────────────

    @Test
    fun multipleTestFlags_testWinsOverTestResume() {
        // The when block checks `test` before `testResume`, so `test` always wins when both are present.
        assertEquals(Command.Test, parse("--test", "--test-resume"))
        assertEquals(Command.Test, parse("--test-resume", "--test"))
    }

    @Test
    fun priorityOrder_testResumeOverTestCoverLetter() {
        assertEquals(Command.TestResume, parse("--test-coverletter", "--test-resume"))
    }

    @Test
    fun priorityOrder_testCoverLetterOverTestSupabase() {
        assertEquals(Command.TestCoverLetter, parse("--test-supabase", "--test-coverletter"))
    }

    @Test
    fun priorityOrder_notifyTimeoutWinsOverTest() {
        // notify-timeout is checked first in the final when block.
        assertEquals(Command.NotifyTimeout(30), parse("--test", "--notify-timeout", "30"))
    }

    @Test
    fun priorityOrder_scrapeJdTunerOverResumeGen() {
        val cmd = parse("--resume-gen", "resume.docx", "--scrapetuner")
        assertTrue(cmd is Command.ScrapeJdTuner)
    }

    @Test
    fun priorityOrder_resumeGenOverInitProfile() {
        val cmd = parse("--init-profile", "profile.pdf", "--resume-gen", "resume.docx")
        assertTrue(cmd is Command.ResumeGen)
        assertEquals("resume.docx", cmd.path)
    }

    @Test
    fun priorityOrder_signedInOverProcessor() {
        assertEquals(Command.SignedIn, parse("--processor", "--signed-in"))
    }

    // ── test-chrome / test-steel / steel-signin ─────────────────────────────

    @Test
    fun testChromeFlagWithoutUrl() {
        val cmd = parse("--test-chrome")
        assertTrue(cmd is Command.TestChrome)
        assertNull(cmd.url)
    }

    @Test
    fun testChromeFlagWithUrl() {
        val cmd = parse("--test-chrome", "https://example.com/job")
        assertTrue(cmd is Command.TestChrome)
        assertEquals("https://example.com/job", cmd.url)
    }

    @Test
    fun testChromeFlagDoesNotConsumeFollowingFlag() {
        val cmd = parse("--test-chrome", "--signed-in")
        assertTrue(cmd is Command.TestChrome)
        assertNull(cmd.url)
    }

    @Test
    fun testSteelFlagWithoutUrl() {
        val cmd = parse("--test-steel")
        assertTrue(cmd is Command.TestSteel)
        assertNull(cmd.url)
    }

    @Test
    fun testSteelFlagWithUrl() {
        val cmd = parse("--test-steel", "https://example.com/job")
        assertTrue(cmd is Command.TestSteel)
        assertEquals("https://example.com/job", cmd.url)
    }

    @Test
    fun steelSigninFlagWithoutUrl() {
        val cmd = parse("--steel-signin")
        assertTrue(cmd is Command.SteelSignin)
        assertNull(cmd.url)
    }

    @Test
    fun steelSigninFlagWithUrl() {
        val cmd = parse("--steel-signin", "https://example.com/login")
        assertTrue(cmd is Command.SteelSignin)
        assertEquals("https://example.com/login", cmd.url)
    }

    // ── health ───────────────────────────────────────────────────────────────

    @Test
    fun healthFlag() = assertEquals(Command.Health, parse("--health"))

    @Test
    fun healthWinsOverEveryOtherFlag() {
        // health is checked first in the final when block.
        assertEquals(Command.Health, parse("--test", "--processor", "--health"))
    }

    // ── numeric parsing edge cases ───────────────────────────────────────────

    @Test
    fun maxIterationsInvalidNumberDefaultsToFive() {
        val cmd = parse("--scrapetuner", "--max-iterations", "notanumber")
        assertTrue(cmd is Command.ScrapeJdTuner)
        assertEquals(5, cmd.maxIterations)
    }

    @Test
    fun maxIterationsBelowOneIsCoercedToOne() {
        val cmd = parse("--scrapetuner", "--max-iterations", "0")
        assertTrue(cmd is Command.ScrapeJdTuner)
        assertEquals(1, cmd.maxIterations)
    }

    @Test
    fun notifyTimeoutInvalidNumberFallsThroughToUsage() {
        // toIntOrNull() yields null, so notifyTimeoutMinutes stays null and the flag has no effect.
        assertEquals(Command.Usage, parse("--notify-timeout", "notanumber"))
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun parse(vararg args: String): Command = CommandParser.parse(args.toList().toTypedArray())
}
