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
    @DisplayName("mocked LLM rewrites bullets; hits/quantified are re-verified against the rewritten text")
    fun mockedLlmRewritesBullets() {
        // R1 genuinely contains "Kotlin" + a number; R2 claims nothing; P1R claims a Kotlin
        // hit the text does NOT contain (and a bogus quantified=false with no digits) — the
        // deterministic verification must correct the metadata, not echo the LLM's claims.
        val json = """
            [
                {"role":"Eng","company":"Acme","start_date":"2020","bullets":[
                    {"original":"B1","category":"Impact","rewritten":"Rebuilt Kotlin suites, cutting runtime 40%","must_have_hits":["Kotlin"],"quantified":true,"seniority_signal":false},
                    {"original":"B2","category":"Scale","rewritten":"Standardized flaky-test triage across teams","must_have_hits":[],"quantified":false,"seniority_signal":true}
                ]},
                {"role":"Maintainer","company":"OSS","start_date":"2022","bullets":[
                    {"original":"P1","category":"Ownership","rewritten":"Maintained an open-source test library","must_have_hits":["Kotlin"],"quantified":false,"seniority_signal":true}
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
        assertEquals("Rebuilt Kotlin suites, cutting runtime 40%", careerBullets[0].text)
        assertEquals("Impact", careerBullets[0].category)

        assertEquals(3, result.tailoredBullets!!.size)
        val first = result.tailoredBullets!!.first()
        assertEquals("B1", first.original)
        assertEquals(listOf("Kotlin"), first.mustHaveHits, "verified hit: 'Kotlin' is literally present")
        assertTrue(first.quantified, "verified: digits present")

        val engMeta = result.bulletMeta!![roleKey("Eng", "Acme", "2020")]
        assertNotNull(engMeta)
        assertEquals(2, engMeta!!.size)
        assertEquals(1, engMeta[0].mustHaveHits)

        // The bogus project claim is corrected: no literal "Kotlin" in the text → 0 hits.
        val projMeta = result.bulletMeta!![roleKey("Maintainer", "OSS", "2022")]!!
        assertEquals(0, projMeta[0].mustHaveHits, "LLM-claimed hit absent from text is discarded")
    }
}
