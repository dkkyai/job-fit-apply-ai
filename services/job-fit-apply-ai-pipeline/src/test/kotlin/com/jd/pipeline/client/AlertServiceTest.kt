package com.jd.pipeline.client

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import kotlin.test.assertTrue

@DisplayName("AlertService")
class AlertServiceTest {

    @Test
    @DisplayName("dispatches to both Discord and Telegram with severity emoji, title and message")
    fun dispatchesBothChannels() {
        val client = mock<NotificationClient>()
        AlertService(client).send(Alert(Severity.ERROR, "Boom", "it broke"))
        verify(client).postDiscord(check {
            assertTrue(it.contains("🔴"), "should carry the ERROR emoji")
            assertTrue(it.contains("Boom"), "should carry the title")
            assertTrue(it.contains("it broke"), "should carry the message")
        })
        verify(client).postTelegramHtml(check {
            assertTrue(it.contains("Boom"))
            assertTrue(it.contains("it broke"))
        })
    }

    @Test
    @DisplayName("renders linkUrl as an HTML anchor on Telegram and a bare link on Discord")
    fun rendersLink() {
        val client = mock<NotificationClient>()
        AlertService(client).send(Alert(Severity.INFO, "See", "details", linkUrl = "https://x.example/report.md"))
        verify(client).postTelegramHtml(check {
            assertTrue(it.contains("<a href=\"https://x.example/report.md\">"), "telegram should anchor the link")
        })
        verify(client).postDiscord(check {
            assertTrue(it.contains("https://x.example/report.md"), "discord should include the link")
        })
    }

    @Test
    @DisplayName("HTML-escapes interpolated text in the Telegram message")
    fun escapesTelegram() {
        val client = mock<NotificationClient>()
        AlertService(client).send(Alert(Severity.WARN, "A & B", "<danger>"))
        verify(client).postTelegramHtml(check {
            assertTrue(it.contains("A &amp; B"), "title should be HTML-escaped")
            assertTrue(it.contains("&lt;danger&gt;"), "message should be HTML-escaped")
        })
    }

    @Test
    @DisplayName("de-dupes alerts that share a key for the lifetime of the service")
    fun dedupesByKey() {
        val client = mock<NotificationClient>()
        val alerts = AlertService(client)
        alerts.send(Alert(Severity.WARN, "x", "y"), dedupKey = "k")
        alerts.send(Alert(Severity.WARN, "x", "y"), dedupKey = "k")
        verify(client, times(1)).postDiscord(any())
    }

    @Test
    @DisplayName("does not de-dupe when no key is supplied")
    fun noDedupWithoutKey() {
        val client = mock<NotificationClient>()
        val alerts = AlertService(client)
        alerts.send(Alert(Severity.WARN, "x", "y"))
        alerts.send(Alert(Severity.WARN, "x", "y"))
        verify(client, times(2)).postDiscord(any())
    }

    @Test
    @DisplayName("reauthRequired names the site and de-dupes per site")
    fun reauthRequired() {
        val client = mock<NotificationClient>()
        val alerts = AlertService(client)
        alerts.reauthRequired("LinkedIn")
        alerts.reauthRequired("LinkedIn")
        verify(client, times(1)).postDiscord(check {
            assertTrue(it.contains("Sign-in required: LinkedIn"), "should name the site that needs sign-in")
        })
    }

    @Test
    @DisplayName("chromeDebugUnavailable de-dupes and references the endpoint")
    fun chromeDebugUnavailable() {
        val client = mock<NotificationClient>()
        val alerts = AlertService(client)
        alerts.chromeDebugUnavailable("http://localhost:9222")
        alerts.chromeDebugUnavailable("http://localhost:9222")
        verify(client, times(1)).postDiscord(check {
            assertTrue(it.contains("http://localhost:9222"), "should reference the unreachable endpoint")
        })
    }

    @Test
    @DisplayName("pipelineTimeout states the duration and that it timed out")
    fun pipelineTimeout() {
        val client = mock<NotificationClient>()
        AlertService(client).pipelineTimeout(120)
        verify(client).postDiscord(check {
            assertTrue(it.contains("120 min"), "should state the timeout duration")
            assertTrue(it.contains("timed out"), "should say it timed out")
        })
    }

    @Test
    @DisplayName("send is a safe no-op when the client has no configured channels")
    fun safeNoOpWhenUnconfigured() {
        val alerts = AlertService(NotificationClient("", "", "", ""))
        // Must not throw — NotificationClient swallows posts when unconfigured.
        alerts.send(Alert(Severity.INFO, "hi", "there"))
        alerts.reauthRequired("LinkedIn")
        alerts.pipelineTimeout(5)
    }
}
