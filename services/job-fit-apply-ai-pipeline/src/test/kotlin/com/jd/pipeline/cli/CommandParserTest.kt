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
    fun jsearchFlag() = assertEquals(Command.JSearch, parse("--jsearch"))
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
    fun priorityOrder_signedInOverJSearch() {
        assertEquals(Command.SignedIn, parse("--jsearch", "--signed-in"))
    }

    @Test
    fun priorityOrder_jSearchOverProcessor() {
        assertEquals(Command.JSearch, parse("--processor", "--jsearch"))
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun parse(vararg args: String): Command = CommandParser.parse(args.toList().toTypedArray())
}
