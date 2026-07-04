package com.jd.pipeline.nodes.tailor

import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.client.LlmClient
import com.jd.pipeline.config.Config
import com.jd.pipeline.utils.CandidateProfileRenderer
import java.nio.file.Files

/**
 * Tailor subgraph node 3/6: Summary Rewrite
 */
class SummaryRewriteNode(
    private val llm: LlmCaller = LlmClient.reasoningClient(nodeKey = "summary_rewrite")
) {

    fun process(state: TailorState): TailorState {
        val jd = state.jdStructured ?: return state.copy(error = "summary_rewrite: jdStructured is null")
        val gap = state.gapAnalysis ?: return state.copy(error = "summary_rewrite: gapAnalysis is null")
        val profile = state.candidateProfile
            ?: return state.copy(error = "summary_rewrite: candidateProfile is null")

        println("[summary_rewrite] Rewriting summary for: ${state.roleTitle} @ ${state.company}")

        return try {
            val prompt = buildPrompt(jd, gap, state, CandidateProfileRenderer.renderForTailoring(profile))
            var summary = llm.call(prompt).trim()

            // Anti-parrot guard: local models at low temperature tend to echo the base
            // summary despite instructions. One retry with the rejected draft as feedback.
            val baseSummary = profile.background.summary
            if (baseSummary.isNotBlank() && shingleOverlap(baseSummary, summary) > SIMILARITY_RETRY_THRESHOLD) {
                println("[summary_rewrite] WARN: draft too similar to base summary — retrying with feedback")
                val retryPrompt = prompt + "\n\nREJECTED DRAFT (too close to CURRENT SUMMARY — write different sentences " +
                    "that lead with this JD's role framing and required skills):\n" + summary
                val retry = llm.call(retryPrompt).trim()
                if (retry.isNotBlank()) summary = retry
                if (shingleOverlap(baseSummary, summary) > SIMILARITY_RETRY_THRESHOLD) {
                    println("[summary_rewrite] WARN: retry still similar to base summary — keeping it anyway")
                }
            }

            println("[summary_rewrite] Summary length: ${summary.length} chars")
            state.copy(tailoredSummary = summary)
        } catch (e: Exception) {
            state.copy(error = "summary_rewrite: ${e.message}")
        }
    }

    private fun buildPrompt(jd: JdStructured, gap: GapAnalysis, state: TailorState, profileMarkdown: String): String {
        val skillPrompt = try {
            if (Files.exists(Config.SUMMARY_REWRITE_SKILL)) Files.readString(Config.SUMMARY_REWRITE_SKILL)
            else DEFAULT_PROMPT
        } catch (_: Exception) { DEFAULT_PROMPT }

        val currentSummary = state.candidateProfile?.background?.summary?.takeIf { it.isNotBlank() }
            ?: "(no prior summary on the profile)"

        val bulletsToPromote = gap.bulletsToPromote.take(3)
            .joinToString("\n") { "- $it" }
            .ifBlank { "(none identified)" }

        return buildString {
            appendLine(skillPrompt.trim())
            appendLine()
            appendLine("TARGET ROLE: ${jd.roleTitle} (${jd.seniority}) at ${state.company}")
            appendLine("REQUIRED SKILLS: ${jd.requiredSkills.take(10).joinToString(", ")}")
            appendLine("ATS PHRASES TO INCLUDE: ${jd.atsExactPhrases.take(8).joinToString(", ")}")
            appendLine("COMPANY VALUE SIGNALS: ${jd.companyValueSignals.joinToString(", ")}")
            appendLine("TOP STRENGTHS (from gap analysis): ${gap.topStrengths.take(5).joinToString(", ")}")
            appendLine("TOP GAPS (do NOT claim these): ${gap.topGaps.take(5).joinToString(", ").ifBlank { "(none)" }}")
            appendLine()
            appendLine("BULLETS TO PROMOTE (source the proof sentence from these):")
            appendLine(bulletsToPromote)
            appendLine()
            appendLine("CURRENT SUMMARY (fact source only — do NOT reuse its sentences):")
            appendLine(currentSummary)
            state.atsScore?.let { score ->
                appendLine()
                appendLine("PREVIOUS ATS FEEDBACK (revision pass — address these where truthful):")
                appendLine("- Previous overall score: ${score.overallScore}/100")
                appendLine("- Remaining gaps: ${score.remainingGaps.joinToString("; ").ifBlank { "(none)" }}")
                appendLine("- Improvements: ${score.top3Improvements.joinToString("; ").ifBlank { "(none)" }}")
            }
            appendLine()
            appendLine("CANDIDATE PROFILE (structured; do not fabricate anything not here):")
            append(profileMarkdown)
        }
    }

    companion object {
        /** Word-trigram containment above which a draft counts as parroting the base summary. */
        internal const val SIMILARITY_RETRY_THRESHOLD = 0.5

        /**
         * Containment overlap of word trigrams: |shingles(a) ∩ shingles(b)| / min size.
         * ~1.0 when one text mostly reuses the other's sentences; near 0 for a fresh rewrite.
         */
        internal fun shingleOverlap(a: String, b: String): Double {
            fun shingles(s: String): Set<String> {
                val words = s.lowercase().replace(Regex("[^a-z0-9\\s]"), " ")
                    .split(Regex("\\s+")).filter { it.isNotBlank() }
                if (words.size < 3) return words.toSet()
                return (0..words.size - 3).map { i -> words.subList(i, i + 3).joinToString(" ") }.toSet()
            }
            val sa = shingles(a)
            val sb = shingles(b)
            if (sa.isEmpty() || sb.isEmpty()) return 0.0
            return sa.intersect(sb).size.toDouble() / minOf(sa.size, sb.size)
        }

        private val DEFAULT_PROMPT = """
            |You are a professional resume writer. Rewrite the candidate's summary section to align with
            |the target role. Write in first-person-implicit (no "I") professional tone.
            |
            |Rules:
            |- Maximum 4 sentences.
            |- Use only experience, skills, and metrics already evidenced in the resume. Do not fabricate.
            |- Naturally incorporate at least 2 of the ATS phrases provided.
            |- Mirror the seniority level of the target role.
            |- Plain text only — no markdown, no bullet points, no special characters.
            |- Output ONLY the rewritten summary text. No preamble, no explanation.
        """.trimMargin()
    }
}
