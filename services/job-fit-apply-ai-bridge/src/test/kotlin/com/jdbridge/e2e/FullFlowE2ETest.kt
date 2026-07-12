package com.jdbridge.e2e

import com.jdbridge.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * E2E tests exercise the full queue-backed worker protocol over HTTP:
 *   POST /api/jobs → GET /api/queue/claim → POST /api/jobs/{id}/result
 *   → POST /api/jobs/{id}/artifacts → GET /api/jobs/{id}
 */
class FullFlowE2ETest {

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun submitJob(
        client: io.ktor.client.HttpClient,
        jdText: String = sampleJdText(),
        jobUrl: String? = null,
    ): String {
        val body = buildJsonObject {
            put("jd_text", jdText)
            if (jobUrl != null) put("job_url", jobUrl)
        }.toString()
        val resp = client.post("/api/jobs") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.Accepted, resp.status)
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject["job_id"]!!.jsonPrimitive.content
    }

    private suspend fun claimJob(client: io.ktor.client.HttpClient): JsonObject? {
        val resp = client.get("/api/queue/claim")
        if (resp.status == HttpStatusCode.NoContent) return null
        assertEquals(HttpStatusCode.OK, resp.status)
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject
    }

    private suspend fun postResult(
        client: io.ktor.client.HttpClient,
        jobId: String,
        fitScore: Int = 82,
        pipelineAction: String = "TAILOR",
        error: String? = null,
    ) {
        val body = buildJsonObject {
            put("pipeline_action", pipelineAction)
            put("fit_score", fitScore)
            if (error != null) put("error", error)
        }.toString()
        val resp = client.post("/api/jobs/$jobId/result") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    private suspend fun uploadArtifacts(
        client: io.ktor.client.HttpClient,
        jobId: String,
        includeCoverLetter: Boolean = true,
    ) {
        val resp = client.post("/api/jobs/$jobId/artifacts") {
            setBody(MultiPartFormDataContent(formData {
                append("resume_pdf", fakePdf(), Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"resume.pdf\"")
                    append(HttpHeaders.ContentType, ContentType.Application.Pdf.toString())
                })
                if (includeCoverLetter) {
                    append("cover_letter_txt", fakeCoverLetter().toByteArray(), Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"cover_letter.txt\"")
                        append(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                    })
                }
            }))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `full happy-path flow submit to done with artifacts`() = testApplication {
        application { configureApplication() }

        // 1. Submit
        val jobId = submitJob(client, sampleJdText(), jobUrl = "https://example.com/jobs/123")

        // 2. Verify initial status is pending
        val pending = Json.parseToJsonElement(client.get("/api/jobs/$jobId").bodyAsText()).jsonObject
        assertEquals("pending", pending["status"]!!.jsonPrimitive.content)

        // 3. Claim
        val claim = claimJob(client)
        assertNotNull(claim, "Claim should return a job")
        assertEquals(jobId, claim["job_id"]!!.jsonPrimitive.content)
        assertTrue(claim["jd_record"]!!.jsonObject.containsKey("jd_text"))

        // 4. Verify claimed status
        val claimed = Json.parseToJsonElement(client.get("/api/jobs/$jobId").bodyAsText()).jsonObject
        assertEquals("claimed", claimed["status"]!!.jsonPrimitive.content)

        // 5. Upload artifacts, then post result
        uploadArtifacts(client, jobId)
        postResult(client, jobId, fitScore = 82, pipelineAction = "TAILOR")

        // 6. Verify done status with fit_score and artifacts
        val done = Json.parseToJsonElement(client.get("/api/jobs/$jobId").bodyAsText()).jsonObject
        assertEquals("done", done["status"]!!.jsonPrimitive.content)
        assertEquals(82, done["fit_score"]!!.jsonPrimitive.int)
        val artifacts = done["artifacts"]!!.jsonObject
        assertTrue(artifacts["resume_pdf"]!!.jsonPrimitive.content.contains(jobId))
        assertTrue(artifacts["cover_letter_txt"]!!.jsonPrimitive.content.contains(jobId))

        // 7. Download resume PDF
        val pdfResp = client.get("/api/jobs/$jobId/resume.pdf")
        assertEquals(HttpStatusCode.OK, pdfResp.status)
        assertEquals(ContentType.Application.Pdf, pdfResp.contentType()?.withoutParameters())
        assertTrue(pdfResp.readBytes().decodeToString().startsWith("%PDF"))

        // 8. Download cover letter
        val clResp = client.get("/api/jobs/$jobId/cover_letter.txt")
        assertEquals(HttpStatusCode.OK, clResp.status)
        assertTrue(clResp.bodyAsText().contains("Dear Hiring Manager"))

        // 9. Verify files on disk
        val pdfOnDisk = STORE_DIR.resolve("jobs/$jobId/resume.pdf").toFile()
        val clOnDisk  = STORE_DIR.resolve("jobs/$jobId/cover_letter.txt").toFile()
        assertTrue(pdfOnDisk.exists(), "resume.pdf must exist on disk")
        assertTrue(clOnDisk.exists(),  "cover_letter.txt must exist on disk")
    }

    @Test
    fun `error result flow transitions job to error with message`() = testApplication {
        application { configureApplication() }

        val jobId = submitJob(client)
        claimJob(client)
        postResult(client, jobId, fitScore = 30, pipelineAction = "SKIP", error = "Score too low")

        val body = Json.parseToJsonElement(client.get("/api/jobs/$jobId").bodyAsText()).jsonObject
        assertEquals("error", body["status"]!!.jsonPrimitive.content)
        assertEquals("Score too low", body["error"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("artifacts"), "No artifacts for error job")
    }

    @Test
    fun `three sequential submits each claim and complete independently`() = testApplication {
        application { configureApplication() }

        val jobIds = (1..3).map { submitJob(client) }

        val artifactPaths = jobIds.map { jobId ->
            val claim = claimJob(client)
            assertNotNull(claim)
            assertEquals(jobId, claim["job_id"]!!.jsonPrimitive.content)
            uploadArtifacts(client, jobId)
            postResult(client, jobId, fitScore = 80 + jobIds.indexOf(jobId))

            val body = Json.parseToJsonElement(client.get("/api/jobs/$jobId").bodyAsText()).jsonObject
            assertEquals("done", body["status"]!!.jsonPrimitive.content)
            body["artifacts"]!!.jsonObject["resume_pdf"]!!.jsonPrimitive.content
        }

        assertEquals(3, artifactPaths.toSet().size, "Each job must have a distinct resume_pdf path")
    }

    @Test
    fun `claim returns 204 when all pending jobs are claimed`() = testApplication {
        application { configureApplication() }

        val jobId = submitJob(client)
        val claim = claimJob(client)
        assertNotNull(claim)
        assertEquals(jobId, claim["job_id"]!!.jsonPrimitive.content)

        val second = client.get("/api/queue/claim")
        assertEquals(HttpStatusCode.NoContent, second.status)
    }

    @Test
    fun `dedup by job_url returns same id with deduped flag on second submit`() = testApplication {
        application { configureApplication() }

        val jobId = submitJob(client, jobUrl = "https://example.com/jobs/dedup-test")

        val secondResp = client.post("/api/jobs") {
            contentType(ContentType.Application.Json)
            setBody("""{"jd_text":"${sampleJdText().replace("\"", "\\\"")}","job_url":"https://example.com/jobs/dedup-test"}""")
        }
        assertTrue(secondResp.status.isSuccess(), "Dedup response should be a 2xx")
        val secondBody = Json.parseToJsonElement(secondResp.bodyAsText()).jsonObject
        assertEquals(jobId, secondBody["job_id"]!!.jsonPrimitive.content)
        assertTrue(secondBody["deduped"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `artifact upload with an unrecognized filename sets no artifacts`() = testApplication {
        application { configureApplication() }

        val jobId = submitJob(client)
        claimJob(client)
        val resp = client.post("/api/jobs/$jobId/artifacts") {
            setBody(MultiPartFormDataContent(formData {
                append("notes", "just some notes".toByteArray(), Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"notes.txt\"")
                    append(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                })
            }))
        }
        assertEquals(HttpStatusCode.OK, resp.status, "unrecognized parts must not fail the upload")
        postResult(client, jobId)

        val body = Json.parseToJsonElement(client.get("/api/jobs/$jobId").bodyAsText()).jsonObject
        assertEquals("done", body["status"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("artifacts"), "no artifacts should be set when no file matched pdf/cover_letter")
    }

    @Test
    fun `artifact upload without cover letter results in empty cover_letter_txt`() = testApplication {
        application { configureApplication() }

        val jobId = submitJob(client)
        claimJob(client)
        uploadArtifacts(client, jobId, includeCoverLetter = false)
        postResult(client, jobId)

        val body = Json.parseToJsonElement(client.get("/api/jobs/$jobId").bodyAsText()).jsonObject
        assertEquals("done", body["status"]!!.jsonPrimitive.content)
        val artifacts = body["artifacts"]!!.jsonObject
        val cl = artifacts["cover_letter_txt"]!!.jsonPrimitive.content
        assertTrue(cl.isEmpty(), "cover_letter_txt should be empty when not uploaded")
    }
}
