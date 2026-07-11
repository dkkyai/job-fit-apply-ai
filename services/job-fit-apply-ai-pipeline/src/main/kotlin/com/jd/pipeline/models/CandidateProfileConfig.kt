package com.jd.pipeline.models

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Slim pipeline config loaded from `candidate_profile.yaml` — everything the résumé
 * does NOT carry: scoring aids + recruiter preferences. Merged with [ResumeYaml] into
 * a [CandidateProfile] by [ProfileLoader].
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CandidateProfileConfig(
    @JsonProperty("scoring") val scoring: ScoringAids = ScoringAids(),
    @JsonProperty("preferences") val preferences: CandidatePreferences = CandidatePreferences(),
    @JsonProperty("tailoring") val tailoring: TailoringAids = TailoringAids()
)

/**
 * Curated hints Score Fit uses that a résumé does not spell out: the role the
 * candidate is targeting and the top differentiators to weigh most heavily.
 *
 * Intentionally absent (all derivable from `resume.yaml`, so not hand-maintained here):
 *  - `years_experience` — computed from experience dates ([ProfileLoader.computeYearsExperience])
 *  - `languages` / `domain_expertise` — parsed from the résumé's labelled skill groups
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ScoringAids(
    @JsonProperty("target_title") val targetTitle: String = "",
    @JsonProperty("core_strengths") val coreStrengths: List<String> = emptyList()
)

/**
 * Candidate-curated inputs for the resume-tailoring subgraph — truth the résumé alone
 * doesn't carry:
 *  - [positioningStatement] — one sentence, the candidate's own framing; keeps the
 *    rewritten summary's voice anchored instead of drifting per JD.
 *  - [evidenceBank] — verified facts (metrics, tools, scope) that are true but not on the
 *    résumé. Gap analysis may cite them as evidence, widening what can be truthfully
 *    claimed without bloating the base résumé.
 *  - [neverClaim] — terms the candidate refuses to be interviewed on (touched once,
 *    long-rusty, or plain untrue). Always classified unsupported, even when a JD match
 *    looks plausible, and leak-checked in the final output.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TailoringAids(
    @JsonProperty("positioning_statement") val positioningStatement: String = "",
    @JsonProperty("evidence_bank") val evidenceBank: List<String> = emptyList(),
    @JsonProperty("never_claim") val neverClaim: List<String> = emptyList()
) {
    /** Drop unfilled `__TODO__` placeholders and blanks so template values never reach prompts. */
    fun sanitized(): TailoringAids = TailoringAids(
        positioningStatement = positioningStatement.trim()
            .takeUnless { it.isBlank() || it.contains("__TODO__") } ?: "",
        evidenceBank = evidenceBank.map { it.trim() }.filter { it.isNotBlank() && !it.contains("__TODO__") },
        neverClaim = neverClaim.map { it.trim() }.filter { it.isNotBlank() && !it.contains("__TODO__") }
    )
}
