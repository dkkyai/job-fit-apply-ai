package com.jd.pipeline.nodes.tailor

import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.client.LlmClient
import com.jd.pipeline.config.Config
import com.jd.pipeline.utils.CandidateProfileRenderer
import java.nio.file.Files

/**
 * Tailor subgraph node C (3/7): summary rewrite.
 *
 * Produces a 3–4 line, zero-filler summary whose first line frames the candidate to the
 * JD's target title + years + domain, weaves in top must-haves (JD phrasing, `supported`
 * set only), and carries 1–2 quantified proof points taken from real resume metrics.
 *
 * Keeps the anti-parrot guard: local models at low temperature tend to echo the base
 * summary despite instructions, so a word-trigram containment check triggers one retry
 * with the rejected draft as feedback.
 */
class SummaryRewriteNode(
    private val llm: LlmCaller = LlmClient.reasoningClient(nodeKey = "summary_rewrite")
) {

    fun process(state: TailorState): TailorState {
        val jd = state.jdRequirements ?: return state.copy(error = "summary_rewrite: jdRequirements is null")
        val gap = state.gapAnalysis ?: return state.copy(error = "summary_rewrite: gapAnalysis is null")
        val profile = state.candidateProfile
            ?: return state.copy(error = "summary_rewrite: candidateProfile is null")

        println("[summary_rewrite] Rewriting summary for: ${state.roleTitle} @ ${state.company}")

        return try {
            val prompt = buildPrompt(jd, gap, state, CandidateProfileRenderer.renderForTailoring(profile))
            var summary = sanitizeSummary(llm.call(prompt))

            // Plausibility guard: local models sometimes echo the prompt's context block back as
            // JSON, or ramble for thousands of chars. One corrective retry, then degrade to base.
            if (!isPlausibleSummary(summary)) {
                println("[summary_rewrite] WARN: output not a plain-prose summary (${summary.length} chars) — retrying")
                val fixPrompt = prompt + "\n\nYOUR PREVIOUS OUTPUT WAS INVALID (it was JSON, a list, or far too long). " +
                    "Output ONLY 3–4 sentences of plain prose — no JSON, no braces, no labels, no bullet points."
                summary = sanitizeSummary(llm.call(fixPrompt))
            }
            if (!isPlausibleSummary(summary)) {
                return state.copy(error = "summary_rewrite: model did not return a plain-prose summary (${summary.length} chars)")
            }

            val baseSummary = profile.background.summary
            if (baseSummary.isNotBlank() && shingleOverlap(baseSummary, summary) > SIMILARITY_RETRY_THRESHOLD) {
                println("[summary_rewrite] WARN: draft too similar to base summary — retrying with feedback")
                val retryPrompt = prompt + "\n\nREJECTED DRAFT (too close to CURRENT SUMMARY — write different sentences " +
                    "that open with the TARGET TITLE and this JD's must-have terms):\n" + summary
                val retry = sanitizeSummary(llm.call(retryPrompt))
                if (isPlausibleSummary(retry)) summary = retry
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

    private fun buildPrompt(jd: JdRequirements, gap: GapAnalysis, state: TailorState, profileMarkdown: String): String {
        val skillPrompt = try {
            if (Files.exists(Config.SUMMARY_REWRITE_SKILL)) Files.readString(Config.SUMMARY_REWRITE_SKILL)
            else DEFAULT_PROMPT
        } catch (_: Exception) { DEFAULT_PROMPT }

        val currentSummary = state.candidateProfile?.background?.summary?.takeIf { it.isNotBlank() }
            ?: "(no prior summary on the profile)"

        val supported = gap.supported.take(12).joinToString("\n") { "- ${it.term} — evidence: ${it.evidence}" }
            .ifBlank { "(none)" }
        val pullForward = gap.missingButSupported.take(5).joinToString("\n") { "- ${it.term} — evidence: ${it.evidence}" }
            .ifBlank { "(none)" }

        return buildString {
            appendLine(TailorRubric.prepend(skillPrompt))
            appendLine()
            appendLine("TARGET TITLE (line 1 must frame to this): ${jd.targetTitle} at ${state.company}")
            appendLine("SENIORITY SIGNALS TO MIRROR: ${jd.senioritySignals.joinToString(", ").ifBlank { "(none)" }}")
            appendLine("COMPANY VALUE SIGNALS: ${jd.companyValueSignals.joinToString(", ").ifBlank { "(none)" }}")
            appendLine()
            appendLine("SUPPORTED TERMS (the ONLY terms you may claim; mirror the JD phrasing):")
            appendLine(supported)
            appendLine()
            appendLine("PULL FORWARD FIRST (must-haves the resume under-surfaces — highest value):")
            appendLine(pullForward)
            appendLine()
            appendLine("DO NOT CLAIM (no resume evidence): ${gap.unsupported.joinToString(", ").ifBlank { "(none)" }}")
            appendLine()
            appendLine("CURRENT SUMMARY (fact source only — do NOT reuse its sentences; note it may contain")
            appendLine("errors such as doubled words — never reproduce them):")
            appendLine(currentSummary)
            state.atsReport?.let { report ->
                appendLine()
                appendLine("PREVIOUS VALIDATION FEEDBACK (revision pass — fix these concretely):")
                appendLine("- Must-have coverage: ${report.mustHaveCoveragePct}%")
                appendLine("- Supported terms still MISSING from the output: ${report.missingTerms.joinToString(", ").ifBlank { "(none)" }}")
                appendLine("- Unsupported terms that LEAKED (remove): ${report.leakedUnsupportedTerms.joinToString(", ").ifBlank { "(none)" }}")
                appendLine("- Improvements: ${report.topImprovements.joinToString("; ").ifBlank { "(none)" }}")
            }
            appendLine()
            appendLine("CANDIDATE PROFILE (structured; quantified proof points must come from here):")
            appendLine(profileMarkdown)
            appendLine()
            // Final instruction last, where the model weights it most — curbs the local-model
            // habit of echoing the structured context above back as JSON.
            append("Now write ONLY the 3–4 sentence prose summary. No JSON, no braces, no field labels, " +
                "no bullet list — just the sentences, starting with the target title.")
        }
    }

    companion object {
        /** Word-trigram containment above which a draft counts as parroting the base summary. */
        internal const val SIMILARITY_RETRY_THRESHOLD = 0.5

        /** A dense 3–4 line summary is ~350–700 chars; anything past this is the model rambling. */
        internal const val MAX_SUMMARY_CHARS = 1200

        /** Strip code fences and a leading "Summary:" label; trim. */
        internal fun sanitizeSummary(raw: String): String =
            raw.replace(Regex("```(?:json|text)?", RegexOption.IGNORE_CASE), "").trim()
                .removePrefix("Summary:").removePrefix("SUMMARY:").removePrefix("**Summary:**").trim()

        /** True only for plain-prose output of a sane length — rejects JSON/list/markdown echoes and rambles. */
        internal fun isPlausibleSummary(s: String): Boolean {
            if (s.isBlank() || s.length > MAX_SUMMARY_CHARS) return false
            val head = s.trimStart().firstOrNull() ?: return false
            return head !in "{[-*#" // JSON braces/brackets or a markdown list/heading marker — not prose
        }

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
            |Rewrite the candidate's summary for the target role. 3–4 dense lines, plain text only.
            |Line 1 names the TARGET TITLE, years of experience, and domain. Weave in the top
            |supported must-have terms using the JD's exact wording. Include 1–2 quantified proof
            |points taken only from the candidate profile. No filler adjectives ("passionate",
            |"adept at"). No doubled words. Never claim an unsupported term.
            |Output ONLY the rewritten summary text — no preamble, no labels, no markdown.
        """.trimMargin()
    }
}
