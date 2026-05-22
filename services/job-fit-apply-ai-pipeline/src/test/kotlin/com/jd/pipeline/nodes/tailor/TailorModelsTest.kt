package com.jd.pipeline.nodes.tailor

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests Jackson deserialization of [TailorModels].
 * Pure data-layer tests — no LLM required.
 */
@DisplayName("TailorModelsTest")
class TailorModelsTest {

    private val mapper = ObjectMapper().registerKotlinModule()

    @Test
    @DisplayName("JdStructured deserializes from complete JSON with all fields")
    fun jdStructuredComplete() {
        val json = """
            {
              "role_title": "Staff Engineer",
              "seniority": "Staff",
              "required_skills": ["Kotlin", "Java"],
              "preferred_skills": ["Rust"],
              "domain_keywords": ["FinTech"],
              "ats_exact_phrases": ["distributed systems"],
              "company_value_signals": ["move fast"]
            }
        """.trimIndent()

        val parsed = mapper.readValue(json, JdStructured::class.java)
        assertEquals("Staff Engineer", parsed.roleTitle)
        assertEquals("Staff", parsed.seniority)
        assertEquals(listOf("Kotlin", "Java"), parsed.requiredSkills)
        assertEquals(listOf("Rust"), parsed.preferredSkills)
        assertEquals(listOf("FinTech"), parsed.domainKeywords)
        assertEquals(listOf("distributed systems"), parsed.atsExactPhrases)
        assertEquals(listOf("move fast"), parsed.companyValueSignals)
    }

    @Test
    @DisplayName("SkillGap deserializes with valid action values")
    fun skillGapActions() {
        val actions = listOf("highlight", "add_if_honest", "omit")
        actions.forEach { action ->
            val json = """{"skill":"Kotlin","in_jd":true,"on_resume":true,"action":"$action"}"""
            val parsed = mapper.readValue(json, SkillGap::class.java)
            assertEquals(action, parsed.action, "action '$action' should round-trip")
        }
    }

    @Test
    @DisplayName("GapAnalysis deserializes nested skills_table array")
    fun gapAnalysisNested() {
        val json = """
            {
              "skills_table": [
                {"skill":"Kotlin","in_jd":true,"on_resume":true,"action":"highlight"}
              ],
              "keyword_coverage_score": 75,
              "top_gaps": ["Rust"],
              "top_strengths": ["Kotlin"],
              "bullets_to_promote": ["Built microservices"]
            }
        """.trimIndent()

        val parsed = mapper.readValue(json, GapAnalysis::class.java)
        assertEquals(1, parsed.skillsTable.size)
        assertEquals("Kotlin", parsed.skillsTable.first().skill)
        assertEquals(75, parsed.keywordCoverageScore)
        assertEquals(listOf("Rust"), parsed.topGaps)
        assertEquals(listOf("Built microservices"), parsed.bulletsToPromote)
    }

    @Test
    @DisplayName("TailoredBullet deserializes with jdAlignmentScore")
    fun tailoredBullet() {
        val json = """{"original":"foo","rewritten":"bar","jd_alignment_score":88}"""
        val parsed = mapper.readValue(json, TailoredBullet::class.java)
        assertEquals("foo", parsed.original)
        assertEquals("bar", parsed.rewritten)
        assertEquals(88, parsed.jdAlignmentScore)
    }

    @Test
    @DisplayName("RestructuredSkills deserializes with groupedByCategory Map")
    fun restructuredSkillsWithMap() {
        val json = """
            {
              "restructured_text": "Languages: Kotlin, Java",
              "removed_for_this_role": ["PHP"],
              "jd_matched_skills": ["Kotlin"],
              "grouped_by_category": {
                "Languages": ["Kotlin", "Java"],
                "Frameworks": ["Spring"]
              }
            }
        """.trimIndent()

        val parsed = mapper.readValue(json, RestructuredSkills::class.java)
        assertEquals("Languages: Kotlin, Java", parsed.restructuredText)
        assertEquals(listOf("PHP"), parsed.removedForThisRole)
        assertEquals(listOf("Kotlin"), parsed.jdMatchedSkills)
        assertEquals(listOf("Kotlin", "Java"), parsed.groupedByCategory["Languages"])
        assertEquals(listOf("Spring"), parsed.groupedByCategory["Frameworks"])
    }

    @Test
    @DisplayName("AtsScore deserializes with all sub-scores")
    fun atsScoreComplete() {
        val json = """
            {
              "overall_score": 82,
              "keyword_match": 90,
              "skill_coverage": 80,
              "seniority_alignment": 75,
              "quantification": 85,
              "format_safety": 95,
              "remaining_gaps": ["Rust"],
              "top_3_improvements": ["Add metrics"]
            }
        """.trimIndent()

        val parsed = mapper.readValue(json, AtsScore::class.java)
        assertEquals(82, parsed.overallScore)
        assertEquals(90, parsed.keywordMatch)
        assertEquals(80, parsed.skillCoverage)
        assertEquals(75, parsed.seniorityAlignment)
        assertEquals(85, parsed.quantification)
        assertEquals(95, parsed.formatSafety)
        assertEquals(listOf("Rust"), parsed.remainingGaps)
        assertEquals(listOf("Add metrics"), parsed.top3Improvements)
    }

    @Test
    @DisplayName("All models ignore unknown JSON properties")
    fun ignoreUnknownProperties() {
        val models = listOf(
            JdStructured::class.java to """{"role_title":"X","extra_field":123}""",
            SkillGap::class.java to """{"skill":"X","unknown":true}""",
            GapAnalysis::class.java to """{"keyword_coverage_score":50,"unexpected":"val"}""",
            TailoredBullet::class.java to """{"original":"X","bonus":42}""",
            RestructuredSkills::class.java to """{"restructured_text":"X","foo":"bar"}""",
            AtsScore::class.java to """{"overall_score":50,"baz":[]}"""
        )

        models.forEach { (clazz, json) ->
            val parsed = mapper.readValue(json, clazz)
            assertTrue(parsed != null, "$clazz should deserialize despite unknown fields")
        }
    }

    @Test
    @DisplayName("Default values populate when fields are absent from JSON")
    fun defaultValues() {
        val jd = mapper.readValue("{}", JdStructured::class.java)
        assertEquals("", jd.roleTitle)
        assertTrue(jd.requiredSkills.isEmpty())

        val gap = mapper.readValue("{}", SkillGap::class.java)
        assertEquals("", gap.skill)
        assertEquals(false, gap.inJd)

        val analysis = mapper.readValue("{}", GapAnalysis::class.java)
        assertTrue(analysis.skillsTable.isEmpty())
        assertEquals(0, analysis.keywordCoverageScore)

        val bullet = mapper.readValue("{}", TailoredBullet::class.java)
        assertEquals("", bullet.original)
        assertEquals(0, bullet.jdAlignmentScore)

        val skills = mapper.readValue("{}", RestructuredSkills::class.java)
        assertEquals("", skills.restructuredText)
        assertTrue(skills.groupedByCategory.isEmpty())

        val score = mapper.readValue("{}", AtsScore::class.java)
        assertEquals(0, score.overallScore)
        assertTrue(score.remainingGaps.isEmpty())
    }
}
