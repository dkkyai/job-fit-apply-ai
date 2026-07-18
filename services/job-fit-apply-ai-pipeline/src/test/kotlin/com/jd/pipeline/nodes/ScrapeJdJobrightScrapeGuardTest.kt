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
 *  1. When a jobright scrape fails to yield a substantive JD (blocked / auth wall / thin preview),
 *     the leftover thin digest-summary jdText is blanked so score_fit SKIPs it instead of scoring
 *     a <400-char summary.
 *  2. Blanking keeps the posting a *job posting* and gives it an error, so it stays visible and
 *     retryable rather than vanishing from the digest fan-out (or landing on JD_Not_Found).
 *
 * Routing jobright to the authenticated CDP browser — needed for the full __NEXT_DATA__ JD — is
 * configuration (CDP_FORCE_DOMAINS in .env), not logic, so it is not asserted here.
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

    // ── 1. Host matching ─────────────────────────────────────────────────────

    @Test
    @DisplayName("look-alike hosts do not match the jobright rule")
    fun lookalikesDoNotMatch() {
        assertTrue(node.isJobrightHost("jobright.ai"))
        assertTrue(node.isJobrightHost("www.jobright.ai"))
        assertFalse(node.isJobrightHost("notjobright.ai"))        // suffix without a dot boundary
        assertFalse(node.isJobrightHost("jobright.ai.evil.com"))  // jobright as a subdomain of attacker
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
        assertTrue(result.error.contains("blocked", ignoreCase = true))
    }

    @Test
    @DisplayName("a failed jobright scrape stays a job posting so it is not silently dropped")
    fun failedScrapeStaysAJobPosting() {
        // Regression guard. Clearing isJobPosting makes the posting VANISH: EmailResolution
        // re-enqueues digest children only when isJobPosting, so the child would get no bridge job,
        // no terminal label, no run-log line and no retry — and a non-digest jobright email would
        // classify as SkipNotJob → JD_Not_Found, which the intake query excludes forever. It must
        // stay a job posting (with a blank JD) so score_fit SKIPs it visibly instead.
        node.batchBlockedDomains.add("jobright.ai")
        val input = JDState(
            isJobPosting = true,
            jobUrl = "https://jobright.ai/jobs/info/abc123",
            jdText = digestSummary,
        )

        val result = node.process(input)

        assertTrue(result.isJobPosting, "a failed fetch must stay re-enqueueable/labelable, not vanish")
        assertTrue(result.error.isNotBlank(), "the failure must carry an error so it is visible")
    }

    @Test
    @DisplayName("a thin jobright scrape with no other error still gets one, so it is not JD_Processed")
    fun thinScrapeSetsAnError() {
        // The successful-but-thin path sets no error of its own. Without one, a non-digest jobright
        // email would look like a clean success and be labelled JD_Processed with nothing scored.
        val thinNode = ScrapeJdNode(llm = LlmCaller { """{"role_title":"Test Automation Lead","jd_text":null}""" })
        thinNode.resetBatch()
        val input = JDState(
            isJobPosting = true,
            jobUrl = "https://jobright.ai/jobs/info/abc123",
            jdText = digestSummary,
            capturedText = "Sign in to view this job on Jobright.",
        )

        val result = thinNode.process(input)

        assertTrue(result.jdText.isBlank())
        assertTrue(result.error.contains("no usable jobright JD"), "got: '${result.error}'")
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
