package com.jd.pipeline.nodes

import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.config.Config
import com.microsoft.playwright.Page
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [ScrapeJdNode.waitForBotCheckToClear] — the wait-and-retry that turns a transient
 * Cloudflare interstitial (e.g. Glassdoor's "Just a moment…") into a real scrape instead of poisoning
 * the whole batch. Uses a mocked [Page] so no live browser is needed; the actual sleeping is delegated
 * to [Page.waitForTimeout] (a no-op on the mock), so the loop runs instantly and deterministically.
 */
@DisplayName("ScrapeJdNode — waitForBotCheckToClear()")
class ScrapeJdNodeBotCheckTest {

    private val node = ScrapeJdNode(llm = LlmCaller { error("LLM must not be called in this test") })

    private val botCheckHtml =
        "<html><head><title>Just a moment...</title></head><body>Checking your browser before you continue.</body></html>"
    private val realJdHtml =
        "<html><head><title>Senior Engineer at Acme</title></head><body>We are hiring a backend engineer. Requirements: Kotlin, AWS.</body></html>"

    @Test
    @DisplayName("returns the cleared HTML once the challenge resolves on a later poll")
    fun clearsAfterChallengeResolves() {
        val page = mock<Page>()
        whenever(page.url()).thenReturn("https://www.glassdoor.com/job/123")
        // First read is still the interstitial; the challenge JS completes before the second read.
        whenever(page.content()).thenReturn(botCheckHtml, realJdHtml)

        val result = node.waitForBotCheckToClear(page)

        assertEquals(realJdHtml, result, "Should return the page HTML once the bot-check clears")
    }

    @Test
    @DisplayName("returns null and stops after a bounded number of polls when the challenge never clears")
    fun givesUpWhenChallengeNeverClears() {
        val page = mock<Page>()
        whenever(page.url()).thenReturn("https://www.glassdoor.com/job/123")
        whenever(page.content()).thenReturn(botCheckHtml)

        val result = node.waitForBotCheckToClear(page)

        assertNull(result, "Should give up when the interstitial never clears")
        // Bounded by attempts = MAX_WAIT / POLL — proves the loop cannot spin forever.
        val expectedAttempts =
            (Config.CDP_BOTCHECK_MAX_WAIT_MS / Config.CDP_BOTCHECK_POLL_MS.coerceAtLeast(250L)).toInt().coerceAtLeast(1)
        verify(page, times(expectedAttempts)).waitForTimeout(any())
    }
}
