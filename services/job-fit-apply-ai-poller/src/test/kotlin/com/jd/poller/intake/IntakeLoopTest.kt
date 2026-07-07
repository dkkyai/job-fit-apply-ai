package com.jd.poller.intake

import com.jd.poller.bridge.PollerBridgeClient
import com.jd.poller.gmail.GmailClient
import com.jd.poller.gmail.TerminalLabels
import com.jd.poller.model.RawEmail
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals

@DisplayName("IntakeLoopTest")
class IntakeLoopTest {

    private fun email(id: String) = RawEmail(id, "Role $id", "rec@x.com", "body", "", false)

    @Test
    @DisplayName("submits every fetched email and marks each JD_Processing")
    fun submitsAndLabels() {
        val gmail = mock<GmailClient> {
            on { fetchIntakeEmails() } doReturn listOf(email("a"), email("b"))
            on { getOrCreateLabel(TerminalLabels.JD_PROCESSING) } doReturn "proc-id"
        }
        val bridge = mock<PollerBridgeClient> { on { submitEmail(any()) } doReturn "job-x" }

        val submitted = IntakeLoop(gmail, bridge).pollOnce()

        assertEquals(2, submitted)
        verify(bridge, times(2)).submitEmail(any())
        verify(gmail).labelEmail(eq("a"), eq("proc-id"))
        verify(gmail).labelEmail(eq("b"), eq("proc-id"))
    }

    @Test
    @DisplayName("a failed submit does not block the other emails and is not counted")
    fun submitFailureIsIsolated() {
        val gmail = mock<GmailClient> {
            on { fetchIntakeEmails() } doReturn listOf(email("bad"), email("good"))
            on { getOrCreateLabel(any()) } doReturn "proc-id"
        }
        val bridge = mock<PollerBridgeClient>()
        whenever(bridge.submitEmail(any())).thenThrow(RuntimeException("bridge down")).thenReturn("job-ok")

        val submitted = IntakeLoop(gmail, bridge).pollOnce()

        assertEquals(1, submitted)
        // The failed email is never labeled in-flight, so it is re-fetched next pass.
        verify(gmail, never()).labelEmail(eq("bad"), any())
        verify(gmail).labelEmail(eq("good"), eq("proc-id"))
    }

    @Test
    @DisplayName("empty inbox submits nothing")
    fun emptyInbox() {
        val gmail = mock<GmailClient> { on { fetchIntakeEmails() } doReturn emptyList() }
        val bridge = mock<PollerBridgeClient>()
        assertEquals(0, IntakeLoop(gmail, bridge).pollOnce())
        verify(bridge, never()).submitEmail(any())
    }

    @Test
    @DisplayName("a labeling failure still counts the submit (bridge already has the work item)")
    fun labelFailureStillCountsSubmit() {
        val gmail = mock<GmailClient> {
            on { fetchIntakeEmails() } doReturn listOf(email("a"))
            on { getOrCreateLabel(any()) } doReturn "proc-id"
        }
        doThrow(RuntimeException("gmail 500")).whenever(gmail).labelEmail(any(), any())
        val bridge = mock<PollerBridgeClient> { on { submitEmail(any()) } doReturn "job-a" }

        assertEquals(1, IntakeLoop(gmail, bridge).pollOnce())
    }
}
