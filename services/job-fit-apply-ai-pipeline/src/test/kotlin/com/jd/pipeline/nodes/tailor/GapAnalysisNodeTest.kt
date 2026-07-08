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
        skills = listOf(
            SkillGroup("Primary Stack", listOf("Kotlin"))
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
        jdRequirements = JdRequirements(targetTitle = "Staff SDET", mustHave = listOf("Kotlin", "Espresso"))
    )

    @Test
    @DisplayName("returns error when jdRequirements is null")
    fun errorWhenJdRequirementsNull() {
        val result = GapAnalysisNode().process(baseState.copy(jdRequirements = null))
        assertTrue(result.error.contains("jdRequirements is null"))
    }

    @Test
    @DisplayName("returns error when candidateProfile is null")
    fun errorWhenProfileNull() {
        val result = GapAnalysisNode().process(baseState.copy(candidateProfile = null))
        assertTrue(result.error.contains("candidateProfile is null"))
    }

    @Test
    @DisplayName("buildPrompt includes JD must-have terms and candidate profile markdown")
    fun buildPromptContent() {
        val node = GapAnalysisNode()
        val m = GapAnalysisNode::class.java.getDeclaredMethod("buildPrompt", JdRequirements::class.java, String::class.java)
        m.isAccessible = true
        val jd = baseState.jdRequirements!!
        val prompt = m.invoke(node, jd, "MARKDOWN_HERE") as String
        assertTrue(prompt.contains("MUST-HAVE TERMS"))
        jd.mustHave.forEach { term -> assertTrue(prompt.contains(term), "expected prompt to contain must-have term: $term") }
        assertTrue(prompt.contains("MARKDOWN_HERE"))
    }

    @Test
    @DisplayName("strips JSON fences and parses LLM response")
    fun stripsFencesAndParses() {
        val json = """
            ```json
            {"supported":[{"term":"Kotlin","evidence":"built Kotlin frameworks"}],"unsupported":["Pact"],"missing_but_supported":[{"term":"Espresso","evidence":"maintained Espresso suites"}]}
            ```
        """.trimIndent()
        val mockLlm = LlmCaller { json }
        val result = GapAnalysisNode(llm = mockLlm).process(baseState)
        assertEquals("", result.error, "expected no error, got: ${result.error}")
        assertNotNull(result.gapAnalysis)
        assertEquals(listOf(EvidencedTerm("Kotlin", "built Kotlin frameworks")), result.gapAnalysis!!.supported)
        assertEquals(listOf("Pact"), result.gapAnalysis!!.unsupported)
        assertEquals(listOf(EvidencedTerm("Espresso", "maintained Espresso suites")), result.gapAnalysis!!.missingButSupported)
    }

    @Test
    @DisplayName("returns error when the LLM returns an empty partition")
    fun errorWhenEmptyPartition() {
        val json = """{"supported":[],"unsupported":[],"missing_but_supported":[]}"""
        val mockLlm = LlmCaller { json }
        val result = GapAnalysisNode(llm = mockLlm).process(baseState)
        assertTrue(result.error.contains("empty partition"))
    }
}
