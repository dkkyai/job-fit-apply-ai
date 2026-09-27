package com.jdbridge.integration

import com.jdbridge.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Pins the exact JSON key sets of the three read endpoints other services consume:
 *
 *   GET /api/jobs/{id}        — status polling (dashboard, e2e suite)
 *   GET /api/queue/claim      — the Processor's work intake
 *   GET /api/jobs/completed   — the Poller's write-back feed and the Notifier's event stream
 *
 * Each job is driven through every field the store can carry, so a key missing here means a
 * consumer silently lost data. Refactors of the bridge's internal row types (JobRow and
 * friends) must leave these sets untouched; adding a key is a deliberate contract change and
 * belongs in this test.
 */
class WireContractTest {

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
    }

    private fun keys(body: String): Set<String> = Json.parseToJsonElement(body).jsonObject.keys

    /** An EMAIL_RAW job completed with every write-back and processed-posting field set. */
    private fun fullyLoadedEmailJob(): String = runBlocking {
        val jobId = enqueue(defaultJdJson(), "https://example.com/jobs/1", "idem-1",
            type = WorkItemType.EMAIL_RAW, messageId = "msg-1")
        val claimed = claimNext()!!
        recordResult(jobId, ResultRequest(
            pipeline_action  = "TAILOR",
            fit_score        = 88,
            strengths        = listOf("kotlin"),
            output_path      = "/tmp/out",
            has_cover_letter = true,
            company          = "Acme Corp",
            role_title       = "Staff SDET",
            job_url          = "https://example.com/jobs/1",
            artifact_url     = "https://markserv.example/report.md",
            terminal_label   = "JD_Tailored",
            draft_text       = "Thanks for reaching out",
            is_recruiter     = true,
            message_id       = "msg-1",
            claim_token      = claimed.claimToken,
        ))
        setArtifacts(jobId, ArtifactUrls(
            resume_pdf       = "/api/jobs/$jobId/resume.pdf",
            cover_letter_txt = "/api/jobs/$jobId/cover_letter.txt",
        ))
        jobId
    }

    @Test
    fun `GET job status - done job exposes exactly the status contract`() = testApplication {
        application { configureApplication() }
        val jobId = fullyLoadedEmailJob()

        val response = client.get("/api/jobs/$jobId")
        assertEquals(HttpStatusCode.OK, response.status)
        // title/company exist on JobStatusResponse but the route has never populated them.
        assertEquals(
            setOf("job_id", "status", "fit_score", "pipeline_action", "artifacts"),
            keys(response.bodyAsText()),
        )
    }

    @Test
    fun `GET job status - pending job exposes only id and status`() = testApplication {
        application { configureApplication() }
        val jobId = runBlocking { enqueue(defaultJdJson(), null, null) }

        val response = client.get("/api/jobs/$jobId")
        assertEquals(setOf("job_id", "status"), keys(response.bodyAsText()))
    }

    @Test
    fun `GET queue claim - exposes job_id, type, jd_record and claim_token`() = testApplication {
        application { configureApplication() }
        runBlocking { enqueue(defaultJdJson(), null, null, type = WorkItemType.EMAIL_RAW, messageId = "msg-2") }

        val response = client.get("/api/queue/claim")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertEquals(setOf("job_id", "type", "jd_record", "claim_token"), keys(body))
        // jd_record is the producer's payload verbatim, not a re-encoding.
        assertEquals(
            Json.parseToJsonElement(defaultJdJson()),
            Json.parseToJsonElement(body).jsonObject["jd_record"],
        )
    }

    @Test
    fun `GET completed feed - every write-back and posting field reaches consumers`() = testApplication {
        application { configureApplication() }
        val jobId = fullyLoadedEmailJob()

        val response = client.get("/api/jobs/completed?since=0&all=true")
        assertEquals(HttpStatusCode.OK, response.status)
        val events = Json.parseToJsonElement(response.bodyAsText()).jsonArray
        assertEquals(1, events.size)
        val event = events[0].jsonObject
        assertEquals(
            setOf(
                "job_id", "completed_seq", "status", "message_id", "terminal_label", "draft_text",
                "is_recruiter", "artifacts", "company", "role_title", "fit_score", "pipeline_action",
                "job_url", "artifact_url",
            ),
            event.keys,
        )
        assertEquals(jobId, event["job_id"]!!.jsonPrimitive.content)
        assertEquals("JD_Tailored", event["terminal_label"]!!.jsonPrimitive.content)
        assertEquals("Thanks for reaching out", event["draft_text"]!!.jsonPrimitive.content)
        assertEquals(true, event["is_recruiter"]!!.jsonPrimitive.boolean)
        assertEquals("msg-1", event["message_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `GET completed feed - writeback-done drops the job from the Poller feed only`() = testApplication {
        application { configureApplication() }
        val jobId = fullyLoadedEmailJob()

        assertEquals(HttpStatusCode.OK, client.post("/api/jobs/$jobId/writeback-done").status)

        val pollerFeed = Json.parseToJsonElement(client.get("/api/jobs/completed?since=0").bodyAsText()).jsonArray
        val eventStream = Json.parseToJsonElement(client.get("/api/jobs/completed?since=0&all=true").bodyAsText()).jsonArray
        assertTrue(pollerFeed.isEmpty(), "write-back acknowledged → gone from the Poller's queue")
        assertEquals(1, eventStream.size, "the Notifier's event stream still carries it")
    }
}
