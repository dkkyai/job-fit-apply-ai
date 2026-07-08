package com.jd.pipeline.nodes.tailor

import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.models.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Non-LLM tests for [SkillsRestructureNode]:
 * validation, prompt construction, prerequisite checks, JSON fence stripping.
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

    private fun makeState(jd: JdStructured? = JdStructured("Role", "Sr", listOf("Kotlin")), profile: CandidateProfile? = makeProfile()) = TailorState(
        jdText = "jd",
        candidateProfile = profile,
        fitScore = 0f,
        strengths = emptyList(),
        gaps = emptyList(),
        company = "Acme",
        roleTitle = "Eng",
        trackId = 0,
        jdStructured = jd
    )

    @Test
    @DisplayName("process returns error when jdStructured is null")
    fun errorWhenJdStructuredNull() {
        val result = node.process(makeState(jd = null))
        assertTrue(result.error.contains("jdStructured is null"))
    }

    @Test
    @DisplayName("process returns error when candidateProfile is null")
    fun errorWhenProfileNull() {
        val result = node.process(makeState(profile = null))
        assertTrue(result.error.contains("candidateProfile is null"))
    }

    @Test
    @DisplayName("validateNotExampleText throws on placeholder strings")
    fun validateThrowsOnPlaceholders() {
        listOf("skill1", "skill2", "skill3", "skill4", "<MostRelevantCategory>", "<jd_matched_skill>", "<placeholder>")
            .forEach { tell ->
                val ex = assertThrows<IllegalStateException> {
                    validateNotExampleText("Some text with $tell inside")
                }
                assertTrue(ex.message!!.contains(tell, ignoreCase = true))
            }
    }

    @Test
    @DisplayName("validateNotExampleText passes on legitimate text")
    fun validatePassesOnLegitimateText() {
        validateNotExampleText("Kotlin, Java, Spring Boot, Kubernetes, Docker")
        // no exception
    }

    @Test
    @DisplayName("buildPrompt includes all candidate skill buckets")
    fun promptIncludesSkillBuckets() {
        val jd = JdStructured(roleTitle = "Staff", seniority = "Staff", requiredSkills = listOf("Kotlin"))
        val profile = makeProfile()
        val prompt = buildPrompt(jd, profile)
        assertTrue(prompt.contains("Primary Stack"))
        assertTrue(prompt.contains("Mobile Automation"))
        assertTrue(prompt.contains("CI/CD Platforms"))
        assertTrue(prompt.contains("Web & API Automation"))
        assertTrue(prompt.contains("Infrastructure & Observability"))
        assertTrue(prompt.contains("Leadership"))
    }

    @Test
    @DisplayName("buildPrompt includes JD required and preferred skills")
    fun promptIncludesJdSkills() {
        val jd = JdStructured(
            roleTitle = "Eng", seniority = "Sr",
            requiredSkills = listOf("Kotlin"), preferredSkills = listOf("Rust")
        )
        val prompt = buildPrompt(jd, makeProfile())
        assertTrue(prompt.contains("JD REQUIRED SKILLS: Kotlin"))
        assertTrue(prompt.contains("JD PREFERRED SKILLS: Rust"))
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

    // ── Mock LLM: process() happy path ────────────────────────────────────────

    @Test
    @DisplayName("valid LLM response populates restructuredSkills on the state")
    fun validLlmResponsePopulatesSkills() {
        val json = """
            {
              "restructured_text": "Kotlin | Java | Playwright | GitHub Actions",
              "removed_for_this_role": [],
              "jd_matched_skills": ["Kotlin", "Playwright"],
              "grouped_by_category": {
                "Languages": ["Kotlin", "Java"],
                "Automation": ["Playwright"]
              }
            }
        """.trimIndent()
        val mockNode = SkillsRestructureNode(llm = LlmCaller { json })
        val result = mockNode.process(makeState())
        assertNotNull(result.restructuredSkills)
        assertEquals("Kotlin | Java | Playwright | GitHub Actions", result.restructuredSkills!!.restructuredText)
        assertEquals(listOf("Kotlin", "Playwright"), result.restructuredSkills!!.jdMatchedSkills)
        assertEquals(2, result.restructuredSkills!!.groupedByCategory.size)
    }

    @Test
    @DisplayName("LLM returning placeholder text produces error state")
    fun placeholderTextProducesError() {
        val json = """
            {
              "restructured_text": "skill1 | skill2 | skill3",
              "removed_for_this_role": [],
              "jd_matched_skills": [],
              "grouped_by_category": {}
            }
        """.trimIndent()
        val mockNode = SkillsRestructureNode(llm = LlmCaller { json })
        val result = mockNode.process(makeState())
        assertTrue(result.error.isNotBlank(), "Expected error for placeholder text")
    }

    @Test
    @DisplayName("LLM exception produces error state")
    fun llmExceptionProducesError() {
        val mockNode = SkillsRestructureNode(llm = LlmCaller { throw RuntimeException("LLM timeout") })
        val result = mockNode.process(makeState())
        assertTrue(result.error.isNotBlank())
    }

    // ── reflection helpers to exercise internal methods ─────────────────────

    private fun validateNotExampleText(text: String) {
        val m = SkillsRestructureNode::class.java.getDeclaredMethod("validateNotExampleText", String::class.java)
        m.isAccessible = true
        try {
            m.invoke(node, text)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun buildPrompt(jd: JdStructured, profile: CandidateProfile): String {
        val m = SkillsRestructureNode::class.java.getDeclaredMethod("buildPrompt", JdStructured::class.java, CandidateProfile::class.java)
        m.isAccessible = true
        return m.invoke(node, jd, profile) as String
    }

    private fun invokeStripJsonFences(text: String): String {
        val m = SkillsRestructureNode::class.java.getDeclaredMethod("stripJsonFences", String::class.java)
        m.isAccessible = true
        return m.invoke(node, text) as String
    }
}
