package com.jd.pipeline.nodes.tailor

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Data models for the ResumeTailoringSubgraph.
 *
 * All classes carry @JsonIgnoreProperties(ignoreUnknown = true) so that
 * Jackson deserialisation is resilient to extra fields the LLM may emit.
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

@JsonIgnoreProperties(ignoreUnknown = true)
data class SkillGap(
    @JsonProperty("skill") val skill: String = "",
    @JsonProperty("in_jd") val inJd: Boolean = false,
    @JsonProperty("on_resume") val onResume: Boolean = false,
    @JsonProperty("action") val action: String = ""   // "highlight" | "add_if_honest" | "omit"
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GapAnalysis(
    @JsonProperty("skills_table") val skillsTable: List<SkillGap> = emptyList(),
    @JsonProperty("keyword_coverage_score") val keywordCoverageScore: Int = 0,
    @JsonProperty("top_gaps") val topGaps: List<String> = emptyList(),
    @JsonProperty("top_strengths") val topStrengths: List<String> = emptyList(),
    @JsonProperty("bullets_to_promote") val bulletsToPromote: List<String> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TailoredBullet(
    @JsonProperty("original") val original: String = "",
    @JsonProperty("rewritten") val rewritten: String = "",
    @JsonProperty("jd_alignment_score") val jdAlignmentScore: Int = 0
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RestructuredSkills(
    @JsonProperty("restructured_text") val restructuredText: String = "",
    @JsonProperty("removed_for_this_role") val removedForThisRole: List<String> = emptyList(),
    @JsonProperty("jd_matched_skills") val jdMatchedSkills: List<String> = emptyList(),
    @JsonProperty("grouped_by_category") val groupedByCategory: Map<String, List<String>> = emptyMap()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AtsScore(
    @JsonProperty("overall_score") val overallScore: Int = 0,
    @JsonProperty("keyword_match") val keywordMatch: Int = 0,
    @JsonProperty("skill_coverage") val skillCoverage: Int = 0,
    @JsonProperty("seniority_alignment") val seniorityAlignment: Int = 0,
    @JsonProperty("quantification") val quantification: Int = 0,
    @JsonProperty("format_safety") val formatSafety: Int = 0,
    @JsonProperty("remaining_gaps") val remainingGaps: List<String> = emptyList(),
    @JsonProperty("top_3_improvements") val top3Improvements: List<String> = emptyList()
)
