package com.jd.pipeline.nodes.tailor

import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.models.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * LLM-mocked tests for [BulletRewriteNode.process].
 */
@DisplayName("BulletRewriteNodeProcessTest")
class BulletRewriteNodeProcessTest {

    private val baseProfile = CandidateProfile(
        identity = CandidateIdentity(name = "X", firstName = "X", lastName = "Y", email = "e", phone = "p", location = "R"),
        background = CandidateBackground(
            targetTitle = "Eng", yearsExperience = 5,
            summary = "", education = emptyList(),
            careerHistory = listOf(
                CareerEntry("Eng", "Acme", "SF", "2020", null, listOf(Bullet("", "B1"), Bullet("", "B2")))
            ),
            coreStrengths = emptyList(), languages = emptyList(), domainExpertise = emptyList()
        ),
        skills = emptyList(),
        projects = listOf(CareerEntry("Maintainer", "OSS", "", "2022", null, listOf(Bullet("", "P1"))))
    )

    private val baseState = TailorState(
        jdText = "jd",
        candidateProfile = baseProfile,
        fitScore = 80f,
        strengths = emptyList(), gaps = emptyList(),
        company = "Acme", roleTitle = "Eng", trackId = 1,
        jdStructured = JdStructured("Eng", "Sr", listOf("Kotlin")),
        gapAnalysis = GapAnalysis()
    )

    @Test
    @DisplayName("process returns error when jdStructured is null")
    fun errorWhenJdStructuredNull() {
        val result = BulletRewriteNode().process(baseState.copy(jdStructured = null))
        assertTrue(result.error.contains("jdStructured is null"))
    }

    @Test
    @DisplayName("process returns error when gapAnalysis is null")
    fun errorWhenGapAnalysisNull() {
        val result = BulletRewriteNode().process(baseState.copy(gapAnalysis = null))
        assertTrue(result.error.contains("gapAnalysis is null"))
    }

    @Test
    @DisplayName("process returns error when candidateProfile is null")
    fun errorWhenProfileNull() {
        val result = BulletRewriteNode().process(baseState.copy(candidateProfile = null))
        assertTrue(result.error.contains("candidateProfile is null"))
    }

    @Test
    @DisplayName("buildPrompt includes job-level rewrite requirements")
    fun buildPromptContent() {
        val node = BulletRewriteNode()
        val m = BulletRewriteNode::class.java.getDeclaredMethod(
            "buildPrompt", JdStructured::class.java, GapAnalysis::class.java, TailorState::class.java, CandidateProfile::class.java
        )
        m.isAccessible = true
        val prompt = m.invoke(node, baseState.jdStructured, baseState.gapAnalysis, baseState, baseProfile) as String
        assertTrue(prompt.contains("TARGET ROLE"), "should include TARGET ROLE")
        assertTrue(prompt.contains("CANDIDATE ROLES"), "should include candidate roles")
    }

    @Test
    @DisplayName("mocked LLM rewrites bullets and populates career + projects")
    fun mockedLlmRewritesBullets() {
        val json = """
            [
                {"role":"Eng","company":"Acme","start_date":"2020","bullets":[
                    {"original":"B1","rewritten":"R1","jd_alignment_score":90},
                    {"original":"B2","rewritten":"R2","jd_alignment_score":80}
                ]},
                {"role":"Maintainer","company":"OSS","start_date":"2022","bullets":[
                    {"original":"P1","rewritten":"P1R","jd_alignment_score":85}
                ]}
            ]
        """.trimIndent()
        val mockLlm = LlmCaller { json }
        val result = BulletRewriteNode(llm = mockLlm).process(baseState)
        assertNotNull(result.tailoredCareerHistory)
        assertNotNull(result.tailoredProjects)
        assertNotNull(result.tailoredBullets)
        assertEquals(2, result.tailoredCareerHistory!!.first().bullets.size)
        assertEquals("R1", result.tailoredCareerHistory!!.first().bullets[0].text)
        assertEquals("P1R", result.tailoredProjects!!.first().bullets[0].text)
        assertEquals(3, result.tailoredBullets!!.size)
    }
}
