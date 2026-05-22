package com.jd.pipeline.nodes.tailor

import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.models.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * LLM-mocked tests for [GapAnalysisNode].
 */
@DisplayName("GapAnalysisNodeTest")
class GapAnalysisNodeTest {

    private val baseProfile = CandidateProfile(
        identity = CandidateIdentity(name = "X", firstName = "X", lastName = "Y", email = "e", phone = "p", location = "R"),
        background = CandidateBackground(
            targetTitle = "Eng", yearsExperience = 5,
            summary = "", education = emptyList(), careerHistory = emptyList(),
            coreStrengths = listOf("Leadership"), languages = emptyList(), domainExpertise = emptyList()
        ),
        skills = CandidateSkills(
            primaryStack = listOf("Kotlin"), mobileAutomation = emptyList(),
            ciCdPlatforms = emptyList(), webApiAutomation = emptyList(),
            infrastructureObservability = emptyList(), leadershipAbilities = emptyList()
        )
    )

    private val baseState = TailorState(
        jdText = "jd",
        candidateProfile = baseProfile,
        fitScore = 80f,
        strengths = emptyList(),
        gaps = emptyList(),
        company = "Acme",
        roleTitle = "Eng",
        trackId = 1,
        jdStructured = JdStructured(roleTitle = "Eng", seniority = "Sr", requiredSkills = listOf("Kotlin"))
    )

    @Test
    @DisplayName("returns error when jdStructured is null")
    fun errorWhenJdStructuredNull() {
        val result = GapAnalysisNode().process(baseState.copy(jdStructured = null))
        assertTrue(result.error.contains("jdStructured is null"))
    }

    @Test
    @DisplayName("returns error when candidateProfile is null")
    fun errorWhenProfileNull() {
        val result = GapAnalysisNode().process(baseState.copy(candidateProfile = null))
        assertTrue(result.error.contains("candidateProfile is null"))
    }

    @Test
    @DisplayName("buildPrompt includes JD skills and candidate profile markdown")
    fun buildPromptContent() {
        val node = GapAnalysisNode()
        val m = GapAnalysisNode::class.java.getDeclaredMethod("buildPrompt", JdStructured::class.java, String::class.java)
        m.isAccessible = true
        val prompt = m.invoke(node, baseState.jdStructured, "MARKDOWN_HERE") as String
        assertTrue(prompt.contains("JD REQUIRED SKILLS:"))
        assertTrue(prompt.contains("JD PREFERRED SKILLS:"))
        assertTrue(prompt.contains("MARKDOWN_HERE"))
    }

    @Test
    @DisplayName("strips JSON fences and parses LLM response")
    fun stripsFencesAndParses() {
        val json = """
            {"skills_table":[],"keyword_coverage_score":85,"top_gaps":[],"top_strengths":[],"bullets_to_promote":[]}
        """.trimIndent()
        val mockLlm = LlmCaller { json }
        val result = GapAnalysisNode(llm = mockLlm).process(baseState)
        assertNotNull(result.gapAnalysis)
        assertEquals(85, result.gapAnalysis!!.keywordCoverageScore)
    }
}
