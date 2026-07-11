package com.jd.poller.bridge

import com.jd.poller.model.RawEmail
import com.jd.poller.testutil.FakeBridge
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration test: the real [PollerBridgeClient] (Apache HttpClient + Jackson) exercised over a
 * real socket against an in-process [FakeBridge]. Verifies the HTTP contract and JSON wire format
 * without touching the shared production bridge.
 */
@DisplayName("PollerBridgeClientIntegrationTest")
class PollerBridgeClientIntegrationTest {

    private lateinit var bridge: FakeBridge
    private lateinit var client: PollerBridgeClient

    @BeforeEach
    fun setup() {
        bridge = FakeBridge().start()
        client = PollerBridgeClient(bridge.baseUrl)
    }

    @AfterEach
    fun tearDown() = bridge.stop()

    @Test
    @DisplayName("submitEmail POSTs snake_case and returns the job id")
    fun submitEmail() {
        val jobId = client.submitEmail(
            RawEmail("m1", "Staff SDET", "rec@firm.com", "hello", "<p>hello</p>", false)
        )
        assertEquals("job-m1", jobId)
        assertEquals(1, bridge.submitted.size)
        val sent = bridge.submitted.first()
        assertEquals("m1", sent["message_id"])
        assertEquals("hello", sent["body"])
        assertEquals("<p>hello</p>", sent["html_body"])
        assertEquals("m1", sent["idempotency_key"])   // defaults to message_id
    }

    @Test
    @DisplayName("fetchCompleted deserializes the feed and honors the since cursor")
    fun fetchCompleted() {
        bridge.complete(CompletedJob(jobId = "j1", completedSeq = 1, status = "done", messageId = "m1", terminalLabel = "JD_Processed"))
        bridge.complete(CompletedJob(jobId = "j2", completedSeq = 2, status = "done", messageId = "m2", terminalLabel = "JD_Processed"))

        val all = client.fetchCompleted(since = 0)
        assertEquals(listOf("j1", "j2"), all.map { it.jobId })

        val afterFirst = client.fetchCompleted(since = 1)
        assertEquals(listOf("j2"), afterFirst.map { it.jobId })
    }

    @Test
    @DisplayName("markWritebackDone drops the job from the feed")
    fun markWritebackDone() {
        bridge.complete(CompletedJob(jobId = "j1", completedSeq = 1, status = "done", messageId = "m1"))
        assertTrue(client.markWritebackDone("j1"))
        assertTrue(bridge.wasWrittenBack("j1"))
        assertTrue(client.fetchCompleted(since = 0).isEmpty())
    }

    @Test
    @DisplayName("downloadArtifact streams bytes to a temp file; missing artifact yields null")
    fun downloadArtifact() {
        bridge.artifacts["/api/jobs/j1/resume.pdf"] = "PDFDATA".toByteArray()

        val file = client.downloadArtifact("/api/jobs/j1/resume.pdf", ".pdf")
        assertNotNull(file)
        assertEquals("PDFDATA", file.readText())

        assertNull(client.downloadArtifact("/api/jobs/j1/missing.pdf", ".pdf"))
        assertNull(client.downloadArtifact("", ".pdf"))   // blank path short-circuits to null
    }
}
