package com.jd.pipeline.nodes.tailor

import com.jd.pipeline.models.CandidateProfile
import com.jd.pipeline.models.CareerEntry

/**
 * Structured output of [ResumeTailoringSubgraph]. Wraps the original
 * [CandidateProfile] (unchanged) plus the JD-aligned rewrites produced
 * by the subgraph nodes.
 *
 * [com.jd.pipeline.utils.ResumeHtmlRenderer] consumes this as the
 * authoritative source when generating `tailored_resume.html`.
 *
 * The five tailored fields take precedence over the corresponding fields
 * in [base] at render time:
 *   - [summary] overrides `base.background.summary`
 *   - [careerHistory] overrides `base.background.careerHistory`
 *   - [projects] overrides `base.projects`
 *   - [skillGroups] overrides the résumé's labelled skill groups (`base.skills`)
 *   - [jdMatchedSkills] is used to lead each skill group with the
 *     JD-aligned items first
 */
data class TailoredProfile(
    /** Original profile — used for identity, education, languages, etc. */
    val base: CandidateProfile,

    /** Rewritten background.summary, JD-aligned. */
    val summary: String,

    /**
     * Career history with rewritten bullets per role. Same length and order
     * as `base.background.careerHistory`; non-bullet fields (role, company,
     * location, dates) are copied through unchanged.
     */
    val careerHistory: List<CareerEntry>,

    /**
     * Projects with rewritten bullets. Same length and order as `base.projects`.
     * Empty when the candidate has no projects section.
     */
    val projects: List<CareerEntry>,

    /**
     * Skills grouped by JD-aligned category names (e.g. "Cloud",
     * "Testing Frameworks", "Languages"). Replaces the résumé's labelled
     * skill groups at render time so the rendered resume's Skills section
     * matches the role's terminology.
     */
    val skillGroups: Map<String, List<String>>,

    /** Skills present on both the resume and the JD — rendered first within each group. */
    val jdMatchedSkills: List<String> = emptyList()
) {
    /**
     * [skillGroups] with each group's items reordered to lead with the JD-matched skills
     * (original spelling preserved). The single ordering both renderers (HTML preview and
     * YAML→LaTeX PDF) consume, so the two artifacts never disagree.
     */
    fun orderedSkillGroups(): Map<String, List<String>> {
        if (jdMatchedSkills.isEmpty()) return skillGroups
        val jd = jdMatchedSkills.map { it.lowercase() }.toSet()
        return skillGroups.entries.associate { (label, items) ->
            val (matched, rest) = items.partition { it.lowercase() in jd }
            label to (matched + rest)
        }
    }

    companion object {
        /**
         * Convenience factory for the sparse-JD fallback: produces a
         * [TailoredProfile] that mirrors [profile] verbatim, with no
         * JD-driven rewrites. The résumé's labelled skill groups pass
         * straight through as the skill-groups map (order preserved).
         */
        fun untailored(profile: CandidateProfile): TailoredProfile {
            val groups = linkedMapOf<String, List<String>>()
            profile.skills.forEach { group ->
                if (group.items.isNotEmpty()) groups[group.label] = group.items
            }
            return TailoredProfile(
                base = profile,
                summary = profile.background.summary,
                careerHistory = profile.background.careerHistory,
                projects = profile.projects,
                skillGroups = groups
            )
        }
    }
}
