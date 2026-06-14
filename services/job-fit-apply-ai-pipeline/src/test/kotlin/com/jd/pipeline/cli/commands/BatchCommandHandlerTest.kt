package com.jd.pipeline.cli.commands

import com.jd.pipeline.cli.Command
import com.jd.pipeline.cli.EmailLabelingServiceImpl
import com.jd.pipeline.client.BridgeClient
import com.jd.pipeline.client.gmail.GmailTransport
import com.jd.pipeline.pipeline.IngestionPipeline
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.source.JdRecord
import com.jd.pipeline.source.IngestionSource
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@DisplayName("BatchCommandHandler")
class BatchCommandHandlerTest {

    private lateinit var gmail: GmailTransport
    private lateinit var ingestion: IngestionPipeline
    private lateinit var bridge: BridgeClient
    private lateinit var labeling: EmailLabelingServiceImpl

    private val cmd = Command.Batch(maxEmails = 10, debug = false)

    @BeforeEach
    fun setUp() {
        gmail    = mock()
        ingestion = mock()
        bridge   = mock()
        labeling  = mock()
        // applyLabeling returns a non-null LabelingResult — set up a safe default
        whenever(labeling.applyLabeling(any(), any())).doReturn(
            com.jd.pipeline.cli.LabelingResult(
                labelApplied = "JD_Processed",
                wasArchived = true, wasStarred = false,
                wasMarkedUnread = false, wasKeptInInbox = false,
            )
        )
        // batchBlockedDomains and batchLinkedInSessionExpired have safe defaults
        whenever(ingestion.batchLinkedInSessionExpired()).doReturn(false)
        whenever(ingestion.batchBlockedDomains()).doReturn(emptySet())
    }

    private fun run() = BatchCommandHandler.run(cmd, gmail, ingestion, bridge, labeling)

    private fun emailState(
        emailId: String = "email-001",
        isJobPosting: Boolean = true,
        isDigest: Boolean = false,
        isRecruiter: Boolean = false,
    ) = JDState(
        intake = IntakeContext.Email(
            emailId = emailId, from = "hr@acme.com", subject = "Staff SDET at Acme",
            rawBody = "We are hiring.", htmlBody = "",
            isRecruiter = isRecruiter, isDigest = isDigest, isInlineDigest = false,
        ),
        isJobPosting = isJobPosting,
        company = "Acme", roleTitle = "Staff SDET", jdText = "We are hiring.",
    )

    private fun minRecord(company: String = "Acme") = JdRecord(
        jdText = "jd text", company = company, roleTitle = "Engineer",
        location = null, jobUrl = null,
        source = IngestionSource.EMAIL,
    )

    // ── Empty inbox ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("empty inbox")
    inner class EmptyInbox {

        @Test
        @DisplayName("returns early when no emails found — no ingestion or bridge calls")
        fun noEmailsNoWork() {
            whenever(gmail.fetchJdEmails(any(), any())).doReturn(emptyList())
            run()
            verify(ingestion, never()).invoke(any())
            verify(bridge, never()).submit(any())
        }
    }

    // ── Non-job-posting email ─────────────────────────────────────────────────

    @Nested
    @DisplayName("non-job-posting email")
    inner class NonJobPosting {

        @Test
        @DisplayName("labels immediately without submitting to bridge")
        fun nonJobPostingLabeled() {
            val email = emailState(isJobPosting = false)
            val ingested = email.copy(isJobPosting = false)
            whenever(gmail.fetchJdEmails(any(), any())).doReturn(listOf(email))
            whenever(ingestion.invoke(email)).doReturn(ingested)
            run()
            verify(bridge, never()).submit(any())
            verify(labeling).applyLabeling(any(), any())
        }
    }

    // ── Single job-posting email ──────────────────────────────────────────────

    @Nested
    @DisplayName("single job-posting email")
    inner class SingleJobPosting {

        @Test
        @DisplayName("ingests, submits to bridge, applies processing + terminal label — fire-and-forget (no poll)")
        fun jobPostingFullFlow() {
            val email = emailState()
            val ingested = email.copy(isJobPosting = true, jdText = "full jd text")
            val record = minRecord()
            whenever(gmail.fetchJdEmails(any(), any())).doReturn(listOf(email))
            whenever(ingestion.invoke(email)).doReturn(ingested)
            whenever(ingestion.toJdRecord(any(), any())).doReturn(record)
            whenever(bridge.submit(record)).doReturn("job-001")

            run()

            verify(bridge).submit(record)
            verify(labeling).applyProcessing(any(), any())
            verify(labeling).applyLabeling(any(), any())
            // The batch is fire-and-forget: the jd-worker owns terminal state, so the
            // batch must never block on pollUntilTerminal.
            verify(bridge, never()).pollUntilTerminal(any(), any(), any())
        }

        @Test
        @DisplayName("direct recruiter job posting is labeled Recruiter_Response_Required (kept in inbox, not archived)")
        fun recruiterEmailKeptInInbox() {
            val email = emailState(isRecruiter = true)
            val ingested = email.copy(isJobPosting = true, jdText = "full jd text")
            val record = minRecord()
            whenever(gmail.fetchJdEmails(any(), any())).doReturn(listOf(email))
            whenever(ingestion.invoke(email)).doReturn(ingested)
            whenever(ingestion.toJdRecord(any(), any())).doReturn(record)
            whenever(bridge.submit(record)).doReturn("job-001")

            run()

            val captor = argumentCaptor<JDState>()
            verify(labeling).applyLabeling(captor.capture(), any())
            assert(captor.firstValue.isRecruiterResponseRequired) {
                "recruiter email must be labeled Recruiter_Response_Required, not archived"
            }
        }

        @Test
        @DisplayName("digest email submits each child job to the bridge")
        fun digestSubmitsEachChild() {
            val parent = emailState(isDigest = true)
            val child1 = emailState(isJobPosting = true)
            val child2 = emailState(isJobPosting = true)
            val ingested = parent.copy(digestJobs = listOf(child1, child2))
            whenever(gmail.fetchJdEmails(any(), any())).doReturn(listOf(parent))
            whenever(ingestion.invoke(parent)).doReturn(ingested)
            // Digest children are converted via toJdRecord(child) — idempotencyKey defaults
            // to null, so the second matcher must accept null (anyOrNull, not any).
            whenever(ingestion.toJdRecord(any(), anyOrNull())).doReturn(minRecord("Co1"), minRecord("Co2"))
            whenever(bridge.submit(any())).doReturn("job-c1", "job-c2")

            run() // must not throw

            verify(bridge, org.mockito.Mockito.times(2)).submit(any())
            verify(labeling).applyProcessing(any(), any())
            verify(bridge, never()).pollUntilTerminal(any(), any(), any())
        }

        @Test
        @DisplayName("bridge submit exception is caught — email is not labeled as processing")
        fun bridgeSubmitExceptionCaught() {
            val email = emailState()
            val ingested = email.copy(isJobPosting = true)
            val record = minRecord()
            whenever(gmail.fetchJdEmails(any(), any())).doReturn(listOf(email))
            whenever(ingestion.invoke(email)).doReturn(ingested)
            whenever(ingestion.toJdRecord(any(), any())).doReturn(record)
            whenever(bridge.submit(any())).thenThrow(RuntimeException("bridge unreachable"))
            run() // must not propagate exception
            verify(labeling, never()).applyProcessing(any(), any())
        }
    }

    // ── Ingestion error ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("ingestion error")
    inner class IngestionError {

        @Test
        @DisplayName("ingestion exception labels email with error and continues to next email")
        fun ingestionExceptionHandled() {
            val email = emailState()
            whenever(gmail.fetchJdEmails(any(), any())).doReturn(listOf(email))
            whenever(ingestion.invoke(email)).thenThrow(RuntimeException("scan failed"))
            run() // must not throw
            // labeling is applied even on ingestion failure
            verify(labeling).applyLabeling(any(), any())
            verify(bridge, never()).submit(any())
        }
    }

    // ── LinkedIn / blocked-domain warnings ───────────────────────────────────

    @Nested
    @DisplayName("post-batch warnings")
    inner class PostBatchWarnings {

        @Test
        @DisplayName("batchLinkedInSessionExpired warning does not throw")
        fun linkedInWarningDoesNotThrow() {
            val email = emailState(isJobPosting = false)
            whenever(gmail.fetchJdEmails(any(), any())).doReturn(listOf(email))
            whenever(ingestion.invoke(any())).doReturn(email.copy(isJobPosting = false))
            whenever(ingestion.batchLinkedInSessionExpired()).doReturn(true)
            whenever(ingestion.batchBlockedDomains()).doReturn(setOf("linkedin.com"))
            run() // must not throw
        }
    }
}
