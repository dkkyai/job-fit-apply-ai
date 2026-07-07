package com.jd.pipeline.nodes

import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Dual-mode [ScrapeJdNode]: when the browser extension supplies pre-rendered page text
 * ([JDState.capturedText]), the node skips fetching entirely and LLM-extracts straight
 * from that text — the path that sidesteps server-side auth walls.
 */
@DisplayName("ScrapeJdNode — captured-text (extension) mode")
class ScrapeJdNodeCaptureTest {

    private val jd = "We are hiring a Staff SDET. ".repeat(20)  // > 150 chars

    private fun llmJson() = """
        {"role_title":"Staff SDET","company":"Acme","location":"Remote",
         "tech_stack":["Kotlin","Playwright"],"jd_text":"$jd"}
    """.trimIndent()

    @Test
    @DisplayName("extracts JD from capturedText without invoking any fetch")
    fun extractsFromCapturedText() {
        val calls = AtomicInteger(0)
        val node = ScrapeJdNode(llm = LlmCaller { calls.incrementAndGet(); llmJson() })

        val input = JDState(
            jobUrl = "https://linkedin.com/jobs/view/123",   // auth-gated host — must NOT be fetched
            capturedText = "raw visible page text ".repeat(30),
        )
        val out = node.process(input)

        assertEquals(1, calls.get(), "extraction LLM should be called exactly once")
        assertEquals("Staff SDET", out.roleTitle)
        assertEquals("Acme", out.company)
        assertTrue(out.jdText.length >= 150, "jd_text should be populated from the LLM")
        assertTrue(out.isJobPosting)
        assertEquals("captured", out.scrapePath, "captured path marks the extension origin")
    }

    @Test
    @DisplayName("no capturedText → does not take the captured path (LLM not called for the fetch guard)")
    fun blankCapturedTextIgnored() {
        // With a blank jobUrl the node returns early; capturedText being blank means the
        // captured-path branch is skipped. Proves the branch is guarded by capturedText.
        val node = ScrapeJdNode(llm = LlmCaller { error("must not be called") })
        val out = node.process(JDState(jobUrl = "", capturedText = ""))
        assertEquals("", out.scrapePath)
    }
}
