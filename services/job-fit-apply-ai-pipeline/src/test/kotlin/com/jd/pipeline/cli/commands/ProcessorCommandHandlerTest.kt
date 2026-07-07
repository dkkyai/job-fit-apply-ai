package com.jd.pipeline.cli.commands

import com.jd.pipeline.client.BridgeClient
import com.jd.pipeline.client.ClaimDto
import com.jd.pipeline.client.ClaimedEmail
import com.jd.pipeline.client.WorkItemType
import com.jd.pipeline.nodes.ScrapeJdNode
import com.jd.pipeline.pipeline.IngestionPipeline
import com.jd.pipeline.pipeline.ProcessingPipeline
import com.jd.pipeline.source.IngestionSource
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.source.JdRecord
import com.jd.pipeline.source.ProcessingResult
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("ProcessorCommandHandlerTest")
class ProcessorCommandHandlerTest {

    private fun fakeRecord() = JdRecord(
        jdText    = "x".repeat(200),
        company   = "Acme Corp",
        roleTitle = "Staff SDET",
        location  = "Seattle, WA",
        jobUrl    = "https://boards.example.com/acme/job/123",
        source    = IngestionSource.EMAIL,
    )

    private fun successResult() = ProcessingResult(
        pipelineAction = "TAILOR",
        fitScore       = 82,
        strengths      = listOf("Kotlin", "CI/CD"),
        isDuplicate    = false,
        outputPath     = null,
        hasCoverLetter = false,
        error          = null,
        artifactUrl    = "https://artifacts.example.com/acme",
    )

    @Test
    @DisplayName("processor processes a claimed job and posts the result")
    fun processorProcessesClaimAndPostsResult() {
        val record = fakeRecord()
        val result = successResult()
        val claim  = ClaimDto(jobId = "job-1", jdRecord = record)

        val resultPosted = CountDownLatch(1)

        val bridge = mock<BridgeClient>()
        val pipeline = mock<ProcessingPipeline>()

        val processorThread = Thread { ProcessorCommandHandler.run(bridge, pipeline) }

        // First claim: return the job. Second claim: interrupt to stop the loop.
        whenever(bridge.claim())
            .doReturn(claim)
            .doAnswer {
                processorThread.interrupt()
                null
            }
        whenever(pipeline.invoke(record)).doReturn(result)
        doAnswer { resultPosted.countDown() }.whenever(bridge).postResult(any(), any())

        processorThread.isDaemon = true
        processorThread.start()

        assertTrue(resultPosted.await(5, TimeUnit.SECONDS), "processor should post result within 5s")

        verify(pipeline).invoke(record)
        verify(bridge).postResult("job-1", result)
    }

    @Test
    @DisplayName("processor posts a SKIP error result when pipeline throws")
    fun processorPostsErrorResultOnPipelineException() {
        val record = fakeRecord()
        val claim  = ClaimDto(jobId = "job-err", jdRecord = record)

        val resultPosted = CountDownLatch(1)

        val bridge = mock<BridgeClient>()
        val pipeline = mock<ProcessingPipeline>()

        val processorThread = Thread { ProcessorCommandHandler.run(bridge, pipeline) }

        whenever(bridge.claim())
            .doReturn(claim)
            .doAnswer {
                processorThread.interrupt()
                null
            }
        whenever(pipeline.invoke(record)).thenThrow(RuntimeException("pipeline exploded"))
        doAnswer { resultPosted.countDown() }.whenever(bridge).postResult(any(), any())

        processorThread.isDaemon = true
        processorThread.start()

        assertTrue(resultPosted.await(5, TimeUnit.SECONDS), "processor should post error result within 5s")

        verify(bridge).postResult(
            org.mockito.kotlin.eq("job-err"),
            org.mockito.kotlin.argThat { error != null && error!!.contains("pipeline exploded") }
        )
    }

    // (Per-job Discord/Telegram messaging moved to the Notifier service — see NotifierTest there.)

    // ── EMAIL_RAW claims (scan/scrape happen in the Processor) ──────────────────

    @Nested
    @DisplayName("EMAIL_RAW claims")
    inner class EmailRawClaims {

        private fun emailClaim() = ClaimDto(
            jobId = "job-email",
            type  = WorkItemType.EMAIL_RAW,
            email = ClaimedEmail(
                messageId = "m1", subject = "Staff SDET role", body = "hi",
                htmlBody = null, from = "rec@firm.com", isRecruiterHint = false,
            ),
        )

        private fun ingested(isJobPosting: Boolean, isDigest: Boolean = false, children: List<JDState> = emptyList()) =
            JDState(
                isJobPosting = isJobPosting,
                intake = IntakeContext.Email(
                    emailId = "m1", from = "rec@firm.com", subject = "Staff SDET role",
                    rawBody = "hi", htmlBody = "",
                    isRecruiter = false, isDigest = isDigest, isInlineDigest = false,
                ),
                digestJobs = children,
            )

        @Test
        @DisplayName("scans a single-posting email and processes the resolved record")
        fun scansAndProcessesSinglePosting() {
            val record = fakeRecord()
            val bridge = mock<BridgeClient>()
            val pipeline = mock<ProcessingPipeline>()
            val ingestion = mock<IngestionPipeline>()

            val processorThread = Thread { ProcessorCommandHandler.run(bridge, pipeline, ingestion) }
            val posted = CountDownLatch(1)

            whenever(bridge.claim()).doReturn(emailClaim()).doAnswer { processorThread.interrupt(); null }
            whenever(ingestion.invoke(any())).doReturn(ingested(isJobPosting = true))
            whenever(ingestion.toJdRecord(any(), anyOrNull())).doReturn(record)
            whenever(pipeline.invoke(record)).doReturn(successResult())
            doAnswer { posted.countDown() }.whenever(bridge).postResult(any(), any())

            processorThread.isDaemon = true
            processorThread.start()

            assertTrue(posted.await(5, TimeUnit.SECONDS), "processor should post result within 5s")
            verify(pipeline).invoke(record)
            verify(bridge).postResult(eq("job-email"), any())
        }

        @Test
        @DisplayName("re-enqueues digest children and completes the parent without processing it")
        fun reEnqueuesDigestChildren() {
            val bridge = mock<BridgeClient>()
            val pipeline = mock<ProcessingPipeline>()
            val ingestion = mock<IngestionPipeline>()

            val children = listOf(ingested(isJobPosting = true), ingested(isJobPosting = true))
            val childRecord = fakeRecord()

            val processorThread = Thread { ProcessorCommandHandler.run(bridge, pipeline, ingestion) }
            val posted = CountDownLatch(1)

            whenever(bridge.claim()).doReturn(emailClaim()).doAnswer { processorThread.interrupt(); null }
            whenever(ingestion.invoke(any())).doReturn(ingested(isJobPosting = false, isDigest = true, children = children))
            whenever(ingestion.toJdRecord(any(), anyOrNull())).doReturn(childRecord)
            doAnswer { posted.countDown() }.whenever(bridge).postResult(any(), any())

            processorThread.isDaemon = true
            processorThread.start()

            assertTrue(posted.await(5, TimeUnit.SECONDS), "processor should complete the parent within 5s")
            verify(bridge, times(2)).submit(childRecord)      // one per job-posting child
            verify(bridge).postResult(eq("job-email"), any()) // parent digest completed
            verify(pipeline, never()).invoke(any())           // the digest parent itself is never processed
        }

        @Test
        @DisplayName("skips a non-job email and never processes it")
        fun skipsNonJobEmail() {
            val bridge = mock<BridgeClient>()
            val pipeline = mock<ProcessingPipeline>()
            val ingestion = mock<IngestionPipeline>()

            val processorThread = Thread { ProcessorCommandHandler.run(bridge, pipeline, ingestion) }
            val posted = CountDownLatch(1)

            whenever(bridge.claim()).doReturn(emailClaim()).doAnswer { processorThread.interrupt(); null }
            whenever(ingestion.invoke(any())).doReturn(ingested(isJobPosting = false))
            doAnswer { posted.countDown() }.whenever(bridge).postResult(any(), any())

            processorThread.isDaemon = true
            processorThread.start()

            assertTrue(posted.await(5, TimeUnit.SECONDS), "processor should complete the non-job within 5s")
            verify(bridge).postResult(eq("job-email"), any())
            verify(pipeline, never()).invoke(any())
            verify(bridge, never()).submit(any())
        }

        @Test
        @DisplayName("posts a skip when an EMAIL_RAW claim has no email payload")
        fun skipsWhenEmailPayloadMissing() {
            val bridge = mock<BridgeClient>()
            val pipeline = mock<ProcessingPipeline>()
            val ingestion = mock<IngestionPipeline>()

            val badClaim = ClaimDto(jobId = "job-bad", type = WorkItemType.EMAIL_RAW, email = null)
            val processorThread = Thread { ProcessorCommandHandler.run(bridge, pipeline, ingestion) }
            val posted = CountDownLatch(1)

            whenever(bridge.claim()).doReturn(badClaim).doAnswer { processorThread.interrupt(); null }
            doAnswer { posted.countDown() }.whenever(bridge).postResult(any(), any())

            processorThread.isDaemon = true
            processorThread.start()

            assertTrue(posted.await(5, TimeUnit.SECONDS), "processor should post a skip within 5s")
            verify(bridge).postResult(eq("job-bad"), argThat { error != null && error!!.contains("missing email payload") })
            verify(ingestion, never()).invoke(any())
            verify(pipeline, never()).invoke(any())
        }
    }

    // ── JD_PAGE_RAW claims (browser-extension captures — LLM-extracted here) ────

    @Nested
    @DisplayName("JD_PAGE_RAW claims")
    inner class PageCaptureClaims {

        private fun pageClaim() = ClaimDto(
            jobId       = "job-page",
            type        = WorkItemType.JD_PAGE_RAW,
            pageCapture = com.jd.pipeline.client.ClaimedPageCapture(
                url = "https://linkedin.com/jobs/view/123", text = "raw page text".repeat(50), title = "Staff SDET",
            ),
        )

        /** A JDState as the dual-mode ScrapeJdNode would return after extraction. */
        private fun extracted(jdText: String, error: String = "") = JDState(
            jobUrl = "https://linkedin.com/jobs/view/123",
            company = "Acme", roleTitle = "Staff SDET", location = "Remote",
            jdText = jdText, isJobPosting = jdText.isNotBlank(), error = error,
        )

        @Test
        @DisplayName("extracts a captured page and processes it as an EXTENSION-sourced JdRecord")
        fun extractsAndProcesses() {
            val bridge = mock<BridgeClient>()
            val pipeline = mock<ProcessingPipeline>()
            val ingestion = mock<IngestionPipeline>()
            val scrapeNode = mock<ScrapeJdNode>()

            whenever(ingestion.scrapeNode).doReturn(scrapeNode)
            whenever(scrapeNode.process(any())).doReturn(extracted("x".repeat(300)))

            val recordCaptor = argumentCaptor<JdRecord>()
            whenever(pipeline.invoke(recordCaptor.capture())).doReturn(successResult())

            val processorThread = Thread { ProcessorCommandHandler.run(bridge, pipeline, ingestion) }
            val posted = CountDownLatch(1)
            whenever(bridge.claim()).doReturn(pageClaim()).doAnswer { processorThread.interrupt(); null }
            doAnswer { posted.countDown() }.whenever(bridge).postResult(any(), any())

            processorThread.isDaemon = true
            processorThread.start()
            assertTrue(posted.await(5, TimeUnit.SECONDS), "processor should post result within 5s")

            verify(bridge).postResult(eq("job-page"), any())
            val record = recordCaptor.firstValue
            assertEquals(IngestionSource.EXTENSION, record.source)
            assertEquals("https://linkedin.com/jobs/view/123", record.idempotencyKey)
            assertEquals("Acme", record.company)
        }

        @Test
        @DisplayName("skips (no processing) when extraction yields no usable JD")
        fun skipsWhenNoJdExtracted() {
            val bridge = mock<BridgeClient>()
            val pipeline = mock<ProcessingPipeline>()
            val ingestion = mock<IngestionPipeline>()
            val scrapeNode = mock<ScrapeJdNode>()

            whenever(ingestion.scrapeNode).doReturn(scrapeNode)
            whenever(scrapeNode.process(any())).doReturn(extracted(jdText = "", error = "scrape_jd: empty"))

            val processorThread = Thread { ProcessorCommandHandler.run(bridge, pipeline, ingestion) }
            val posted = CountDownLatch(1)
            whenever(bridge.claim()).doReturn(pageClaim()).doAnswer { processorThread.interrupt(); null }
            doAnswer { posted.countDown() }.whenever(bridge).postResult(any(), any())

            processorThread.isDaemon = true
            processorThread.start()
            assertTrue(posted.await(5, TimeUnit.SECONDS), "processor should post a skip within 5s")

            verify(bridge).postResult(eq("job-page"), argThat { error != null && error!!.contains("job posting") })
            verify(pipeline, never()).invoke(any())
        }

        @Test
        @DisplayName("posts a skip when a JD_PAGE_RAW claim has no page payload")
        fun skipsWhenPayloadMissing() {
            val bridge = mock<BridgeClient>()
            val pipeline = mock<ProcessingPipeline>()
            val ingestion = mock<IngestionPipeline>()

            val badClaim = ClaimDto(jobId = "job-bad-page", type = WorkItemType.JD_PAGE_RAW, pageCapture = null)
            val processorThread = Thread { ProcessorCommandHandler.run(bridge, pipeline, ingestion) }
            val posted = CountDownLatch(1)
            whenever(bridge.claim()).doReturn(badClaim).doAnswer { processorThread.interrupt(); null }
            doAnswer { posted.countDown() }.whenever(bridge).postResult(any(), any())

            processorThread.isDaemon = true
            processorThread.start()
            assertTrue(posted.await(5, TimeUnit.SECONDS), "processor should post a skip within 5s")

            verify(bridge).postResult(eq("job-bad-page"), argThat { error != null && error!!.contains("missing page payload") })
            verify(pipeline, never()).invoke(any())
        }
    }

    @Test
    @DisplayName("processor sleeps and retries when queue is empty")
    fun processorSleepsOnEmptyQueue() {
        val bridge = mock<BridgeClient>()
        val pipeline = mock<ProcessingPipeline>()

        val processorThread = Thread { ProcessorCommandHandler.run(bridge, pipeline) }

        // Null claim (empty queue) — interrupt after first idle
        var callCount = 0
        whenever(bridge.claim()).doAnswer {
            callCount++
            if (callCount >= 2) processorThread.interrupt()
            null
        }

        processorThread.isDaemon = true
        processorThread.start()
        processorThread.join(5_000)

        assertTrue(callCount >= 1, "processor should poll at least once")
        verify(pipeline, org.mockito.kotlin.never()).invoke(any())
    }
}
