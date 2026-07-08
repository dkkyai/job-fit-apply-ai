package com.jd.pipeline.nodes.tailor

import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.models.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * LLM-mocked tests for [SummaryRewriteNode].
 */
@DisplayName("SummaryRewriteNodeTest")
class SummaryRewriteNodeTest {

    private val baseProfile = CandidateProfile(
        identity = CandidateIdentity(name = "X", firstName = "X", lastName = "Y", email = "e", phone = "p", location = "R"),
        background = CandidateBackground(
            targetTitle = "Eng", yearsExperience = 5,
            summary = "Original summary",
            education = emptyList(), careerHistory = emptyList(),
            coreStrengths = emptyList(), languages = emptyList(), domainExpertise = emptyList()
        ),
        skills = emptyList()
    )

    private val baseState = TailorState(
        jdText = "jd",
        candidateProfile = baseProfile,
        fitScore = 80f,
        strengths = emptyList(), gaps = emptyList(),
        company = "Acme", roleTitle = "Eng", trackId = 1,
        jdRequirements = JdRequirements(targetTitle = "Staff SDET", mustHave = listOf("Kotlin", "Espresso")),
        gapAnalysis = GapAnalysis(
            supported = listOf(EvidencedTerm("Kotlin", "built Kotlin frameworks")),
            unsupported = listOf("Pact")
        )
    )

    private fun invokeBuildPrompt(state: TailorState): String {
        val node = SummaryRewriteNode()
        val m = SummaryRewriteNode::class.java.getDeclaredMethod(
            "buildPrompt", JdRequirements::class.java, GapAnalysis::class.java, TailorState::class.java, String::class.java
        )
        m.isAccessible = true
        return m.invoke(node, state.jdRequirements, state.gapAnalysis, state, "PROFILE_MD") as String
    }

    @Test
    @DisplayName("returns error when jdRequirements is null")
    fun errorWhenJdRequirementsNull() {
        val result = SummaryRewriteNode().process(baseState.copy(jdRequirements = null))
        assertTrue(result.error.contains("jdRequirements is null"))
    }

    @Test
    @DisplayName("returns error when gapAnalysis is null")
    fun errorWhenGapAnalysisNull() {
        val result = SummaryRewriteNode().process(baseState.copy(gapAnalysis = null))
        assertTrue(result.error.contains("gapAnalysis is null"))
    }

    @Test
    @DisplayName("returns error when candidateProfile is null")
    fun errorWhenProfileNull() {
        val result = SummaryRewriteNode().process(baseState.copy(candidateProfile = null))
        assertTrue(result.error.contains("candidateProfile is null"))
    }

    @Test
    @DisplayName("buildPrompt includes target title, supported terms, do-not-claim list, and current summary")
    fun buildPromptContent() {
        val prompt = invokeBuildPrompt(baseState)
        assertTrue(prompt.contains("TARGET TITLE"))
        assertTrue(prompt.contains("Staff SDET"))
        assertTrue(prompt.contains("SUPPORTED TERMS"))
        assertTrue(prompt.contains("Kotlin"))
        assertTrue(prompt.contains("DO NOT CLAIM"))
        assertTrue(prompt.contains("Pact"))
        assertTrue(prompt.contains("CURRENT SUMMARY"))
        assertTrue(prompt.contains("Original summary"))
        assertTrue(prompt.contains("PROFILE_MD"))
        // The skill file's own instructions mention the feedback block by name, so assert
        // on the block's CONTENT lines, which only the node emits when atsReport is set.
        assertFalse(prompt.contains("Must-have coverage:"), "no revision block without an atsReport")
    }

    @Test
    @DisplayName("buildPrompt includes validation feedback when atsReport is present")
    fun buildPromptRevisionBlock() {
        val report = AtsReport(
            mustHaveCoveragePct = 60,
            missingTerms = listOf("Espresso"),
            topImprovements = listOf("add coverage numbers")
        )
        val prompt = invokeBuildPrompt(baseState.copy(atsReport = report))
        assertTrue(prompt.contains("PREVIOUS VALIDATION FEEDBACK"))
        assertTrue(prompt.contains("Espresso"))
        assertTrue(prompt.contains("add coverage numbers"))
    }

    @Test
    @DisplayName("calls LLM and stores trimmed summary")
    fun callsLlmAndStoresSummary() {
        val mockLlm = LlmCaller { "Rewritten summary." }
        val result = SummaryRewriteNode(llm = mockLlm).process(baseState)
        assertNotNull(result.tailoredSummary)
        assertEquals("Rewritten summary.", result.tailoredSummary)
    }

    @Test
    @DisplayName("shingleOverlap detects parroting and clears fresh rewrites")
    fun shingleOverlapDetection() {
        val base = "Seasoned SDET leading mobile automation teams across large retail organisations"
        assertTrue(SummaryRewriteNode.shingleOverlap(base, base) > 0.9)
        assertTrue(
            SummaryRewriteNode.shingleOverlap(
                base,
                "Staff engineer specialising in CI/CD pipeline ownership and cross-platform test frameworks"
            ) < 0.2
        )
    }

    @Test
    @DisplayName("sanitizeSummary strips fences and labels; isPlausibleSummary rejects JSON and rambles")
    fun sanitationAndPlausibility() {
        assertEquals("A tight summary.", SummaryRewriteNode.sanitizeSummary("Summary: A tight summary."))
        assertEquals("Prose here.", SummaryRewriteNode.sanitizeSummary("```\nProse here.\n```"))
        assertTrue(SummaryRewriteNode.isPlausibleSummary("Staff SDET with 15 years building test infra."))
        assertFalse(SummaryRewriteNode.isPlausibleSummary("""{"role": "SUMMARY_REWRITE_SKILL", "x": 1}"""), "JSON echo rejected")
        assertFalse(SummaryRewriteNode.isPlausibleSummary("- a\n- b"), "list echo rejected")
        assertFalse(SummaryRewriteNode.isPlausibleSummary("x".repeat(SummaryRewriteNode.MAX_SUMMARY_CHARS + 1)), "ramble rejected")
    }

    @Test
    @DisplayName("a JSON-echo output triggers one corrective retry, then degrades to an error")
    fun jsonEchoDegrades() {
        var calls = 0
        val jsonEcho = """{"role": "SUMMARY_REWRITE_SKILL", "target_title": "Staff SDET", "supported_terms": ["Kotlin"]}"""
        val mockLlm = LlmCaller { calls++; jsonEcho }   // never returns prose
        val result = SummaryRewriteNode(llm = mockLlm).process(baseState)
        assertEquals(2, calls, "one initial call + one corrective retry")
        assertTrue(result.tailoredSummary == null)
        assertTrue(result.error.contains("plain-prose summary"), "must degrade with a clear error: ${result.error}")
    }

    @Test
    @DisplayName("a corrective retry that returns prose is accepted")
    fun correctiveRetryRecovers() {
        var calls = 0
        val mockLlm = LlmCaller {
            calls++
            if (calls == 1) """{"echo": "of the prompt"}""" else "Staff SDET with deep Kotlin and Espresso automation depth."
        }
        val result = SummaryRewriteNode(llm = mockLlm).process(baseState)
        assertEquals(2, calls)
        assertEquals("Staff SDET with deep Kotlin and Espresso automation depth.", result.tailoredSummary)
    }

    @Test
    @DisplayName("retries once when the draft parrots the base summary")
    fun retriesWhenDraftParrots() {
        val base = "Seasoned SDET leading mobile automation teams across large retail organisations"
        val profile = baseProfile.copy(background = baseProfile.background.copy(summary = base))
        var calls = 0
        val mockLlm = LlmCaller {
            calls++
            if (calls == 1) base else "Staff engineer specialising in CI/CD pipeline ownership and cross-platform test frameworks"
        }
        val result = SummaryRewriteNode(llm = mockLlm).process(baseState.copy(candidateProfile = profile))
        assertEquals(2, calls, "Expected one retry after a parroted draft")
        assertEquals(
            "Staff engineer specialising in CI/CD pipeline ownership and cross-platform test frameworks",
            result.tailoredSummary
        )
    }
}
