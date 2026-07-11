package com.jd.pipeline.models

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Lean JD structure extracted by score_fit's combined scoring call (SCORE_SKILL.md).
 *
 * Used ONLY by the scoring path ([com.jd.pipeline.nodes.ScoreFitNode] →
 * [com.jd.pipeline.state.JDState.jdStructured]). The resume-tailoring subgraph runs its
 * own richer extraction ([com.jd.pipeline.nodes.tailor.JdRequirements]) and does not
 * consume this type.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class JdStructured(
    @JsonProperty("role_title") val roleTitle: String = "",
    @JsonProperty("seniority") val seniority: String = "",
    @JsonProperty("required_skills") val requiredSkills: List<String> = emptyList(),
    @JsonProperty("preferred_skills") val preferredSkills: List<String> = emptyList(),
    @JsonProperty("domain_keywords") val domainKeywords: List<String> = emptyList(),
    @JsonProperty("ats_exact_phrases") val atsExactPhrases: List<String> = emptyList(),
    @JsonProperty("company_value_signals") val companyValueSignals: List<String> = emptyList()
)
