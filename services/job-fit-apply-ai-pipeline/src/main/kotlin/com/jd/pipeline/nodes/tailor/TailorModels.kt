package com.jd.pipeline.nodes.tailor

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Data contracts for the resume-tailoring subgraph.
 *
 * The subgraph optimises the tailored resume for three ordered targets:
 *  1. ATS pass — parse cleanly, cover the JD's must-have terms.
 *  2. Recruiter skim (~7s) — strongest content first, scannable bullets.
 *  3. Hiring-manager depth — Staff/Senior scope signals that survive the interview.
 *
 * Integrity is enforced structurally: [GapAnalysis] partitions JD terms into what the
 * candidate can PROVE ([GapAnalysis.supported] / [GapAnalysis.missingButSupported]) and
 * what they cannot ([GapAnalysis.unsupported]); downstream nodes may only emphasise the
 * proven sets, and [AtsValidationNode] deterministically detects any unsupported term
 * that leaks into the output.
 *
 * All LLM-parsed classes carry @JsonIgnoreProperties(ignoreUnknown = true) so extra
 * fields the model emits never break deserialisation.
 */

// ── Node A: JD extraction ────────────────────────────────────────────────────

/** One of the JD's own skill groupings (its section labels), reused by the skills node. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class JdSkillGroup(
    @JsonProperty("label") val label: String = "",
    @JsonProperty("items") val items: List<String> = emptyList()
)

/**
 * Structured requirement set extracted from the JD — the single source every downstream
 * node reads (no node re-parses the raw JD).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class JdRequirements(
    /** The JD's title, verbatim (e.g. "Staff Software Development Engineer in Test"). */
    @JsonProperty("target_title") val targetTitle: String = "",
    /** Seniority phrases quoted from the JD ("set standards", "mentor senior engineers", "cross-team"). */
    @JsonProperty("seniority_signals") val senioritySignals: List<String> = emptyList(),
    /** Hard requirements, in the JD's EXACT phrasing — the ATS match targets. */
    @JsonProperty("must_have") val mustHave: List<String> = emptyList(),
    /** Nice-to-have / "plus" items, in the JD's exact phrasing. */
    @JsonProperty("nice_to_have") val niceToHave: List<String> = emptyList(),
    /** Tools/certifications/protocols that ATS parsers match literally (frameworks, cloud, standards). */
    @JsonProperty("exact_match_terms") val exactMatchTerms: List<String> = emptyList(),
    /** The JD's own skill groupings/section labels, for the skills-restructure node. */
    @JsonProperty("skill_groupings") val skillGroupings: List<JdSkillGroup> = emptyList(),
    /** Industry/domain vocabulary worth weaving into bullets where truthful. */
    @JsonProperty("domain_keywords") val domainKeywords: List<String> = emptyList(),
    /** Culture/values clues ("move fast", "data-driven quality"). */
    @JsonProperty("company_value_signals") val companyValueSignals: List<String> = emptyList()
)

// ── Node B: gap analysis ─────────────────────────────────────────────────────

/** A JD term paired with the resume evidence that makes it safe to claim. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class EvidencedTerm(
    @JsonProperty("term") val term: String = "",
    /** Quoted text from the source resume that backs the term. */
    @JsonProperty("evidence") val evidence: String = ""
)

/**
 * The "safe to claim" partition of the JD's terms. Downstream emphasis draws ONLY from
 * [supported] and [missingButSupported]; [unsupported] terms must never appear in output.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GapAnalysis(
    /** JD terms with real resume evidence — safe to emphasise and mirror verbatim. */
    @JsonProperty("supported") val supported: List<EvidencedTerm> = emptyList(),
    /** JD terms with NO resume evidence — must not be claimed anywhere. */
    @JsonProperty("unsupported") val unsupported: List<String> = emptyList(),
    /**
     * Must-haves the candidate clearly has but that the resume does not currently surface
     * prominently — the highest-value additions for summary/bullets/skills.
     */
    @JsonProperty("missing_but_supported") val missingButSupported: List<EvidencedTerm> = emptyList()
)

// ── Node D: bullet rewrite ───────────────────────────────────────────────────

/**
 * Deterministic reorder inputs for one rewritten bullet, emitted by the bullet-rewrite
 * LLM and consumed by [BulletReorderNode]'s scoring.
 */
data class BulletMeta(
    /** How many JD must-have terms this bullet covers. */
    val mustHaveHits: Int = 0,
    /** True when the bullet carries a real quantified result (from the source resume). */
    val quantified: Boolean = false,
    /** True when the bullet shows Staff-level scope (ownership, cross-team adoption, downstream impact). */
    val senioritySignal: Boolean = false
) {
    /** Reorder sort key: relevance first, then quantification, then scope. */
    val score: Int get() = mustHaveHits * 4 + (if (quantified) 2 else 0) + (if (senioritySignal) 1 else 0)
}

/** Original→rewritten pair for the `tailored_bullets.txt` diagnostic file. */
data class TailoredBullet(
    val original: String = "",
    val rewritten: String = "",
    val mustHaveHits: List<String> = emptyList(),
    val quantified: Boolean = false,
    val senioritySignal: Boolean = false
)

/** Join key used to fold per-role LLM output back into the profile's career entries. */
fun roleKey(role: String, company: String, startDate: String): String =
    "${role.trim().lowercase()}|${company.trim().lowercase()}|${startDate.trim()}"

// ── Node F: skills restructure ───────────────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class RestructuredSkills(
    /**
     * The authoritative output consumed by the HTML render: category label → skills,
     * ordered by JD relevance (Jackson preserves JSON object order via LinkedHashMap).
     */
    @JsonProperty("grouped_by_category") val groupedByCategory: Map<String, List<String>> = emptyMap(),
    /** Skills present on both the resume and the JD — rendered first within each group. */
    @JsonProperty("jd_matched_skills") val jdMatchedSkills: List<String> = emptyList(),
    /** Resume skills dropped as irrelevant noise for THIS role (analytics only). */
    @JsonProperty("removed_for_this_role") val removedForThisRole: List<String> = emptyList()
)

// ── Node G: ATS validation ───────────────────────────────────────────────────

/** The LLM-judged portion of the ATS report (everything else is computed in Kotlin). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AtsLlmScores(
    @JsonProperty("seniority_alignment") val seniorityAlignment: Int = 0,
    @JsonProperty("quantification") val quantification: Int = 0,
    @JsonProperty("format_safety") val formatSafety: Int = 0,
    @JsonProperty("top_improvements") val topImprovements: List<String> = emptyList()
)

/**
 * Actionable validation result — a score plus a concrete gap list, never a silent pass.
 * The deterministic fields are ground truth (word-boundary matching over the full
 * tailored text); the sub-scores come from [AtsLlmScores].
 */
data class AtsReport(
    /** supported must-haves present in the output ÷ total must-haves (×100). */
    val mustHaveCoveragePct: Int = 0,
    /** Supported must-haves NOT yet in the output — actionable, fed to the refine pass. */
    val missingTerms: List<String> = emptyList(),
    /** Must-haves the candidate cannot claim (unsupported) — reported, never added. */
    val unreachableTerms: List<String> = emptyList(),
    /** Integrity failures: unsupported terms that leaked into the output. */
    val leakedUnsupportedTerms: List<String> = emptyList(),
    /** Consecutive duplicated words ("reducing reducing") found in the output. */
    val doubledWords: List<String> = emptyList(),
    val seniorityAlignment: Int = 0,
    val quantification: Int = 0,
    val formatSafety: Int = 0,
    val topImprovements: List<String> = emptyList(),
    /** Weighted composite: coverage 40%, seniority 25%, quantification 20%, format 15%, minus integrity penalties. */
    val overallScore: Int = 0
) {
    /** True when a refinement pass could concretely improve the output. */
    val needsRefinement: Boolean
        get() = leakedUnsupportedTerms.isNotEmpty() || doubledWords.isNotEmpty() || missingTerms.isNotEmpty()
}
