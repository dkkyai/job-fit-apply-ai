package com.jd.pipeline.nodes.tailor

import com.fasterxml.jackson.databind.ObjectMapper
import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.client.LlmClient
import com.jd.pipeline.config.Config
import java.nio.file.Files

/**
 * Tailor subgraph node 1/6: JD Extraction
 */
class JdExtractionNode(
    private val llm: LlmCaller = LlmClient.orchestrationClient(nodeKey = "jd_extraction")
) {
    private val mapper = ObjectMapper()

    fun process(state: TailorState): TailorState {
        // Skip if score_fit already produced JdStructured (combined call path)
        if (state.jdStructured != null) {
            println("[jd_extraction] Already extracted by score_fit — skipping LLM call")
            return state
        }

        if (state.jdText.isBlank()) {
            return state.copy(error = "jd_extraction: jdText is empty")
        }

        println("[jd_extraction] Extracting structure for: ${state.roleTitle} @ ${state.company}")

        return try {
            val prompt = "${loadSkillPrompt()}\n\n<job_description>\n${state.jdText}\n</job_description>"
            val response = llm.call(prompt)
            val cleaned = stripJsonFences(response)
            val parsed = mapper.readValue(cleaned, JdStructured::class.java)
            println("[jd_extraction] Required skills: ${parsed.requiredSkills.size}, ATS phrases: ${parsed.atsExactPhrases.size}")
            state.copy(jdStructured = parsed)
        } catch (e: Exception) {
            state.copy(error = "jd_extraction: ${e.message}")
        }
    }

    private fun loadSkillPrompt(): String = try {
        if (Files.exists(Config.JD_EXTRACTION_SKILL)) Files.readString(Config.JD_EXTRACTION_SKILL)
        else DEFAULT_PROMPT
    } catch (_: Exception) { DEFAULT_PROMPT }

    private fun stripJsonFences(text: String): String =
        text.replace(Regex("```(?:json)?"), "").trim()
            .let { if (it.endsWith("`")) it.dropLast(1).trim() else it }

    companion object {
        private val DEFAULT_PROMPT = """
            |You are a structured JD parser. Extract the following from the job description below.
            |Return ONLY valid JSON with no markdown fences or preamble:
            |{
            |  "role_title": "string",
            |  "seniority": "string (e.g. Staff, Senior, Principal, IC5)",
            |  "required_skills": ["string", ...],
            |  "preferred_skills": ["string", ...],
            |  "domain_keywords": ["string", ...],
            |  "ats_exact_phrases": ["string", ...],
            |  "company_value_signals": ["string", ...]
            |}
            |
            |Guidelines:
            |- required_skills: explicitly stated requirements
            |- preferred_skills: nice-to-haves or "plus" items
            |- domain_keywords: industry-specific terms (acronyms, platform names, methodologies)
            |- ats_exact_phrases: multi-word phrases to include verbatim in the resume for ATS matching
            |- company_value_signals: culture/values clues ("move fast", "data-driven", "customer obsessed")
            |Do not invent skills not present in the JD.
        """.trimMargin()
    }
}
