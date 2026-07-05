package com.jd.notifier.bridge

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Contract test: the real [NotifierBridgeClient] against a REAL bridge (gated on CONTRACT_BRIDGE_URL).
 * Proves the widened completed-feed event (company/role/fit/action/urls) + `?all=true` + head cursor
 * work end-to-end. Point CONTRACT_BRIDGE_URL at a DISPOSABLE bridge — never production.
 */
@DisplayName("NotifierBridgeContractTest (real bridge)")
class NotifierBridgeContractTest {

    private val baseUrl: String? = System.getenv("CONTRACT_BRIDGE_URL")
    private val http = HttpClient.newHttpClient()

    @BeforeEach fun requireBridge() = assumeTrue(baseUrl != null, "CONTRACT_BRIDGE_URL not set — skipping")

    @Test
    @DisplayName("a posted result surfaces as a completed event with the processed-posting fields")
    fun contract() {
        val client = NotifierBridgeClient(baseUrl!!)

        // 1. Submit a JD, then post a Processor-shaped result carrying the new fields.
        val jobId = post("/api/jobs",
            """{"jd_text":"${"long jd ".repeat(30)}","company":"Acme","role_title":"SDET","source":"JSEARCH","idempotency_key":"notif-ct-1"}""")
            .let { readField(it, "job_id") }
        post("/api/jobs/$jobId/result",
            """{"pipeline_action":"tailor","fit_score":91,"company":"Acme","role_title":"Staff SDET",
                "job_url":"https://acme.co/j","artifact_url":"http://markserv/x"}""")

        // 2. The notifier's event stream (all=true) must carry those fields.
        val event = client.fetchEvents(since = 0, limit = 200).firstOrNull { it.jobId == jobId }
            ?: fail("posted job did not appear in the event stream")
        assertEquals("Acme", event.company)
        assertEquals("Staff SDET", event.roleTitle)
        assertEquals(91, event.fitScore)
        assertEquals("tailor", event.pipelineAction)
        assertEquals("https://acme.co/j", event.jobUrl)
        assertEquals("http://markserv/x", event.artifactUrl)

        // 3. head cursor is at least this event's seq (used for cold-start seeding).
        assertTrue(client.headSeq() >= event.completedSeq)
    }

    private fun post(path: String, body: String): String {
        val resp = http.send(
            HttpRequest.newBuilder(URI("$baseUrl$path")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            BodyHandlers.ofString(),
        )
        check(resp.statusCode() in 200..202) { "POST $path → ${resp.statusCode()}: ${resp.body()}" }
        return resp.body()
    }

    private fun readField(json: String, field: String): String =
        com.fasterxml.jackson.databind.ObjectMapper().readTree(json).get(field).asText()
}
