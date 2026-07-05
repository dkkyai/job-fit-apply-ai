package com.jd.jsearch.bridge

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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Contract test: the real [JsearchBridgeClient] against a REAL bridge (gated on CONTRACT_BRIDGE_URL).
 * Proves the JSearch submit DTO fits the bridge's POST /api/jobs (JD_SCRAPED), the <150-char 422,
 * and idempotency dedup. Point CONTRACT_BRIDGE_URL at a DISPOSABLE bridge — never production.
 */
@DisplayName("JsearchBridgeContractTest (real bridge)")
class JsearchBridgeContractTest {

    private val baseUrl: String? = System.getenv("CONTRACT_BRIDGE_URL")
    private val http = HttpClient.newHttpClient()

    @BeforeEach
    fun requireBridge() = assumeTrue(baseUrl != null, "CONTRACT_BRIDGE_URL not set — skipping")

    @Test
    @DisplayName("submits a JD_SCRAPED job, rejects short jd (422), and dedups by idempotency key")
    fun contract() {
        val client = JsearchBridgeClient(baseUrl!!)
        val key = "jsearch-ct-1"
        val req = SubmitJobRequest(
            jdText = "This is a sufficiently long JSearch job description. ".repeat(5),
            roleTitle = "Staff SDET", company = "Acme", location = "Seattle, WA",
            jobUrl = "https://acme.co/apply", source = "JSEARCH", idempotencyKey = key,
        )

        // 1. First submit → queued, returns a job id.
        val first = client.submit(req) ?: fail("submit returned null for a valid job")
        assertTrue(first.jobId.isNotBlank())
        assertTrue(!first.deduped)

        // 2. The job exists on the bridge as pending.
        val status = mapperNode(getRaw("/api/jobs/${first.jobId}"))
        assertEquals(first.jobId, status["job_id"])

        // 3. Re-submitting the same idempotency key → deduped.
        val second = client.submit(req) ?: fail("re-submit returned null")
        assertTrue(second.deduped, "same idempotency_key should dedup")

        // 4. jd_text < 150 chars → bridge 422 → null.
        assertNull(client.submit(req.copy(jdText = "too short", idempotencyKey = "jsearch-ct-short")))
    }

    private fun getRaw(path: String): String {
        val resp = http.send(HttpRequest.newBuilder(URI("$baseUrl$path")).GET().build(), BodyHandlers.ofString())
        check(resp.statusCode() == 200) { "GET $path -> ${resp.statusCode()}" }
        return resp.body()
    }

    private fun mapperNode(json: String): Map<*, *> =
        com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map::class.java)
}
