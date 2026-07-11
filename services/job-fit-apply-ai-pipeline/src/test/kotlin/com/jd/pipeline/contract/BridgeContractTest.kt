package com.jd.pipeline.contract

import com.jd.pipeline.client.BridgeClient
import com.jd.pipeline.client.WorkItemType
import com.jd.pipeline.source.IngestionSource
import com.jd.pipeline.source.JdRecord
import com.jd.pipeline.source.ProcessingResult
import com.jd.pipeline.utils.Json
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Cross-service contract test against a REAL bridge (not a fake), gated on CONTRACT_BRIDGE_URL.
 * Proves the Processor's actual DTOs (BridgeClient / parseClaimTree / ProcessingResult) fit the
 * bridge's real wire format at the three cutover seams:
 *   1. claim an EMAIL_RAW item → ClaimedEmail populated (bridge stores a serialized email)
 *   2. claim a JD_SCRAPED item → JdRecord populated
 *   3. post a result with terminal_label/draft_text/message_id → they round-trip into the feed
 *
 * Point CONTRACT_BRIDGE_URL at a DISPOSABLE bridge (temp store, spare port) — never production.
 */
@DisplayName("BridgeContractTest (real bridge)")
class BridgeContractTest {

    private val baseUrl: String? = System.getenv("CONTRACT_BRIDGE_URL")
    private val http = HttpClient.newHttpClient()
    private val mapper = Json.mapper

    @BeforeEach
    fun requireBridge() {
        assumeTrue(baseUrl != null, "CONTRACT_BRIDGE_URL not set — skipping real-bridge contract test")
    }

    @Test
    @DisplayName("EMAIL_RAW + JD_SCRAPED claim and result-feed round-trip against the real bridge")
    fun contract() {
        val bridge = BridgeClient(baseUrl!!)

        // 1. Submit a raw email the way the Poller does (POST /api/emails, snake_case).
        val emailJobId = postJson(
            "/api/emails",
            """{"message_id":"ct-m1","body":"Great Staff SDET role at Acme — apply at https://acme.co/job",
                "subject":"Staff SDET","html_body":null,"from":"Jane <jane@firm.com>",
                "is_recruiter_hint":true,"idempotency_key":"ct-m1"}""",
        ).get("job_id").asText()

        // 2. Processor claims it → the bridge's serialized email must deserialize into ClaimedEmail.
        val claimed = bridge.claim() ?: fail("claim() returned null for a queued EMAIL_RAW item")
        assertEquals(emailJobId, claimed.jobId)
        assertEquals(WorkItemType.EMAIL_RAW, claimed.type, "claim type discriminator mismatch")
        val email = assertNotNull(claimed.email, "EMAIL_RAW claim must carry a ClaimedEmail payload")
        assertEquals("ct-m1", email.messageId)
        assertEquals("Jane <jane@firm.com>", email.from)
        assertTrue(email.isRecruiterHint, "is_recruiter_hint must survive the round-trip")
        assertTrue(email.body.contains("Acme"))

        // 3. Processor posts a recruiter result carrying the Gmail write-back fields.
        bridge.postResult(
            claimed.jobId,
            ProcessingResult(
                pipelineAction = "TAILOR", fitScore = 90, strengths = listOf("Kotlin"),
                isDuplicate = false, outputPath = null, hasCoverLetter = false,
                error = null, artifactUrl = null,
                terminalLabel = "Recruiter_Response_Required", draftText = "Thanks for reaching out!",
                isRecruiter = true, messageId = "ct-m1",
            ),
        )

        // 4. The write-back fields must round-trip into the completed feed (what the Poller reads).
        val feed = mapper.readTree(getRaw("/api/jobs/completed?since=0"))
        val entry = feed.firstOrNull { it.get("job_id").asText() == claimed.jobId }
            ?: fail("posted job did not appear in the completed feed")
        assertEquals("ct-m1", entry.get("message_id").asText())
        assertEquals("Recruiter_Response_Required", entry.get("terminal_label").asText())
        assertEquals("Thanks for reaching out!", entry.get("draft_text").asText())
        assertTrue(entry.get("is_recruiter").asBoolean(), "is_recruiter must round-trip into the feed")

        // 5. JD_SCRAPED path — the other claim branch.
        val jdJobId = bridge.submit(
            JdRecord(jdText = "x".repeat(200), company = "Acme", roleTitle = "SDET",
                location = "Remote", jobUrl = null, source = IngestionSource.JSEARCH)
        )
        val jdClaim = bridge.claim() ?: fail("claim() returned null for a queued JD_SCRAPED item")
        assertEquals(jdJobId, jdClaim.jobId)
        assertEquals(WorkItemType.JD_SCRAPED, jdClaim.type)
        assertEquals("Acme", assertNotNull(jdClaim.jdRecord).company)
    }

    private fun postJson(path: String, body: String) = mapper.readTree(
        http.send(
            HttpRequest.newBuilder(URI("$baseUrl$path"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            BodyHandlers.ofString(),
        ).also { check(it.statusCode() in 200..202) { "POST $path → ${it.statusCode()}: ${it.body()}" } }.body()
    )

    private fun getRaw(path: String): String =
        http.send(HttpRequest.newBuilder(URI("$baseUrl$path")).GET().build(), BodyHandlers.ofString())
            .also { check(it.statusCode() == 200) { "GET $path → ${it.statusCode()}" } }.body()
}
