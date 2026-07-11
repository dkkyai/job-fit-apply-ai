package com.jd.pipeline.nodes.tailor

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests Jackson deserialization of the tailor data contracts plus the small pure
 * helpers ([BulletMeta.score], [roleKey], [AtsReport.needsRefinement]). No LLM required.
 */
@DisplayName("TailorModelsTest")
class TailorModelsTest {

    private val mapper = ObjectMapper().registerKotlinModule()

    // ── JdRequirements ────────────────────────────────────────────────────────

    @Test
    @DisplayName("JdRequirements deserializes from complete JSON with all fields")
    fun jdRequirementsComplete() {
        val json = """
            {
              "target_title": "Staff Software Development Engineer in Test",
              "seniority_signals": ["set standards", "mentor senior engineers"],
              "must_have": ["Kotlin", "cross-functional collaboration"],
              "nice_to_have": ["Kotlin Multiplatform (KMP)"],
              "exact_match_terms": ["Espresso", "GitHub Actions"],
              "skill_groupings": [ {"label": "Testing", "items": ["Espresso", "XCUITest"]} ],
              "domain_keywords": ["device farms"],
              "company_value_signals": ["move fast"]
            }
        """.trimIndent()

        val parsed = mapper.readValue(json, JdRequirements::class.java)
        assertEquals("Staff Software Development Engineer in Test", parsed.targetTitle)
        assertEquals(listOf("set standards", "mentor senior engineers"), parsed.senioritySignals)
        assertEquals(listOf("Kotlin", "cross-functional collaboration"), parsed.mustHave)
        assertEquals(listOf("Kotlin Multiplatform (KMP)"), parsed.niceToHave)
        assertEquals(listOf("Espresso", "GitHub Actions"), parsed.exactMatchTerms)
        assertEquals("Testing", parsed.skillGroupings[0].label)
        assertEquals(listOf("Espresso", "XCUITest"), parsed.skillGroupings[0].items)
        assertEquals(listOf("device farms"), parsed.domainKeywords)
        assertEquals(listOf("move fast"), parsed.companyValueSignals)
    }

    @Test
    @DisplayName("JdRequirements tolerates missing fields and unknown extras")
    fun jdRequirementsSparse() {
        val parsed = mapper.readValue(
            """{"target_title": "SDET", "must_have": ["Kotlin"], "hallucinated_extra": 42}""",
            JdRequirements::class.java
        )
        assertEquals("SDET", parsed.targetTitle)
        assertEquals(listOf("Kotlin"), parsed.mustHave)
        assertTrue(parsed.niceToHave.isEmpty())
        assertTrue(parsed.skillGroupings.isEmpty())
    }

    // ── GapAnalysis ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("GapAnalysis deserializes the supported/unsupported/missing partition")
    fun gapAnalysisComplete() {
        val json = """
            {
              "supported": [ {"term": "Kotlin", "evidence": "rewrote entire Android framework in Kotlin"} ],
              "unsupported": ["Pact", "gRPC"],
              "missing_but_supported": [ {"term": "Firebase Test Lab", "evidence": "integrated with Firebase"} ]
            }
        """.trimIndent()

        val parsed = mapper.readValue(json, GapAnalysis::class.java)
        assertEquals("Kotlin", parsed.supported[0].term)
        assertEquals("rewrote entire Android framework in Kotlin", parsed.supported[0].evidence)
        assertEquals(listOf("Pact", "gRPC"), parsed.unsupported)
        assertEquals("Firebase Test Lab", parsed.missingButSupported[0].term)
    }

    // ── RestructuredSkills ────────────────────────────────────────────────────

    @Test
    @DisplayName("RestructuredSkills preserves category order and parses all fields")
    fun restructuredSkillsOrderPreserved() {
        val json = """
            {
              "grouped_by_category": {
                "Testing & Automation": ["Espresso", "XCUITest"],
                "CI/CD": ["GitHub Actions"],
                "Languages": ["Kotlin"]
              },
              "jd_matched_skills": ["Espresso", "Kotlin"],
              "removed_for_this_role": ["SAP systems"]
            }
        """.trimIndent()

        val parsed = mapper.readValue(json, RestructuredSkills::class.java)
        assertEquals(listOf("Testing & Automation", "CI/CD", "Languages"), parsed.groupedByCategory.keys.toList())
        assertEquals(listOf("Espresso", "XCUITest"), parsed.groupedByCategory["Testing & Automation"])
        assertEquals(listOf("Espresso", "Kotlin"), parsed.jdMatchedSkills)
        assertEquals(listOf("SAP systems"), parsed.removedForThisRole)
    }

    // ── AtsLlmScores ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("AtsLlmScores deserializes sub-scores and improvements")
    fun atsLlmScores() {
        val parsed = mapper.readValue(
            """{"seniority_alignment": 85, "quantification": 70, "format_safety": 95, "top_improvements": ["add Pact"]}""",
            AtsLlmScores::class.java
        )
        assertEquals(85, parsed.seniorityAlignment)
        assertEquals(70, parsed.quantification)
        assertEquals(95, parsed.formatSafety)
        assertEquals(listOf("add Pact"), parsed.topImprovements)
    }

    // ── pure helpers ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("BulletMeta.score ranks relevance over quantification over seniority")
    fun bulletMetaScore() {
        val oneHit = BulletMeta(mustHaveHits = 1, quantified = false, senioritySignal = false)
        val quantifiedAndSenior = BulletMeta(mustHaveHits = 0, quantified = true, senioritySignal = true)
        assertTrue(oneHit.score > quantifiedAndSenior.score, "one must-have hit outranks quantified+seniority")
        assertEquals(4 + 2 + 1, BulletMeta(1, quantified = true, senioritySignal = true).score)
        assertEquals(0, BulletMeta().score)
    }

    @Test
    @DisplayName("roleKey normalises case and whitespace")
    fun roleKeyNormalises() {
        assertEquals(roleKey("Staff SDET", "Acme Corp", "2024-09"), roleKey(" staff sdet ", " ACME CORP ", " 2024-09 "))
    }

    @Test
    @DisplayName("AtsReport.needsRefinement fires on leaks, doubled words, or missing terms — not on score")
    fun needsRefinement() {
        assertFalse(AtsReport(mustHaveCoveragePct = 10, overallScore = 5).needsRefinement, "low score alone is not actionable")
        assertTrue(AtsReport(missingTerms = listOf("Kotlin")).needsRefinement)
        assertTrue(AtsReport(leakedUnsupportedTerms = listOf("Pact")).needsRefinement)
        assertTrue(AtsReport(doubledWords = listOf("reducing")).needsRefinement)
    }
}
