package com.jd.poller.bridge

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("bridge DTO JSON mapping")
class DtosTest {

    private val mapper = Json.mapper

    @Test
    @DisplayName("CompletedJob parses the bridge's snake_case feed payload")
    fun parsesCompletedJob() {
        val json = """
            {"job_id":"j1","completed_seq":7,"status":"done","message_id":"m1",
             "terminal_label":"Recruiter_Response_Required","draft_text":"hi","is_recruiter":true,
             "artifacts":{"resume_pdf":"/api/jobs/j1/resume.pdf","cover_letter_txt":"/api/jobs/j1/cover_letter.txt"},
             "error":null}
        """.trimIndent()
        val job = mapper.readValue(json, CompletedJob::class.java)
        assertEquals("j1", job.jobId)
        assertEquals(7L, job.completedSeq)
        assertEquals("m1", job.messageId)
        assertEquals("Recruiter_Response_Required", job.terminalLabel)
        assertTrue(job.isRecruiter)
        assertEquals("/api/jobs/j1/resume.pdf", job.artifacts?.resumePdf)
        assertNull(job.error)
    }

    @Test
    @DisplayName("CompletedJob tolerates a non-email job with missing fields")
    fun parsesMinimalCompletedJob() {
        val job = mapper.readValue("""{"job_id":"j2","completed_seq":1,"status":"done"}""", CompletedJob::class.java)
        assertEquals("j2", job.jobId)
        assertNull(job.messageId)
        assertNull(job.terminalLabel)
        assertTrue(!job.isRecruiter)
    }

    @Test
    @DisplayName("SubmitEmailRequest serializes to snake_case the bridge expects")
    fun serializesSubmitEmail() {
        val json = mapper.writeValueAsString(
            SubmitEmailRequest(messageId = "m1", body = "b", subject = "s", htmlBody = "<p>b</p>", from = "r@x.com", isRecruiterHint = true, idempotencyKey = "m1")
        )
        assertTrue(json.contains("\"message_id\":\"m1\""), json)
        assertTrue(json.contains("\"html_body\":\"<p>b</p>\""), json)
        assertTrue(json.contains("\"is_recruiter_hint\":true"), json)
        assertTrue(json.contains("\"idempotency_key\":\"m1\""), json)
    }

    @Test
    @DisplayName("SubmitJobResponse parses job_id + deduped")
    fun parsesSubmitResponse() {
        val r = mapper.readValue("""{"job_id":"abc","status":"pending","deduped":true}""", SubmitJobResponse::class.java)
        assertEquals("abc", r.jobId)
        assertTrue(r.deduped)
    }
}
