package com.jd.pipeline.nodes.tailor

import com.fasterxml.jackson.databind.ObjectMapper
import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.client.LlmClient
import com.jd.pipeline.config.Config
import com.jd.pipeline.utils.CandidateProfileRenderer
import java.nio.file.Files

/**
 * Tailor subgraph node 2/6: Gap Analysis
 */
class GapAnalysisNode(
    private val llm: LlmCaller = LlmClient.orchestrationClient(nodeKey = "gap_analysis")
) {
    private val mapper = ObjectMapper()

    fun process(state: TailorState): TailorState {
        val jd = state.jdStructured
            ?: return state.copy(error = "gap_analysis: jdStructured is null")
        val profile = state.candidateProfile
            ?: return state.copy(error = "gap_analysis: candidateProfile is null")

        println("[gap_analysis] Analysing skills gap for: ${state.roleTitle}")

        return try {
            val prompt = buildPrompt(jd, CandidateProfileRenderer.renderForTailoring(profile))
            val response = llm.call(prompt)
            val cleaned = stripJsonFences(response)
            val parsed = mapper.readValue(cleaned, GapAnalysis::class.java)
            println("[gap_analysis] Coverage score: ${parsed.keywordCoverageScore}, gaps: ${parsed.topGaps.size}")
            state.copy(gapAnalysis = parsed)
        } catch (e: Exception) {
            state.copy(error = "gap_analysis: ${e.message}")
        }
    }

    private fun buildPrompt(jd: JdStructured, profileMarkdown: String): String {
        val skillPrompt = try {
            if (Files.exists(Config.GAP_ANALYSIS_SKILL)) Files.readString(Config.GAP_ANALYSIS_SKILL)
            else DEFAULT_PROMPT
        } catch (_: Exception) { DEFAULT_PROMPT }

        return """
            $skillPrompt

            JD REQUIRED SKILLS: ${jd.requiredSkills.joinToString(", ")}
            JD PREFERRED SKILLS: ${jd.preferredSkills.joinToString(", ")}
            JD DOMAIN KEYWORDS: ${jd.domainKeywords.joinToString(", ")}
            JD ATS PHRASES: ${jd.atsExactPhrases.joinToString(", ")}

            CANDIDATE PROFILE (structured; use this for gap analysis):
            $profileMarkdown
        """.trimIndent()
    }

    private fun stripJsonFences(text: String): String =
        text.replace(Regex("```(?:json)?"), "").trim()
            .let { if (it.endsWith("`")) it.dropLast(1).trim() else it }

    companion object {
        private val DEFAULT_PROMPT = """
            |You are a resume gap analyser. Compare the JD requirements with the candidate's resume.
            |Return ONLY valid JSON with no markdown fences or preamble:
            |{
            |  "skills_table": [
            |    {"skill": "string", "in_jd": boolean, "on_resume": boolean, "action": "highlight|add_if_honest|omit"}
            |  ],
            |  "keyword_coverage_score": integer (0-100),
            |  "top_gaps": ["string", ...],
            |  "top_strengths": ["string", ...],
            |  "bullets_to_promote": ["string", ...]
            |}
            |
            |Guidelines:
            |- action="highlight" when the skill is in the JD AND on the resume (make it prominent)
            |- action="add_if_honest" when the skill is in the JD and the resume shows adjacent/related work
            |- action="omit" when the skill is in the JD but not on the resume at all
            |- bullets_to_promote: copy the exact resume bullet text that already aligns well with the JD
            |- Do not fabricate skills the candidate does not have.
        """.trimMargin()
    }
}
