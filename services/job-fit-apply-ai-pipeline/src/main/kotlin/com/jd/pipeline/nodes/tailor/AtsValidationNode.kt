package com.jd.pipeline.nodes.tailor

import com.fasterxml.jackson.databind.ObjectMapper
import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.client.LlmClient
import com.jd.pipeline.config.Config
import java.nio.file.Files

/**
 * Tailor subgraph node G (7/7): ATS validation — actionable, never a silent pass.
 *
 * The ground truth is computed deterministically in Kotlin over the full tailored text
 * (word-boundary matching, so "Java" never matches "JavaScript"):
 *  - **must-have coverage %** — supported must-haves present ÷ total must-haves;
 *  - **missing terms** — supported must-haves not yet placed (fed to the refine pass);
 *  - **leaked unsupported terms** — integrity failures that must be removed;
 *  - **doubled words** — the "reducing reducing" class of artifact;
 *  - **style warnings** — overlong bullets and repeated lead verbs (score-neutral,
 *    fed to the refine pass).
 *
 * The LLM judges only what needs judgment (seniority alignment, quantification, format
 * safety) and proposes concrete improvements. The composite [AtsReport.overallScore]
 * weights coverage 40%, seniority 25%, quantification 20%, format 15%, minus 5 points per
 * integrity finding (leaked term or doubled word), floored at 0.
 */
class AtsValidationNode(
    // nodeKey stays "ats_scoring" so NodeTimer's display mapping keeps working.
    private val llm: LlmCaller = LlmClient.orchestrationClient(nodeKey = "ats_scoring")
) {
    private val mapper = ObjectMapper()

    fun process(state: TailorState): TailorState {
        val jd = state.jdRequirements ?: return state.copy(error = "ats_validation: jdRequirements is null")
        val gap = state.gapAnalysis ?: return state.copy(error = "ats_validation: gapAnalysis is null")
        if (state.tailoredCareerHistory == null) return state.copy(error = "ats_validation: tailoredCareerHistory is null")
        if (state.restructuredSkills == null) return state.copy(error = "ats_validation: restructuredSkills is null")
        // The summary may have degraded to the base (non-fatal); validate whatever gets rendered.

        println("[ats_validation] Validating tailored resume for: ${state.roleTitle}")

        return try {
            val outputText = assembleOutputText(state)
            val deterministic = validateDeterministically(jd, gap, outputText)
            val style = findStyleWarnings(state)
            val llmScores = judgeWithLlm(jd, state, deterministic, style)
            val report = buildReport(deterministic, style, llmScores)

            val colour = if (report.needsRefinement) "[93m" else "[92m"
            println("${colour}[ats_validation] overall=${report.overallScore} coverage=${report.mustHaveCoveragePct}% " +
                "missing=${report.missingTerms.size} leaked=${report.leakedUnsupportedTerms.size} doubled=${report.doubledWords.size}[0m")
            state.copy(atsReport = report)
        } catch (e: Exception) {
            state.copy(error = "ats_validation: ${e.message}")
        }
    }

    // ── deterministic core ─────────────────────────────────────────────────────

    /** The summary that will actually be rendered — tailored when present, else the base profile's. */
    private fun renderedSummary(state: TailorState): String =
        state.tailoredSummary ?: state.candidateProfile?.background?.summary ?: ""

    /** Everything the candidate-facing resume will contain, for term matching. */
    internal fun assembleOutputText(state: TailorState): String = buildString {
        appendLine(renderedSummary(state))
        (state.tailoredCareerHistory.orEmpty() + state.tailoredProjects.orEmpty()).forEach { entry ->
            entry.bullets.forEach { appendLine("${it.category}: ${it.text}") }
        }
        state.restructuredSkills?.groupedByCategory?.forEach { (label, items) ->
            appendLine("$label: ${items.joinToString(", ")}")
        }
    }

    internal data class DeterministicChecks(
        val coveragePct: Int,
        val missingTerms: List<String>,
        val unreachableTerms: List<String>,
        val leakedTerms: List<String>,
        val doubledWords: List<String>
    )

    /** Word-boundary presence check — delegates to the shared [TermMatch]. `internal` for test visibility. */
    internal fun termPresent(term: String, text: String): Boolean = TermMatch.present(term, text)

    /** Consecutive duplicated words ("reducing reducing"), case-insensitive, distinct. */
    internal fun findDoubledWords(text: String): List<String> =
        Regex("\\b([A-Za-z]+)\\s+\\1\\b", RegexOption.IGNORE_CASE)
            .findAll(text)
            .map { it.groupValues[1].lowercase() }
            .distinct()
            .toList()

    internal fun validateDeterministically(jd: JdRequirements, gap: GapAnalysis, outputText: String): DeterministicChecks {
        val unsupportedSet = gap.unsupported.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        // A keyword is unreachable when it (or an unsupported term contained in it) was flagged unsupported.
        fun isUnreachable(term: String): Boolean {
            val t = term.trim().lowercase()
            return unsupportedSet.any { it == t || (it.length > 2 && t.contains(it)) }
        }

        // Coverage universe = the JD's ATOMIC literal-match keywords: named tools/skills PLUS the
        // short must-have phrases recruiters search verbatim. Long must-have requirement sentences
        // are described in prose, not keyword-matched, so they are excluded.
        val universe = jd.coverageKeywords(MAX_KEYWORD_WORDS)

        val reachable = universe.filterNot { isUnreachable(it) }
        val present = reachable.filter { termPresent(it, outputText) }
        val missing = reachable.filterNot { termPresent(it, outputText) }
        val unreachable = universe.filter { isUnreachable(it) }
        // Coverage over the terms the candidate CAN legitimately claim (unreachable reported separately).
        val coverage = if (reachable.isEmpty()) 100 else (present.size * 100) / reachable.size

        val leaked = gap.unsupported.distinct().filter { it.isNotBlank() && termPresent(it, outputText) }

        return DeterministicChecks(
            coveragePct = coverage,
            missingTerms = missing,
            unreachableTerms = unreachable,
            leakedTerms = leaked,
            doubledWords = findDoubledWords(outputText)
        )
    }

    /**
     * Deterministic skim-readability findings over the tailored bullets — score-neutral,
     * but concrete enough for the refine pass to act on. `internal` for test visibility.
     */
    internal fun findStyleWarnings(state: TailorState): List<String> {
        val bullets = (state.tailoredCareerHistory.orEmpty() + state.tailoredProjects.orEmpty())
            .flatMap { it.bullets }
        val warnings = mutableListOf<String>()
        bullets.filter { it.text.length > MAX_BULLET_CHARS }.forEach {
            warnings += "Bullet too long (${it.text.length} chars, max ~$MAX_BULLET_CHARS — one idea, ≤2 rendered lines): \"${it.text.take(70)}…\""
        }
        bullets.mapNotNull { b ->
            b.text.trim().split(Regex("\\s+")).firstOrNull()
                ?.filter(Char::isLetter)?.lowercase()?.takeIf { it.isNotBlank() }
        }.groupingBy { it }.eachCount().filter { it.value > MAX_VERB_REPEATS }.forEach { (verb, n) ->
            warnings += "Opening verb \"$verb\" starts $n bullets — vary the lead verbs (max $MAX_VERB_REPEATS uses)"
        }
        return warnings
    }

    // ── LLM judgment (qualitative only) ───────────────────────────────────────

    private fun judgeWithLlm(jd: JdRequirements, state: TailorState, checks: DeterministicChecks, style: List<String>): AtsLlmScores {
        val prompt = buildPrompt(jd, state, checks, style)
        val response = llm.call(prompt)
        return mapper.readValue(stripJsonFences(response), AtsLlmScores::class.java)
    }

    private fun buildReport(d: DeterministicChecks, style: List<String>, llm: AtsLlmScores): AtsReport {
        val integrityPenalty = 5 * (d.leakedTerms.size + d.doubledWords.size)
        val weighted = d.coveragePct * 0.40 + llm.seniorityAlignment * 0.25 +
            llm.quantification * 0.20 + llm.formatSafety * 0.15
        return AtsReport(
            mustHaveCoveragePct = d.coveragePct,
            missingTerms = d.missingTerms,
            unreachableTerms = d.unreachableTerms,
            leakedUnsupportedTerms = d.leakedTerms,
            doubledWords = d.doubledWords,
            seniorityAlignment = llm.seniorityAlignment,
            quantification = llm.quantification,
            formatSafety = llm.formatSafety,
            topImprovements = llm.topImprovements,
            styleWarnings = style,
            overallScore = (weighted - integrityPenalty).toInt().coerceIn(0, 100)
        )
    }

    private fun buildPrompt(jd: JdRequirements, state: TailorState, checks: DeterministicChecks, style: List<String>): String {
        val skillPrompt = try {
            if (Files.exists(Config.ATS_VALIDATION_SKILL)) Files.readString(Config.ATS_VALIDATION_SKILL)
            else DEFAULT_PROMPT
        } catch (_: Exception) { DEFAULT_PROMPT }

        val bullets = (state.tailoredCareerHistory.orEmpty() + state.tailoredProjects.orEmpty())
            .joinToString("\n") { entry ->
                "ROLE: ${entry.role} @ ${entry.company}\n" +
                    entry.bullets.joinToString("\n") { "- ${it.category}: ${it.text}" }
            }
        val skills = state.restructuredSkills?.groupedByCategory
            ?.entries?.joinToString("\n") { "- ${it.key}: ${it.value.joinToString(", ")}" } ?: ""

        return buildString {
            appendLine(TailorRubric.prepend(skillPrompt))
            appendLine()
            appendLine("TARGET TITLE: ${jd.targetTitle} at ${state.company}")
            appendLine("SENIORITY SIGNALS EXPECTED: ${jd.senioritySignals.joinToString(", ").ifBlank { "(none stated)" }}")
            appendLine()
            appendLine("DETERMINISTIC CHECKS (already computed — ground truth, do not re-estimate):")
            appendLine("- Must-have coverage: ${checks.coveragePct}%")
            appendLine("- Supported terms missing: ${checks.missingTerms.joinToString(", ").ifBlank { "(none)" }}")
            appendLine("- Leaked unsupported terms: ${checks.leakedTerms.joinToString(", ").ifBlank { "(none)" }}")
            appendLine("- Doubled words: ${checks.doubledWords.joinToString(", ").ifBlank { "(none)" }}")
            appendLine("- Style warnings (factor into format_safety and top_improvements):")
            appendLine(style.joinToString("\n") { "    - $it" }.ifBlank { "    (none)" })
            appendLine()
            appendLine("SUMMARY (as it will render):")
            appendLine(renderedSummary(state))
            appendLine()
            appendLine("TAILORED BULLETS (post-reorder):")
            appendLine(bullets)
            appendLine()
            appendLine("RESTRUCTURED SKILLS:")
            append(skills)
        }
    }

    private fun stripJsonFences(text: String): String =
        text.replace(Regex("```(?:json)?"), "").trim()
            .let { if (it.endsWith("`")) it.dropLast(1).trim() else it }

    companion object {
        /** Max word count for a must-have phrase to count as an atomic keyword in the coverage universe. */
        private const val MAX_KEYWORD_WORDS = 5

        /** Bullet length past which the recruiter skim breaks (~2 rendered lines). */
        internal const val MAX_BULLET_CHARS = 250

        /** Max times the same opening verb may lead a bullet before it reads templated. */
        internal const val MAX_VERB_REPEATS = 2

        private val DEFAULT_PROMPT = """
            |Judge the tailored resume's qualitative quality. Keyword coverage is already computed
            |deterministically — do NOT re-estimate it. Score only:
            |- seniority_alignment (0-100): does the content evidence the JD's stated scope/level?
            |- quantification (0-100): fraction of bullets carrying a real measurable impact.
            |- format_safety (0-100): plain scannable text, standard headings, no odd characters.
            |And list top_improvements: the 3 most concrete, actionable edits (name the term or
            |bullet). Return ONLY valid JSON, no fences:
            |{ "seniority_alignment": 0, "quantification": 0, "format_safety": 0,
            |  "top_improvements": ["...", ...] }
        """.trimMargin()
    }
}
