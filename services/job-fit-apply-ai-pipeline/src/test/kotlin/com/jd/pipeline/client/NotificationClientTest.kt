package com.jd.pipeline.client

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the configured-flag logic that gates every notification. A blank token or
 * channel id must leave the channel disabled — this is the exact condition that
 * silently suppressed all messages when the credentials were missing from `.env`.
 */
@DisplayName("NotificationClient configuration flags")
class NotificationClientTest {

    private fun client(
        discordToken: String = "",
        discordChannelId: String = "",
        telegramToken: String = "",
        telegramChatId: String = "",
    ) = NotificationClient(discordToken, discordChannelId, telegramToken, telegramChatId)

    @Test
    @DisplayName("both channels disabled when all credentials are blank")
    fun allBlankDisablesBoth() {
        val c = client()
        assertFalse(c.discordConfigured)
        assertFalse(c.telegramConfigured)
    }

    @Test
    @DisplayName("Discord requires both token and channel id")
    fun discordNeedsBoth() {
        assertFalse(client(discordToken = "tok").discordConfigured)
        assertFalse(client(discordChannelId = "123").discordConfigured)
        assertTrue(client(discordToken = "tok", discordChannelId = "123").discordConfigured)
    }

    @Test
    @DisplayName("Telegram requires both token and chat id")
    fun telegramNeedsBoth() {
        assertFalse(client(telegramToken = "tok").telegramConfigured)
        assertFalse(client(telegramChatId = "123").telegramConfigured)
        assertTrue(client(telegramToken = "tok", telegramChatId = "123").telegramConfigured)
    }

    @Test
    @DisplayName("blank-but-present (whitespace) credentials are treated as unconfigured")
    fun whitespaceIsBlank() {
        assertFalse(client(discordToken = "   ", discordChannelId = "   ").discordConfigured)
        assertFalse(client(telegramToken = "   ", telegramChatId = "   ").telegramConfigured)
    }

    @Test
    @DisplayName("post methods are safe no-ops when unconfigured")
    fun unconfiguredPostsAreNoOps() {
        val c = client()
        // Must not throw or attempt any HTTP when channels are disabled.
        c.postDiscord("hello")
        c.postTelegram("hello")
        c.postTelegramHtml("<a href=\"https://x/report.md\">hello</a>")
    }
}
