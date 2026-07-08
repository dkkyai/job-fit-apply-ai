package com.jd.pipeline.nodes.tailor

import com.jd.pipeline.client.LlmCaller
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

    private val baseState = TailorState(
        jdText = "We need a Kotlin engineer...",
        candidateProfile = null,
        fitScore = 80f,
        strengths = emptyList(), gaps = emptyList(),
        company = "Acme", roleTitle = "Engineer", trackId = 1
    )

    private fun fullJson(targetTitle: String, mustHave: List<String>): String {
        val must = mustHave.joinToString(", ") { "\"$it\"" }
        return """{"target_title":"$targetTitle","seniority_signals":[],"must_have":[$must],"nice_to_have":[],"exact_match_terms":[],"skill_groupings":[],"domain_keywords":[],"company_value_signals":[]}"""
    }

    @Test
    @DisplayName("returns error when jdText is blank")
    fun errorWhenJdTextBlank() {
        val state = baseState.copy(jdText = "")
        val result = JdExtractionNode().process(state)
        assertTrue(result.error.contains("jdText is empty"))
    }

    @Test
    @DisplayName("parses LLM response into jdRequirements")
    fun parsesLlmResponse() {
        val json = fullJson("Staff SDET", listOf("Kotlin", "Espresso"))
        val mockLlm = LlmCaller { json }
        val result = JdExtractionNode(llm = mockLlm).process(baseState)
        assertEquals("", result.error, "expected no error, got: ${result.error}")
        assertNotNull(result.jdRequirements, "expected jdRequirements to be non-null")
        assertEquals("Staff SDET", result.jdRequirements!!.targetTitle)
        assertEquals(listOf("Kotlin", "Espresso"), result.jdRequirements!!.mustHave)
    }

    @Test
    @DisplayName("strips markdown fences before parsing")
    fun stripsMarkdownFences() {
        val json = """
            ```json
            ${fullJson("Junior Engineer", listOf("Java"))}
            ```
        """.trimIndent()
        val mockLlm = LlmCaller { json }
        val result = JdExtractionNode(llm = mockLlm).process(baseState)
        assertEquals("", result.error, "expected no error, got: ${result.error}")
        assertNotNull(result.jdRequirements, "expected jdRequirements to be non-null")
        assertEquals("Junior Engineer", result.jdRequirements!!.targetTitle)
    }

    @Test
    @DisplayName("returns error when the LLM returns an empty requirement set")
    fun errorWhenEmptyRequirementSet() {
        val json = fullJson(targetTitle = "", mustHave = emptyList())
        val mockLlm = LlmCaller { json }
        val result = JdExtractionNode(llm = mockLlm).process(baseState)
        assertTrue(result.error.contains("empty requirement set"))
    }
}
