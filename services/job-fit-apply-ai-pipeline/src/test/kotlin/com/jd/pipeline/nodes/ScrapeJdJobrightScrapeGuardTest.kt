package com.jd.pipeline.nodes

import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the jobright.ai digest-split scrape path so a posting is scored on a full JD, not the
 * one-line digest summary that seeds each child's jdText.
 *
 * Two behaviours are locked here:
 *  1. jobright.ai is always routed to the authenticated CDP browser ([ScrapeJdNode.forcesCdp]),
 *     so the full __NEXT_DATA__ JD is fetched from the logged-in session rather than a thin
 *     logged-out HTTP preview.
 *  2. When a jobright scrape fails to yield a substantive JD (blocked / auth wall / thin preview),
 *     the leftover thin digest-summary jdText is blanked so score_fit SKIPs it instead of scoring
 *     a <400-char summary.
 */
@DisplayName("ScrapeJdNode — jobright digest-split JD guard")
class ScrapeJdJobrightScrapeGuardTest {

    private lateinit var node: ScrapeJdNode

    @BeforeEach
    fun setUp() {
        node = ScrapeJdNode(llm = LlmCaller { error("LLM must not be called in this test") })
        node.resetBatch()
    }

    // A one-line digest summary, exactly what createParsedDigestJob seeds each child's jdText with.
    private val digestSummary =
        "Test Automation Lead @ Qualitest | Remote | \$122K/yr - \$126K/yr | https://jobright.ai/jobs/info/abc123"

    // ── 1. Force-CDP routing ─────────────────────────────────────────────────

    @Test
    @DisplayName("jobright.ai and its subdomains always route to the CDP browser")
    fun jobrightForcesCdp() {
        assertTrue(node.forcesCdp("jobright.ai"))
        assertTrue(node.forcesCdp("www.jobright.ai"))
        assertTrue(node.isJobrightHost("jobright.ai"))
    }

    @Test
    @DisplayName("look-alike and unlisted hosts are not force-CDP by the jobright rule")
    fun lookalikesDoNotForceCdp() {
        assertFalse(node.isJobrightHost("notjobright.ai"))       // suffix without a dot boundary
        assertFalse(node.isJobrightHost("jobright.ai.evil.com"))  // jobright as a subdomain of attacker
        assertFalse(node.forcesCdp("jobright.ai.evil.com"))       // the jobright rule must not over-match
    }

    // ── 2. Thin-JD guard (block / thin-page case) ────────────────────────────

    @Test
    @DisplayName("a blocked jobright posting is not scored on the leftover digest summary")
    fun blockedJobrightClearsSummary() {
        // Pre-mark jobright.ai as blocked this batch → process() hits the batch-skip early return
        // without any network fetch (the LLM strict-mock proves no scrape/extraction ran).
        node.batchBlockedDomains.add("jobright.ai")
        val input = JDState(
            isJobPosting = true,
            jobUrl = "https://jobright.ai/jobs/info/abc123",
            jdText = digestSummary,
        )

        val result = node.process(input)

        assertTrue(result.jdText.isBlank(), "thin digest summary must be blanked so score_fit skips it")
        assertFalse(result.isJobPosting, "a posting we could not fetch must not look scorable")
        assertTrue(result.error.contains("blocked", ignoreCase = true))
    }

    @Test
    @DisplayName("a thin jobright page (LLM finds no jd_text) is not scored on a summary")
    fun thinJobrightPageClearsJd() {
        // capturedText path: no network. The rendered preview is thin and the LLM returns no jd_text,
        // so the only candidate JD would be the thin preview / summary — which must be dropped.
        val thinNode = ScrapeJdNode(llm = LlmCaller { """{"role_title":"Test Automation Lead","jd_text":null}""" })
        thinNode.resetBatch()
        val input = JDState(
            isJobPosting = true,
            jobUrl = "https://jobright.ai/jobs/info/abc123",
            jdText = digestSummary,
            capturedText = "Sign in to view this job on Jobright.",
        )

        val result = thinNode.process(input)

        assertTrue(result.jdText.isBlank(), "thin page must not survive as a scorable JD")
        assertFalse(result.isJobPosting)
    }

    @Test
    @DisplayName("a full jobright JD is preserved and marked scorable")
    fun fullJobrightJdPreserved() {
        val fullJd = "Responsibilities: " + "Own the automation strategy. ".repeat(40) // > 400 chars
        val fullNode = ScrapeJdNode(llm = LlmCaller {
            """{"role_title":"Test Automation Lead","company":"Qualitest","jd_text":${jsonString(fullJd)}}"""
        })
        fullNode.resetBatch()
        val input = JDState(
            isJobPosting = true,
            jobUrl = "https://jobright.ai/jobs/info/abc123",
            jdText = digestSummary,
            capturedText = "Test Automation Lead at Qualitest. Full description below. " + fullJd,
        )

        val result = fullNode.process(input)

        assertTrue(result.jdText.length >= 400, "a substantive JD must be kept: ${result.jdText.length} chars")
        assertEquals(fullJd, result.jdText)
        assertTrue(result.isJobPosting)
    }

    private fun jsonString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
