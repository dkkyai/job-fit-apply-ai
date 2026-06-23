package com.jd.pipeline.cli.commands

import com.jd.pipeline.cli.BatchNotificationService
import com.jd.pipeline.client.BridgeClient
import com.jd.pipeline.client.ClaimDto
import com.jd.pipeline.pipeline.ProcessingPipeline
import com.jd.pipeline.source.IngestionSource
import com.jd.pipeline.source.JdRecord
import com.jd.pipeline.source.ProcessingResult
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

@DisplayName("WorkerCommandHandlerTest")
class WorkerCommandHandlerTest {

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
    @DisplayName("worker processes a claimed job and posts the result")
    fun workerProcessesClaimAndPostsResult() {
        val record = fakeRecord()
        val result = successResult()
        val claim  = ClaimDto(jobId = "job-1", jdRecord = record)

        val resultPosted = CountDownLatch(1)

        val bridge = mock<BridgeClient>()
        val pipeline = mock<ProcessingPipeline>()

        val workerThread = Thread { WorkerCommandHandler.run(bridge, pipeline) }

        // First claim: return the job. Second claim: interrupt to stop the loop.
        whenever(bridge.claim())
            .doReturn(claim)
            .doAnswer {
                workerThread.interrupt()
                null
            }
        whenever(pipeline.invoke(record)).doReturn(result)
        doAnswer { resultPosted.countDown() }.whenever(bridge).postResult(any(), any())

        workerThread.isDaemon = true
        workerThread.start()

        assertTrue(resultPosted.await(5, TimeUnit.SECONDS), "worker should post result within 5s")

        verify(pipeline).invoke(record)
        verify(bridge).postResult("job-1", result)
    }

    @Test
    @DisplayName("worker posts a SKIP error result when pipeline throws")
    fun workerPostsErrorResultOnPipelineException() {
        val record = fakeRecord()
        val claim  = ClaimDto(jobId = "job-err", jdRecord = record)

        val resultPosted = CountDownLatch(1)

        val bridge = mock<BridgeClient>()
        val pipeline = mock<ProcessingPipeline>()

        val workerThread = Thread { WorkerCommandHandler.run(bridge, pipeline) }

        whenever(bridge.claim())
            .doReturn(claim)
            .doAnswer {
                workerThread.interrupt()
                null
            }
        whenever(pipeline.invoke(record)).thenThrow(RuntimeException("pipeline exploded"))
        doAnswer { resultPosted.countDown() }.whenever(bridge).postResult(any(), any())

        workerThread.isDaemon = true
        workerThread.start()

        assertTrue(resultPosted.await(5, TimeUnit.SECONDS), "worker should post error result within 5s")

        verify(bridge).postResult(
            org.mockito.kotlin.eq("job-err"),
            org.mockito.kotlin.argThat { error != null && error!!.contains("pipeline exploded") }
        )
    }

    @Test
    @DisplayName("worker notifies per-job result mapped from the pipeline output")
    fun workerNotifiesJobResult() {
        val record = fakeRecord()
        val result = successResult()
        val claim  = ClaimDto(jobId = "job-notify", jdRecord = record)

        val notified = CountDownLatch(1)

        val bridge = mock<BridgeClient>()
        val pipeline = mock<ProcessingPipeline>()
        val notifier = mock<BatchNotificationService>()

        val workerThread = Thread { WorkerCommandHandler.run(bridge, pipeline, notifier) }

        whenever(bridge.claim())
            .doReturn(claim)
            .doAnswer {
                workerThread.interrupt()
                null
            }
        whenever(pipeline.invoke(record)).doReturn(result)
        doAnswer { notified.countDown() }.whenever(notifier).notifyJobResult(any())

        workerThread.isDaemon = true
        workerThread.start()

        assertTrue(notified.await(5, TimeUnit.SECONDS), "worker should notify within 5s")

        verify(notifier).logConfigStatus()
        verify(notifier).notifyJobResult(argThat {
            company == "Acme Corp" &&
                roleTitle == "Staff SDET" &&
                fitScore == 82 &&
                pipelineAction == "TAILOR" &&
                error == null &&
                artifactUrl == "https://artifacts.example.com/acme" &&
                jobUrl == "https://boards.example.com/acme/job/123"
        })
    }

    @Test
    @DisplayName("worker maps a blank artifactUrl to null in the notification")
    fun workerMapsBlankArtifactUrlToNull() {
        val record = fakeRecord()
        val result = successResult().copy(artifactUrl = "")
        val claim  = ClaimDto(jobId = "job-blank-url", jdRecord = record)

        val notified = CountDownLatch(1)

        val bridge = mock<BridgeClient>()
        val pipeline = mock<ProcessingPipeline>()
        val notifier = mock<BatchNotificationService>()

        val workerThread = Thread { WorkerCommandHandler.run(bridge, pipeline, notifier) }

        whenever(bridge.claim())
            .doReturn(claim)
            .doAnswer {
                workerThread.interrupt()
                null
            }
        whenever(pipeline.invoke(record)).doReturn(result)
        doAnswer { notified.countDown() }.whenever(notifier).notifyJobResult(any())

        workerThread.isDaemon = true
        workerThread.start()

        assertTrue(notified.await(5, TimeUnit.SECONDS), "worker should notify within 5s")

        verify(notifier).notifyJobResult(argThat { artifactUrl == null })
    }

    @Test
    @DisplayName("worker still notifies (with the error) when the pipeline throws")
    fun workerNotifiesOnPipelineError() {
        val record = fakeRecord()
        val claim  = ClaimDto(jobId = "job-err-notify", jdRecord = record)

        val notified = CountDownLatch(1)

        val bridge = mock<BridgeClient>()
        val pipeline = mock<ProcessingPipeline>()
        val notifier = mock<BatchNotificationService>()

        val workerThread = Thread { WorkerCommandHandler.run(bridge, pipeline, notifier) }

        whenever(bridge.claim())
            .doReturn(claim)
            .doAnswer {
                workerThread.interrupt()
                null
            }
        whenever(pipeline.invoke(record)).thenThrow(RuntimeException("boom"))
        doAnswer { notified.countDown() }.whenever(notifier).notifyJobResult(any())

        workerThread.isDaemon = true
        workerThread.start()

        assertTrue(notified.await(5, TimeUnit.SECONDS), "worker should notify error within 5s")

        verify(notifier).notifyJobResult(argThat {
            error != null && error!!.contains("boom") && fitScore == 0
        })
    }

    @Test
    @DisplayName("recruiter draft is skipped (no hang) when the Gmail token is not valid")
    fun recruiterDraftSkippedWhenTokenInvalid() {
        val recruiterRecord = fakeRecord().copy(
            intakeMeta = com.jd.pipeline.source.IntakeContext.Email(
                emailId = "e1", from = "rec@firm.com", subject = "Great role",
                rawBody = "hi", htmlBody = "<p>hi</p>",
                isRecruiter = true, isDigest = false, isInlineDigest = false,
            )
        )
        val result = successResult() // TAILOR, error == null → recruiter draft path is reached
        val claim  = ClaimDto(jobId = "job-rec", jdRecord = recruiterRecord)

        val tokenChecked = java.util.concurrent.atomic.AtomicBoolean(false)
        val resultPosted = CountDownLatch(1)

        val bridge = mock<BridgeClient>()
        val pipeline = mock<ProcessingPipeline>()
        val notifier = mock<BatchNotificationService>()

        val workerThread = Thread {
            WorkerCommandHandler.run(bridge, pipeline, notifier, gmailTokenValid = {
                tokenChecked.set(true); false   // simulate missing/expired token
            })
        }

        whenever(bridge.claim())
            .doReturn(claim)
            .doAnswer { workerThread.interrupt(); null }
        whenever(pipeline.invoke(recruiterRecord)).doReturn(result)
        doAnswer { resultPosted.countDown() }.whenever(bridge).postResult(any(), any())

        workerThread.isDaemon = true
        workerThread.start()

        // The worker must post the result and keep going — never block on interactive OAuth.
        assertTrue(resultPosted.await(5, TimeUnit.SECONDS), "worker should post result and not hang on OAuth")
        workerThread.join(2_000)
        assertTrue(tokenChecked.get(), "worker should consult the gmail token guard for a recruiter TAILOR job")
    }

    @Test
    @DisplayName("worker sleeps and retries when queue is empty")
    fun workerSleepsOnEmptyQueue() {
        val bridge = mock<BridgeClient>()
        val pipeline = mock<ProcessingPipeline>()

        val workerThread = Thread { WorkerCommandHandler.run(bridge, pipeline) }

        // Null claim (empty queue) — interrupt after first idle
        var callCount = 0
        whenever(bridge.claim()).doAnswer {
            callCount++
            if (callCount >= 2) workerThread.interrupt()
            null
        }

        workerThread.isDaemon = true
        workerThread.start()
        workerThread.join(5_000)

        assertTrue(callCount >= 1, "worker should poll at least once")
        verify(pipeline, org.mockito.kotlin.never()).invoke(any())
    }
}
