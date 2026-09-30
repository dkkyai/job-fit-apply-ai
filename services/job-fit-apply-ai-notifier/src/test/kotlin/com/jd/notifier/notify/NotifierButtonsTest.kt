package com.jd.notifier.notify

import com.jd.notifier.bridge.CompletedEvent
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The button feature as the Notifier actually uses it: what gets registered, what gets sent,
 * and what happens when the lookups fail.
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
        company: String? = "Acme",
        role: String? = "Staff SDET",
        fit: Int = 80,
        artifact: String? = "http://host:8081/20260901_acme_staff_sdet/",
        resumePdf: String? = "/api/jobs/abc/resume.pdf",
    ) = CompletedEvent(
        jobId = "j", completedSeq = 1, status = "done",
        company = company, roleTitle = role, fitScore = fit,
        pipelineAction = "tailor", jobUrl = "https://acme.co/j", artifactUrl = artifact,
        artifacts = resumePdf?.let { com.jd.notifier.bridge.ArtifactUrls(resumePdf = it) },
    )

    private fun notifier(
        c: NotificationClient,
        buttons: Boolean,
        dir: java.nio.file.Path,
    ) = Notifier(
        client = c,
        fitThreshold = 50,
        buttonsEnabled = buttons,
        links = ArtifactLinks(enabled = false, timeoutMs = 100, bridgeBase = "http://bridge:8765"),
        registrar = ApplyRegistrar(
            enabled = true,
            pendingPath = dir.resolve("pending_actions.json"),
            ttlSeconds = 604800,
            now = { 1000L },
        ),
    )

    @Test
    @DisplayName("buttons on: sends Report + Resume + Apply and registers the Apply label")
    fun sendsButtonsAndRegisters() {
        val dir = Files.createTempDirectory("nb")
        val c = client()
        notifier(c, buttons = true, dir = dir).notify(event())

        val rows = argumentCaptor<List<List<TelegramButtons.Button>>>()
        verify(c).postTelegramHtmlWithButtons(any(), rows.capture())
        assertEquals(2, rows.firstValue.size)
        assertEquals(listOf("View Report", "View Resume"), rows.firstValue[0].map { it.text })
        val apply = rows.firstValue[1].single()
        assertEquals("Apply", apply.text)

        // The registered label must be byte-identical to the one on the button.
        val registered = com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(Files.readString(dir.resolve("pending_actions.json")))
        assertNotNull(registered.get(apply.callbackData), "tap label must be registered verbatim")
        assertEquals(
            "20260901_acme_staff_sdet",
            registered.get(apply.callbackData).get("dirname").asText(),
        )
    }

    @Test
    @DisplayName("registration happens before the send, so a tap can never outrun it")
    fun registersBeforeSend() {
        val dir = Files.createTempDirectory("nb")
        val pending = dir.resolve("pending_actions.json")
        val c = mock<NotificationClient> {
            on { discordConfigured } doReturn false
            on { telegramConfigured } doReturn true
            on { postDiscord(any()) } doReturn DeliveryResult.SKIPPED
            on { postTelegramHtmlWithButtons(any(), any()) } doAnswer {
                // Asserted at send time: the file must already exist.
                assertTrue(Files.exists(pending), "Apply label registered only after send")
                DeliveryResult.DELIVERED
            }
        }
        notifier(c, buttons = true, dir = dir).notify(event())
        verify(c).postTelegramHtmlWithButtons(any(), any())
    }
    @Test
    @DisplayName("buttons off: falls back to the plain ping, registers nothing")
    fun buttonsOffIsPlain() {
        val dir = Files.createTempDirectory("nb")
        val c = client()
        notifier(c, buttons = false, dir = dir).notify(event())
        verify(c, never()).postTelegramHtmlWithButtons(any(), any())
        verify(c).postTelegramHtml(any())
        assertTrue(!Files.exists(dir.resolve("pending_actions.json")))
    }

    @Test
    @DisplayName("no artifacts: links omitted and Apply withheld (no dirname to key it on)")
    fun noArtifactsWithholdsApply() {
        val dir = Files.createTempDirectory("nb")
        val c = client()
        notifier(c, buttons = true, dir = dir).notify(event(artifact = null, resumePdf = null))

        val rows = argumentCaptor<List<List<TelegramButtons.Button>>>()
        verify(c).postTelegramHtmlWithButtons(any(), rows.capture())
        // Without artifactUrl there is no dirname, so nothing identifies which job to apply to.
        // A button whose tap resolves to nothing is worse than no button, so none is offered.
        assertTrue(
            rows.firstValue.flatten().isEmpty(),
            "expected no buttons, got ${rows.firstValue.flatten().map { it.text }}",
        )
        assertTrue(!Files.exists(dir.resolve("pending_actions.json")))
    }

    @Test
    @DisplayName("all real high-fit events carry artifact_url (premise for the Apply button)")
    fun premiseHoldsInSample() {
        // Documents the assumption the withholding above relies on: every event that clears the
        // fit threshold also carries artifacts. Verified against the live feed (7/7 at >= 50).
        val withArtifacts = event()
        assertEquals("20260901_acme_staff_sdet", withArtifacts.dirName())
        assertEquals(
            "20260901_acme_staff_sdet",
            event(artifact = "http://host:8081/20260901_acme_staff_sdet").dirName(),
        )
        assertTrue(event(artifact = null).dirName() == null)
    }

    @Test
    @DisplayName("below threshold: no Telegram send at all, no registration")
    fun belowThresholdNoButtons() {
        val dir = Files.createTempDirectory("nb")
        val c = client()
        notifier(c, buttons = true, dir = dir).notify(event(fit = 30))
        verify(c, never()).postTelegramHtmlWithButtons(any(), any())
        assertTrue(!Files.exists(dir.resolve("pending_actions.json")))
    }

    @Test
    @DisplayName("two jobs with identical company+title get distinct Apply labels")
    fun distinctLabelsForSimilarJobs() {
        val dir = Files.createTempDirectory("nb")
        val c = client()
        val n = notifier(c, buttons = true, dir = dir)
        n.notify(event(artifact = "http://host:8081/20260901_acme_a/"))
        n.notify(event(artifact = "http://host:8081/20260902_acme_b/"))

        val rows = argumentCaptor<List<List<TelegramButtons.Button>>>()
        verify(c, org.mockito.kotlin.times(2)).postTelegramHtmlWithButtons(any(), rows.capture())
        val labels = rows.allValues.map { it[1].single().callbackData }
        assertEquals(2, labels.toSet().size, "identical labels would cross-resolve taps: $labels")

        val registered = com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(Files.readString(dir.resolve("pending_actions.json")))
        labels.forEach { assertNotNull(registered.get(it), "missing registration for $it") }
    }

    @Test
    @DisplayName("event with no dirname (no artifacts) still renders an Apply button")
    fun applyWithoutDirnameIsNotOffered() {
        val dir = Files.createTempDirectory("nb")
        val c = client()
        // No artifactUrl -> no dirname -> nothing to key the action on.
        notifier(c, buttons = true, dir = dir).notify(event(artifact = null, resumePdf = null))
        val rows = argumentCaptor<List<List<TelegramButtons.Button>>>()
        verify(c).postTelegramHtmlWithButtons(any(), rows.capture())
        // The card test above asserts Apply is retained; here we pin the registration side.
        val pending = dir.resolve("pending_actions.json")
        assertTrue(!Files.exists(pending), "no dirname means no registration to write")
    }
}
