package com.jd.pipeline.models

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * CandidateProfile — all static, candidate-supplied information used across the pipeline.
 *
 * Assembled once at startup by [ProfileLoader] from two YAML files and threaded through
 * [com.jd.pipeline.state.JDState.candidateProfile] so every node has access without
 * reloading:
 *  - `resume.yaml` (canonical résumé) → identity, summary, education, career history, skills, projects
 *  - `candidate_profile.yaml` (slim config) → target title, years of experience, core strengths,
 *    languages, domain expertise, and recruiter preferences
 *
 * The in-memory shape below is the single contract every downstream node reads
 * (Score Fit, Resume Tailoring, Cover Letter, PDF render), regardless of on-disk layout.
 *
 * ## Sections
 * - **identity:** name and contact — used by Draft Reply sign-off and the résumé contact line
 * - **background:** career history — used by Score Fit for rubric-accurate scoring
 * - **skills:** labelled skill groups — used by Score Fit and Resume Tailoring
 * - **preferences:** recruiter pre-screening answers — used by Draft Reply
 */
data class CandidateProfile(
    @JsonProperty("identity")
    val identity: CandidateIdentity,

    @JsonProperty("background")
    val background: CandidateBackground,

    /** Labelled skill groups (e.g. "Core", "Programming Languages & Frameworks"). */
    @JsonProperty("skills")
    val skills: List<SkillGroup>,

    @JsonProperty("preferences")
    val preferences: CandidatePreferences = CandidatePreferences(),

    /** Independent projects (open-source, side projects, etc.) — same shape as career history. */
    @JsonProperty("projects")
    val projects: List<CareerEntry> = emptyList()
)

/**
 * Identity fields used by Draft Reply sign-off, the résumé contact line, and PDF filenames.
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

    /** Start date (`YYYY-MM` or `YYYY`). `start` in résumé YAML. */
    @JsonProperty("start_date")
    @JsonAlias("start")
    val startDate: String,

    /** End date; `null`/blank represents an active/current role (rendered as "Present"). `end` in résumé YAML. */
    @JsonProperty("end_date")
    @JsonAlias("end")
    val endDate: String? = null,

    @JsonProperty("bullets")
    val bullets: List<Bullet> = emptyList()
) {
    /** Convenience for rendering "start – end" with "Present" for active roles. */
    val dateRange: String
        get() = "$startDate – ${endDate?.takeIf { it.isNotBlank() } ?: "Present"}"
}

/**
 * A single résumé bullet: a short bold category label plus the accomplishment text.
 * Rendered as `<li><strong>{category}:</strong> {text}</li>`. The category is JD-alignable —
 * the tailoring subgraph may rewrite both the label and the text per job.
 */
data class Bullet(
    @JsonProperty("category")
    val category: String = "",

    @JsonProperty("text")
    val text: String
)

data class EducationEntry(
    @JsonProperty("degree")
    val degree: String,

    /** Field of study, e.g. "Computer Engineering". Rendered as "Degree | Field".
     *  Named `fieldOfStudy` because `field` is reserved inside property accessors. */
    @JsonProperty("field")
    val fieldOfStudy: String = "",

    @JsonProperty("school")
    val school: String,

    @JsonProperty("location")
    val location: String? = null,

    /** Graduation year (or range). Kept as a string so "2005" or "2003–2005" both round-trip. */
    @JsonProperty("year")
    val year: String = ""
) {
    /** "Degree | Field" when a field is present, otherwise just the degree. */
    val degreeLine: String
        get() = if (fieldOfStudy.isNotBlank()) "$degree | $fieldOfStudy" else degree
}

/**
 * A labelled skill group, e.g. `{label: "Core", items: [Android, Kotlin, ...]}`.
 * Groups flow straight through to the rendered Skills section and fold into the
 * tailored `skillGroups` map produced by SkillsRestructureNode.
 */
data class SkillGroup(
    @JsonProperty("label")
    val label: String,

    @JsonProperty("items")
    val items: List<String> = emptyList()
)
