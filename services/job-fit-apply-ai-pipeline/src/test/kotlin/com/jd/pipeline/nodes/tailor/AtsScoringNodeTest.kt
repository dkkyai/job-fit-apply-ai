package com.jd.pipeline.nodes.tailor

import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.models.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * LLM-mocked tests for [AtsScoringNode].
 */
@DisplayName("AtsScoringNodeTest")
class AtsScoringNodeTest {

    private val baseProfile = CandidateProfile(
        identity = CandidateIdentity(name = "X", firstName = "X", lastName = "Y", email = "e", phone = "p", location = "R"),
        background = CandidateBackground(
            targetTitle = "Eng", yearsExperience = 5,
            summary = "", education = emptyList(),
            careerHistory = listOf(CareerEntry("Eng", "Acme", "", "2020", null, listOf("B1"))),
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
        gapAnalysis = GapAnalysis(keywordCoverageScore = 90, topGaps = listOf("Rust")),
        tailoredSummary = "Summary text",
        tailoredCareerHistory = baseProfile.background.careerHistory,
        tailoredProjects = emptyList(),
        tailoredBullets = emptyList(),
        restructuredSkills = RestructuredSkills(restructuredText = "Skills text")
    )

    @Test
    @DisplayName("returns error when any prerequisite is null")
    fun prerequisiteErrors() {
        val node = AtsScoringNode()
        assertTrue(node.process(baseState.copy(jdStructured = null)).error.contains("jdStructured is null"))
        assertTrue(node.process(baseState.copy(gapAnalysis = null)).error.contains("gapAnalysis is null"))
        assertTrue(node.process(baseState.copy(tailoredSummary = null)).error.contains("tailoredSummary is null"))
        assertTrue(node.process(baseState.copy(tailoredBullets = null)).error.contains("tailoredBullets is null"))
        assertTrue(node.process(baseState.copy(restructuredSkills = null)).error.contains("restructuredSkills is null"))
    }

    @Test
    @DisplayName("buildPrompt includes sub-scores, remaining gaps, and verified keyword presence")
    fun buildPromptContent() {
        val node = AtsScoringNode()
        val verification = node.verifyKeywords(baseState.jdStructured, "Summary text mentioning Kotlin")
        val m = AtsScoringNode::class.java.getDeclaredMethod(
            "buildPrompt", TailorState::class.java, AtsScoringNode.KeywordVerification::class.java
        )
        m.isAccessible = true
        val prompt = m.invoke(node, baseState, verification) as String
        assertTrue(prompt.contains("TAILORED SUMMARY"))
        assertTrue(prompt.contains("REMAINING GAPS"))
        assertTrue(prompt.contains("KEYWORD COVERAGE"))
        assertTrue(prompt.contains("VERIFIED KEYWORD PRESENCE"))
    }

    @Test
    @DisplayName("mocked LLM returns parsed AtsScore with overall recomputed from sub-scores")
    fun mockedLlmReturnsScore() {
        val json = """
            {"overall_score":88,"keyword_match":90,"skill_coverage":85,"seniority_alignment":80,"quantification":75,"format_safety":95,"remaining_gaps":[],"top_3_improvements":[]}
        """.trimIndent()
        val mockLlm = LlmCaller { json }
        val result = AtsScoringNode(llm = mockLlm).process(baseState)
        assertNotNull(result.atsScore)
        // No ATS phrases extracted → keyword_match kept from the LLM (90); overall is always
        // the recomputed composite: 90*.30 + 85*.25 + 80*.20 + 75*.15 + 95*.10 = 85.
        assertEquals(90, result.atsScore!!.keywordMatch)
        assertEquals(85, result.atsScore!!.overallScore)
        assertEquals("", result.error)
    }

    @Test
    @DisplayName("keyword_match is recomputed deterministically from verified ATS phrase presence")
    fun deterministicKeywordCalibration() {
        val state = baseState.copy(
            jdStructured = JdStructured(
                roleTitle = "Eng", seniority = "Sr", requiredSkills = listOf("Kotlin"),
                atsExactPhrases = listOf("test automation framework", "pipeline ownership")
            ),
            tailoredSummary = "Built a test automation framework at scale."
        )
        val json = """
            {"overall_score":95,"keyword_match":100,"skill_coverage":80,"seniority_alignment":80,"quantification":60,"format_safety":100,"remaining_gaps":[],"top_3_improvements":[]}
        """.trimIndent()
        val result = AtsScoringNode(llm = LlmCaller { json }).process(state)
        // 1 of 2 phrases literally present → keyword_match 50 (LLM claimed 100);
        // overall = 50*.30 + 80*.25 + 80*.20 + 60*.15 + 100*.10 = 70.
        assertEquals(50, result.atsScore!!.keywordMatch)
        assertEquals(70, result.atsScore!!.overallScore)
    }

    @Test
    @DisplayName("verifyKeywords matches case- and whitespace-insensitively")
    fun verifyKeywordsNormalises() {
        val node = AtsScoringNode()
        val jd = JdStructured(
            roleTitle = "Eng", seniority = "Sr",
            requiredSkills = listOf("Kotlin", "Rust"),
            atsExactPhrases = listOf("CI/CD Pipeline Ownership")
        )
        val v = node.verifyKeywords(jd, "Owned ci/cd   pipeline ownership for kotlin services")
        assertEquals(listOf("CI/CD Pipeline Ownership"), v.phrasesFound)
        assertEquals(listOf("Kotlin"), v.skillsFound)
        assertEquals(listOf("Rust"), v.skillsMissing)
    }
}
