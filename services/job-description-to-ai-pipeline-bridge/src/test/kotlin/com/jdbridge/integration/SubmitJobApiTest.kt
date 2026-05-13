package com.jdbridge.integration

import com.jdbridge.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class SubmitJobValidationTest {

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
    }

    @Test
    fun `submit with valid body returns 202 and UUID job_id`() = testApplication {
        application { configureApplication(FakePipelineRunner()) }
        val response = client.post("/api/jobs") {
            contentType(ContentType.Application.Json)
            setBody("""{"jd_text":"${"x".repeat(200)}"}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val jobId = body["job_id"]!!.jsonPrimitive.content
        assertNotNull(UUID.fromString(jobId), "job_id should be a valid UUID")
    }

    @Test
    fun `submit with minimal payload returns 202`() = testApplication {
        application { configureApplication(FakePipelineRunner()) }
        val response = client.post("/api/jobs") {
            contentType(ContentType.Application.Json)
            setBody("""{"jd_text":"${"x".repeat(150)}"}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `submit with all optional fields returns 202 and stores them`() = testApplication {
        application { configureApplication(FakePipelineRunner()) }
        val req = SubmitJobRequest(
            jd_text    = "x".repeat(200),
            role_title = "Staff SDET",
            company    = "Acme Corp",
            location   = "Seattle, WA",
            job_url    = "https://example.com/job/1",
            site       = "greenhouse",
        )
        val response = client.post("/api/jobs") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(req))
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        val jobId = Json.parseToJsonElement(response.bodyAsText()).jsonObject["job_id"]!!.jsonPrimitive.content
        val row = getJob(jobId)!!
        assertEquals("Staff SDET", row.title)
        assertEquals("Acme Corp",  row.company)
    }

    @Test
    fun `submit with exactly 149 char jd_text returns 422`() = testApplication {
        application { configureApplication(FakePipelineRunner()) }
        val response = client.post("/api/jobs") {
            contentType(ContentType.Application.Json)
            setBody("""{"jd_text":"${"x".repeat(149)}"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body.containsKey("detail"))
    }

    @Test
    fun `submit with exactly 150 char jd_text returns 202`() = testApplication {
        application { configureApplication(FakePipelineRunner()) }
        val response = client.post("/api/jobs") {
            contentType(ContentType.Application.Json)
            setBody("""{"jd_text":"${"x".repeat(150)}"}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `submit with empty jd_text returns 422`() = testApplication {
        application { configureApplication(FakePipelineRunner()) }
        val response = client.post("/api/jobs") {
            contentType(ContentType.Application.Json)
            setBody("""{"jd_text":""}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
    }

    @Test
    fun `submit missing jd_text returns 400`() = testApplication {
        application { configureApplication(FakePipelineRunner()) }
        val response = client.post("/api/jobs") {
            contentType(ContentType.Application.Json)
            setBody("""{"company":"Acme"}""")
        }
        assertTrue(
            response.status == HttpStatusCode.BadRequest ||
            response.status == HttpStatusCode.UnprocessableEntity,
            "Expected 400 or 422 for missing jd_text, got ${response.status}"
        )
    }

    @Test
    fun `three sequential submits produce distinct job_ids`() = testApplication {
        application { configureApplication(FakePipelineRunner()) }
        val ids = (1..3).map {
            val r = client.post("/api/jobs") {
                contentType(ContentType.Application.Json)
                setBody("""{"jd_text":"${"x".repeat(200)}"}""")
            }
            Json.parseToJsonElement(r.bodyAsText()).jsonObject["job_id"]!!.jsonPrimitive.content
        }
        assertEquals(3, ids.toSet().size, "All three job_ids must be unique")
    }

    @Test
    fun `success response Content-Type is application json`() = testApplication {
        application { configureApplication(FakePipelineRunner()) }
        val response = client.post("/api/jobs") {
            contentType(ContentType.Application.Json)
            setBody("""{"jd_text":"${"x".repeat(200)}"}""")
        }
        assertTrue(response.contentType()?.match(ContentType.Application.Json) == true)
    }

    @Test
    fun `submit with malformed JSON returns 400`() = testApplication {
        application { configureApplication(FakePipelineRunner()) }
        val response = client.post("/api/jobs") {
            contentType(ContentType.Application.Json)
            setBody("""{"jd_text":"${"x".repeat(200)}", invalid field}""")
        }
        assertTrue(
            response.status == HttpStatusCode.BadRequest ||
            response.status == HttpStatusCode.UnprocessableEntity,
            "Expected 400 or 422 for malformed JSON, got ${response.status}"
        )
    }

    @Test
    fun `submit with very long jd_text returns 202`() = testApplication {
        application { configureApplication(FakePipelineRunner()) }
        val veryLongText = "x".repeat(15_000)
        val response = client.post("/api/jobs") {
            contentType(ContentType.Application.Json)
            setBody("""{"jd_text":"$veryLongText"}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val jobId = body["job_id"]!!.jsonPrimitive.content
        assertNotNull(UUID.fromString(jobId), "job_id should be a valid UUID")
    }

    @Test
    fun `submit with unknown JSON fields returns 400 with strict parsing`() = testApplication {
        application { configureApplication(FakePipelineRunner()) }
        val response = client.post("/api/jobs") {
            contentType(ContentType.Application.Json)
            setBody("""{"jd_text":"${"x".repeat(200)}","role_title":"SDET","unknown_field":"should_be_rejected"}""")
        }
        // Ktor kotlinx.serialization is strict by default and rejects unknown keys
        assertTrue(
            response.status == HttpStatusCode.BadRequest ||
            response.status == HttpStatusCode.UnprocessableEntity,
            "Expected 400 or 422 for unknown JSON fields with strict parsing, got ${response.status}"
        )
    }
}
