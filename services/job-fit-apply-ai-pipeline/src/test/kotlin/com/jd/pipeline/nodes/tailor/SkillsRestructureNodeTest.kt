package com.jd.pipeline.nodes.tailor

import com.jd.pipeline.models.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        skills = CandidateSkills(
            primaryStack = listOf("Kotlin"),
            mobileAutomation = listOf("Appium"),
            ciCdPlatforms = listOf("GitHub Actions"),
            webApiAutomation = listOf("Playwright"),
            infrastructureObservability = listOf("K8s"),
            leadershipAbilities = listOf("Mentoring")
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
        assertTrue(prompt.contains("Primary stack"))
        assertTrue(prompt.contains("Mobile automation"))
        assertTrue(prompt.contains("CI/CD platforms"))
        assertTrue(prompt.contains("Web & API automation"))
        assertTrue(prompt.contains("Infrastructure & observability"))
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
