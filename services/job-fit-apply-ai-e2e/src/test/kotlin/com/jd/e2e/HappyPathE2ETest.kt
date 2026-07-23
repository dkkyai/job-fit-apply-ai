package com.jd.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The v1 happy path: submit a fixture JD through `POST /api/jobs` (the JSearch route,
 * type JD_SCRAPED — skips scraping entirely), let the containerized Processor run the
 * full tailor pipeline against the in-process [FakeLlmServer], and verify every surface:
 * bridge, output dir, markserv, Postgres `tracks`, `/api/tracks`, and the notifier via
 * the in-process [MockNotificationSink].
 *
 * Tier A tests are structural and also hold under `E2E_REAL_LLM=1`.
 * Tier B tests assert exact canned values and the exact LLM call sequence — they are the
 * checks that catch a *silently degraded* run (every tailor-gate failure degrades a node
 * rather than crashing, so Tier A alone would still pass).
 *
 * Requires the e2e slice to be up: `make e2e-up` (or `make e2e` for the full cycle).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Bridge → Processor → Notifier happy path")
class HappyPathE2ETest {

    private val mapper = ObjectMapper().registerKotlinModule()
    private val http: HttpClient = HttpClient.newHttpClient()

    private val fakeLlm = FakeLlmServer(E2eConfig.fakeLlmPort, E2eConfig.fixturesDir)
    private val sink = MockNotificationSink(E2eConfig.sinkPort)

    // Populated once in setup, asserted by the tests.
    private lateinit var company: String
    private lateinit var jobId: String
    private lateinit var finalStatus: JsonNode
    private lateinit var completedEvent: JsonNode
    private lateinit var artifactUrl: String
    private lateinit var jobOutputDir: Path
    private lateinit var discordMessages: List<String>

    private val expectedRole = "Staff Software Engineer in Test"

    @BeforeAll
    fun runTheWholeFlow() {
        if (!E2eConfig.realLlm) fakeLlm.start()
        sink.start()

        preflight()
        submit()
        awaitDone()
        gatherCompletedEvent()
        awaitNotifierDelivery()
    }

    @AfterAll
    fun tearDown() {
        fakeLlm.stop()
        sink.stop()
    }

    // ── Setup steps ──────────────────────────────────────────────────────────────

    private fun preflight() {
        val health = runCatching { getString("${E2eConfig.bridgeUrl}/health") }
        if (health.isFailure) {
            fail(
                "Bridge not reachable at ${E2eConfig.bridgeUrl} — is the e2e slice running? " +
                    "Start it with `make e2e-up`. (${health.exceptionOrNull()?.message})"
            )
        }
    }

    private fun submit() {
        company = "E2E Acme ${System.currentTimeMillis()}"
        val jdText = Files.readString(E2eConfig.fixturesDir.resolve("jd-staff-sdet.txt"))
            .replace("{{COMPANY}}", company)
        val body = mapper.writeValueAsString(
            mapOf(
                "jd_text" to jdText,
                "company" to company,
                "role_title" to expectedRole,
                "location" to "Remote (US)",
                "source" to "MANUAL",
                "idempotency_key" to "e2e-${company.substringAfterLast(' ')}",
            )
        )
        val resp = postJson("${E2eConfig.bridgeUrl}/api/jobs", body)
        jobId = resp.path("job_id").asText("")
        assertTrue(jobId.isNotBlank(), "submit returned no job_id: $resp")
        assertTrue(!resp.path("deduped").asBoolean(false), "nonce company should never dedupe: $resp")
        println("[e2e] submitted job $jobId as '$company'")
    }

    private fun awaitDone() {
        finalStatus = pollUntil(E2eConfig.timeoutSeconds, 2000, "job $jobId to reach done") {
            val status = mapper.readTree(getString("${E2eConfig.bridgeUrl}/api/jobs/$jobId"))
            when (status.path("status").asText()) {
                "done" -> status
                "error" -> fail(
                    "job $jobId ended in status=error: ${status.path("error").asText()} " +
                        "(fake-llm calls so far: ${fakeLlm.calls})"
                )
                else -> null
            }
        }
        println("[e2e] job done: $finalStatus")
    }

    private fun gatherCompletedEvent() {
        val feed = mapper.readTree(getString("${E2eConfig.bridgeUrl}/api/jobs/completed?since=0&limit=200&all=true"))
        completedEvent = feed.firstOrNull { it.path("job_id").asText() == jobId }
            ?: fail("job $jobId missing from the completed feed")
        artifactUrl = completedEvent.path("artifact_url").asText("")
        assertTrue(
            artifactUrl.isNotBlank(),
            "completed event carries no artifact_url — ARTIFACT_BASE_URL not reaching the processor " +
                "(it must live in .e2e/pipeline.env, not compose environment)",
        )
        val dirName = URLDecoder.decode(
            artifactUrl.trimEnd('/').substringAfterLast('/'), Charsets.UTF_8
        )
        jobOutputDir = E2eConfig.outputDir.resolve(dirName)
    }

    private fun awaitNotifierDelivery() {
        discordMessages = pollUntil(90, 1000, "notifier to deliver the Discord message") {
            sink.discordTexts().filter { it.contains(company) }.takeIf { it.isNotEmpty() }
        }
    }

    // ── Tier A — structural (hold under both fake and real LLM) ─────────────────

    @Test
    @DisplayName("A2: pipeline_action is TAILOR with fit_score >= 50")
    fun tailoredWithPassingScore() {
        assertEquals("TAILOR", finalStatus.path("pipeline_action").asText())
        assertTrue(finalStatus.path("fit_score").asInt() >= 50, "fit_score below threshold: $finalStatus")
    }

    @Test
    @DisplayName("A3: bridge serves a real resume.pdf")
    fun bridgeServesPdf() {
        val bytes = getBytes("${E2eConfig.bridgeUrl}/api/jobs/$jobId/resume.pdf")
        assertTrue(bytes.size > 1000, "resume.pdf suspiciously small: ${bytes.size} bytes")
        assertEquals("%PDF-", String(bytes.copyOfRange(0, 5), Charsets.US_ASCII))
    }

    @Test
    @DisplayName("A4: bridge serves a non-empty cover_letter.txt")
    fun bridgeServesCoverLetter() {
        assertTrue(getString("${E2eConfig.bridgeUrl}/api/jobs/$jobId/cover_letter.txt").isNotBlank())
    }

    @Test
    @DisplayName("A5: output dir has the full artifact set and no render litter")
    fun outputDirComplete() {
        assertTrue(Files.isDirectory(jobOutputDir), "output dir missing: $jobOutputDir")
        for (f in listOf("tailored_resume.yaml", "tailored_resume.tex", "tailored_resume.html", "report.md")) {
            assertTrue(Files.exists(jobOutputDir.resolve(f)), "missing $f in $jobOutputDir")
        }
        assertTrue(
            Files.list(jobOutputDir).use { s -> s.anyMatch { it.fileName.toString().endsWith(".pdf") } },
            "no PDF in $jobOutputDir"
        )
        assertTrue(!Files.exists(jobOutputDir.resolve("fonts")), "leftover fonts/ (render cleanup regressed)")
        assertTrue(!Files.exists(jobOutputDir.resolve("render_pdf.log")), "leftover render_pdf.log (success should remove it)")
    }

    @Test
    @DisplayName("A6: markserv serves the report and the PDF at the artifact URL")
    fun markservServesArtifacts() {
        // artifact_url is built from ARTIFACT_BASE_URL (markserv) and always ends with /
        assertEquals(200, statusOf(artifactUrl.trimEnd('/') + "/report.md"))
        assertEquals(200, statusOf(artifactUrl.trimEnd('/') + "/tailored_resume.pdf"))
    }

    @Test
    @DisplayName("A7: the tracks row landed in Postgres with the expected fields")
    fun tracksRowInPostgres() {
        E2eConfig.pgConnection().use { conn ->
            conn.prepareStatement(
                "SELECT role_title, pipeline_action, artifact_url, output_path FROM tracks WHERE company = ?"
            ).use { ps ->
                ps.setString(1, company)
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next(), "no tracks row for company '$company'")
                    assertEquals(expectedRole, rs.getString("role_title"))
                    assertEquals("tailor", rs.getString("pipeline_action"))
                    assertTrue(!rs.getString("artifact_url").isNullOrBlank(), "tracks.artifact_url blank")
                    assertTrue(!rs.getString("output_path").isNullOrBlank(), "tracks.output_path blank")
                }
            }
        }
    }

    @Test
    @DisplayName("A8: GET /api/tracks (the backlog UI's data path) returns the row")
    fun tracksApiReturnsRow() {
        val tracks = mapper.readTree(getString("${E2eConfig.bridgeUrl}/api/tracks"))
        assertTrue(
            tracks.any { it.path("company").asText() == company },
            "/api/tracks has no row for '$company'"
        )
    }

    @Test
    @DisplayName("A9: notifier posted the Discord message for this job")
    fun discordMessageDelivered() {
        val msg = discordMessages.first()
        assertTrue(msg.startsWith("• "), "unexpected Discord format: $msg")
        assertTrue(msg.contains("(TAILOR)"), "Discord message missing action: $msg")
    }

    @Test
    @DisplayName("A10: completed feed carries the job with a monotonic completed_seq")
    fun completedFeedHasJob() {
        assertEquals("done", completedEvent.path("status").asText())
        assertTrue(completedEvent.path("completed_seq").asLong() >= 1, "bad completed_seq: $completedEvent")
    }

    // ── Tier B — exact values (fake LLM only; catch silently-degraded runs) ────

    @Test
    @Tag("tier-b")
    @DisplayName("B11: fit_score equals the canned value exactly")
    fun exactFitScore() {
        assumeFalse(E2eConfig.realLlm, "Tier B runs only against the fake LLM")
        assertEquals(72, finalStatus.path("fit_score").asInt())
    }

    @Test
    @Tag("tier-b")
    @DisplayName("B12: the fake LLM served exactly the expected call sequence")
    fun exactCallSequence() {
        assumeFalse(E2eConfig.realLlm, "Tier B runs only against the fake LLM")
        assertEquals(
            listOf(
                "score_fit", "jd_extraction", "gap_analysis", "summary_rewrite",
                "bullet_rewrite", "skills_restructure", "ats_validation", "cover_letter",
            ),
            fakeLlm.calls.toList(),
            "call sequence drifted — a retry/refine fired or a node was skipped (degraded run?)"
        )
    }

    @Test
    @Tag("tier-b")
    @DisplayName("B13: tailored_resume.yaml carries the canned summary and skill groups")
    fun tailoredYamlHasCannedContent() {
        assumeFalse(E2eConfig.realLlm, "Tier B runs only against the fake LLM")
        val yaml = Files.readString(jobOutputDir.resolve("tailored_resume.yaml"))
        // Short distinctive fragments — YAML emitters may re-wrap long lines.
        assertTrue(yaml.contains("paved road"), "canned summary missing from tailored_resume.yaml (summary_rewrite degraded?)")
        assertTrue(yaml.contains("Mobile Test Automation"), "canned skill group missing (skills_restructure degraded?)")
    }

    @Test
    @Tag("tier-b")
    @DisplayName("B14: cover_letter.txt equals the canned cover letter")
    fun exactCoverLetter() {
        assumeFalse(E2eConfig.realLlm, "Tier B runs only against the fake LLM")
        val expected = Files.readString(E2eConfig.fixturesDir.resolve("llm/cover_letter.txt")).trim()
        assertEquals(expected, getString("${E2eConfig.bridgeUrl}/api/jobs/$jobId/cover_letter.txt").trim())
    }

    @Test
    @Tag("tier-b")
    @DisplayName("B15: Telegram sink received the high-fit message")
    fun telegramHighFitDelivered() {
        assumeFalse(E2eConfig.realLlm, "Tier B runs only against the fake LLM")
        val msgs = pollUntil(30, 1000, "Telegram high-fit message") {
            sink.telegramTexts().filter { it.contains(company.replace("&", "&amp;")) }.takeIf { it.isNotEmpty() }
        }
        val msg = msgs.first()
        assertTrue(msg.startsWith("High-fit:"), "unexpected Telegram format: $msg")
        assertTrue(msg.contains("72"), "Telegram message missing the score: $msg")
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private fun <T> pollUntil(deadlineSeconds: Long, intervalMs: Long, what: String, probe: () -> T?): T {
        val deadline = System.nanoTime() + deadlineSeconds * 1_000_000_000L
        while (true) {
            probe()?.let { return it }
            if (System.nanoTime() > deadline) fail("timed out after ${deadlineSeconds}s waiting for $what")
            Thread.sleep(intervalMs)
        }
    }

    private fun getString(url: String): String {
        val resp = http.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(resp.statusCode() in 200..299) { "GET $url → ${resp.statusCode()}: ${resp.body().take(300)}" }
        return resp.body()
    }

    private fun getBytes(url: String): ByteArray {
        val resp = http.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        check(resp.statusCode() in 200..299) { "GET $url → ${resp.statusCode()}" }
        return resp.body()
    }

    private fun statusOf(url: String): Int = http.send(
        HttpRequest.newBuilder(URI.create(url)).GET().build(),
        HttpResponse.BodyHandlers.discarding(),
    ).statusCode()

    private fun postJson(url: String, body: String): JsonNode {
        val resp = http.send(
            HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(resp.statusCode() in 200..299) { "POST $url → ${resp.statusCode()}: ${resp.body().take(300)}" }
        return mapper.readTree(resp.body())
    }
}
