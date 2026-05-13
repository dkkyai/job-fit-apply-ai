package com.jdbridge.unit

import com.jdbridge.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class JobStatusValuesTest {

    @Test
    fun `all status enum values are correct strings`() {
        assertEquals("queued",               JobStatus.QUEUED.value)
        assertEquals("scoring",              JobStatus.SCORING.value)
        assertEquals("tailoring",            JobStatus.TAILORING.value)
        assertEquals("writing_cover_letter", JobStatus.WRITING_CL.value)
        assertEquals("converting_pdf",       JobStatus.CONVERTING.value)
        assertEquals("complete",             JobStatus.COMPLETE.value)
        assertEquals("error",                JobStatus.ERROR.value)
    }

    @Test
    fun `fromValue round-trips all statuses`() {
        for (status in JobStatus.entries) {
            assertEquals(status, JobStatus.fromValue(status.value))
        }
    }

    @Test
    fun `fromValue throws on unknown value`() {
        assertFailsWith<IllegalArgumentException> { JobStatus.fromValue("unknown") }
    }

    @Test
    fun `serialized status uses value string not name`() {
        val json = Json.encodeToString(JobStatus.COMPLETE)
        assertEquals("\"complete\"", json)
    }
}

class SubmitJobRequestSerializationTest {

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json { explicitNulls = false }

    @Test
    fun `exactly 150 char jd_text serializes correctly`() {
        val req = SubmitJobRequest(jd_text = "x".repeat(150))
        val encoded = json.encodeToString(req)
        val decoded = Json.decodeFromString<SubmitJobRequest>(encoded)
        assertEquals("x".repeat(150), decoded.jd_text)
    }

    @Test
    fun `optional fields are absent from JSON when null`() {
        val req = SubmitJobRequest(jd_text = "x".repeat(150))
        val encoded = json.encodeToString(req)
        assertFalse(encoded.contains("role_title"), "null role_title should not appear")
        assertFalse(encoded.contains("company"),    "null company should not appear")
    }

    @Test
    fun `all optional fields round-trip correctly`() {
        val req = SubmitJobRequest(
            jd_text    = "x".repeat(200),
            role_title = "Staff SDET",
            company    = "Acme Corp",
            location   = "Seattle, WA",
            job_url    = "https://example.com/job/1",
            site       = "greenhouse",
        )
        val decoded = Json.decodeFromString<SubmitJobRequest>(json.encodeToString(req))
        assertEquals("Staff SDET",  decoded.role_title)
        assertEquals("Acme Corp",   decoded.company)
        assertEquals("Seattle, WA", decoded.location)
    }
}

class ArtifactUrlsSerializationTest {

    @Test
    fun `full ArtifactUrls round-trips`() {
        val urls = ArtifactUrls(
            resume_pdf       = "/api/jobs/abc/resume.pdf",
            cover_letter_txt = "/api/jobs/abc/cover_letter.txt",
        )
        val decoded = Json.decodeFromString<ArtifactUrls>(Json.encodeToString(urls))
        assertEquals("/api/jobs/abc/resume.pdf",       decoded.resume_pdf)
        assertEquals("/api/jobs/abc/cover_letter.txt", decoded.cover_letter_txt)
    }

    @Test
    fun `empty cover_letter_txt round-trips`() {
        val urls = ArtifactUrls(resume_pdf = "/api/jobs/abc/resume.pdf", cover_letter_txt = "")
        val decoded = Json.decodeFromString<ArtifactUrls>(Json.encodeToString(urls))
        assertEquals("", decoded.cover_letter_txt)
    }
}

class JobStatusResponseSerializationTest {

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json { explicitNulls = false; encodeDefaults = true }

    @Test
    fun `null optional fields are omitted from JSON`() {
        val resp = JobStatusResponse(job_id = "abc", status = "queued")
        val encoded = json.encodeToString(resp)
        assertFalse(encoded.contains("fit_score"),        "null fit_score should be omitted")
        assertFalse(encoded.contains("\"artifacts\""),    "null artifacts should be omitted")
        assertFalse(encoded.contains("\"error\""),        "null error should be omitted")
        assertFalse(encoded.contains("progress_message"), "null progress_message should be omitted")
    }

    @Test
    fun `complete response with all fields serializes correctly`() {
        val resp = JobStatusResponse(
            job_id           = "abc-123",
            status           = JobStatus.COMPLETE.value,
            title            = "Staff SDET",
            company          = "Acme Corp",
            progress_message = "Done.",
            fit_score        = 82,
            artifacts        = ArtifactUrls("/api/jobs/abc-123/resume.pdf", "/api/jobs/abc-123/cover_letter.txt"),
            error            = null,
        )
        val decoded = Json.decodeFromString<JobStatusResponse>(json.encodeToString(resp))
        assertEquals(82, decoded.fit_score)
        assertEquals("complete", decoded.status)
        assertEquals("/api/jobs/abc-123/resume.pdf", decoded.artifacts?.resume_pdf)
    }

    @Test
    fun `fit_score of 0 is not omitted`() {
        val resp = JobStatusResponse(job_id = "abc", status = "error", fit_score = 0)
        val encoded = json.encodeToString(resp)
        assert(encoded.contains("\"fit_score\":0")) { "fit_score of 0 must appear in JSON" }
    }

    @Test
    fun `SubmitJobResponse job_id survives round-trip`() {
        val resp = SubmitJobResponse(job_id = "test-uuid-1234")
        val decoded = Json.decodeFromString<SubmitJobResponse>(Json.encodeToString(resp))
        assertEquals("test-uuid-1234", decoded.job_id)
    }
}
