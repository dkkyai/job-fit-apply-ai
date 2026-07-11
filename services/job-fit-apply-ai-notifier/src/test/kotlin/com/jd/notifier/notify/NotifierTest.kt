package com.jd.notifier.notify

import com.jd.notifier.bridge.CompletedEvent
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("Notifier (message formatting + gating)")
class NotifierTest {

    private fun client(discord: Boolean = true, telegram: Boolean = true) = mock<NotificationClient> {
        on { discordConfigured } doReturn discord
        on { telegramConfigured } doReturn telegram
    }

    private fun job(
        company: String? = "Acme", role: String? = "Staff SDET", fit: Int? = 30,
        action: String? = "tailor", artifact: String? = null, jobUrl: String? = null, error: String? = null,
    ) = CompletedEvent(
        jobId = "j", completedSeq = 1, status = if (error != null) "error" else "done",
        company = company, roleTitle = role, fitScore = fit, pipelineAction = action,
        jobUrl = jobUrl, artifactUrl = artifact, error = error,
    )

    @Test
    @DisplayName("no channels configured → nothing sent")
    fun noChannels() {
        val c = client(discord = false, telegram = false)
        assertFalse(Notifier(c, fitThreshold = 50).notify(job()))
        verify(c, never()).postDiscord(any())
        verify(c, never()).postTelegramHtml(any())
    }

    @Test
    @DisplayName("non-job event (no company, no error) is skipped")
    fun nonJobSkipped() {
        val c = client()
        assertFalse(Notifier(c, 50).notify(job(company = null, role = null, error = null)))
        verify(c, never()).postDiscord(any())
    }

    @Test
    @DisplayName("scored job below threshold → Discord only, no Telegram")
    fun belowThreshold() {
        val c = client()
        assertTrue(Notifier(c, fitThreshold = 50).notify(job(fit = 30, action = "tailor")))
        verify(c).postDiscord(argThat { contains("Acme") && contains("Staff SDET") && contains("30") && contains("tailor") })
        verify(c, never()).postTelegramHtml(any())
    }

    @Test
    @DisplayName("high-fit job (>= threshold) → Discord + Telegram")
    fun highFit() {
        val c = client()
        Notifier(c, fitThreshold = 50).notify(job(fit = 80))
        verify(c).postDiscord(any())
        verify(c).postTelegramHtml(argThat { contains("High-fit") && contains("80") })
    }

    @Test
    @DisplayName("error event → Discord error line, no Telegram")
    fun errorEvent() {
        val c = client()
        Notifier(c, 50).notify(job(fit = null, error = "scrape failed"))
        verify(c).postDiscord(argThat { contains("error: scrape failed") })
        verify(c, never()).postTelegramHtml(any())
    }

    @Test
    @DisplayName("Discord label links the title to the report when artifactUrl present")
    fun discordLinkedTitle() {
        val c = client()
        Notifier(c, 50).notify(job(fit = 30, artifact = "http://markserv/x"))
        verify(c).postDiscord(argThat { contains("[Staff SDET](http://markserv/x)") })
    }

    @Test
    @DisplayName("Telegram high-fit uses HTML links (company→jobUrl, title→report.md) and escapes")
    fun telegramHtmlLinks() {
        val c = client()
        Notifier(c, 50).notify(job(fit = 90, artifact = "http://markserv/x", jobUrl = "https://acme.co/j"))
        verify(c).postTelegramHtml(argThat {
            contains("<a href=\"https://acme.co/j\">Acme</a>") &&
                contains("<a href=\"http://markserv/x/report.md\">Staff SDET</a>")
        })
    }
}
