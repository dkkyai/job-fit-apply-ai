package com.jd.pipeline.nodes.tailor

import com.fasterxml.jackson.databind.ObjectMapper
import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.client.LlmClient
import com.jd.pipeline.config.Config
import java.nio.file.Files

/**
 * Tailor subgraph node 6/6: ATS Scoring
 */
class AtsScoringNode(
    private val llm: LlmCaller = LlmClient.orchestrationClient(nodeKey = "ats_scoring")
) {
    private val mapper = ObjectMapper()

    fun process(state: TailorState): TailorState {
        if (state.jdStructured == null) return state.copy(error = "ats_scoring: jdStructured is null")
        if (state.gapAnalysis == null) return state.copy(error = "ats_scoring: gapAnalysis is null")
        if (state.tailoredSummary == null) return state.copy(error = "ats_scoring: tailoredSummary is null")
        if (state.tailoredBullets == null) return state.copy(error = "ats_scoring: tailoredBullets is null")
        if (state.restructuredSkills == null) return state.copy(error = "ats_scoring: restructuredSkills is null")

        println("[ats_scoring] Scoring tailored resume for: ${state.roleTitle}")

        return try {
            val verification = verifyKeywords(state.jdStructured, assembleResumeText(state))
            val prompt = buildPrompt(state, verification)
            val response = llm.call(prompt)
            val cleaned = stripJsonFences(response)
            // calibrate(): keyword_match and the overall composite are recomputed
            // deterministically from the verified phrase presence.
            val parsed = calibrate(mapper.readValue(cleaned, AtsScore::class.java), verification)
            println("\u001B[92m[ats_scoring] ATS overall score = ${parsed.overallScore}\u001B[0m")
            state.copy(atsScore = parsed)
        } catch (e: Exception) {
            state.copy(error = "ats_scoring: ${e.message}")
        }
    }

    /** Everything the candidate-facing resume will contain, for exact phrase matching. */
    private fun assembleResumeText(state: TailorState): String = buildString {
        appendLine(state.tailoredSummary ?: "")
        state.tailoredBullets?.forEach { appendLine(it.rewritten) }
        appendLine(state.restructuredSkills?.restructuredText ?: "")
        appendLine(state.restructuredSkills?.jdMatchedSkills?.joinToString(", ") ?: "")
    }

    /**
     * Deterministic presence check: which JD ATS phrases and required skills literally
     * appear in the tailored text (case- and whitespace-insensitive). LLM keyword
     * estimates are unreliable; this grounds keyword_match in the actual output.
     */
    internal fun verifyKeywords(jd: JdStructured?, resumeText: String): KeywordVerification {
        fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()
        val haystack = norm(resumeText)
        fun split(items: List<String>) = items.distinct()
            .partition { it.isNotBlank() && haystack.contains(norm(it)) }
        val (phrasesFound, phrasesMissing) = split(jd?.atsExactPhrases ?: emptyList())
        val (skillsFound, skillsMissing) = split(jd?.requiredSkills ?: emptyList())
        return KeywordVerification(phrasesFound, phrasesMissing, skillsFound, skillsMissing)
    }

    /**
     * Replace the LLM's keyword_match with the deterministic percentage (when the JD
     * provided ATS phrases) and recompute the weighted composite so overall_score
     * reflects it. Falls back to the LLM's keyword_match when no phrases were extracted.
     */
    private fun calibrate(parsed: AtsScore, v: KeywordVerification): AtsScore {
        val totalPhrases = v.phrasesFound.size + v.phrasesMissing.size
        val keywordMatch = if (totalPhrases > 0) (v.phrasesFound.size * 100) / totalPhrases else parsed.keywordMatch
        val overall = (keywordMatch * 0.30 + parsed.skillCoverage * 0.25 + parsed.seniorityAlignment * 0.20 +
            parsed.quantification * 0.15 + parsed.formatSafety * 0.10).toInt()
        return parsed.copy(keywordMatch = keywordMatch, overallScore = overall)
    }

    internal data class KeywordVerification(
        val phrasesFound: List<String>,
        val phrasesMissing: List<String>,
        val skillsFound: List<String>,
        val skillsMissing: List<String>
    )

    private fun buildPrompt(state: TailorState, verification: KeywordVerification): String {
        val jd = state.jdStructured!!
        val gap = state.gapAnalysis!!
        val bullets = state.tailoredBullets!!
        val skills = state.restructuredSkills!!

        val skillPrompt = try {
            if (Files.exists(Config.ATS_SCORING_SKILL)) Files.readString(Config.ATS_SCORING_SKILL)
            else DEFAULT_PROMPT
        } catch (_: Exception) { DEFAULT_PROMPT }

        val allBullets = bullets.joinToString("\n") { "- ${it.rewritten}" }
        val v = verification

        return buildString {
            appendLine(skillPrompt.trim())
            appendLine()
            appendLine("TARGET ROLE: ${jd.roleTitle} (${jd.seniority}) at ${state.company}")
            appendLine("JD REQUIRED SKILLS: ${jd.requiredSkills.joinToString(", ")}")
            appendLine("JD ATS PHRASES: ${jd.atsExactPhrases.joinToString(", ")}")
            appendLine("KEYWORD COVERAGE (from gap analysis): ${gap.keywordCoverageScore}/100")
            appendLine("REMAINING GAPS: ${gap.topGaps.joinToString(", ")}")
            appendLine()
            appendLine("VERIFIED KEYWORD PRESENCE (computed deterministically from the full tailored text — treat as ground truth):")
            appendLine("- ATS phrases found (${v.phrasesFound.size}/${v.phrasesFound.size + v.phrasesMissing.size}): ${v.phrasesFound.joinToString(", ").ifBlank { "(none)" }}")
            appendLine("- ATS phrases MISSING: ${v.phrasesMissing.joinToString(", ").ifBlank { "(none)" }}")
            appendLine("- Required skills literally present (${v.skillsFound.size}/${v.skillsFound.size + v.skillsMissing.size}): ${v.skillsFound.joinToString(", ").ifBlank { "(none)" }}")
            appendLine("- Required skills not literally present (may still be covered by synonyms — judge): ${v.skillsMissing.joinToString(", ").ifBlank { "(none)" }}")
            appendLine()
            appendLine("TAILORED SUMMARY:")
            appendLine(state.tailoredSummary ?: "")
            appendLine()
            appendLine("TAILORED BULLETS (all):")
            appendLine(allBullets)
            appendLine()
            appendLine("RESTRUCTURED SKILLS:")
            appendLine(skills.restructuredText)
            append("JD-MATCHED SKILLS: ${skills.jdMatchedSkills.joinToString(", ")}")
        }
    }

    private fun stripJsonFences(text: String): String =
        text.replace(Regex("```(?:json)?"), "").trim()
            .let { if (it.endsWith("`")) it.dropLast(1).trim() else it }

    companion object {
        private val DEFAULT_PROMPT = """
            |You are an ATS expert. Score the tailored resume output against the job description.
            |Return ONLY valid JSON with no markdown fences or preamble:
            |{
            |  "overall_score": integer (0-100),
            |  "keyword_match": integer (0-100),
            |  "skill_coverage": integer (0-100),
            |  "seniority_alignment": integer (0-100),
            |  "quantification": integer (0-100),
            |  "format_safety": integer (0-100),
            |  "remaining_gaps": ["string", ...],
            |  "top_3_improvements": ["string", ...]
            |}
            |
            |Sub-score guidance:
            |- keyword_match: fraction of JD ATS phrases present in the tailored output
            |- skill_coverage: required skills covered vs. total required
            |- seniority_alignment: title/scope match to the stated level
            |- quantification: fraction of bullets that include a measurable impact
            |- format_safety: absence of tables, columns, graphics, non-ASCII (higher = safer)
            |- overall_score: weighted composite (keyword 30%, skills 25%, seniority 20%, quant 15%, format 10%)
        """.trimMargin()
    }
}
