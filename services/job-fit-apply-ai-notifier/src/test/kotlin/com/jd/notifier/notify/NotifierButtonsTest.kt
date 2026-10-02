package com.jd.notifier.notify

import com.jd.notifier.bridge.ArtifactUrls
import com.jd.notifier.bridge.CompletedEvent
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/**
 * The button feature as the Notifier actually uses it: which buttons a high-fit event gets, what
 * their callback data says, and the job reference line the agent resolves replies against.
 */
@DisplayName("Notifier (Telegram buttons)")
class NotifierButtonsTest {

    private fun client() = mock<NotificationClient> {
        on { discordConfigured } doReturn false
        on { telegramConfigured } doReturn true
        // The Notifier calls postDiscord unconditionally; the real client returns SKIPPED itself
        // when unconfigured, so an unstubbed mock would hand back null and trip NotifyOutcome.
        on { postDiscord(any()) } doReturn DeliveryResult.SKIPPED
        on { postTelegramHtml(any()) } doReturn DeliveryResult.DELIVERED
        on { postTelegramHtmlWithButtons(any(), any()) } doReturn DeliveryResult.DELIVERED
    }

    private fun event(
        seq: Long = 7663,
        jobUrl: String? = "https://acme.co/j",
        messageId: String? = "gmail-1",
        recruiter: Boolean = true,
        terminalLabel: String? = "Recruiter_Response_Required",
    ) = CompletedEvent(
        jobId = "j", completedSeq = seq, status = "done",
        company = "Acme", roleTitle = "Staff SDET", fitScore = 80,
        pipelineAction = "tailor", jobUrl = jobUrl,
        artifactUrl = "http://host:8081/20260901_acme_staff_sdet/",
        artifacts = ArtifactUrls(resumePdf = "/api/jobs/abc/resume.pdf"),
        messageId = messageId, isRecruiter = recruiter, terminalLabel = terminalLabel,
    )

    private fun notifier(
        c: NotificationClient,
        buttons: Boolean = true,
        actions: String = "apply,reply,archive",
    ) = Notifier(
        client = c,
        fitThreshold = 50,
        buttonsEnabled = buttons,
        linkButtonsEnabled = buttons,
        actions = TelegramButtons.parseActions(actions),
        links = ArtifactLinks(enabled = false, timeoutMs = 100, bridgeBase = "http://bridge:8765"),
    )

    private fun sent(c: NotificationClient): Pair<String, List<List<TelegramButtons.Button>>> {
        val text = argumentCaptor<String>()
        val rows = argumentCaptor<List<List<TelegramButtons.Button>>>()
        verify(c).postTelegramHtmlWithButtons(text.capture(), rows.capture())
        return text.firstValue to rows.firstValue
    }

    @Test
    @DisplayName("every enabled, eligible action becomes a <verb>:<completed_seq> callback")
    fun actionsCarryVerbAndSeq() {
        val c = client()
        notifier(c).notify(event())
        val (_, rows) = sent(c)

        assertEquals(listOf("View Report", "View Resume"), rows[0].map { it.text })
        assertEquals(listOf("Apply", "Reply", "Archive"), rows[1].map { it.text })
        assertEquals(
            listOf("apply:7663", "reply:7663", "archive:7663"),
            rows[1].map { it.callbackData },
        )
        rows[1].forEach { assertNull(it.url, "${it.text} must be a callback, not a URL") }
    }

    @Test
    @DisplayName("the ping text ends with the #J<completed_seq> job reference line")
    fun textCarriesJobRef() {
        val c = client()
        notifier(c).notify(event())
        val (text, _) = sent(c)
        assertTrue(text.startsWith("High-fit: "), text)
        assertEquals("#J7663", text.lines().last(), "job ref must be its own last line: $text")
        assertTrue(text.lines().first().endsWith("— 80"), "score stays on the first line: $text")
    }

    @Test
    @DisplayName("the plain (no-buttons) ping also carries the job reference")
    fun plainPingCarriesJobRef() {
        val c = client()
        notifier(c, buttons = false).notify(event())
        val text = argumentCaptor<String>()
        verify(c).postTelegramHtml(text.capture())
        verify(c, never()).postTelegramHtmlWithButtons(any(), any())
        assertEquals("#J7663", text.firstValue.lines().last())
    }

    @Test
    @DisplayName("no completed_seq: no job ref line and no action buttons, links still sent")
    fun noSeqNoActions() {
        val c = client()
        notifier(c).notify(event(seq = 0))
        val (text, rows) = sent(c)
        assertTrue(!text.contains("#J"), text)
        assertEquals(listOf(listOf("View Report", "View Resume")), rows.map { r -> r.map { it.text } })
    }

    @Test
    @DisplayName("a disabled verb is never sent, even when the event qualifies")
    fun disabledVerbsWithheld() {
        val c = client()
        notifier(c, actions = "apply").notify(event())
        val (_, rows) = sent(c)
        assertEquals(listOf("Apply"), rows[1].map { it.text })
    }

    @Test
    @DisplayName("an ineligible verb is never sent, even when enabled (the dead-button guard)")
    fun ineligibleVerbsWithheld() {
        val c = client()
        // Not a recruiter email, already archived by the poller, no posting URL.
        notifier(c).notify(event(jobUrl = null, recruiter = false, terminalLabel = "JD_Processed"))
        val (_, rows) = sent(c)
        assertEquals(listOf(listOf("View Report", "View Resume")), rows.map { r -> r.map { it.text } })
    }

    @Test
    @DisplayName("unknown verbs in the config are dropped, not sent as dead buttons")
    fun unknownVerbsDropped() {
        val c = client()
        notifier(c, actions = "apply, investigate ,frobnicate").notify(event())
        val (_, rows) = sent(c)
        assertEquals(listOf("apply:7663"), rows[1].map { it.callbackData })
    }

    @Test
    @DisplayName("below the threshold nothing goes to Telegram")
    fun belowThresholdSilent() {
        val c = client()
        notifier(c).notify(event().copy(fitScore = 10))
        verify(c, never()).postTelegramHtmlWithButtons(any(), any())
        verify(c, never()).postTelegramHtml(any())
    }
}
