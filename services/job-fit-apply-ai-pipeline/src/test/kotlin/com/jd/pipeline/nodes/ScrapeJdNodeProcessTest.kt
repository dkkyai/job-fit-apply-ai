package com.jd.pipeline.nodes

import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [ScrapeJdNode.process] covering branches that do NOT require
 * a live HTTP connection or Playwright. Specifically: blank-URL early return,
 * and batch-level skip tracking (blocked domains, LinkedIn session expiry).
 *
 * The full scrape → LLM parse path requires network/browser and is covered
 * by integration/functional tests.
 */
@DisplayName("ScrapeJdNode — process()")
class ScrapeJdNodeProcessTest {

    private lateinit var node: ScrapeJdNode

    @BeforeEach
    fun setUp() {
        // LLM is never reached in these tests, but we provide a strict mock
        // so that any accidental invocation causes an obvious test failure.
        node = ScrapeJdNode(llm = LlmCaller { error("LLM must not be called in this test") })
        node.resetBatch()
    }

    // ── Blank URL early return ────────────────────────────────────────────────

    @Nested
    @DisplayName("blank / null jobUrl")
    inner class BlankJobUrl {

        @Test
        @DisplayName("returns input unchanged when jobUrl is empty")
        fun emptyJobUrlPassesThrough() {
            val input = JDState(isJobPosting = true, company = "Acme", jdText = "existing jd text")
            val result = node.process(input)
            assertEquals(input, result)
        }

        @Test
        @DisplayName("whitespace-only jobUrl is not treated as blank — node attempts fetch")
        fun whitespaceJobUrlNotBlank() {
            // "   " is non-null and non-empty, so the guard doesn't fire.
            // The node will try to extract a host and do a fetch; it fails with an error.
            val input = JDState(isJobPosting = true, jobUrl = "   ")
            val result = node.process(input)
            // The key behaviour: input is NOT returned unchanged — node tried to process it
            assertFalse(result == input && result.error.isBlank(),
                "Whitespace URL should either be processed or return an error state")
        }

        @Test
        @DisplayName("preserves existing jdText when jobUrl is empty")
        fun preservesJdText() {
            val input = JDState(jobUrl = "", jdText = "pre-populated jd text", company = "Corp")
            val result = node.process(input)
            assertEquals("pre-populated jd text", result.jdText)
        }
    }

    // ── Batch-level skip: blocked domains ────────────────────────────────────

    @Nested
    @DisplayName("blocked-domain batch skip")
    inner class BlockedDomain {

        @Test
        @DisplayName("returns error state when domain was blocked earlier in the batch")
        fun blockedDomainReturnsError() {
            node.batchBlockedDomains.add("example.com")
            val input = JDState(isJobPosting = true, jobUrl = "https://example.com/jobs/123")
            val result = node.process(input)
            assertTrue(result.error.isNotBlank(), "Expected an error message for blocked domain")
            assertTrue(result.error.contains("example.com"), "Error should mention the blocked domain")
        }

        @Test
        @DisplayName("unblocked domain is not pre-rejected (falls through to fetch)")
        fun unblockedDomainNotRejected() {
            // This test just ensures blocked-domain check doesn't incorrectly fire for different domains
            node.batchBlockedDomains.add("other.com")
            // We use a synthetic URL that won't resolve — the error will come from the HTTP fetch
            // not from the batch-block path. We verify the error does NOT contain "blocked earlier".
            val input = JDState(isJobPosting = true, jobUrl = "https://totally-different-domain.example/job/1")
            val result = node.process(input)
            // May error (network) but not from the blocked-domain guard
            assertFalse(result.error.contains("blocked earlier"), "Should not be rejected as blocked domain")
        }
    }

    // ── Batch-level skip: LinkedIn session ────────────────────────────────────

    @Nested
    @DisplayName("LinkedIn session expiry batch skip")
    inner class LinkedInSessionExpiry {

        @Test
        @DisplayName("returns re-auth error when the host hit an auth wall earlier this batch")
        fun sessionExpiredReturnsError() {
            node.batchAuthExpiredDomains.add("linkedin.com")
            val input = JDState(isJobPosting = true, jobUrl = "https://www.linkedin.com/jobs/view/12345")
            val result = node.process(input)
            assertTrue(result.isChromeSessionExpired, "Expected isChromeSessionExpired flag")
            assertTrue(result.error.contains("linkedin", ignoreCase = true), "Error should name the host")
            assertTrue(node.batchLinkedInSessionExpired, "LinkedIn back-compat accessor should be true")
        }
    }

    // ── Batch state management ────────────────────────────────────────────────

    @Nested
    @DisplayName("batch state management")
    inner class BatchState {

        @Test
        @DisplayName("resetBatch clears batchBlockedDomains and batchAuthExpiredDomains")
        fun resetBatchClearsState() {
            node.batchBlockedDomains.add("example.com")
            node.batchAuthExpiredDomains.add("linkedin.com")
            node.resetBatch()
            assertTrue(node.batchBlockedDomains.isEmpty())
            assertTrue(node.batchAuthExpiredDomains.isEmpty())
            assertFalse(node.batchLinkedInSessionExpired)
        }

        @Test
        @DisplayName("verbose flag defaults to true")
        fun verboseDefaultsTrue() {
            assertTrue(node.verbose)
        }
    }
}
