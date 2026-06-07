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

class ArtifactApiTest {

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
    }

    /** Manually seed a job directory with artifacts, bypassing the pipeline. */
    private fun seedArtifacts(jobId: String, pdf: Boolean = true, cl: Boolean = true) {
        val dir = jobDir(jobId).toFile()
        if (pdf) dir.resolve("resume.pdf").writeBytes(fakePdf())
        if (cl)  dir.resolve("cover_letter.txt").writeText(fakeCoverLetter())
    }

    @Test
    fun `GET resume_pdf when file exists returns 200 and PDF bytes`() = testApplication {
        application { configureApplication() }
        seedArtifacts("pdf-test-001")
        val response = client.get("/api/jobs/pdf-test-001/resume.pdf")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.Pdf, response.contentType()?.withoutParameters())
        assertTrue(response.readBytes().take(4).toByteArray().decodeToString().startsWith("%PDF"))
    }

    @Test
    fun `GET resume_pdf when file missing returns 404 with detail`() = testApplication {
        application { configureApplication() }
        val response = client.get("/api/jobs/no-pdf-job/resume.pdf")
        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body.containsKey("detail"))
    }

    @Test
    fun `GET cover_letter when file exists returns 200 and text`() = testApplication {
        application { configureApplication() }
        seedArtifacts("cl-test-001")
        val response = client.get("/api/jobs/cl-test-001/cover_letter.txt")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("Dear Hiring Manager"))
    }

    @Test
    fun `GET cover_letter when file missing returns 404 with detail`() = testApplication {
        application { configureApplication() }
        val response = client.get("/api/jobs/no-cl-job/cover_letter.txt")
        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body.containsKey("detail"))
    }

    @Test
    fun `Content-Disposition on PDF includes filename resume_pdf`() = testApplication {
        application { configureApplication() }
        seedArtifacts("cd-test-001")
        val response = client.get("/api/jobs/cd-test-001/resume.pdf")
        val disposition = response.headers[HttpHeaders.ContentDisposition]
        assertNotNull(disposition, "Content-Disposition header must be present")
        assertTrue(disposition.contains("resume.pdf"), "filename must be resume.pdf")
    }

    @Test
    fun `Content-Disposition on cover letter includes filename`() = testApplication {
        application { configureApplication() }
        seedArtifacts("cd-test-002")
        val response = client.get("/api/jobs/cd-test-002/cover_letter.txt")
        val disposition = response.headers[HttpHeaders.ContentDisposition]
        assertNotNull(disposition)
        assertTrue(disposition.contains("cover_letter.txt"))
    }
}
