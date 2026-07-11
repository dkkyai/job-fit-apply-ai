package com.jdbridge.integration

import com.jdbridge.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the Phase-1 bridge additions: the EMAIL_RAW work-item, and the Gmail
 * write-back feed (completed jobs + writeback_done). SQLite only — no Postgres.
 */
class WriteBackApiTest {

    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
    }

    private suspend fun submitEmail(client: io.ktor.client.HttpClient, messageId: String) =
        client.post("/api/emails") {
            contentType(ContentType.Application.Json)
            setBody("""{"message_id":"$messageId","subject":"Staff SDET","body":"${"x".repeat(60)}","from":"r@x.com","is_recruiter_hint":true}""")
        }

    @Test
    fun `POST emails enqueues EMAIL_RAW and claim returns the type + payload`() = testApplication {
        application { configureApplication() }
        assertEquals(HttpStatusCode.Accepted, submitEmail(client, "msg-1").status)

        val claim = client.get("/api/queue/claim")
        assertEquals(HttpStatusCode.OK, claim.status)
        val body = json.parseToJsonElement(claim.bodyAsText()).jsonObject
        assertEquals(WorkItemType.EMAIL_RAW, body["type"]!!.jsonPrimitive.content)
        assertEquals("msg-1", body["jd_record"]!!.jsonObject["message_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `email submit dedups by message-id`() = testApplication {
        application { configureApplication() }
        submitEmail(client, "dup-1")
        val second = submitEmail(client, "dup-1")
        assertEquals(HttpStatusCode.OK, second.status)
        assertTrue(json.parseToJsonElement(second.bodyAsText()).jsonObject["deduped"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `result write-back fields surface in the completed feed, then writeback-done clears it`() = testApplication {
        application { configureApplication() }
        submitEmail(client, "msg-wb")
        val jobId = json.parseToJsonElement(client.get("/api/queue/claim").bodyAsText())
            .jsonObject["job_id"]!!.jsonPrimitive.content

        client.post("/api/jobs/$jobId/result") {
            contentType(ContentType.Application.Json)
            setBody("""{"pipeline_action":"tailor","fit_score":88,"terminal_label":"Recruiter_Response_Required","draft_text":"Hi there","is_recruiter":true,"message_id":"msg-wb"}""")
        }

        val feed = json.parseToJsonElement(client.get("/api/jobs/completed").bodyAsText()).jsonArray
        assertEquals(1, feed.size)
        val item = feed[0].jsonObject
        assertEquals("msg-wb", item["message_id"]!!.jsonPrimitive.content)
        assertEquals("Recruiter_Response_Required", item["terminal_label"]!!.jsonPrimitive.content)
        assertEquals("Hi there", item["draft_text"]!!.jsonPrimitive.content)
        assertTrue(item["is_recruiter"]!!.jsonPrimitive.boolean)

        assertEquals(HttpStatusCode.OK, client.post("/api/jobs/$jobId/writeback-done").status)
        val after = json.parseToJsonElement(client.get("/api/jobs/completed").bodyAsText()).jsonArray
        assertTrue(after.isEmpty(), "writeback-done should drop the job from the feed")
    }

    @Test
    fun `POST emails with an empty body is rejected 422`() = testApplication {
        application { configureApplication() }
        val res = client.post("/api/emails") {
            contentType(ContentType.Application.Json)
            setBody("""{"message_id":"empty-1","body":""}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, res.status)
    }

    @Test
    fun `writeback-done on an unknown job returns 404`() = testApplication {
        application { configureApplication() }
        assertEquals(
            HttpStatusCode.NotFound,
            client.post("/api/jobs/does-not-exist/writeback-done").status,
        )
    }

    @Test
    fun `completed feed filters by the since cursor`() = testApplication {
        application { configureApplication() }
        // Two completed email jobs → seq 1 and 2.
        val ids = listOf("s1", "s2").map { mid ->
            submitEmail(client, mid)
            val jid = json.parseToJsonElement(client.get("/api/queue/claim").bodyAsText())
                .jsonObject["job_id"]!!.jsonPrimitive.content
            client.post("/api/jobs/$jid/result") {
                contentType(ContentType.Application.Json)
                setBody("""{"pipeline_action":"skip","fit_score":0,"message_id":"$mid"}""")
            }
            jid
        }
        assertEquals(2, ids.size)

        val all = json.parseToJsonElement(client.get("/api/jobs/completed").bodyAsText()).jsonArray
        assertEquals(2, all.size)
        val firstSeq = all[0].jsonObject["completed_seq"]!!.jsonPrimitive.long

        val afterFirst = json.parseToJsonElement(
            client.get("/api/jobs/completed?since=$firstSeq").bodyAsText()
        ).jsonArray
        assertEquals(1, afterFirst.size)
        assertTrue(afterFirst[0].jsonObject["completed_seq"]!!.jsonPrimitive.long > firstSeq)
    }

    // ── Regression: blank/null terminal_label must surface in the completed feed ──
    // Bug: the Poller's WritebackLoop skipped LabelApplier.apply() when terminalLabel
    // was blank/null, so JD_Processing was never cleared in Gmail — but writeback_done
    // was still marked, silently dropping the job from the feed with the label stuck.
    // These tests verify the bridge side: a result with no terminal_label still
    // produces a completed-feed entry with message_id present, and writeback-done
    // correctly clears it. The Poller-side tests (WritebackLoopTest) verify the
    // label-clearing behaviour itself.

    @Test
    fun `result with blank terminal_label surfaces in completed feed with message_id`() = testApplication {
        application { configureApplication() }
        submitEmail(client, "msg-blank")
        val jobId = json.parseToJsonElement(client.get("/api/queue/claim").bodyAsText())
            .jsonObject["job_id"]!!.jsonPrimitive.content

        client.post("/api/jobs/$jobId/result") {
            contentType(ContentType.Application.Json)
            setBody("""{"pipeline_action":"skip","fit_score":0,"message_id":"msg-blank","terminal_label":""}""")
        }

        val feed = json.parseToJsonElement(client.get("/api/jobs/completed").bodyAsText()).jsonArray
        assertEquals(1, feed.size)
        val item = feed[0].jsonObject
        assertEquals("msg-blank", item["message_id"]!!.jsonPrimitive.content)
        // terminal_label should be null or blank — the Poller must still clear JD_Processing.
        assertTrue(
            item["terminal_label"]?.jsonPrimitive?.contentOrNull.isNullOrBlank(),
            "terminal_label should be null/blank but the job must still appear in the feed"
        )

        // writeback-done should clear it from the feed — the Poller should always be able to drain it.
        assertEquals(HttpStatusCode.OK, client.post("/api/jobs/$jobId/writeback-done").status)
        val after = json.parseToJsonElement(client.get("/api/jobs/completed").bodyAsText()).jsonArray
        assertTrue(after.isEmpty(), "writeback-done should drop the job from the feed even with blank terminal_label")
    }

    @Test
    fun `result with null terminal_label surfaces in completed feed with message_id`() = testApplication {
        application { configureApplication() }
        submitEmail(client, "msg-null")
        val jobId = json.parseToJsonElement(client.get("/api/queue/claim").bodyAsText())
            .jsonObject["job_id"]!!.jsonPrimitive.content

        // No terminal_label field at all — defaults to null in ResultRequest.
        client.post("/api/jobs/$jobId/result") {
            contentType(ContentType.Application.Json)
            setBody("""{"pipeline_action":"skip","fit_score":0,"message_id":"msg-null"}""")
        }

        val feed = json.parseToJsonElement(client.get("/api/jobs/completed").bodyAsText()).jsonArray
        assertEquals(1, feed.size)
        val item = feed[0].jsonObject
        assertEquals("msg-null", item["message_id"]!!.jsonPrimitive.content)
        assertTrue(
            item["terminal_label"]?.jsonPrimitive?.contentOrNull.isNullOrBlank(),
            "terminal_label should be null but the job must still appear in the feed"
        )

        assertEquals(HttpStatusCode.OK, client.post("/api/jobs/$jobId/writeback-done").status)
        val after = json.parseToJsonElement(client.get("/api/jobs/completed").bodyAsText()).jsonArray
        assertTrue(after.isEmpty())
    }

    @Test
    fun `blank terminal_label job stays in completed feed until writeback-done`() = testApplication {
        application { configureApplication() }
        submitEmail(client, "msg-persist")
        val jobId = json.parseToJsonElement(client.get("/api/queue/claim").bodyAsText())
            .jsonObject["job_id"]!!.jsonPrimitive.content

        client.post("/api/jobs/$jobId/result") {
            contentType(ContentType.Application.Json)
            setBody("""{"pipeline_action":"skip","fit_score":0,"message_id":"msg-persist","terminal_label":""}""")
        }

        // Should remain in the feed until explicitly marked done.
        val before = json.parseToJsonElement(client.get("/api/jobs/completed").bodyAsText()).jsonArray
        assertEquals(1, before.size)

        // Simulate a second poll — still there.
        val stillThere = json.parseToJsonElement(client.get("/api/jobs/completed").bodyAsText()).jsonArray
        assertEquals(1, stillThere.size, "job should persist in the feed until writeback-done")

        client.post("/api/jobs/$jobId/writeback-done")
        val after = json.parseToJsonElement(client.get("/api/jobs/completed").bodyAsText()).jsonArray
        assertTrue(after.isEmpty())
    }
}
