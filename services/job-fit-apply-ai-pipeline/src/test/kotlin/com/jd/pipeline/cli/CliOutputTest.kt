package com.jd.pipeline.cli

import com.jd.pipeline.pipeline.IngestionPipeline
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import com.jd.pipeline.utils.NodeTimer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Instant

import kotlin.test.assertTrue

/**
 * Unit tests for CliOutput — pure println formatting, verified by capturing stdout.
 */
@DisplayName("CliOutputTest")
class CliOutputTest {

    private lateinit var originalOut: PrintStream
    private lateinit var capture: ByteArrayOutputStream

    @BeforeEach
    fun setUp() {
        originalOut = System.out
        capture = ByteArrayOutputStream()
        System.out.flush()
        System.setOut(PrintStream(capture))
        NodeTimer.reset()
    }

    @AfterEach
    fun tearDown() {
        System.setOut(originalOut)
        NodeTimer.reset()
    }

    private fun output(): String {
        System.out.flush()
        return capture.toString(Charsets.UTF_8.name())
    }

    private val jobEmail = IntakeContext.Email(
        emailId = "e2", from = "jobs@example.com", subject = "JD",
        rawBody = "", htmlBody = "", isRecruiter = false, isDigest = false, isInlineDigest = false,
    )

    // ── printBanner / printModels ───────────────────────────────────────────

    @Test
    @DisplayName("printBanner writes the banner text")
    fun printBannerWritesBanner() {
        CliOutput.printBanner()
        assertTrue(output().contains("JD Pipeline (Kotlin)"))
    }

    @Test
    @DisplayName("printModels lists all configured model names")
    fun printModelsListsModels() {
        CliOutput.printModels()
        val out = output()
        assertTrue(out.contains("SCAN_MODEL"))
        assertTrue(out.contains("SCORE_MODEL"))
        assertTrue(out.contains("RESUME_REASONING_MODEL"))
        assertTrue(out.contains("COVER_LETTER_MODEL"))
        assertTrue(out.contains("DRAFT_REPLY_MODEL"))
    }

    // ── printBatchSummary ────────────────────────────────────────────────────

    @Test
    @DisplayName("printBatchSummary prints counts and run time")
    fun printBatchSummaryPrintsCounts() {
        val start = Instant.now().minusSeconds(5)
        CliOutput.printBatchSummary(
            emailsProcessed = 10,
            jobs = 6,
            tailored = 2,
            skipped = 3,
            duplicate = 1,
            batchStartTime = start,
            scoredJobs = emptyList(),
        )
        val out = output()
        assertTrue(out.contains("Batch Summary"))
        assertTrue(out.contains("Emails processed"))
        assertTrue(out.contains("10"))
    }

    @Test
    @DisplayName("printBatchSummary prints scored jobs table when jobs are present")
    fun printBatchSummaryWithScoredJobs() {
        val job = JDState(intake = jobEmail, company = "Acme", roleTitle = "Engineer", fitScore = 85.0f)
        CliOutput.printBatchSummary(
            emailsProcessed = 1,
            jobs = 1,
            tailored = 1,
            skipped = 0,
            duplicate = 0,
            batchStartTime = Instant.now(),
            scoredJobs = listOf(job),
        )
        val out = output()
        assertTrue(out.contains("Scored Jobs"))
        assertTrue(out.contains("Acme"))
    }

    @Test
    @DisplayName("printBatchSummary prints node timing table when timings were recorded")
    fun printBatchSummaryWithNodeTimings() {
        NodeTimer.record("scan_email", 1200)
        CliOutput.printBatchSummary(
            emailsProcessed = 1,
            jobs = 0,
            tailored = 0,
            skipped = 0,
            duplicate = 0,
            batchStartTime = Instant.now(),
            scoredJobs = emptyList(),
        )
        val out = output()
        assertTrue(out.contains("Node Timings"))
        assertTrue(out.contains("ScanEmail"))
    }

    @Test
    @DisplayName("printBatchSummary formats run time in minutes when over a minute")
    fun printBatchSummaryLongRunTime() {
        val start = Instant.now().minusSeconds(125)
        CliOutput.printBatchSummary(
            emailsProcessed = 1,
            jobs = 0,
            tailored = 0,
            skipped = 0,
            duplicate = 0,
            batchStartTime = start,
            scoredJobs = emptyList(),
        )
        assertTrue(output().contains("m"))
    }

    // ── printScrapeBatchWarnings ─────────────────────────────────────────────

    @Nested
    @DisplayName("printScrapeBatchWarnings")
    inner class PrintScrapeBatchWarnings {

        @Test
        @DisplayName("prints nothing when there are no warnings")
        fun noWarnings() {
            val pipeline = IngestionPipeline()
            CliOutput.printScrapeBatchWarnings(pipeline)
            assertTrue(output().isEmpty())
        }

        @Test
        @DisplayName("prints LinkedIn session-expired warning")
        fun linkedInSessionExpired() {
            val pipeline = IngestionPipeline()
            pipeline.scrapeNode.batchAuthExpiredDomains.add("www.linkedin.com")
            CliOutput.printScrapeBatchWarnings(pipeline)
            assertTrue(output().contains("LinkedIn session expired"))
        }

        @Test
        @DisplayName("prints blocked-domains warning")
        fun blockedDomains() {
            val pipeline = IngestionPipeline()
            pipeline.scrapeNode.batchBlockedDomains.add("example.com")
            CliOutput.printScrapeBatchWarnings(pipeline)
            val out = output()
            assertTrue(out.contains("blocked scraping"))
            assertTrue(out.contains("example.com"))
        }
    }

}
