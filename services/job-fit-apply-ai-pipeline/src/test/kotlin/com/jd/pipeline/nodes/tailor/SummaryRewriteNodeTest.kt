package com.jd.pipeline.nodes.tailor

import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.models.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
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
        skills = CandidateSkills(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    )

    private val baseState = TailorState(
        jdText = "jd",
        candidateProfile = baseProfile,
        fitScore = 80f,
        strengths = emptyList(), gaps = emptyList(),
        company = "Acme", roleTitle = "Eng", trackId = 1,
        jdStructured = JdStructured("Eng", "Sr", listOf("Kotlin")),
        gapAnalysis = GapAnalysis(keywordCoverageScore = 90, topStrengths = listOf("Kotlin"))
    )

    @Test
    @DisplayName("returns error when jdStructured is null")
    fun errorWhenJdStructuredNull() {
        val result = SummaryRewriteNode().process(baseState.copy(jdStructured = null))
        assertTrue(result.error.contains("jdStructured is null"))
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
    @DisplayName("buildPrompt includes current summary and ATS phrases")
    fun buildPromptContent() {
        val node = SummaryRewriteNode()
        val m = SummaryRewriteNode::class.java.getDeclaredMethod(
            "buildPrompt", JdStructured::class.java, GapAnalysis::class.java, TailorState::class.java, String::class.java
        )
        m.isAccessible = true
        val prompt = m.invoke(node, baseState.jdStructured, baseState.gapAnalysis, baseState, "PROFILE_MD") as String
        assertTrue(prompt.contains("CURRENT SUMMARY"))
        assertTrue(prompt.contains("Original summary"))
        assertTrue(prompt.contains("PROFILE_MD"))
        assertTrue(prompt.contains("ATS PHRASES"))
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
