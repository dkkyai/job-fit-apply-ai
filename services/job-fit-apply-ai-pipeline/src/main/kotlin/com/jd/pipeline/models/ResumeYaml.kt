package com.jd.pipeline.models

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Structured, candidate-authored résumé loaded from `resume.yaml` — the canonical
 * source for all résumé content. Merged with [CandidateProfileConfig] into a
 * [CandidateProfile] by [ProfileLoader].
 *
 * `experience`/`projects` reuse [CareerEntry] (whose `start`/`end` and `{category,text}`
 * bullets deserialise directly from this file via `@JsonAlias`); `education` reuses
 * [EducationEntry]; `skills` reuses [SkillGroup].
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ResumeYaml(
    @JsonProperty("demographics") val demographics: Demographics = Demographics(),
    @JsonProperty("summary") val summary: String = "",
    @JsonProperty("experience") val experience: List<CareerEntry> = emptyList(),
    @JsonProperty("projects") val projects: List<CareerEntry> = emptyList(),
    @JsonProperty("education") val education: List<EducationEntry> = emptyList(),
    @JsonProperty("skills") val skills: List<SkillGroup> = emptyList()
)

/**
 * Résumé header block. Maps onto [CandidateIdentity] during the merge. Includes the
 * optional contact URLs (which a résumé header rarely prints but the pipeline uses
 * elsewhere) so identity lives wholly in `resume.yaml`.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Demographics(
    @JsonProperty("first_name") val firstName: String = "",
    @JsonProperty("last_name") val lastName: String = "",
    @JsonProperty("email") val email: String = "",
    @JsonProperty("phone") val phone: String = "",
    @JsonProperty("location") val location: String = "",
    @JsonProperty("postal_code") val postalCode: String = "",
    @JsonProperty("linkedin_url") val linkedinUrl: String? = null,
    @JsonProperty("github_url") val githubUrl: String? = null,
    @JsonProperty("portfolio_url") val portfolioUrl: String? = null,
    @JsonProperty("website_url") val websiteUrl: String? = null
) {
    val fullName: String get() = "$firstName $lastName".trim()
}
