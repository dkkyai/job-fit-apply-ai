package com.jd.pipeline.nodes.tailor

import com.fasterxml.jackson.databind.ObjectMapper
import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.client.LlmClient
import com.jd.pipeline.config.Config
import java.nio.file.Files

/**
 * Tailor subgraph node A (1/7): JD extraction.
 *
 * Parses the raw JD once into a structured [JdRequirements] set — must-have vs
 * nice-to-have terms in the JD's exact phrasing, literal-match tools, the JD's own skill
 * groupings, and seniority signals. Every downstream node consumes this object instead
 * of re-reading the raw JD.
 *
 * Always runs its own LLM call — score_fit's leaner extraction (JdStructured) serves
 * scoring only and is not reused here.
 */
class JdExtractionNode(
    private val llm: LlmCaller = LlmClient.orchestrationClient(nodeKey = "jd_extraction")
) {
    private val mapper = ObjectMapper()

    fun process(state: TailorState): TailorState {
        if (state.jdText.isBlank()) {
            return state.copy(error = "jd_extraction: jdText is empty")
        }

        println("[jd_extraction] Extracting requirement set for: ${state.roleTitle} @ ${state.company}")

        return try {
            val prompt = "${TailorRubric.prepend(loadSkillPrompt())}\n\n<job_description>\n${state.jdText}\n</job_description>"
            val response = llm.call(prompt)
            val parsed = mapper.readValue(stripJsonFences(response), JdRequirements::class.java)
            if (parsed.mustHave.isEmpty() && parsed.targetTitle.isBlank()) {
                return state.copy(error = "jd_extraction: LLM returned an empty requirement set")
            }
            println("[jd_extraction] must-have: ${parsed.mustHave.size}, nice-to-have: ${parsed.niceToHave.size}, exact-match terms: ${parsed.exactMatchTerms.size}")
            state.copy(jdRequirements = parsed)
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
            |Extract the job description below into a structured requirement set. Quote the JD's
            |EXACT phrasing for every term — downstream ATS matching is literal.
            |Return ONLY valid JSON with no markdown fences or preamble:
            |{
            |  "target_title": "the JD's title verbatim",
            |  "seniority_signals": ["seniority phrases quoted from the JD", ...],
            |  "must_have": ["hard requirement, JD's exact phrasing", ...],
            |  "nice_to_have": ["preferred/plus item, JD's exact phrasing", ...],
            |  "exact_match_terms": ["tools/certs/protocols parsers match literally", ...],
            |  "skill_groupings": [ {"label": "the JD's own grouping label", "items": ["...", ...]} ],
            |  "domain_keywords": ["industry terms", ...],
            |  "company_value_signals": ["culture clues", ...]
            |}
            |Do not invent terms not present in the JD. Empty arrays for absent fields.
        """.trimMargin()
    }
}
