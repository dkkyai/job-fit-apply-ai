package com.jdbridge.integration

import com.jdbridge.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

class BatchSubmitApiTest {

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
    }

    private fun batchBody(vararg texts: String): String =
        texts.joinToString(prefix = "[", postfix = "]") { """{"jd_text":"$it"}""" }

    @Test
    fun `batch with two valid items returns 202 and two distinct job_ids`() = testApplication {
        application { configureApplication() }
        val response = client.post("/api/jobs/batch") {
            contentType(ContentType.Application.Json)
            setBody(batchBody("x".repeat(200), "y".repeat(200)))
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
        assertEquals(2, body.size)
        val ids = body.map { it.jsonObject["job_id"]!!.jsonPrimitive.content }
        assertEquals(2, ids.toSet().size, "job_ids must be distinct")
    }

    @Test
    fun `batch with one item behaves like single submit`() = testApplication {
        application { configureApplication() }
        val response = client.post("/api/jobs/batch") {
            contentType(ContentType.Application.Json)
            setBody(batchBody("x".repeat(150)))
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
        assertEquals(1, body.size)
        assertNotNull(body[0].jsonObject["job_id"])
    }

    @Test
    fun `batch with empty array returns 202 and empty array`() = testApplication {
        application { configureApplication() }
        val response = client.post("/api/jobs/batch") {
            contentType(ContentType.Application.Json)
            setBody("[]")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals(0, Json.parseToJsonElement(response.bodyAsText()).jsonArray.size)
    }

    @Test
    fun `batch with one short jd_text returns 422`() = testApplication {
        application { configureApplication() }
        val response = client.post("/api/jobs/batch") {
            contentType(ContentType.Application.Json)
            setBody(batchBody("x".repeat(200), "short"))
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertTrue(Json.parseToJsonElement(response.bodyAsText()).jsonObject.containsKey("detail"))
    }

    @Test
    fun `batch dedup returns same job_id with deduped true`() = testApplication {
        application { configureApplication() }
        val url = "https://example.com/job/batch-dedup"
        val first = client.post("/api/jobs") {
            contentType(ContentType.Application.Json)
            setBody("""{"jd_text":"${"x".repeat(200)}","job_url":"$url"}""")
        }
        val firstId = Json.parseToJsonElement(first.bodyAsText()).jsonObject["job_id"]!!.jsonPrimitive.content

        val batch = client.post("/api/jobs/batch") {
            contentType(ContentType.Application.Json)
            setBody("""[{"jd_text":"${"x".repeat(200)}","job_url":"$url"}]""")
        }
        assertEquals(HttpStatusCode.Accepted, batch.status)
        val item = Json.parseToJsonElement(batch.bodyAsText()).jsonArray[0].jsonObject
        assertEquals(firstId, item["job_id"]!!.jsonPrimitive.content)
        assertTrue(item["deduped"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `batch all items enqueued are claimable in order`() = testApplication {
        application { configureApplication() }
        val response = client.post("/api/jobs/batch") {
            contentType(ContentType.Application.Json)
            setBody(batchBody("x".repeat(200), "y".repeat(200), "z".repeat(200)))
        }
        val ids = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            .map { it.jsonObject["job_id"]!!.jsonPrimitive.content }

        ids.forEach { expectedId ->
            val claim = client.get("/api/queue/claim")
            assertEquals(HttpStatusCode.OK, claim.status)
            assertEquals(expectedId, Json.parseToJsonElement(claim.bodyAsText()).jsonObject["job_id"]!!.jsonPrimitive.content)
        }

        assertEquals(HttpStatusCode.NoContent, client.get("/api/queue/claim").status)
    }

    @Test
    fun `batch response Content-Type is application json`() = testApplication {
        application { configureApplication() }
        val response = client.post("/api/jobs/batch") {
            contentType(ContentType.Application.Json)
            setBody(batchBody("x".repeat(200)))
        }
        assertTrue(response.contentType()?.match(ContentType.Application.Json) == true)
    }
}
