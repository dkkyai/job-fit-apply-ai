package com.jd.pipeline.nodes.tailor

import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.models.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Non-LLM tests for [SkillsRestructureNode]:
 * output validation, prompt construction, prerequisite checks, JSON fence stripping.
 */
@DisplayName("SkillsRestructureNodeTest")
class SkillsRestructureNodeTest {

    private val node = SkillsRestructureNode()

    private fun makeProfile() = CandidateProfile(
        identity = CandidateIdentity(
            name = "X", firstName = "X", lastName = "Y",
            email = "x@y.com", phone = "1", location = "Remote"
        ),
        background = CandidateBackground(
            targetTitle = "Eng", yearsExperience = 5,
            summary = "", education = emptyList(),
            careerHistory = emptyList(),
            coreStrengths = listOf("Leadership"),
            languages = emptyList(),
            domainExpertise = listOf("SaaS")
        ),
        skills = listOf(
            SkillGroup("Primary Stack", listOf("Kotlin")),
            SkillGroup("Mobile Automation", listOf("Appium")),
            SkillGroup("CI/CD Platforms", listOf("GitHub Actions")),
            SkillGroup("Web & API Automation", listOf("Playwright")),
            SkillGroup("Infrastructure & Observability", listOf("K8s")),
            SkillGroup("Leadership", listOf("Mentoring"))
        )
    )

    private fun makeState(
        jd: JdRequirements? = JdRequirements(
            targetTitle = "Staff Engineer",
            mustHave = listOf("Kotlin", "Appium"),
            niceToHave = listOf("Rust"),
            exactMatchTerms = listOf("GitHub Actions"),
            skillGroupings = listOf(JdSkillGroup("Languages", listOf("Kotlin", "Java")))
        ),
        gap: GapAnalysis? = GapAnalysis(
            supported = listOf(EvidencedTerm("Kotlin", "Primary Stack lists Kotlin")),
            unsupported = listOf("COBOL")
        ),
        profile: CandidateProfile? = makeProfile()
    ) = TailorState(
        jdText = "jd",
        candidateProfile = profile,
        fitScore = 0f,
        strengths = emptyList(),
        gaps = emptyList(),
        company = "Acme",
        roleTitle = "Eng",
        trackId = 0,
        jdRequirements = jd,
        gapAnalysis = gap
    )

    /** Valid new-schema LLM response (no legacy restructured_text field). */
    private fun validJson() = """
        {
          "grouped_by_category": {
            "Languages": ["Kotlin", "Java"],
            "Automation": ["Playwright"]
          },
          "jd_matched_skills": ["Kotlin", "Playwright"],
          "removed_for_this_role": ["Mentoring"]
        }
    """.trimIndent()

    // ── prerequisite guards ───────────────────────────────────────────────────

    @Test
    @DisplayName("process returns error when jdRequirements is null")
    fun errorWhenJdRequirementsNull() {
        val result = node.process(makeState(jd = null))
        assertTrue(result.error.contains("jdRequirements is null"))
    }

    @Test
    @DisplayName("process returns error when gapAnalysis is null")
    fun errorWhenGapAnalysisNull() {
        val result = node.process(makeState(gap = null))
        assertTrue(result.error.contains("gapAnalysis is null"))
    }

    @Test
    @DisplayName("process returns error when candidateProfile is null")
    fun errorWhenProfileNull() {
        val result = node.process(makeState(profile = null))
        assertTrue(result.error.contains("candidateProfile is null"))
    }

    // ── prompt construction (captured through the mock LLM) ──────────────────

    private fun capturePrompt(state: TailorState = makeState()): String {
        var captured = ""
        val mockNode = SkillsRestructureNode(llm = LlmCaller { prompt ->
            captured = prompt
            validJson()
        })
        mockNode.process(state)
        return captured
    }

    @Test
    @DisplayName("prompt lists every candidate skill group as '- label: items'")
    fun promptIncludesSkillBuckets() {
        val prompt = capturePrompt()
        assertTrue(prompt.contains("CANDIDATE SKILLS BY GROUP"))
        assertTrue(prompt.contains("- Primary Stack: Kotlin"))
        assertTrue(prompt.contains("- Mobile Automation: Appium"))
        assertTrue(prompt.contains("- CI/CD Platforms: GitHub Actions"))
        assertTrue(prompt.contains("- Web & API Automation: Playwright"))
        assertTrue(prompt.contains("- Infrastructure & Observability: K8s"))
        assertTrue(prompt.contains("- Leadership: Mentoring"))
    }

    @Test
    @DisplayName("prompt includes must-have terms, supported terms, and the JD's own skill groupings")
    fun promptIncludesJdSections() {
        val prompt = capturePrompt()
        assertTrue(prompt.contains("MUST-HAVE TERMS"))
        assertTrue(prompt.contains("SUPPORTED TERMS"))
        assertTrue(prompt.contains("THE JD'S OWN SKILL GROUPINGS"))
        assertTrue(prompt.contains("- Languages: Kotlin, Java"))
    }

    @Test
    @DisplayName("stripJsonFences removes markdown fences in various formats")
    fun stripJsonFencesFormats() {
        val tests = listOf(
            "```json\n{\"a\":1}\n```" to "{\"a\":1}",
            "```\n{\"a\":1}\n```"   to "{\"a\":1}",
            "```\n{\"a\":1}\n`"     to "{\"a\":1}",
            "{\"a\":1}"             to "{\"a\":1}"
        )
        tests.forEach { (input, expected) ->
            assertEquals(expected, invokeStripJsonFences(input))
        }
    }

    // ── Mock LLM: process() paths ─────────────────────────────────────────────

    @Test
    @DisplayName("valid LLM response populates restructuredSkills on the state")
    fun validLlmResponsePopulatesSkills() {
        val mockNode = SkillsRestructureNode(llm = LlmCaller { validJson() })
        val result = mockNode.process(makeState())
        assertNotNull(result.restructuredSkills)
        assertEquals(2, result.restructuredSkills!!.groupedByCategory.size)
        assertEquals(listOf("Kotlin", "Java"), result.restructuredSkills!!.groupedByCategory["Languages"])
        assertEquals(listOf("Kotlin", "Playwright"), result.restructuredSkills!!.jdMatchedSkills)
        assertEquals(listOf("Mentoring"), result.restructuredSkills!!.removedForThisRole)
    }

    @Test
    @DisplayName("LLM returning placeholder skills inside a group produces error state")
    fun placeholderTextProducesError() {
        val json = """
            {
              "grouped_by_category": { "Languages": ["skill1", "skill2"] },
              "jd_matched_skills": [],
              "removed_for_this_role": []
            }
        """.trimIndent()
        val mockNode = SkillsRestructureNode(llm = LlmCaller { json })
        val result = mockNode.process(makeState())
        assertTrue(result.error.contains("example/placeholder"), "Expected example/placeholder error, got: ${result.error}")
    }

    @Test
    @DisplayName("LLM returning empty grouped_by_category produces error state")
    fun emptyGroupsProduceError() {
        val json = """
            {
              "grouped_by_category": {},
              "jd_matched_skills": [],
              "removed_for_this_role": []
            }
        """.trimIndent()
        val mockNode = SkillsRestructureNode(llm = LlmCaller { json })
        val result = mockNode.process(makeState())
        assertTrue(result.error.contains("no skill groups"), "Expected no-skill-groups error, got: ${result.error}")
    }

    @Test
    @DisplayName("LLM exception produces error state")
    fun llmExceptionProducesError() {
        val mockNode = SkillsRestructureNode(llm = LlmCaller { throw RuntimeException("LLM timeout") })
        val result = mockNode.process(makeState())
        assertTrue(result.error.isNotBlank())
    }

    // ── reflection helper to exercise the private fence stripper ─────────────

    private fun invokeStripJsonFences(text: String): String {
        val m = SkillsRestructureNode::class.java.getDeclaredMethod("stripJsonFences", String::class.java)
        m.isAccessible = true
        return m.invoke(node, text) as String
    }
}
