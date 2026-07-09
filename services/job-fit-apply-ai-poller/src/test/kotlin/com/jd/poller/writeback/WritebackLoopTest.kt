package com.jd.poller.writeback

import com.jd.poller.bridge.ArtifactUrls
import com.jd.poller.bridge.CompletedJob
import com.jd.poller.bridge.PollerBridgeClient
import com.jd.poller.gmail.GmailClient
import com.jd.poller.gmail.TerminalLabels
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import kotlin.test.assertEquals

@DisplayName("WritebackLoopTest")
class WritebackLoopTest {

    private fun processedJob() = CompletedJob(
        jobId = "job-1", completedSeq = 1, status = "done",
        messageId = "m1", terminalLabel = TerminalLabels.JD_PROCESSED, isRecruiter = false,
    )

    private fun recruiterJob() = CompletedJob(
        jobId = "job-2", completedSeq = 2, status = "done",
        messageId = "m2", terminalLabel = TerminalLabels.RECRUITER,
        draftText = "Thanks for reaching out!", isRecruiter = true,
        artifacts = ArtifactUrls(resumePdf = "/api/jobs/job-2/resume.pdf", coverLetterTxt = "/api/jobs/job-2/cover_letter.txt"),
    )

    private fun labelStubs() = mock<GmailClient> {
        on { getOrCreateLabel(any()) } doReturn "lbl"
        on { findLabelId(any()) } doReturn "existing"
    }

    @Test
    @DisplayName("non-recruiter job: applies terminal label, marks written-back, no draft")
    fun processedJobLabelledAndDone() {
        val gmail = labelStubs()
        val bridge = mock<PollerBridgeClient> { on { fetchCompleted(any(), any()) } doReturn listOf(processedJob()) }

        val done = WritebackLoop(gmail, bridge).drainOnce()

        assertEquals(1, done)
        verify(gmail).getOrCreateLabel(TerminalLabels.JD_PROCESSED)
        verify(gmail).archiveEmail("m1")
        verify(gmail, never()).createDraftReply(any(), any(), any(), any(), any())
        verify(bridge).markWritebackDone("job-1")
    }

    @Test
    @DisplayName("recruiter job: delivers the draft with downloaded attachments, then marks done")
    fun recruiterJobDeliversDraft() {
        val pdf = File.createTempFile("resume", ".pdf").apply { deleteOnExit() }
        val cl = File.createTempFile("cover", ".txt").apply { deleteOnExit() }
        val gmail = labelStubs().apply {
            whenever(getMessageMeta("m2")).doReturn(GmailClient.MessageMeta("Jane Recruiter <jane@firm.com>", "Staff SDET role"))
            whenever(createDraftReply(any(), any(), any(), any(), any())).doReturn("draft-id")
        }
        val bridge = mock<PollerBridgeClient> {
            on { fetchCompleted(any(), any()) } doReturn listOf(recruiterJob())
            on { downloadArtifact(eq("/api/jobs/job-2/resume.pdf"), any()) } doReturn pdf
            on { downloadArtifact(eq("/api/jobs/job-2/cover_letter.txt"), any()) } doReturn cl
        }

        val done = WritebackLoop(gmail, bridge).drainOnce()

        assertEquals(1, done)
        verify(gmail).starEmail("m2")
        verify(gmail).createDraftReply(
            eq("m2"),
            eq("jane@firm.com"),                 // address extracted from "Jane Recruiter <jane@firm.com>"
            eq("Re: Staff SDET role"),           // Re: prefix added
            eq("Thanks for reaching out!"),
            eq(listOf(pdf.absolutePath, cl.absolutePath)),
        )
        verify(bridge).markWritebackDone("job-2")
    }

    @Test
    @DisplayName("non-email job (no message_id) is marked done with no Gmail interaction")
    fun nonEmailJobJustMarkedDone() {
        val gmail = mock<GmailClient>()
        val nonEmail = CompletedJob(jobId = "job-ext", status = "done", messageId = null)
        val bridge = mock<PollerBridgeClient> { on { fetchCompleted(any(), any()) } doReturn listOf(nonEmail) }

        assertEquals(1, WritebackLoop(gmail, bridge).drainOnce())
        verify(bridge).markWritebackDone("job-ext")
        verify(gmail, never()).getOrCreateLabel(any())
        verify(gmail, never()).createDraftReply(any(), any(), any(), any(), any())
    }

    @Test
    @DisplayName("a Gmail failure leaves the job un-marked for retry")
    fun gmailFailureLeavesJobForRetry() {
        val gmail = mock<GmailClient> {
            on { findLabelId(any()) } doReturn "existing"
        }
        doThrow(RuntimeException("gmail 503")).whenever(gmail).getOrCreateLabel(any())
        val bridge = mock<PollerBridgeClient> { on { fetchCompleted(any(), any()) } doReturn listOf(processedJob()) }

        val done = WritebackLoop(gmail, bridge).drainOnce()

        assertEquals(0, done)
        verify(bridge, never()).markWritebackDone(any())   // stays in the feed for next pass
    }

    @Test
    @DisplayName("recruiter job with no draft text applies the label but delivers no draft")
    fun recruiterWithoutDraftTextSkipsDelivery() {
        val gmail = labelStubs()
        val bridge = mock<PollerBridgeClient> {
            on { fetchCompleted(any(), any()) } doReturn listOf(recruiterJob().copy(draftText = null))
        }

        assertEquals(1, WritebackLoop(gmail, bridge).drainOnce())
        verify(gmail, never()).createDraftReply(any(), any(), any(), any(), any())
        verify(bridge).markWritebackDone("job-2")
    }

    // ── Regression: blank/null terminalLabel must still clear Processing ──────
    // Bug: the old code skipped LabelApplier.apply() when terminalLabel was blank,
    // which meant clearProcessingLabel() never ran. The email kept Processing
    // forever, and markWritebackDone() still fired — so the job left the feed with
    // the Gmail label stuck. These tests guard against that regression.

    @Test
    @DisplayName("blank terminalLabel still clears Processing and marks done")
    fun blankTerminalLabelClearsProcessingAndMarksDone() {
        val gmail = labelStubs()
        val job = CompletedJob(
            jobId = "job-blank", completedSeq = 10, status = "done",
            messageId = "m-blank", terminalLabel = "", isRecruiter = false,
        )
        val bridge = mock<PollerBridgeClient> {
            on { fetchCompleted(any(), any()) } doReturn listOf(job)
        }

        val done = WritebackLoop(gmail, bridge).drainOnce()

        assertEquals(1, done)
        // LabelApplier.apply() must have been called → it clears Processing.
        // findLabelId is called for both clearErrorLabel and clearProcessingLabel;
        // verify the processing one specifically was invoked.
        verify(gmail, org.mockito.kotlin.atLeast(1)).findLabelId(TerminalLabels.PROCESSING)
        verify(gmail, org.mockito.kotlin.atLeast(1)).applyLabels(eq("m-blank"), eq(emptyList()), eq(listOf("existing")))
        verify(bridge).markWritebackDone("job-blank")
    }

    @Test
    @DisplayName("null terminalLabel still clears Processing and marks done")
    fun nullTerminalLabelClearsProcessingAndMarksDone() {
        val gmail = labelStubs()
        val job = CompletedJob(
            jobId = "job-null", completedSeq = 11, status = "done",
            messageId = "m-null", terminalLabel = null, isRecruiter = false,
        )
        val bridge = mock<PollerBridgeClient> {
            on { fetchCompleted(any(), any()) } doReturn listOf(job)
        }

        val done = WritebackLoop(gmail, bridge).drainOnce()

        assertEquals(1, done)
        verify(gmail, org.mockito.kotlin.atLeast(1)).findLabelId(TerminalLabels.PROCESSING)
        verify(gmail, org.mockito.kotlin.atLeast(1)).applyLabels(eq("m-null"), eq(emptyList()), eq(listOf("existing")))
        verify(bridge).markWritebackDone("job-null")
    }

    @Test
    @DisplayName("blank terminalLabel falls back to JD_Not_Found (no unread/archive) so the email exits intake")
    fun blankTerminalLabelAppliesNotFound() {
        val gmail = labelStubs()
        val job = CompletedJob(
            jobId = "job-nolabel", completedSeq = 12, status = "done",
            messageId = "m-nolabel", terminalLabel = "", isRecruiter = false,
        )
        val bridge = mock<PollerBridgeClient> {
            on { fetchCompleted(any(), any()) } doReturn listOf(job)
        }

        WritebackLoop(gmail, bridge).drainOnce()

        // Blank = "not a job" / digest parent → apply JD_Not_Found so it drops out of the intake
        // query, breaking the re-fetch → re-label Processing loop. No unread/archive side effects.
        verify(gmail).getOrCreateLabel(TerminalLabels.JD_NOT_FOUND)
        verify(gmail).labelEmail("m-nolabel", "lbl")
        verify(gmail, never()).markUnread(any())
        verify(gmail, never()).archiveEmail(any())
    }

    @Test
    @DisplayName("null terminalLabel falls back to JD_Not_Found (no unread/archive) so the email exits intake")
    fun nullTerminalLabelAppliesNotFound() {
        val gmail = labelStubs()
        val job = CompletedJob(
            jobId = "job-nolabel2", completedSeq = 13, status = "done",
            messageId = "m-nolabel2", terminalLabel = null, isRecruiter = false,
        )
        val bridge = mock<PollerBridgeClient> {
            on { fetchCompleted(any(), any()) } doReturn listOf(job)
        }

        WritebackLoop(gmail, bridge).drainOnce()

        verify(gmail).getOrCreateLabel(TerminalLabels.JD_NOT_FOUND)
        verify(gmail).labelEmail("m-nolabel2", "lbl")
        verify(gmail, never()).markUnread(any())
        verify(gmail, never()).archiveEmail(any())
    }

    @Test
    @DisplayName("blank terminalLabel marks writeback_done even if clearProcessingLabel fails")
    fun blankLabelGmailFailureLeavesJobForRetry() {
        val gmail = mock<GmailClient> {
            on { findLabelId(any()) } doReturn "lbl-id"
        }
        doThrow(RuntimeException("gmail 503")).whenever(gmail).applyLabels(any(), any(), any())
        val job = CompletedJob(
            jobId = "job-fail", completedSeq = 14, status = "done",
            messageId = "m-fail", terminalLabel = "", isRecruiter = false,
        )
        val bridge = mock<PollerBridgeClient> {
            on { fetchCompleted(any(), any()) } doReturn listOf(job)
        }

        val done = WritebackLoop(gmail, bridge).drainOnce()

        assertEquals(0, done)
        verify(bridge, never()).markWritebackDone(any())  // stays in the feed for retry
    }
}
