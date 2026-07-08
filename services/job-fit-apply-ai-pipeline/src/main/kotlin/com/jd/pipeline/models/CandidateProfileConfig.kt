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
    @JsonProperty("preferences") val preferences: CandidatePreferences = CandidatePreferences()
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
