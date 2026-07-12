package com.jd.jsearch.client

import com.jd.jsearch.search.JSearchConfig
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("JSearchClient")
class JSearchClientTest {

    // NOTE: mock creation must happen in its own statement, never nested inside a `.thenReturn(...)`
    // call — Kotlin evaluates that argument before the outer `whenever(...)` stub is finished, and
    // starting a second stub mid-flight trips Mockito's UnfinishedStubbingException.
    private fun mockResponse(status: Int, body: String): HttpResponse<String> {
        val resp = mock<HttpResponse<String>>()
        whenever(resp.statusCode()).thenReturn(status)
        whenever(resp.body()).thenReturn(body)
        return resp
    }

    private fun stubResponses(http: HttpClient, vararg responses: HttpResponse<String>) {
        val stub = whenever(http.send(any(), any<HttpResponse.BodyHandler<String>>()))
        stub.thenReturn(responses[0], *responses.drop(1).toTypedArray())
    }

    private fun clientWith(httpClient: HttpClient) = JSearchClient(httpClient = httpClient, apiKey = "test-key")

    private fun dataJson(vararg ids: String) = """
        {"status":"OK","data":[${ids.joinToString(",") { id ->
        """{"job_id":"$id","job_title":"SDET","employer_name":"Acme"}"""
    }}]}
    """.trimIndent()

    @Test
    @DisplayName("constructor throws when no apiKey is available")
    fun requiresApiKey() {
        assertTrue(
            runCatching { JSearchClient(httpClient = mock(), apiKey = "") }.exceptionOrNull() is IllegalStateException
        )
    }

    @Test
    @DisplayName("constructs its own default HttpClient when none is injected (no network call made)")
    fun buildsDefaultHttpClient() {
        // Construction only — never invokes search()/fetchPage(), so no real HTTP request happens.
        val client = JSearchClient(apiKey = "test-key")
        assertEquals(0, client.callCount)
    }

    @Test
    @DisplayName("search() parses listings from a 200 response and increments callCount")
    fun parsesListingsAndCounts() {
        val http = mock<HttpClient>()
        val response = mockResponse(200, dataJson("a", "b"))
        stubResponses(http, response)
        val client = clientWith(http)

        val config = JSearchConfig(queries = listOf("SDET"), numPages = 1, location = "Seattle, WA")
        val listings = client.search(listOf(config))

        assertEquals(listOf("a", "b"), listings.map { it.jobId })
        assertEquals(1, client.callCount)
    }

    @Test
    @DisplayName("splits a 'City, ST' location into job_city/job_state query params")
    fun splitsLocationIntoCityState() {
        val http = mock<HttpClient>()
        stubResponses(http, mockResponse(200, dataJson()))
        val client = clientWith(http)
        val captor = argumentCaptor<HttpRequest>()

        client.search(listOf(JSearchConfig(queries = listOf("SDET"), numPages = 1, location = "Seattle, WA")))

        verify(http).send(captor.capture(), any<HttpResponse.BodyHandler<String>>())
        val uri = captor.firstValue.uri().toString()
        assertTrue(uri.contains("job_city=Seattle"), uri)
        assertTrue(uri.contains("job_state=WA"), uri)
    }

    @Test
    @DisplayName("a location with no comma yields job_city only (job_state omitted)")
    fun singlePartLocationOmitsState() {
        val http = mock<HttpClient>()
        stubResponses(http, mockResponse(200, dataJson()))
        val client = clientWith(http)
        val captor = argumentCaptor<HttpRequest>()

        client.search(listOf(JSearchConfig(queries = listOf("SDET"), numPages = 1, location = "Remote")))

        verify(http).send(captor.capture(), any<HttpResponse.BodyHandler<String>>())
        val uri = captor.firstValue.uri().toString()
        assertTrue(uri.contains("job_city=Remote"), uri)
        assertTrue(!uri.contains("job_state="), uri)
    }

    @Test
    @DisplayName("a null/blank location and remoteJobsOnly=true omit city/state and add remote_jobs_only")
    fun nullLocationRemoteOnly() {
        val http = mock<HttpClient>()
        stubResponses(http, mockResponse(200, dataJson()))
        val client = clientWith(http)
        val captor = argumentCaptor<HttpRequest>()

        client.search(listOf(JSearchConfig(queries = listOf("SDET"), numPages = 1, location = null, remoteJobsOnly = true)))

        verify(http).send(captor.capture(), any<HttpResponse.BodyHandler<String>>())
        val uri = captor.firstValue.uri().toString()
        assertTrue(uri.contains("remote_jobs_only=true"), uri)
        assertTrue(!uri.contains("job_city="), uri)
        assertTrue(!uri.contains("job_state="), uri)
    }

    @Test
    @DisplayName("a blank-string location is treated like no location")
    fun blankLocationOmitsCityState() {
        val http = mock<HttpClient>()
        stubResponses(http, mockResponse(200, dataJson()))
        val client = clientWith(http)
        val captor = argumentCaptor<HttpRequest>()

        client.search(listOf(JSearchConfig(queries = listOf("SDET"), numPages = 1, location = "   ")))

        verify(http).send(captor.capture(), any<HttpResponse.BodyHandler<String>>())
        val uri = captor.firstValue.uri().toString()
        assertTrue(!uri.contains("job_city="), uri)
        assertTrue(!uri.contains("job_state="), uri)
    }

    @Test
    @DisplayName("fetches one page per numPages and dedups repeated jobIds across pages/queries/configs")
    fun paginatesAndDedups() {
        val http = mock<HttpClient>()
        val page1 = mockResponse(200, dataJson("a"))
        val page2 = mockResponse(200, dataJson("a", "b")) // "a" repeats -> deduped
        stubResponses(http, page1, page2)
        val client = clientWith(http)

        val config = JSearchConfig(queries = listOf("SDET"), numPages = 2, location = "Seattle, WA")
        val listings = client.search(listOf(config))

        assertEquals(listOf("a", "b"), listings.map { it.jobId })
        assertEquals(2, client.callCount)
        verify(http, times(2)).send(any(), any<HttpResponse.BodyHandler<String>>())
    }

    @Test
    @DisplayName("a non-200 response yields an empty page (no throw)")
    fun nonOkStatusYieldsEmpty() {
        val http = mock<HttpClient>()
        stubResponses(http, mockResponse(500, "server error"))
        val client = clientWith(http)

        val listings = client.search(listOf(JSearchConfig(queries = listOf("SDET"), numPages = 1)))

        assertTrue(listings.isEmpty())
        assertEquals(1, client.callCount, "callCount still increments even on failure")
    }

    @Test
    @DisplayName("a response whose 'data' field is missing/not-an-array yields an empty page")
    fun missingDataFieldYieldsEmpty() {
        val http = mock<HttpClient>()
        stubResponses(http, mockResponse(200, """{"status":"OK","data":"not-an-array"}"""))
        val client = clientWith(http)

        val listings = client.search(listOf(JSearchConfig(queries = listOf("SDET"), numPages = 1)))

        assertTrue(listings.isEmpty())
    }

    @Test
    @DisplayName("an element that fails to deserialize is skipped, valid siblings still returned")
    fun skipsUndeserializableElements() {
        val http = mock<HttpClient>()
        val body = """
            {"status":"OK","data":[
                {"job_title":"missing job_id"},
                {"job_id":"good","job_title":"SDET","employer_name":"Acme"}
            ]}
        """.trimIndent()
        stubResponses(http, mockResponse(200, body))
        val client = clientWith(http)

        val listings = client.search(listOf(JSearchConfig(queries = listOf("SDET"), numPages = 1)))

        assertEquals(listOf("good"), listings.map { it.jobId })
    }

    @Test
    @DisplayName("multiple queries within one config each fetch a page")
    fun multipleQueriesEachFetch() {
        val http = mock<HttpClient>()
        stubResponses(http, mockResponse(200, dataJson("a")), mockResponse(200, dataJson("c")))
        val client = clientWith(http)

        val config = JSearchConfig(queries = listOf("SDET", "QA engineer"), numPages = 1)
        val listings = client.search(listOf(config))

        assertEquals(listOf("a", "c"), listings.map { it.jobId })
        assertEquals(2, client.callCount)
    }
}
