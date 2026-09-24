package com.jd.pipeline.pipeline

import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@DisplayName("ScrapeOutcome")
class ScrapeOutcomeTest {

    @Test
    @DisplayName("classifies malformed job URLs as a terminal scrape failure")
    fun malformedUrlIsTerminalScrapeFailure() {
        val outcome = ScrapeOutcome.classify(JDState(jobUrl = "https://jobs.example.com/a bad-url"))

        assertEquals(ScrapeTerminalReason.MALFORMED_URL, outcome)
    }

    @Test
    @DisplayName("classifies a blocked page after fetch fallback as a terminal scrape failure")
    fun blockedPageIsTerminalScrapeFailure() {
        val outcome = ScrapeOutcome.classify(JDState(error = "scrape_jd: HTTP 403 blocked", scrapePath = "blocked"))

        assertEquals(ScrapeTerminalReason.BLOCKED, outcome)
    }

    @Test
    @DisplayName("classifies navigation timeouts as a terminal scrape failure")
    fun navigationTimeoutIsTerminalScrapeFailure() {
        val outcome = ScrapeOutcome.classify(JDState(error = "scrape_jd: page.goto: Timeout 30000ms exceeded"))

        assertEquals(ScrapeTerminalReason.TIMEOUT, outcome)
    }

    @Test
    @DisplayName("classifies extracted JD text below the bridge minimum as a terminal scrape failure")
    fun insufficientJdTextIsTerminalScrapeFailure() {
        val outcome = ScrapeOutcome.classify(JDState(jobUrl = "https://jobs.example.com/123", jdText = "x".repeat(149), isJobPosting = true))

        assertEquals(ScrapeTerminalReason.INSUFFICIENT_JD_TEXT, outcome)
    }

    @Test
    @DisplayName("leaves transient service faults as JD_Error candidates")
    fun transientServiceFaultIsNotScrapeFailure() {
        val outcome = ScrapeOutcome.classify(JDState(error = "scrape_jd: LLM service returned 503"))

        assertNull(outcome)
    }

    @Test
    @DisplayName("maps a terminal scrape failure to the dedicated Gmail label")
    fun terminalScrapeFailureUsesDedicatedGmailLabel() {
        assertEquals(TerminalLabel.JD_SCRAPE_FAILED, ScrapeTerminalReason.BLOCKED.terminalLabel)
    }
}
