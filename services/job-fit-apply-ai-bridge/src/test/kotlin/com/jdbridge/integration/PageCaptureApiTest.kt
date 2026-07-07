package com.jdbridge.integration

import com.jdbridge.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers POST /api/pages — the browser-extension raw page-capture work-item (JD_PAGE_RAW).
 * The Processor LLM-extracts the JD from the captured text, so the bridge only enqueues it.
 */
class PageCaptureApiTest {

    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
    }

    private suspend fun submitPage(
        client: io.ktor.client.HttpClient,
        url: String,
        text: String = "x".repeat(300),
    ) = client.post("/api/pages") {
        contentType(ContentType.Application.Json)
        setBody("""{"url":"$url","title":"Staff SDET","text":"$text"}""")
    }

    @Test
    fun `POST pages enqueues JD_PAGE_RAW and claim returns the type + payload`() = testApplication {
        application { configureApplication() }
        assertEquals(HttpStatusCode.Accepted, submitPage(client, "https://acme.example/job/1").status)

        val claim = client.get("/api/queue/claim")
        assertEquals(HttpStatusCode.OK, claim.status)
        val body = json.parseToJsonElement(claim.bodyAsText()).jsonObject
        assertEquals(WorkItemType.JD_PAGE_RAW, body["type"]!!.jsonPrimitive.content)
        val record = body["jd_record"]!!.jsonObject
        assertEquals("https://acme.example/job/1", record["url"]!!.jsonPrimitive.content)
        assertTrue(record["text"]!!.jsonPrimitive.content.isNotEmpty())
    }

    @Test
    fun `page submit dedups by url`() = testApplication {
        application { configureApplication() }
        submitPage(client, "https://acme.example/job/dup")
        val second = submitPage(client, "https://acme.example/job/dup")
        assertEquals(HttpStatusCode.OK, second.status)
        assertTrue(json.parseToJsonElement(second.bodyAsText()).jsonObject["deduped"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `POST pages with too-little text is rejected 422`() = testApplication {
        application { configureApplication() }
        val res = submitPage(client, "https://acme.example/job/short", text = "too short")
        assertEquals(HttpStatusCode.UnprocessableEntity, res.status)
    }
}
