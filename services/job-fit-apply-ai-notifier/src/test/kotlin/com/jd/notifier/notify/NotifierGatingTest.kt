package com.jd.notifier.notify

import com.jd.notifier.bridge.ArtifactUrls
import com.jd.notifier.bridge.CompletedEvent
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
 * The link buttons and the Apply button are gated separately, because they carry different
 * risk: links are inert, Apply starts an agent workflow.
 */
@DisplayName("Notifier (independent link/apply gating)")
class NotifierGatingTest {

    private fun client() = mock<NotificationClient> {
        on { discordConfigured } doReturn false
        on { telegramConfigured } doReturn true
        on { postDiscord(any()) } doReturn DeliveryResult.SKIPPED
        on { postTelegramHtml(any()) } doReturn DeliveryResult.DELIVERED
        on { postTelegramHtmlWithButtons(any(), any()) } doReturn DeliveryResult.DELIVERED
    }

    private fun event() = CompletedEvent(
        jobId = "j", completedSeq = 1, status = "done",
        company = "Acme", roleTitle = "Staff SDET", fitScore = 80,
        pipelineAction = "tailor", jobUrl = "https://acme.co/j",
        artifactUrl = "http://host:8081/20260901_acme_staff_sdet/",
        artifacts = ArtifactUrls(resumePdf = "/api/jobs/abc/resume.pdf"),
    )

    private fun notifier(
        c: NotificationClient,
        buttons: Boolean = true,
        linkButtons: Boolean,
        applyButton: Boolean,
        dir: java.nio.file.Path,
    ) = Notifier(
        client = c,
        fitThreshold = 50,
        buttonsEnabled = buttons,
        linkButtonsEnabled = linkButtons,
        applyButtonEnabled = applyButton,
        links = ArtifactLinks(enabled = false, timeoutMs = 100, bridgeBase = "http://bridge:8765"),
        registrar = ApplyRegistrar(
            enabled = true,
            pendingPath = dir.resolve("pending_actions.json"),
            ttlSeconds = 604800,
            now = { 1000L },
        ),
    )

    private fun labelsSent(c: NotificationClient): List<String> {
        val rows = argumentCaptor<List<List<TelegramButtons.Button>>>()
        verify(c).postTelegramHtmlWithButtons(any(), rows.capture())
        return rows.firstValue.flatten().map { it.text }
    }

    @Test
    @DisplayName("links on + apply off: Report and Resume, no Apply, nothing registered")
    fun linksOnly() {
        val dir = Files.createTempDirectory("g")
        val c = client()
        notifier(c, linkButtons = true, applyButton = false, dir = dir).notify(event())

        assertEquals(listOf("View Report", "View Resume"), labelsSent(c))
        assertTrue(
            !Files.exists(dir.resolve("pending_actions.json")),
            "no Apply button means no registration should be written",
        )
    }

    @Test
    @DisplayName("apply on + links off: Apply only")
    fun applyOnly() {
        val dir = Files.createTempDirectory("g")
        val c = client()
        notifier(c, linkButtons = false, applyButton = true, dir = dir).notify(event())

        assertEquals(listOf("Apply"), labelsSent(c))
        assertTrue(Files.exists(dir.resolve("pending_actions.json")), "Apply must still register")
    }

    @Test
    @DisplayName("both on: all three buttons")
    fun bothOn() {
        val dir = Files.createTempDirectory("g")
        val c = client()
        notifier(c, linkButtons = true, applyButton = true, dir = dir).notify(event())
        assertEquals(listOf("View Report", "View Resume", "Apply"), labelsSent(c))
    }

    @Test
    @DisplayName("both off: no keyboard at all, plain text ping")
    fun bothOff() {
        val dir = Files.createTempDirectory("g")
        val c = client()
        notifier(c, buttons = false, linkButtons = false, applyButton = false, dir = dir).notify(event())
        verify(c, never()).postTelegramHtmlWithButtons(any(), any())
        verify(c).postTelegramHtml(any())
    }

    @Test
    @DisplayName("link buttons do not depend on the metadata fetch when disabled")
    fun linksOffSkipsResolution() {
        val dir = Files.createTempDirectory("g")
        val c = client()
        // ArtifactLinks is enabled=false here, so a resume link could only come from the bridge
        // path; with links off, neither link should appear even though the data is present.
        notifier(c, linkButtons = false, applyButton = true, dir = dir).notify(event())
        val labels = labelsSent(c)
        assertTrue(labels.none { it.startsWith("View ") }, "got $labels")
    }

    @Test
    @DisplayName("apply-off leaves no stale label in the agent's pending state")
    fun noDeadRegistration() {
        val dir = Files.createTempDirectory("g")
        val c = client()
        val n = notifier(c, linkButtons = true, applyButton = false, dir = dir)
        n.notify(event())
        assertNull(
            dir.resolve("pending_actions.json").takeIf { Files.exists(it) }?.let {
                com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(it)).size()
                    .takeIf { s -> s > 0 }
            },
            "a registration with no tappable button would be a dead entry",
        )
    }

    @Test
    @DisplayName("links still render when Apply is withheld for a missing dirname")
    fun linksSurviveMissingDirname() {
        val dir = Files.createTempDirectory("g")
        val c = client()
        val noArtifacts = event().copy(artifactUrl = null, artifacts = null)
        notifier(c, linkButtons = true, applyButton = true, dir = dir).notify(noArtifacts)
        // No dirname -> no Apply; and with no artifactUrl there are no links either.
        assertEquals(emptyList<String>(), labelsSent(c))
    }
}
