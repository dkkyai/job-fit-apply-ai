package com.jd.pipeline.models

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.jd.pipeline.config.Config
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads the canonical résumé (`resume.yaml`) and the slim pipeline config
 * (`candidate_profile.yaml`) and merges them into a single in-memory [CandidateProfile] —
 * the shape every downstream node reads.
 *
 * Résumé text uses YAML folded scalars (`>`), which leave a trailing newline; [merge]
 * trims the human-facing fields so both prompts and the rendered HTML stay clean.
 */
object ProfileLoader {

    val YAML: ObjectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

    /** Parse `resume.yaml`. Returns null (with a message) if missing or malformed. */
    fun loadResume(path: Path = Config.RESUME_YAML_PATH): ResumeYaml? {
        if (!Files.exists(path)) {
            System.err.println("[ProfileLoader] résumé YAML not found: $path — create it to enable structured résumé data")
            return null
        }
        return try {
            YAML.readValue(Files.readString(path), ResumeYaml::class.java)
        } catch (e: Exception) {
            System.err.println("[ProfileLoader] Failed to parse ${path.fileName}: ${e.message}")
            null
        }
    }

    /** Parse `candidate_profile.yaml`. Falls back to defaults (empty scoring aids + default preferences). */
    fun loadConfig(path: Path = Config.CANDIDATE_PROFILE_PATH): CandidateProfileConfig {
        if (!Files.exists(path)) {
            System.err.println("[ProfileLoader] candidate_profile YAML not found: $path — using default preferences + empty scoring aids")
            return CandidateProfileConfig()
        }
        return try {
            YAML.readValue(Files.readString(path), CandidateProfileConfig::class.java)
        } catch (e: Exception) {
            System.err.println("[ProfileLoader] Failed to parse ${path.fileName}: ${e.message} — using defaults")
            CandidateProfileConfig()
        }
    }

    /** Load and merge both files. Returns null when the résumé is missing/unparseable. */
    fun loadMergedProfile(): CandidateProfile? {
        val resume = loadResume() ?: return null
        return merge(resume, loadConfig())
    }

    /** Combine a parsed résumé + config into the in-memory [CandidateProfile] contract. */
    fun merge(resume: ResumeYaml, config: CandidateProfileConfig): CandidateProfile {
        val demo = resume.demographics
        return CandidateProfile(
            identity = CandidateIdentity(
                name = demo.fullName,
                firstName = demo.firstName.trim(),
                lastName = demo.lastName.trim(),
                email = demo.email.trim(),
                phone = demo.phone.trim(),
                location = demo.location.trim(),
                linkedinUrl = demo.linkedinUrl,
                githubUrl = demo.githubUrl,
                portfolioUrl = demo.portfolioUrl,
                websiteUrl = demo.websiteUrl
            ),
            background = CandidateBackground(
                targetTitle = config.scoring.targetTitle.trim(),
                yearsExperience = computeYearsExperience(resume),
                summary = resume.summary.trim(),
                education = resume.education,
                careerHistory = resume.experience.map(::normalize),
                coreStrengths = config.scoring.coreStrengths,
                // languages + domain expertise are parsed from the résumé's labelled skill groups
                languages = deriveSkillGroup(resume, "language"),
                domainExpertise = deriveSkillGroup(resume, "domain")
            ),
            skills = resume.skills,
            preferences = config.preferences,
            projects = resume.projects.map(::normalize)
        )
    }

    /** Trim folded-scalar whitespace off a role's bullets so rendered/prompt text is clean. */
    private fun normalize(entry: CareerEntry): CareerEntry =
        entry.copy(bullets = entry.bullets.map { Bullet(it.category.trim(), it.text.trim()) })

    /**
     * Total years of experience, derived from the résumé's experience dates: the span from the
     * earliest start year to the latest end year (an open/blank end counts through the current
     * year). Returns 0 when the résumé has no datable experience.
     */
    fun computeYearsExperience(resume: ResumeYaml): Int {
        val starts = resume.experience.mapNotNull { yearOf(it.startDate) }
        if (starts.isEmpty()) return 0
        val currentYear = java.time.Year.now().value
        val latestEnd = resume.experience.maxOf { yearOf(it.endDate) ?: currentYear }
        return (latestEnd - starts.min()).coerceAtLeast(0)
    }

    private fun yearOf(date: String?): Int? = date?.trim()?.take(4)?.toIntOrNull()

    /**
     * Flatten the items of every résumé skill group whose label contains [labelKeyword]
     * (case-insensitive) — e.g. `"language"` picks up "Programming Languages & Frameworks",
     * `"domain"` picks up "Domain Expertise". Used to derive `languages` / `domain_expertise`
     * from the résumé rather than maintaining them in `candidate_profile.yaml`.
     */
    fun deriveSkillGroup(resume: ResumeYaml, labelKeyword: String): List<String> =
        resume.skills.filter { it.label.contains(labelKeyword, ignoreCase = true) }.flatMap { it.items }
}
