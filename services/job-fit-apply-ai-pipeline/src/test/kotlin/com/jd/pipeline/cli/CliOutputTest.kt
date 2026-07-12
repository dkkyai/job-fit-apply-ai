package com.jd.pipeline.cli

import com.jd.pipeline.nodes.ScrapeJdNode
import com.jd.pipeline.pipeline.IngestionPipeline
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.PipelineAction
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

    private val digestEmail = IntakeContext.Email(
        emailId = "e1", from = "jobs@example.com", subject = "Digest",
        rawBody = "", htmlBody = "", isRecruiter = false, isDigest = true, isInlineDigest = false,
    )

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

    // ── printResult ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("printResult")
    inner class PrintResult {

        @Test
        @DisplayName("digest email with a skip reason prints the reason")
        fun digestWithReason() {
            val state = JDState(intake = digestEmail, skippedReason = "no jobs found")
            CliOutput.printResult(state)
            assertTrue(output().contains("no jobs found"))
        }

        @Test
        @DisplayName("digest email without a skip reason prints the generic digest message")
        fun digestWithoutReason() {
            val state = JDState(intake = digestEmail)
            CliOutput.printResult(state)
            assertTrue(output().contains("Digest email processed"))
        }

        @Test
        @DisplayName("inline digest email is treated like a digest")
        fun inlineDigest() {
            val inlineEmail = jobEmail.copy(isInlineDigest = true)
            val state = JDState(intake = inlineEmail)
            CliOutput.printResult(state)
            assertTrue(output().contains("Digest email processed"))
        }

        @Test
        @DisplayName("non-job-posting email prints skipped message")
        fun notJobPosting() {
            val state = JDState(intake = jobEmail, isJobPosting = false)
            CliOutput.printResult(state)
            assertTrue(output().contains("Not a job posting"))
        }

        @Test
        @DisplayName("tailored job prints output path and job url")
        fun tailoredJob() {
            val state = JDState(
                intake = jobEmail,
                isJobPosting = true,
                company = "Acme",
                roleTitle = "Engineer",
                pipelineAction = PipelineAction.TAILOR,
                outputPath = "/tmp/output/acme_engineer",
                jobUrl = "https://example.com/jobs/1",
            )
            CliOutput.printResult(state)
            val out = output()
            assertTrue(out.contains("→ output: /tmp/output/acme_engineer"))
            assertTrue(out.contains("→ job_url: https://example.com/jobs/1"))
        }

        @Test
        @DisplayName("skipped job prints reason, not output path")
        fun skippedJob() {
            val state = JDState(
                intake = jobEmail,
                isJobPosting = true,
                company = "Acme",
                roleTitle = "Engineer",
                pipelineAction = PipelineAction.SKIP,
                skippedReason = "Fit score below threshold",
            )
            CliOutput.printResult(state)
            val out = output()
            assertTrue(out.contains("→ reason: Fit score below threshold"))
            assertTrue(!out.contains("→ output:"))
        }

        @Test
        @DisplayName("recruiter response required prints draft id when present")
        fun recruiterResponseWithDraftId() {
            val state = JDState(
                intake = jobEmail,
                isJobPosting = true,
                isRecruiterResponseRequired = true,
                draftId = "draft-123",
            )
            CliOutput.printResult(state)
            assertTrue(output().contains("→ draft reply: draft-123"))
        }

        @Test
        @DisplayName("recruiter response required without a draft id prints queued")
        fun recruiterResponseWithoutDraftId() {
            val state = JDState(
                intake = jobEmail,
                isJobPosting = true,
                isRecruiterResponseRequired = true,
                draftId = "",
            )
            CliOutput.printResult(state)
            assertTrue(output().contains("→ draft reply: (queued)"))
        }
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

    // ── printJsonSummary ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("printJsonSummary")
    inner class PrintJsonSummary {

        @Test
        @DisplayName("map overload prints JSON with defaults for missing keys")
        fun mapOverloadDefaults() {
            CliOutput.printJsonSummary(emptyMap<String, Any?>())
            val out = output().trim()
            assertTrue(out.startsWith("{"))
            assertTrue(out.contains("\"pipeline_action\":\"skip\""))
        }

        @Test
        @DisplayName("map overload prints provided values")
        fun mapOverloadProvided() {
            CliOutput.printJsonSummary(
                mapOf(
                    "output_path" to "/tmp/out",
                    "fit_score" to 90,
                    "pipeline_action" to "tailor",
                    "artifact_url" to "https://example.com/art",
                    "error" to "",
                )
            )
            val out = output()
            assertTrue(out.contains("\"output_path\":\"/tmp/out\""))
            assertTrue(out.contains("\"fit_score\":90"))
        }

        @Test
        @DisplayName("JDState overload prints state fields as JSON")
        fun jdStateOverload() {
            val state = JDState(
                intake = jobEmail,
                outputPath = "/tmp/out2",
                fitScore = 77.0f,
                pipelineAction = PipelineAction.TAILOR,
                artifactUrl = "https://example.com/art2",
                error = "",
            )
            CliOutput.printJsonSummary(state)
            val out = output()
            assertTrue(out.contains("\"output_path\":\"/tmp/out2\""))
            assertTrue(out.contains("\"pipeline_action\":\"tailor\""))
        }

        @Test
        @DisplayName("JDState overload defaults fit_score to 0 when null")
        fun jdStateOverloadNullFitScore() {
            val state = JDState(intake = jobEmail, fitScore = null)
            CliOutput.printJsonSummary(state)
            assertTrue(output().contains("\"fit_score\":0"))
        }
    }
}
