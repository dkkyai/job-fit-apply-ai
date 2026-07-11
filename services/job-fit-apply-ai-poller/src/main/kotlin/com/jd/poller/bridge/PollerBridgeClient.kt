package com.jd.poller.bridge

import com.jd.poller.config.PollerConfig
import com.jd.poller.model.RawEmail
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.classic.methods.HttpPost
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.core5.http.ContentType
import org.apache.hc.core5.http.io.entity.EntityUtils
import org.apache.hc.core5.http.io.entity.StringEntity
import java.io.File

/**
 * HTTP client the Poller uses against the bridge:
 *   - submit raw emails (POST /api/emails)
 *   - drain the write-back feed (GET /api/jobs/completed)
 *   - mark a job written back (POST /api/jobs/{id}/writeback-done)
 *   - download artifacts (resume/cover letter) for recruiter drafts
 */
open class PollerBridgeClient(
    private val baseUrl: String = PollerConfig.JD_BRIDGE_URL,
) {
    private val http = HttpClients.createDefault()
    private val mapper = Json.mapper

    /** POST /api/emails — returns the job_id (existing id if the bridge deduped). */
    open fun submitEmail(email: RawEmail): String {
        val req = SubmitEmailRequest(
            messageId       = email.messageId,
            body            = email.body,
            subject         = email.subject,
            htmlBody        = email.htmlBody.ifBlank { null },
            from            = email.from,
            isRecruiterHint = email.isRecruiterHint,
            idempotencyKey  = email.messageId,
        )
        val post = HttpPost("$baseUrl/api/emails").apply {
            entity = StringEntity(mapper.writeValueAsString(req), ContentType.APPLICATION_JSON)
        }
        return http.execute(post) { resp ->
            val body = EntityUtils.toString(resp.entity, Charsets.UTF_8)
            check(resp.code in 200..202) { "POST /api/emails → ${resp.code}: $body" }
            mapper.readValue(body, SubmitJobResponse::class.java).jobId
        }
    }

    /** GET /api/jobs/completed?since=&limit= — jobs awaiting Gmail write-back. */
    open fun fetchCompleted(since: Long = 0L, limit: Int = 50): List<CompletedJob> {
        val get = HttpGet("$baseUrl/api/jobs/completed?since=$since&limit=$limit")
        return http.execute(get) { resp ->
            val body = EntityUtils.toString(resp.entity, Charsets.UTF_8)
            check(resp.code == 200) { "GET /api/jobs/completed → ${resp.code}: $body" }
            mapper.readValue(body, Array<CompletedJob>::class.java).toList()
        }
    }

    /** POST /api/jobs/{id}/writeback-done — drops the job from the feed. */
    open fun markWritebackDone(jobId: String): Boolean {
        val post = HttpPost("$baseUrl/api/jobs/$jobId/writeback-done")
        return http.execute(post) { resp ->
            EntityUtils.consumeQuietly(resp.entity)
            resp.code == 200
        }
    }

    /** Download an artifact at a bridge-relative path (e.g. /api/jobs/x/resume.pdf) to a temp file, or null. */
    open fun downloadArtifact(relativePath: String, suffix: String): File? {
        if (relativePath.isBlank()) return null
        val get = HttpGet("$baseUrl$relativePath")
        return http.execute(get) { resp ->
            if (resp.code != 200) {
                EntityUtils.consumeQuietly(resp.entity)
                return@execute null
            }
            val tmp = File.createTempFile("poller-artifact-", suffix)
            tmp.deleteOnExit()
            tmp.outputStream().use { out -> resp.entity.writeTo(out) }
            tmp
        }
    }
}
