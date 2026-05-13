package com.jd.pipeline.models

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * CandidateProfile — all static, candidate-supplied information used across the pipeline.
 *
 * Loaded once at startup from [Config.CANDIDATE_PROFILE_PATH] and threaded through
 * [com.jd.pipeline.state.JDState.candidateProfile] so every node has access without
 * reloading the file.
 *
 * ## Sections
 * - **identity:** name and contact — used by Draft Reply sign-off and Resume Gen
 * - **background:** career history — used by Score Fit for rubric-accurate scoring
 * - **skills:** strength/technology lists — used by Score Fit and Resume Tailoring
 * - **preferences:** recruiter pre-screening answers — used by Draft Reply
 */
data class CandidateProfile(
    @JsonProperty("identity")
    val identity: CandidateIdentity,

    @JsonProperty("background")
    val background: CandidateBackground,

    @JsonProperty("skills")
    val skills: CandidateSkills,

    @JsonProperty("preferences")
    val preferences: CandidatePreferences = CandidatePreferences(),

    /** Independent projects (open-source, side projects, etc.) — same shape as career history. */
    @JsonProperty("projects")
    val projects: List<CareerEntry> = emptyList()
)

/**
 * Identity fields used by Draft Reply sign-off, Resume Gen contact line, and PDF filenames.
 */
data class CandidateIdentity(
    @JsonProperty("name")
    val name: String,

    @JsonProperty("first_name")
    val firstName: String = "",

    @JsonProperty("last_name")
    val lastName: String = "",

    @JsonProperty("email")
    val email: String,

    @JsonProperty("phone")
    val phone: String,

    @JsonProperty("location")
    val location: String,

    @JsonProperty("linkedin_url")
    val linkedinUrl: String? = null,

    @JsonProperty("github_url")
    val githubUrl: String? = null,

    @JsonProperty("portfolio_url")
    val portfolioUrl: String? = null,

    @JsonProperty("website_url")
    val websiteUrl: String? = null
) {
    /**
     * Full name constructed from first_name + last_name.
     * Falls back to the legacy `name` field if first/last are empty.
     */
    val fullName: String
        get() = when {
            firstName.isNotBlank() && lastName.isNotBlank() -> "$firstName $lastName"
            name.isNotBlank() -> name
            else -> ""
        }
}

/**
 * Career history and core strengths — used by Score Fit node for rubric-accurate scoring.
 * Kept in sync with SCORE_SKILL.md so both stay consistent.
 */
data class CandidateBackground(
    @JsonProperty("target_title")
    val targetTitle: String,

    @JsonProperty("years_experience")
    val yearsExperience: Int,

    /** Professional summary — used by SummaryRewriteNode as the original to rewrite from. */
    @JsonProperty("summary")
    val summary: String = "",

    @JsonProperty("education")
    val education: List<EducationEntry>,

    @JsonProperty("career_history")
    val careerHistory: List<CareerEntry>,

    @JsonProperty("core_strengths")
    val coreStrengths: List<String>,

    @JsonProperty("languages")
    val languages: List<String>,

    @JsonProperty("domain_expertise")
    val domainExpertise: List<String>
)

data class CareerEntry(
    @JsonProperty("role")
    val role: String,

    @JsonProperty("company")
    val company: String,

    /** City/state where the role was based, e.g. "Seattle, WA" or "Remote". Empty when unspecified. */
    @JsonProperty("location")
    val location: String = "",

    @JsonProperty("start_date")
    val startDate: String,

    /** End date; `null` represents an active/current role (rendered as "Present"). */
    @JsonProperty("end_date")
    val endDate: String? = null,

    @JsonProperty("bullets")
    val bullets: List<String>
) {
    /** Convenience for rendering "start – end" with "Present" for active roles. */
    val dateRange: String
        get() = "$startDate – ${endDate ?: "Present"}"
}

data class EducationEntry(
    @JsonProperty("degree")
    val degree: String,

    @JsonProperty("school")
    val school: String,

    @JsonProperty("location")
    val location: String? = null,

    @JsonProperty("start_date")
    val startDate: String,

    /** End date; `null` represents an in-progress degree (rendered as "Present"). */
    @JsonProperty("end_date")
    val endDate: String? = null
) {
    val dateRange: String
        get() = "$startDate – ${endDate ?: "Present"}"
}

/**
 * Technology and skill lists — used by Score Fit and Resume Tailoring nodes.
 * Represents what the candidate _actually has_, not what any single JD wants.
 */
data class CandidateSkills(
    @JsonProperty("primary_stack")
    val primaryStack: List<String>,

    @JsonProperty("mobile_automation")
    val mobileAutomation: List<String>,

    @JsonProperty("ci_cd_platforms")
    val ciCdPlatforms: List<String>,

    @JsonProperty("web_api_automation")
    val webApiAutomation: List<String>,

    @JsonProperty("infrastructure_observability")
    val infrastructureObservability: List<String>,

    @JsonProperty("leadership_abilities")
    val leadershipAbilities: List<String>
)

