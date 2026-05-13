package com.jd.pipeline.nodes.tailor

import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.models.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * LLM-mocked tests for [JdExtractionNode].
 */
@DisplayName("JdExtractionNodeTest")
class JdExtractionNodeTest {

    private val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        .registerModule(com.fasterxml.jackson.module.kotlin.KotlinModule.Builder().build())

    private val baseState = TailorState(
        jdText = "We need a Kotlin engineer...",
        candidateProfile = null,
        fitScore = 80f,
        strengths = emptyList(), gaps = emptyList(),
        company = "Acme", roleTitle = "Engineer", trackId = 1
    )

    private fun fullJson(roleTitle: String, seniority: String, requiredSkills: List<String>): String {
        val skills = requiredSkills.joinToString(", ") { "\"$it\"" }
        return """{"role_title":"$roleTitle","seniority":"$seniority","required_skills":[$skills],"preferred_skills":[],"domain_keywords":[],"ats_exact_phrases":[],"company_value_signals":[]}"""
    }

    @Test
    @DisplayName("skips LLM call when jdStructured is already present")
    fun skipsWhenAlreadyExtracted() {
        val existing = JdStructured(roleTitle = "Sr Eng", seniority = "Sr", requiredSkills = listOf("Kotlin"))
        val state = baseState.copy(jdStructured = existing)
        val result = JdExtractionNode().process(state)
        assertEquals(existing, result.jdStructured)
        assertEquals("", result.error)
    }

    @Test
    @DisplayName("returns error when jdText is blank")
    fun errorWhenJdTextBlank() {
        val state = baseState.copy(jdText = "")
        val result = JdExtractionNode().process(state)
        assertTrue(result.error.contains("jdText is empty"))
    }

    @Test
    @DisplayName("strips JSON fences and parses LLM response")
    fun stripsJsonFencesAndParses() {
        val json = fullJson("Staff", "Staff", listOf("Kotlin"))
        val mockLlm = LlmCaller { json }
        val result = JdExtractionNode(llm = mockLlm).process(baseState)
        assertEquals("", result.error, "expected no error, got: ${result.error}")
        assertNotNull(result.jdStructured, "expected jdStructured to be non-null")
        assertEquals("Staff", result.jdStructured!!.roleTitle)
        assertEquals("Staff", result.jdStructured!!.seniority)
        assertEquals(listOf("Kotlin"), result.jdStructured!!.requiredSkills)
    }

    @Test
    @DisplayName("strips markdown fences before parsing")
    fun stripsMarkdownFences() {
        val json = """
            ```json
            ${fullJson("Jr", "Junior", listOf("Java"))}
            ```
        """.trimIndent()
        val mockLlm = LlmCaller { json }
        val result = JdExtractionNode(llm = mockLlm).process(baseState)
        assertEquals("", result.error, "expected no error, got: ${result.error}")
        assertNotNull(result.jdStructured, "expected jdStructured to be non-null")
        assertEquals("Jr", result.jdStructured!!.roleTitle)
    }
}
