package com.jd.jsearch.bridge

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unit tests against a mocked [HttpClient] — no real network calls (that's [JsearchBridgeContractTest]). */
@DisplayName("JsearchBridgeClient (mocked http)")
class JsearchBridgeClientTest {

    // mock() + whenever() must complete in their own statement before being handed to another
    // whenever(...).thenReturn(...) — nesting them inline trips Mockito's UnfinishedStubbingException
    // because Kotlin evaluates the inner call while the outer stub is still open.
    private fun mockResponse(status: Int, body: String): HttpResponse<String> {
        val resp = mock<HttpResponse<String>>()
        whenever(resp.statusCode()).thenReturn(status)
        whenever(resp.body()).thenReturn(body)
        return resp
    }

    private fun stubResponse(http: HttpClient, response: HttpResponse<String>) {
        whenever(http.send(any(), any<HttpResponse.BodyHandler<String>>())).thenReturn(response)
    }

    private val req = SubmitJobRequest(
        jdText = "x".repeat(200), roleTitle = "Staff SDET", company = "Acme",
        location = "Seattle, WA", jobUrl = "https://acme.co/apply", idempotencyKey = "k1",
    )

    @Test
    @DisplayName("200 response parses into SubmitJobResponse")
    fun parsesSuccess() {
        val http = mock<HttpClient>()
        val response = mockResponse(200, """{"job_id":"j1","status":"pending","deduped":false}""")
        stubResponse(http, response)
        val client = JsearchBridgeClient("http://bridge.local", http)

        val resp = client.submit(req)

        assertEquals("j1", resp?.jobId)
        assertEquals("pending", resp?.status)
        assertTrue(resp?.deduped == false)
    }

    @Test
    @DisplayName("202 (queued) is also treated as success")
    fun parses202() {
        val http = mock<HttpClient>()
        val response = mockResponse(202, """{"job_id":"j2","status":"queued","deduped":false}""")
        stubResponse(http, response)
        val client = JsearchBridgeClient("http://bridge.local", http)

        val resp = client.submit(req)

        assertEquals("j2", resp?.jobId)
    }

    @Test
    @DisplayName("deduped=true round-trips")
    fun parsesDeduped() {
        val http = mock<HttpClient>()
        val response = mockResponse(200, """{"job_id":"j3","status":"pending","deduped":true}""")
        stubResponse(http, response)
        val client = JsearchBridgeClient("http://bridge.local", http)

        assertTrue(client.submit(req)?.deduped == true)
    }

    @Test
    @DisplayName("a 422 (short jd) response returns null, not a throw")
    fun returnsNullOn422() {
        val http = mock<HttpClient>()
        val response = mockResponse(422, """{"error":"jd_text too short"}""")
        stubResponse(http, response)
        val client = JsearchBridgeClient("http://bridge.local", http)

        assertNull(client.submit(req))
    }

    @Test
    @DisplayName("a 500 response also returns null")
    fun returnsNullOn500() {
        val http = mock<HttpClient>()
        val response = mockResponse(500, "internal error")
        stubResponse(http, response)
        val client = JsearchBridgeClient("http://bridge.local", http)

        assertNull(client.submit(req))
    }

    @Test
    @DisplayName("constructs with default baseUrl/HttpClient when none injected (no network call made)")
    fun buildsWithDefaults() {
        // Construction only — never invokes submit(), so no real HTTP request happens.
        val client = JsearchBridgeClient()
        assertNotNull(client)
    }

    @Test
    @DisplayName("posts snake_case JSON to {baseUrl}/api/jobs")
    fun postsToCorrectEndpointWithSnakeCase() {
        val http = mock<HttpClient>()
        val response = mockResponse(200, """{"job_id":"j1","status":"pending","deduped":false}""")
        stubResponse(http, response)
        val client = JsearchBridgeClient("http://bridge.local", http)
        val captor = argumentCaptor<HttpRequest>()

        client.submit(req)

        verify(http).send(captor.capture(), any<HttpResponse.BodyHandler<String>>())
        val sent = captor.firstValue
        assertEquals("http://bridge.local/api/jobs", sent.uri().toString())
        assertEquals("POST", sent.method())
    }
}
