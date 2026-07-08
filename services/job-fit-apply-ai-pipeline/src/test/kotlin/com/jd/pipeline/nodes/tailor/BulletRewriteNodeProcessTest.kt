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
        jdRequirements = JdRequirements(targetTitle = "Eng", mustHave = listOf("Kotlin")),
        gapAnalysis = GapAnalysis()
    )

    @Test
    @DisplayName("process returns error when jdRequirements is null")
    fun errorWhenJdRequirementsNull() {
        val result = BulletRewriteNode().process(baseState.copy(jdRequirements = null))
        assertTrue(result.error.contains("jdRequirements is null"))
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
    @DisplayName("prompt includes job-level rewrite requirements")
    fun buildPromptContent() {
        var captured = ""
        BulletRewriteNode(llm = LlmCaller { prompt ->
            captured = prompt
            "[]"
        }).process(baseState)
        assertTrue(captured.contains("TARGET TITLE"), "should include TARGET TITLE")
        assertTrue(captured.contains("MUST-HAVE TERMS"), "should include must-have terms")
        assertTrue(captured.contains("CANDIDATE ROLES"), "should include candidate roles")
    }

    @Test
    @DisplayName("mocked LLM rewrites bullets and populates career + projects + meta")
    fun mockedLlmRewritesBullets() {
        val json = """
            [
                {"role":"Eng","company":"Acme","start_date":"2020","bullets":[
                    {"original":"B1","category":"Impact","rewritten":"R1","must_have_hits":["Kotlin"],"quantified":true,"seniority_signal":false},
                    {"original":"B2","category":"Scale","rewritten":"R2","must_have_hits":[],"quantified":false,"seniority_signal":true}
                ]},
                {"role":"Maintainer","company":"OSS","start_date":"2022","bullets":[
                    {"original":"P1","category":"Ownership","rewritten":"P1R","must_have_hits":["Kotlin"],"quantified":false,"seniority_signal":true}
                ]}
            ]
        """.trimIndent()
        val mockLlm = LlmCaller { json }
        val result = BulletRewriteNode(llm = mockLlm).process(baseState)
        assertNotNull(result.tailoredCareerHistory)
        assertNotNull(result.tailoredProjects)
        assertNotNull(result.tailoredBullets)
        assertNotNull(result.bulletMeta)

        val careerBullets = result.tailoredCareerHistory!!.first().bullets
        assertEquals(2, careerBullets.size)
        assertEquals("R1", careerBullets[0].text)
        assertEquals("Impact", careerBullets[0].category)
        assertEquals("R2", careerBullets[1].text)
        assertEquals("P1R", result.tailoredProjects!!.first().bullets[0].text)

        assertEquals(3, result.tailoredBullets!!.size)
        val first = result.tailoredBullets!!.first()
        assertEquals("B1", first.original)
        assertEquals(listOf("Kotlin"), first.mustHaveHits)
        assertTrue(first.quantified)

        val engMeta = result.bulletMeta!![roleKey("Eng", "Acme", "2020")]
        assertNotNull(engMeta)
        assertEquals(2, engMeta!!.size)
        assertEquals(1, engMeta[0].mustHaveHits)
    }
}
