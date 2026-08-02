package com.jd.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.function.Executable
import java.util.concurrent.TimeUnit
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
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
 * The Tier A assertion group is structural and also holds under `E2E_REAL_LLM=1`.
 * The Tier B group asserts exact canned values and the exact LLM call sequence — these are
 * the checks that catch a *silently degraded* run (every tailor-gate failure degrades a node
 * rather than crashing, so Tier A alone would still pass).
 *
 * Requires the e2e slice to be up: `make e2e-up` (or `make e2e` for the full cycle).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Bridge → Processor → Notifier happy path")
// Backstop so a wedged slice fails the run instead of burning the whole CI job budget.
// Generous: the scenario is allowed E2E_TIMEOUT_SECONDS (300 fake / 1800 real).
@Timeout(value = 40, unit = TimeUnit.MINUTES)
class HappyPathE2ETest {

    private val mapper = ObjectMapper().registerKotlinModule()

    // Every request is individually bounded. Without this, pollUntil's deadline bounds
    // nothing: it is only evaluated *between* probes, so a single hung send (a wedged or
    // GC-thrashing container that accepts the connection and never answers) would block
    // forever, well past any suite timeout.
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    /** GET, or POST when [body] is given. Both carry a per-request timeout. */
    private fun <T> request(url: String, handler: HttpResponse.BodyHandler<T>, body: String? = null): HttpResponse<T> {
        val b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
        if (body != null) b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)) else b.GET()
        return http.send(b.build(), handler)
    }

    private val fakeLlm = FakeLlmServer(E2eConfig.fakeLlmPort, E2eConfig.fixturesDir)
    private val sink = MockNotificationSink(E2eConfig.sinkPort)

    // Populated by the scenario transaction, then asserted by its grouped checks.
    private var completedCursor: Long = 0
    private lateinit var company: String
    private lateinit var jobId: String
    private lateinit var finalStatus: JsonNode
    private lateinit var completedEvent: JsonNode
    private lateinit var artifactUrl: String
    private lateinit var jobOutputDir: Path
    private lateinit var discordMessages: List<String>

    private val expectedRole = "Staff Software Engineer in Test"

    @BeforeAll
    fun startHarness() {
        if (!E2eConfig.realLlm) fakeLlm.start()
        sink.start()
        preflight()
    }

    @AfterAll
    fun tearDown() {
        fakeLlm.stop()
        sink.stop()
    }

    @Test
    @DisplayName("TAILOR: scraped JD completes through Bridge, Processor, artifacts, tracking, and notification")
    fun tailoredJobCompletesEndToEnd() {
        submit()
        awaitDone()
        gatherCompletedEvent()
        awaitNotifierDelivery()

        val assertionGroups = mutableListOf<Executable>(Executable { assertTierA() })
        if (!E2eConfig.realLlm && !java.lang.Boolean.getBoolean("e2e.excludeTierB")) {
            assertionGroups += Executable { assertTierB() }
        }
        assertAll("Bridge → Processor → Notifier happy path", assertionGroups)
    }

    // ── Harness and scenario steps ───────────────────────────────────────────────

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
        // Seed the completed-feed cursor at the current head *before* submitting. Paging
        // from since=0 works only until 200 events accumulate, which the documented
        // long-lived-slice loop (`make e2e-up` once, `make e2e-run` repeatedly) reaches —
        // and then fails as "job missing from the completed feed", pointing at the wrong
        // subsystem entirely.
        completedCursor = mapper.readTree(getString("${E2eConfig.bridgeUrl}/api/jobs/completed/head"))
            .path("max_seq").asLong(0)

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
        val feed = mapper.readTree(
            "${E2eConfig.bridgeUrl}/api/jobs/completed?since=$completedCursor&limit=200&all=true"
                .let { getString(it) }
        )
        completedEvent = feed.firstOrNull { it.path("job_id").asText() == jobId }
            ?: fail("job $jobId missing from the completed feed since seq=$completedCursor")
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
        discordMessages = pollUntil(
            90, 1000,
            { "notifier to deliver the Discord message (sink saw: ${sink.describe()})" },
        ) {
            sink.discordTexts().filter { it.contains(company) }.takeIf { it.isNotEmpty() }
        }
    }

    // ── Tier A — structural (hold under both fake and real LLM) ─────────────────

    private fun assertTierA() = assertAll(
        "Tier A — structural",
        listOf(
            Executable { assertEquals("done", finalStatus.path("status").asText()) },
            Executable { tailoredWithPassingScore() },
            Executable { bridgeServesPdf() },
            Executable { bridgeServesCoverLetter() },
            Executable { outputDirComplete() },
            Executable { markservServesArtifacts() },
            Executable { tracksRowInPostgres() },
            Executable { tracksApiReturnsRow() },
            Executable { discordMessageDelivered() },
            Executable { completedFeedHasJob() },
        ),
    )

    private fun tailoredWithPassingScore() {
        assertEquals("TAILOR", finalStatus.path("pipeline_action").asText())
        assertTrue(finalStatus.path("fit_score").asInt() >= 50, "fit_score below threshold: $finalStatus")
    }

    private fun bridgeServesPdf() {
        val bytes = getBytes("${E2eConfig.bridgeUrl}/api/jobs/$jobId/resume.pdf")
        assertTrue(bytes.size > 1000, "resume.pdf suspiciously small: ${bytes.size} bytes")
        assertEquals("%PDF-", String(bytes.copyOfRange(0, 5), Charsets.US_ASCII))
    }

    private fun bridgeServesCoverLetter() {
        assertTrue(getString("${E2eConfig.bridgeUrl}/api/jobs/$jobId/cover_letter.txt").isNotBlank())
    }

    private fun outputDirComplete() {
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

    private fun markservServesArtifacts() {
        // artifact_url is built from ARTIFACT_BASE_URL (markserv) and always ends with /.
        // Pin the origin: otherwise an ARTIFACT_BASE_URL pointing at the *production*
        // markserv still returns 200 here and the assertion proves nothing about the slice.
        assertTrue(
            artifactUrl.startsWith("${E2eConfig.markservUrl}/"),
            "artifact_url origin is not the e2e markserv (${E2eConfig.markservUrl}): $artifactUrl",
        )
        assertEquals(200, statusOf(artifactUrl.trimEnd('/') + "/report.md"))
        assertEquals(200, statusOf(artifactUrl.trimEnd('/') + "/tailored_resume.pdf"))
    }

    private fun tracksRowInPostgres() {
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

    private fun tracksApiReturnsRow() {
        val tracks = mapper.readTree(getString("${E2eConfig.bridgeUrl}/api/tracks"))
        assertTrue(
            tracks.any { it.path("company").asText() == company },
            "/api/tracks has no row for '$company'"
        )
    }

    private fun discordMessageDelivered() {
        assertTrue(
            sink.unknownPaths().isEmpty(),
            "notifier posted to unexpected URL(s) — wrong channel id or bot token? ${sink.unknownPaths()}",
        )
        val msg = discordMessages.first()
        assertTrue(msg.startsWith("• "), "unexpected Discord format: $msg")
        assertTrue(msg.contains("(TAILOR)"), "Discord message missing action: $msg")
    }

    private fun completedFeedHasJob() {
        assertEquals("done", completedEvent.path("status").asText())
        assertTrue(completedEvent.path("completed_seq").asLong() >= 1, "bad completed_seq: $completedEvent")
    }

    // ── Tier B — exact values (fake LLM only; catch silently-degraded runs) ────

    private fun assertTierB() = assertAll(
        "Tier B — exact fake-LLM values",
        listOf(
            Executable { exactFitScore() },
            Executable { exactCallSequence() },
            Executable { tailoredYamlHasCannedContent() },
            Executable { exactCoverLetter() },
            Executable { telegramHighFitDelivered() },
        ),
    )

    private fun exactFitScore() {
        assertEquals(72, finalStatus.path("fit_score").asInt())
    }

    private fun exactCallSequence() {
        assertEquals(
            listOf(
                "score_fit", "jd_extraction", "gap_analysis", "summary_rewrite",
                "bullet_rewrite", "skills_restructure", "ats_validation", "cover_letter",
            ),
            fakeLlm.calls.toList(),
            "call sequence drifted — a retry/refine fired or a node was skipped (degraded run?)"
        )
    }

    private fun tailoredYamlHasCannedContent() {
        val yaml = Files.readString(jobOutputDir.resolve("tailored_resume.yaml"))
        // Short distinctive fragments — YAML emitters may re-wrap long lines.
        assertTrue(yaml.contains("paved road"), "canned summary missing from tailored_resume.yaml (summary_rewrite degraded?)")
        assertTrue(yaml.contains("Mobile Test Automation"), "canned skill group missing (skills_restructure degraded?)")
        // BulletRewriteNode silently keeps the ORIGINAL bullet when the role join key
        // (role|company|start_date) misses, so without a marker on the rewritten text a
        // wholesale join failure is indistinguishable from a successful rewrite.
        assertTrue(
            yaml.contains(FakeLlmServer.BULLET_MARKER),
            "no rewritten bullet in tailored_resume.yaml — the bullet_rewrite fold-back join " +
                "matched nothing (role|company|start_date drift?) and every rewrite was discarded",
        )
    }

    private fun exactCoverLetter() {
        val expected = Files.readString(E2eConfig.fixturesDir.resolve("llm/cover_letter.txt")).trim()
        assertEquals(expected, getString("${E2eConfig.bridgeUrl}/api/jobs/$jobId/cover_letter.txt").trim())
    }

    private fun telegramHighFitDelivered() {
        val msgs = pollUntil(
            30, 1000,
            { "Telegram high-fit message (sink saw: ${sink.describe()})" },
        ) {
            sink.telegramTexts().filter { it.contains(company.replace("&", "&amp;")) }.takeIf { it.isNotEmpty() }
        }
        val msg = msgs.first()
        assertTrue(msg.startsWith("High-fit:"), "unexpected Telegram format: $msg")
        // Anchored, not `contains("72")`: the message embeds `company`, which carries a
        // 13-digit epoch — 12 adjacent digit pairs, so a bare substring check passes by
        // chance roughly one run in nine even when the score is missing entirely.
        assertTrue(
            Regex("""—\s*72\s*$""").containsMatchIn(msg),
            "Telegram message does not end with the canned score: $msg",
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /**
     * Poll until [probe] returns non-null or the deadline passes.
     *
     * Transport failures are swallowed and retried, not propagated: every service in the
     * slice is `restart: unless-stopped` and the JVMs run -XX:+ExitOnOutOfMemoryError, so
     * a single "connection refused" during a restart is expected and must not kill a poll
     * that still has minutes of budget. The last such failure is reported if we do time
     * out, so a slice that never comes back still produces a useful message.
     *
     * An AssertionError from inside the probe (e.g. awaitDone's status=error branch) is a
     * real verdict and propagates immediately.
     */
    private fun <T> pollUntil(deadlineSeconds: Long, intervalMs: Long, what: String, probe: () -> T?): T =
        pollUntil(deadlineSeconds, intervalMs, { what }, probe)

    private fun <T> pollUntil(deadlineSeconds: Long, intervalMs: Long, what: () -> String, probe: () -> T?): T {
        val deadline = System.nanoTime() + deadlineSeconds * 1_000_000_000L
        var lastTransient: Exception?
        while (true) {
            try {
                probe()?.let { return it }
                lastTransient = null
            } catch (e: IOException) {
                lastTransient = e
            } catch (e: IllegalStateException) {
                lastTransient = e
            }
            if (System.nanoTime() > deadline) {
                fail(
                    "timed out after ${deadlineSeconds}s waiting for ${what()}" +
                        (lastTransient?.let { " (last transport failure: $it)" } ?: "")
                )
            }
            Thread.sleep(intervalMs)
        }
    }

    private fun getString(url: String): String {
        val resp = request(url, HttpResponse.BodyHandlers.ofString())
        check(resp.statusCode() in 200..299) { "GET $url → ${resp.statusCode()}: ${resp.body().take(300)}" }
        return resp.body()
    }

    private fun getBytes(url: String): ByteArray {
        val resp = request(url, HttpResponse.BodyHandlers.ofByteArray())
        check(resp.statusCode() in 200..299) { "GET $url → ${resp.statusCode()}" }
        return resp.body()
    }

    private fun statusOf(url: String): Int =
        request(url, HttpResponse.BodyHandlers.discarding()).statusCode()

    private fun postJson(url: String, body: String): JsonNode {
        val resp = request(url, HttpResponse.BodyHandlers.ofString(), body = body)
        check(resp.statusCode() in 200..299) { "POST $url → ${resp.statusCode()}: ${resp.body().take(300)}" }
        return mapper.readTree(resp.body())
    }
}
