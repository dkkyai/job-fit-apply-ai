package com.jd.poller.bridge

import com.jd.poller.model.RawEmail
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
 * Cross-service contract test: the real [PollerBridgeClient] against a REAL bridge, gated on
 * CONTRACT_BRIDGE_URL. Proves the Poller's DTOs fit the bridge's wire format — submitEmail is
 * accepted, and a Processor-shaped result deserializes into the Poller's [CompletedJob].
 *
 * The Processor is simulated with raw HTTP (claim + post result). Point CONTRACT_BRIDGE_URL at a
 * DISPOSABLE bridge (temp store, spare port) — never production.
 */
@DisplayName("PollerBridgeContractTest (real bridge)")
class PollerBridgeContractTest {

    private val baseUrl: String? = System.getenv("CONTRACT_BRIDGE_URL")
    private val http = HttpClient.newHttpClient()
    private val mapper = Json.mapper

    @BeforeEach
    fun requireBridge() {
        assumeTrue(baseUrl != null, "CONTRACT_BRIDGE_URL not set — skipping real-bridge contract test")
    }

    @Test
    @DisplayName("submitEmail is accepted; a Processor result round-trips into CompletedJob; writeback drops it")
    fun contract() {
        val client = PollerBridgeClient(baseUrl!!)

        // 1. The Poller submits a raw email — the bridge must accept the DTO shape.
        val jobId = client.submitEmail(
            RawEmail("ct-poll-1", "Great role for you", "Jane <jane@firm.com>", "hi there", "<p>hi</p>", false)
        )
        assertTrue(jobId.isNotBlank(), "submitEmail should return a job id")

        // 2. Simulate the Processor: claim the item, then post a recruiter result with write-back fields.
        val claimBody = getRaw("/api/queue/claim")
        assertTrue(claimBody.contains("ct-poll-1"), "claimed item should be our submitted email")
        postJson(
            "/api/jobs/$jobId/result",
            """{"pipeline_action":"TAILOR","fit_score":88,"strengths":["Kotlin"],"is_duplicate":false,
                "output_path":null,"has_cover_letter":false,"error":null,"artifact_url":null,
                "terminal_label":"Recruiter_Response_Required","draft_text":"Thanks!","is_recruiter":true,
                "message_id":"ct-poll-1"}""",
        )

        // 3. The Poller drains the feed — the real payload must deserialize into CompletedJob.
        val job = client.fetchCompleted(since = 0).firstOrNull { it.jobId == jobId }
            ?: fail("posted job did not appear in the Poller's completed feed")
        assertEquals("ct-poll-1", job.messageId)
        assertEquals("Recruiter_Response_Required", job.terminalLabel)
        assertEquals("Thanks!", job.draftText)
        assertTrue(job.isRecruiter)

        // 4. Marking it written-back drops it from the feed.
        assertTrue(client.markWritebackDone(jobId))
        assertTrue(client.fetchCompleted(since = 0).none { it.jobId == jobId }, "job should leave the feed")
    }

    private fun postJson(path: String, body: String) {
        val resp = http.send(
            HttpRequest.newBuilder(URI("$baseUrl$path"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            BodyHandlers.ofString(),
        )
        check(resp.statusCode() in 200..202) { "POST $path → ${resp.statusCode()}: ${resp.body()}" }
    }

    private fun getRaw(path: String): String {
        val resp = http.send(HttpRequest.newBuilder(URI("$baseUrl$path")).GET().build(), BodyHandlers.ofString())
        check(resp.statusCode() == 200) { "GET $path → ${resp.statusCode()}: ${resp.body()}" }
        return resp.body()
    }
}
