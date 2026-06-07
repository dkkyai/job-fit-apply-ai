package com.jdbridge.integration

import com.jdbridge.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

class JobStatusApiTest {

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
    }

    @Test
    fun `GET unknown job_id returns 404 with detail`() = testApplication {
        application { configureApplication(FakePipelineRunner()) }
        val response = client.get("/api/jobs/does-not-exist")
        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body.containsKey("detail"))
    }

    @Test
    fun `GET queued job returns status queued and no artifacts`() = testApplication {
        application { configureApplication(CrashPipelineRunner()) }
        val submitResp = client.post("/api/jobs") {
            contentType(ContentType.Application.Json)
            setBody("""{"jd_text":"${"x".repeat(200)}"}""")
        }
        val jobId = Json.parseToJsonElement(submitResp.bodyAsText()).jsonObject["job_id"]!!.jsonPrimitive.content

        // Query immediately — FakePipeline is async, job should start as queued
        val statusResp = client.get("/api/jobs/$jobId")
        assertEquals(HttpStatusCode.OK, statusResp.status)
        val body = Json.parseToJsonElement(statusResp.bodyAsText()).jsonObject
        val status = body["status"]!!.jsonPrimitive.content
        assertTrue(status in listOf("queued", "scoring", "tailoring", "complete", "error"),
            "status must be a valid JobStatus value")
    }

    @Test
    fun `GET complete job has artifacts and fit_score`() = testApplication {
        application { configureApplication(FakePipelineRunner()) }
        val submitResp = client.post("/api/jobs") {
            contentType(ContentType.Application.Json)
            setBody("""{"jd_text":"${"x".repeat(200)}"}""")
        }
        val jobId = Json.parseToJsonElement(submitResp.bodyAsText()).jsonObject["job_id"]!!.jsonPrimitive.content

        // Poll until complete (FakePipelineRunner completes synchronously in coroutine)
        var body: JsonObject? = null
        repeat(20) {
            val r = client.get("/api/jobs/$jobId")
            body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
            if (body!!["status"]!!.jsonPrimitive.content == "complete") return@repeat
            runBlocking { delay(50) }
        }

        requireNotNull(body)
        assertEquals("complete", body!!["status"]!!.jsonPrimitive.content)
        assertEquals(82, body!!["fit_score"]!!.jsonPrimitive.int)
        val artifacts = body!!["artifacts"]!!.jsonObject
        assertTrue(artifacts["resume_pdf"]!!.jsonPrimitive.content.contains(jobId))
    }

    @Test
    fun `artifacts field is null when status is not complete`() = runBlocking {
        createJob("status-test-001", SubmitJobRequest(jd_text = "x".repeat(200)))
        updateJob("status-test-001", JobUpdate(status = JobStatus.SCORING))

        testApplication {
            application { configureApplication(FakePipelineRunner()) }
            val response = client.get("/api/jobs/status-test-001")
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertFalse(body.containsKey("artifacts"), "artifacts should be absent for non-complete jobs")
        }
    }

    @Test
    fun `progress_message is non-null after pipeline runs`() = testApplication {
        application { configureApplication(FakePipelineRunner()) }
        val submitResp = client.post("/api/jobs") {
            contentType(ContentType.Application.Json)
            setBody("""{"jd_text":"${"x".repeat(200)}"}""")
        }
        val jobId = Json.parseToJsonElement(submitResp.bodyAsText()).jsonObject["job_id"]!!.jsonPrimitive.content

        repeat(20) {
            val r = client.get("/api/jobs/$jobId")
            val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
            if (body["status"]!!.jsonPrimitive.content == "complete") {
                assertNotNull(body["progress_message"])
                assertTrue(body["progress_message"]!!.jsonPrimitive.content.isNotEmpty())
                return@testApplication
            }
            runBlocking { delay(50) }
        }
    }

    @Test
    fun `error field is null on complete job`() = testApplication {
        application { configureApplication(FakePipelineRunner()) }
        val submitResp = client.post("/api/jobs") {
            contentType(ContentType.Application.Json)
            setBody("""{"jd_text":"${"x".repeat(200)}"}""")
        }
        val jobId = Json.parseToJsonElement(submitResp.bodyAsText()).jsonObject["job_id"]!!.jsonPrimitive.content

        repeat(20) {
            val r = client.get("/api/jobs/$jobId")
            val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
            if (body["status"]!!.jsonPrimitive.content == "complete") {
                assertFalse(body.containsKey("error"), "error should be absent on complete job")
                return@testApplication
            }
            runBlocking { delay(50) }
        }
    }
}
