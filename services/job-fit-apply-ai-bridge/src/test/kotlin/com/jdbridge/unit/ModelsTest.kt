package com.jdbridge.unit

import com.jdbridge.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class JobStatusValuesTest {

    @Test
    fun `all status enum values are correct strings`() {
        assertEquals("pending",  JobStatus.PENDING.value)
        assertEquals("claimed",  JobStatus.CLAIMED.value)
        assertEquals("done",     JobStatus.DONE.value)
        assertEquals("error",    JobStatus.ERROR.value)
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
        val json = Json.encodeToString(JobStatus.DONE)
        assertEquals("\"done\"", json)
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
            jd_text         = "x".repeat(200),
            role_title      = "Staff SDET",
            company         = "Acme Corp",
            location        = "Seattle, WA",
            job_url         = "https://example.com/job/1",
            source          = "JSEARCH",
            idempotency_key = "key-123",
        )
        val decoded = Json.decodeFromString<SubmitJobRequest>(json.encodeToString(req))
        assertEquals("Staff SDET",  decoded.role_title)
        assertEquals("Acme Corp",   decoded.company)
        assertEquals("Seattle, WA", decoded.location)
        assertEquals("JSEARCH",     decoded.source)
        assertEquals("key-123",     decoded.idempotency_key)
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
        val resp = JobStatusResponse(job_id = "abc", status = "pending")
        val encoded = json.encodeToString(resp)
        assertFalse(encoded.contains("fit_score"),     "null fit_score should be omitted")
        assertFalse(encoded.contains("\"artifacts\""), "null artifacts should be omitted")
        assertFalse(encoded.contains("\"error\""),     "null error should be omitted")
    }

    @Test
    fun `complete response with all fields serializes correctly`() {
        val resp = JobStatusResponse(
            job_id          = "abc-123",
            status          = JobStatus.DONE.value,
            fit_score       = 82,
            pipeline_action = "TAILOR",
            artifacts       = ArtifactUrls("/api/jobs/abc-123/resume.pdf", "/api/jobs/abc-123/cover_letter.txt"),
            error           = null,
        )
        val decoded = Json.decodeFromString<JobStatusResponse>(json.encodeToString(resp))
        assertEquals(82, decoded.fit_score)
        assertEquals("done", decoded.status)
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
        val resp = SubmitJobResponse(job_id = "test-uuid-1234", status = "pending")
        val decoded = Json.decodeFromString<SubmitJobResponse>(Json.encodeToString(resp))
        assertEquals("test-uuid-1234", decoded.job_id)
    }

    @Test
    fun `SubmitJobResponse deduped true survives round-trip`() {
        val resp = SubmitJobResponse(job_id = "dup-id", status = "pending", deduped = true)
        val decoded = Json.decodeFromString<SubmitJobResponse>(Json.encodeToString(resp))
        assertEquals(true, decoded.deduped)
    }
}

/**
 * ErrorResponse and ClaimResponse are only ever encoded (as HTTP responses) in the
 * integration tests, which parse the body back as a generic JsonObject rather than
 * decoding into the typed class — so the generated deserializer was never exercised.
 * These decode-direction round-trips close that gap.
 */
class ErrorResponseSerializationTest {

    @Test
    fun `ErrorResponse decodes from JSON`() {
        val decoded = Json.decodeFromString<ErrorResponse>("""{"detail":"jd_text must be at least 150 characters"}""")
        assertEquals("jd_text must be at least 150 characters", decoded.detail)
    }

    @Test
    fun `ErrorResponse round-trips through encode then decode`() {
        val resp = ErrorResponse(detail = "Job not found")
        val decoded = Json.decodeFromString<ErrorResponse>(Json.encodeToString(resp))
        assertEquals("Job not found", decoded.detail)
    }
}

class ClaimResponseSerializationTest {

    @Test
    fun `ClaimResponse decodes with explicit type`() {
        val decoded = Json.decodeFromString<ClaimResponse>(
            """{"job_id":"j1","type":"EMAIL_RAW","jd_record":{"a":1}}"""
        )
        assertEquals("j1", decoded.job_id)
        assertEquals("EMAIL_RAW", decoded.type)
    }

    @Test
    fun `ClaimResponse decodes with default type when omitted`() {
        val decoded = Json.decodeFromString<ClaimResponse>("""{"job_id":"j2","jd_record":{}}""")
        assertEquals(WorkItemType.JD_SCRAPED, decoded.type)
    }

    @Test
    fun `ClaimResponse round-trips a nested jd_record object`() {
        val resp = ClaimResponse(
            job_id    = "j3",
            type      = WorkItemType.JD_PAGE_RAW,
            jd_record = Json.parseToJsonElement("""{"url":"https://x","text":"hello"}"""),
        )
        val decoded = Json.decodeFromString<ClaimResponse>(Json.encodeToString(resp))
        assertEquals(WorkItemType.JD_PAGE_RAW, decoded.type)
        assertEquals("https://x", decoded.jd_record.jsonObject["url"]!!.jsonPrimitive.content)
    }
}

class CompletedJobSerializationTest {

    @Test
    fun `CompletedJob decodes with only the required fields present`() {
        val decoded = Json.decodeFromString<CompletedJob>(
            """{"job_id":"j1","completed_seq":5,"status":"done"}"""
        )
        assertEquals("j1", decoded.job_id)
        assertEquals(5L, decoded.completed_seq)
        assertEquals("done", decoded.status)
        assertNull(decoded.message_id)
        assertNull(decoded.artifacts)
        assertFalse(decoded.is_recruiter)
    }

    @Test
    fun `CompletedJob missing a required field throws`() {
        assertFailsWith<Exception> {
            Json.decodeFromString<CompletedJob>("""{"completed_seq":5,"status":"done"}""")
        }
    }

    @Test
    fun `CompletedJob full round-trip carries every field including nested artifacts`() {
        val job = CompletedJob(
            job_id          = "j-full",
            completed_seq   = 42L,
            status          = "done",
            message_id      = "msg-1",
            terminal_label  = "Recruiter_Response_Required",
            draft_text      = "Hi there",
            is_recruiter    = true,
            artifacts       = ArtifactUrls("/api/jobs/j-full/resume.pdf", "/api/jobs/j-full/cover_letter.txt"),
            error           = null,
            company         = "Acme",
            role_title      = "Staff SDET",
            fit_score       = 88,
            pipeline_action = "tailor",
            job_url         = "https://acme.co/job",
            artifact_url    = "http://markserv/report/report.md",
        )
        val decoded = Json.decodeFromString<CompletedJob>(Json.encodeToString(job))
        assertEquals(job.job_id, decoded.job_id)
        assertEquals(job.completed_seq, decoded.completed_seq)
        assertEquals(job.message_id, decoded.message_id)
        assertEquals(job.terminal_label, decoded.terminal_label)
        assertEquals(job.draft_text, decoded.draft_text)
        assertEquals(job.is_recruiter, decoded.is_recruiter)
        assertEquals(job.artifacts?.resume_pdf, decoded.artifacts?.resume_pdf)
        assertEquals(job.company, decoded.company)
        assertEquals(job.role_title, decoded.role_title)
        assertEquals(job.fit_score, decoded.fit_score)
        assertEquals(job.pipeline_action, decoded.pipeline_action)
        assertEquals(job.job_url, decoded.job_url)
        assertEquals(job.artifact_url, decoded.artifact_url)
    }

    @Test
    fun `CompletedJob with an error and no artifacts round-trips`() {
        val job = CompletedJob(job_id = "j-err", completed_seq = 1L, status = "error", error = "boom")
        val decoded = Json.decodeFromString<CompletedJob>(Json.encodeToString(job))
        assertEquals("boom", decoded.error)
        assertNull(decoded.artifacts)
    }
}

class SubmitEmailRequestSerializationTest {

    @Test
    fun `all optional fields round-trip correctly`() {
        val req = SubmitEmailRequest(
            message_id       = "msg-1",
            body             = "some raw email body",
            subject          = "Re: Staff SDET",
            html_body        = "<p>some raw email body</p>",
            from             = "recruiter@acme.com",
            is_recruiter_hint = true,
            idempotency_key  = "key-xyz",
        )
        val decoded = Json.decodeFromString<SubmitEmailRequest>(Json.encodeToString(req))
        assertEquals("msg-1", decoded.message_id)
        assertEquals("Re: Staff SDET", decoded.subject)
        assertEquals("<p>some raw email body</p>", decoded.html_body)
        assertEquals("recruiter@acme.com", decoded.from)
        assertEquals(true, decoded.is_recruiter_hint)
        assertEquals("key-xyz", decoded.idempotency_key)
    }

    @Test
    fun `defaults apply when only the required fields are present`() {
        val decoded = Json.decodeFromString<SubmitEmailRequest>(
            """{"message_id":"msg-2","body":"hello"}"""
        )
        assertEquals("", decoded.subject)
        assertNull(decoded.html_body)
        assertEquals("", decoded.from)
        assertFalse(decoded.is_recruiter_hint)
        assertNull(decoded.idempotency_key)
    }
}

class SubmitPageCaptureRequestSerializationTest {

    @Test
    fun `all optional fields round-trip correctly`() {
        val req = SubmitPageCaptureRequest(
            url             = "https://acme.example/job/1",
            text            = "x".repeat(300),
            title           = "Staff SDET",
            idempotency_key = "page-key-1",
        )
        val decoded = Json.decodeFromString<SubmitPageCaptureRequest>(Json.encodeToString(req))
        assertEquals("https://acme.example/job/1", decoded.url)
        assertEquals("Staff SDET", decoded.title)
        assertEquals("page-key-1", decoded.idempotency_key)
    }

    @Test
    fun `title and idempotency_key default when omitted`() {
        val decoded = Json.decodeFromString<SubmitPageCaptureRequest>(
            """{"url":"https://acme.example/job/2","text":"hello"}"""
        )
        assertEquals("", decoded.title)
        assertNull(decoded.idempotency_key)
    }
}
