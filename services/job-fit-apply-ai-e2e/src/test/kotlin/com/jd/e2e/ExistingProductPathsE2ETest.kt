package com.jd.e2e

import com.jd.e2e.FakeLlmServer.Companion.failure
import com.jd.e2e.FakeLlmServer.Companion.ok
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Deterministic scenarios for existing product paths; fake-LLM only by contract. */
@Tag("tier-b")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Existing product-path E2E scenarios")
@Timeout(value = 40, unit = TimeUnit.MINUTES)
class ExistingProductPathsE2ETest {
    private lateinit var harness: E2eScenarioHarness

    /**
     * The `tier-b` tag alone does not keep these out of a real-LLM run: it only bites when
     * someone passes `-PexcludeTags` (the Makefile does under REAL_LLM=1; a bare `./gradlew
     * test` — what CI runs — does not). Every scenario here pins a planned fake response and
     * an exact call sequence, so against a real model they cannot pass: skip the container
     * outright rather than fail it. The tag stays — it is what makes `-PexcludeTags` work.
     */
    @BeforeAll
    fun startHarness() {
        assumeFalse(E2eConfig.realLlm, "fake-LLM-only scenarios: planned responses and exact call sequences")
        // Shared with HappyPathE2ETest — fixed ports, JVM-scoped teardown. See [SharedE2eHarness].
        harness = SharedE2eHarness.start()
    }

    @Test
    @DisplayName("low-fit JD completes as SKIP without tailoring artifacts")
    fun lowFitJobSkipsWithoutTailoring() {
        val nonce = System.currentTimeMillis().toString()
        val company = "E2E Skip $nonce"
        val result = harness.runScenario(
            company = company,
            responses = mapOf("score_fit" to listOf(ok(harness.fixture("llm/score_fit_skip.json")))),
        ) {
            submitScrapedJob(
                company = company,
                roleTitle = "Junior Manual QA Analyst",
                jdText = """
                    $company needs a junior manual QA analyst to execute scripted browser checks,
                    record results in spreadsheets, and report visual defects. This role does not
                    include automation, mobile engineering, CI/CD, backend APIs, cloud platforms,
                    observability, performance testing, or test-infrastructure ownership.
                """.trimIndent(),
                idempotencyKey = "e2e-skip-$nonce",
            )
        }

        assertEquals("done", result.finalStatus.path("status").asText())
        assertEquals(42, result.finalStatus.path("fit_score").asInt())
        assertEquals("SKIP", result.finalStatus.path("pipeline_action").asText())
        assertNull(result.artifactUrl, "SKIP must not publish artifact_url")
        assertNull(result.outputDir, "SKIP must not create an output directory")
        assertFalse(result.finalStatus.has("artifacts"), "SKIP status must not advertise artifact endpoints")
        assertEquals(404, harness.statusOf("${E2eConfig.bridgeUrl}/api/jobs/${result.jobId}/resume.pdf"))
        assertEquals(404, harness.statusOf("${E2eConfig.bridgeUrl}/api/jobs/${result.jobId}/cover_letter.txt"))

        assertEquals("skip", result.track.pipelineAction)
        assertTrue(result.track.artifactUrl.isNullOrBlank())
        assertTrue(result.track.outputPath.isNullOrBlank())
        assertEquals(1, result.completedEvents.size)
        assertEquals(result.jobId, result.completedEvent.path("job_id").asText())
        assertTrue(result.discordMessages.single().contains("(SKIP)"))
        assertTrue(result.telegramMessages.isEmpty(), "low-fit SKIP must not emit a high-fit Telegram alert")
        assertEquals(listOf("score_fit"), result.llmCalls, "no post-score tailoring LLM call may run for SKIP")
    }

    @Test
    @DisplayName("ATS validation fails once, refines once, then passes")
    fun atsValidationRefinesExactlyOnce() {
        val nonce = System.currentTimeMillis().toString()
        val company = "E2E Refine $nonce"
        val result = harness.runScenario(
            company = company,
            responses = mapOf(
                "ats_validation" to listOf(
                    ok(harness.fixture("llm/ats_validation_refine.json")),
                    ok(harness.fixture("llm/ats_validation.json")),
                ),
                "summary_rewrite:refine" to listOf(ok(harness.fixture("llm/summary_refined.txt"))),
            ),
        ) {
            submitScrapedJob(
                company = company,
                roleTitle = "Staff Software Engineer in Test",
                jdText = harness.fixture("jd-staff-sdet.txt", mapOf("COMPANY" to company)),
                idempotencyKey = "e2e-refine-$nonce",
            )
        }

        assertEquals("TAILOR", result.finalStatus.path("pipeline_action").asText())
        assertEquals(72, result.finalStatus.path("fit_score").asInt())
        val outputDir = requireNotNull(result.outputDir) { NO_ARTIFACT_URL_HINT }
        val yaml = java.nio.file.Files.readString(outputDir.resolve("tailored_resume.yaml"))
        assertTrue(yaml.contains("ATS_REFINED_MARKER"), "final artifact does not contain the refinement output")
        assertEquals(
            listOf(
                "score_fit", "jd_extraction", "gap_analysis", "summary_rewrite",
                "bullet_rewrite", "skills_restructure", "ats_validation",
                "summary_rewrite:refine", "bullet_rewrite:refine", "skills_restructure:refine",
                "ats_validation", "cover_letter",
            ),
            result.llmCalls,
            "ATS scenario must perform one and only one refinement pass",
        )
        assertEquals("tailor", result.track.pipelineAction)
        assertEquals(1, result.completedEvents.size)
        assertTrue(result.discordMessages.single().contains("(TAILOR)"))
        assertEquals(1, result.telegramMessages.size)
    }

    @Test
    @DisplayName("captured page enters through /api/pages without external scraping")
    fun capturedPageCompletesFromRawText() {
        val nonce = System.currentTimeMillis().toString()
        val company = "E2E Page $nonce"
        val role = "Principal Quality Platform Engineer"
        val captureUrl = "https://capture.invalid/jobs/$nonce"
        val result = harness.runScenario(
            company = company,
            responses = mapOf(
                "scrape_jd" to listOf(
                    ok(harness.fixture("llm/scrape_jd.json", mapOf("COMPANY" to company, "ROLE" to role))),
                ),
            ),
        ) {
            submitPage(
                url = captureUrl,
                title = "$role at $company",
                text = """
                    PAGE_CAPTURE_SOURCE_MARKER
                    $company is hiring a $role. The role owns Kotlin test infrastructure,
                    mobile and API automation, CI/CD paved roads, contract testing, and
                    observability. Candidates should have eight years of experience leading
                    reliable quality platforms across distributed engineering organizations.
                """.trimIndent(),
                idempotencyKey = "e2e-page-$nonce",
            )
        }

        assertEquals("TAILOR", result.finalStatus.path("pipeline_action").asText())
        assertEquals(role, result.track.roleTitle)
        assertEquals(captureUrl, result.track.jobUrl)
        assertTrue(result.track.jdText.contains("PAGE_CAPTURE_JD_MARKER"))
        assertTrue(!result.artifactUrl.isNullOrBlank(), NO_ARTIFACT_URL_HINT)
        assertEquals(
            listOf(
                "scrape_jd", "score_fit", "jd_extraction", "gap_analysis", "summary_rewrite",
                "bullet_rewrite", "skills_restructure", "ats_validation", "cover_letter",
            ),
            result.llmCalls,
            "captured text must route through scrape_jd exactly once before normal processing",
        )
        assertEquals(1, result.completedEvents.size)
        assertTrue(result.discordMessages.single().contains("(TAILOR)"))
        assertEquals(1, result.telegramMessages.size)
    }

    @Test
    @DisplayName("direct recruiter email enters through /api/emails as one correlated work item")
    fun directRecruiterEmailCompletesAsSingleWorkItem() {
        val nonce = System.currentTimeMillis().toString()
        val company = "E2E Email $nonce"
        val role = "Staff Mobile Test Infrastructure Engineer"
        val messageId = "e2e-message-$nonce"
        val result = harness.runScenario(
            company = company,
            responses = mapOf(
                "scan_email" to listOf(
                    ok(harness.fixture("llm/scan_email.json", mapOf("COMPANY" to company, "ROLE" to role))),
                ),
            ),
        ) {
            submitEmail(
                messageId = messageId,
                subject = "$role opportunity at $company",
                body = """
                    EMAIL_SOURCE_MARKER
                    Hello Richard, I am recruiting for a specific $role opening at $company.
                    The role owns mobile, API, and CI/CD test infrastructure and requires eight
                    years of Kotlin, Appium, contract-testing, and platform reliability experience.
                    This is a remote US full-time position. Please review the full description below.
                """.trimIndent(),
                from = "Recruiter <recruiter@example.invalid>",
                idempotencyKey = "e2e-email-$nonce",
            )
        }

        assertEquals("TAILOR", result.finalStatus.path("pipeline_action").asText())
        assertEquals(messageId, result.track.emailId)
        assertEquals(role, result.track.roleTitle)
        assertTrue(result.track.jobUrl.isBlank(), "direct email fixture must not trigger external scraping")
        assertTrue(result.track.jdText.contains("EMAIL_SCAN_JD_MARKER"))
        assertTrue(!result.artifactUrl.isNullOrBlank(), NO_ARTIFACT_URL_HINT)
        assertEquals(
            listOf(
                "scan_email", "score_fit", "jd_extraction", "gap_analysis", "summary_rewrite",
                "bullet_rewrite", "skills_restructure", "ats_validation", "cover_letter", "draft_reply",
            ),
            result.llmCalls,
            "direct email must scan once, avoid scrape_jd, and run one normal processing flow",
        )
        assertEquals(1, result.completedEvents.size, "the submitted email work item must complete exactly once")
        assertEquals(result.jobId, result.completedEvent.path("job_id").asText())
        assertTrue(result.discordMessages.single().contains("(TAILOR)"))
        assertEquals(1, result.telegramMessages.size)
    }

    /**
     * Issue #56, scenario 3 — the Processor error contract.
     *
     * The failure mode is not the one the issue originally assumed. `ScoreFitNode` catches every
     * exception and returns `fitScore = 0f, error = "score_fit: …"`, so the pipeline is never
     * aborted and the job is never stuck `claimed`; the error reaches the bridge through
     * `ProcessingResult.error` instead. What that leaves behind is a **score-0 SKIP that also
     * wrote a tracks row** — a broken LLM shaped exactly like a genuine low-fit skip. The
     * assertions below pin the parts that hold and the parts that make the two distinguishable.
     */
    @Test
    @DisplayName("a failing score_fit call ends the job in error, publishes nothing, and does not wedge the loop")
    fun scoreFitFailureIsTerminalAndRecoverable() {
        val nonce = System.currentTimeMillis().toString()
        val company = "E2E Fail $nonce"
        val failed = harness.runScenario(
            company = company,
            // One queued 500 is enough: LlmClient retries 429 only, so a 500 throws on the
            // first attempt. A second queued failure would never be consumed.
            responses = mapOf("score_fit" to listOf(failure(500))),
            expectTerminal = "error",
        ) {
            submitScrapedJob(
                company = company,
                roleTitle = "Staff Software Engineer in Test",
                jdText = harness.fixture("jd-staff-sdet.txt", mapOf("COMPANY" to company)),
                idempotencyKey = "e2e-fail-$nonce",
            )
        }

        assertEquals("error", failed.finalStatus.path("status").asText())
        assertTrue(
            failed.finalStatus.path("error").asText().startsWith("score_fit:"),
            "the error must name the failing stage: ${failed.finalStatus.path("error").asText()}",
        )
        assertEquals(0, failed.finalStatus.path("fit_score").asInt())
        assertEquals(listOf("score_fit"), failed.llmCalls, "no node may run after the scoring failure")

        // Nothing published: no artifact endpoints, no artifact_url, no output directory.
        assertFalse(failed.finalStatus.has("artifacts"), "a failed run must not advertise artifact endpoints")
        assertNull(failed.artifactUrl, "a failed run must not publish artifact_url")
        assertNull(failed.outputDir, "a failed run must not create an output directory")
        assertEquals(404, harness.statusOf("${E2eConfig.bridgeUrl}/api/jobs/${failed.jobId}/resume.pdf"))
        assertEquals(404, harness.statusOf("${E2eConfig.bridgeUrl}/api/jobs/${failed.jobId}/cover_letter.txt"))

        // Exactly one completed event, and no second job appeared under this company.
        assertEquals(1, failed.completedEvents.size)
        assertEquals(1, failed.eventsForCompany().size, "a failed run must not fan out or re-enqueue")

        // Tracked, but not as a success — and not silently as a genuine low-fit skip.
        assertEquals("skip", failed.track.pipelineAction, "a failed run must not be tracked as a success")
        assertTrue(failed.track.artifactUrl.isNullOrBlank())
        assertTrue(failed.track.outputPath.isNullOrBlank())

        // Notified as an error, never as a high-fit win.
        assertTrue(
            failed.discordMessages.single().contains("error:"),
            "Discord must report the failure: ${failed.discordMessages.single()}",
        )
        assertTrue(failed.telegramMessages.isEmpty(), "a failed run must not emit a high-fit Telegram alert")

        // The loop survived: the very next healthy job completes normally.
        val healthyCompany = "E2E Recover $nonce"
        val healthy = harness.runScenario(healthyCompany) {
            submitScrapedJob(
                company = healthyCompany,
                roleTitle = "Staff Software Engineer in Test",
                jdText = harness.fixture("jd-staff-sdet.txt", mapOf("COMPANY" to healthyCompany)),
                idempotencyKey = "e2e-recover-$nonce",
            )
        }
        assertEquals("TAILOR", healthy.finalStatus.path("pipeline_action").asText())
        assertEquals(72, healthy.finalStatus.path("fit_score").asInt())
        assertTrue(!healthy.artifactUrl.isNullOrBlank(), NO_ARTIFACT_URL_HINT)
    }
}
