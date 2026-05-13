package com.jd.pipeline.utils

import com.jd.pipeline.models.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [CandidateProfileRenderer] — pure logic, no LLM/network required.
 */
@DisplayName("CandidateProfileRendererTest")
class CandidateProfileRendererTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun makeProfile(
        identity: CandidateIdentity = makeIdentity(),
        background: CandidateBackground = makeBackground(),
        skills: CandidateSkills = makeSkills(),
        preferences: CandidatePreferences = CandidatePreferences(),
        projects: List<CareerEntry> = emptyList()
    ) = CandidateProfile(identity, background, skills, preferences, projects)

    private fun makeIdentity(
        name: String = "Alex Rivera",
        firstName: String = "Alex",
        lastName: String = "Rivera",
        location: String = "San Francisco, CA"
    ) = CandidateIdentity(
        name = name, firstName = firstName, lastName = lastName,
        email = "alex@example.com", phone = "555-0199", location = location
    )

    private fun makeBackground(
        careerHistory: List<CareerEntry> = emptyList(),
        education: List<EducationEntry> = emptyList(),
        coreStrengths: List<String> = emptyList(),
        languages: List<String> = emptyList(),
        domainExpertise: List<String> = emptyList(),
        summary: String = "Summary text"
    ) = CandidateBackground(
        targetTitle = "Senior Engineer", yearsExperience = 10,
        summary = summary, education = education,
        careerHistory = careerHistory, coreStrengths = coreStrengths,
        languages = languages, domainExpertise = domainExpertise
    )

    private fun makeSkills() = CandidateSkills(
        primaryStack = listOf("Kotlin", "Java"),
        mobileAutomation = listOf("Appium"),
        ciCdPlatforms = listOf("GitHub Actions"),
        webApiAutomation = listOf("Playwright"),
        infrastructureObservability = listOf("Kubernetes"),
        leadershipAbilities = listOf("Mentoring")
    )

    private fun makeCareer(
        role: String = "Engineer",
        company: String = "Acme",
        location: String = "Remote",
        startDate: String = "2020-01",
        endDate: String? = null,
        bullets: List<String> = listOf("Did thing A", "Did thing B")
    ) = CareerEntry(role, company, location, startDate, endDate, bullets)

    private fun makeEducation(
        degree: String = "B.S.",
        school: String = "Test U",
        location: String? = null,
        startDate: String = "2010",
        endDate: String = "2014"
    ) = EducationEntry(degree, school, location, startDate, endDate)

    // ── renderForScoring ───────────────────────────────────────────────────────

    @Test
    @DisplayName("renderForScoring includes all sections")
    fun renderForScoringIncludesAllSections() {
        val profile = makeProfile(
            background = makeBackground(
                careerHistory = listOf(makeCareer()),
                education = listOf(makeEducation()),
                coreStrengths = listOf("Leadership"),
                languages = listOf("English"),
                domainExpertise = listOf("SaaS")
            ),
            projects = listOf(makeCareer(role = "Maintainer", company = "OSS")),
            preferences = CandidatePreferences(preferredWorkArrangement = "Remote")
        )

        val md = CandidateProfileRenderer.renderForScoring(profile)

        assertTrue(md.contains("**Name:** Alex Rivera"), "should include name")
        assertTrue(md.contains("### Career History"), "should include career history header")
        assertTrue(md.contains("### Projects"), "should include projects header")
        assertTrue(md.contains("### Core Strengths"), "should include core strengths")
        assertTrue(md.contains("### Skills"), "should include skills section")
        assertTrue(md.contains("**Languages:** English"), "should include languages")
        assertTrue(md.contains("**Domain Expertise:** SaaS"), "should include domain")
        assertTrue(md.contains("### Preferences"), "should include preferences")
    }

    @Test
    @DisplayName("renderForScoring omits bullet details in career and projects")
    fun renderForScoringOmitsBullets() {
        val profile = makeProfile(
            background = makeBackground(careerHistory = listOf(makeCareer())),
            projects = listOf(makeCareer(role = "Creator", company = "Side Project"))
        )
        val md = CandidateProfileRenderer.renderForScoring(profile)

        assertTrue(md.contains("| Role |"), "should use table header for career")
        assertFalse(md.contains("- Did thing A"), "should NOT contain bullet list in career")
        assertFalse(md.contains("- Did thing B"), "should NOT contain bullet list in projects")
    }

    @Test
    @DisplayName("renderForTailoring includes bullet lists under each career and project entry")
    fun renderForTailoringIncludesBullets() {
        val profile = makeProfile(
            background = makeBackground(careerHistory = listOf(makeCareer())),
            projects = listOf(makeCareer(role = "Maintainer", company = "OSS"))
        )
        val md = CandidateProfileRenderer.renderForTailoring(profile)

        assertTrue(md.contains("- Did thing A"), "should include career bullets")
        assertTrue(md.contains("- Did thing B"), "should include career bullets")
        assertTrue(md.contains("### Projects"), "should include projects section")
    }

    @Test
    @DisplayName("renderForTailoring omits the Preferences section entirely")
    fun renderForTailoringOmitsPreferences() {
        val profile = makeProfile(preferences = CandidatePreferences(preferredWorkArrangement = "Remote"))
        val md = CandidateProfileRenderer.renderForTailoring(profile)

        assertFalse(md.contains("### Preferences"), "should NOT contain preferences")
        assertFalse(md.contains("Preferred work arrangement"), "should NOT contain preference items")
    }

    @Test
    @DisplayName("empty collections produce correct output without stray headers")
    fun emptyCollectionsOmitHeaders() {
        val profile = makeProfile(
            background = makeBackground(
                careerHistory = emptyList(),
                education = emptyList(),
                coreStrengths = emptyList(),
                languages = emptyList(),
                domainExpertise = emptyList()
            ),
            projects = emptyList(),
            skills = CandidateSkills(
                primaryStack = emptyList(),
                mobileAutomation = emptyList(),
                ciCdPlatforms = emptyList(),
                webApiAutomation = emptyList(),
                infrastructureObservability = emptyList(),
                leadershipAbilities = emptyList()
            )
        )
        val md = CandidateProfileRenderer.renderForScoring(profile)

        assertFalse(md.contains("### Career History"), "should omit career history header")
        assertFalse(md.contains("### Projects"), "should omit projects header")
        assertFalse(md.contains("### Core Strengths"), "should omit core strengths header")
        assertFalse(md.contains("**Languages:**"), "should omit languages line")
        assertFalse(md.contains("**Domain Expertise:**"), "should omit domain line")
    }

    @Test
    @DisplayName("fullName fallback logic: first+last takes precedence over legacy name")
    fun fullNameFirstLastPrecedence() {
        val profile = makeProfile(
            identity = makeIdentity(name = "Legacy Name", firstName = "Alex", lastName = "Rivera")
        )
        val md = CandidateProfileRenderer.renderForScoring(profile)
        assertTrue(md.contains("**Name:** Alex Rivera"), "should prefer first+last")
    }

    @Test
    @DisplayName("fullName falls back to legacy name when first/last are blank")
    fun fullNameFallbackToLegacy() {
        val profile = makeProfile(
            identity = makeIdentity(name = "Legacy Name", firstName = "", lastName = "")
        )
        val md = CandidateProfileRenderer.renderForScoring(profile)
        assertTrue(md.contains("**Name:** Legacy Name"), "should fall back to legacy name")
    }

    @Test
    @DisplayName("fullName is blank when all name fields are empty")
    fun fullNameBlankWhenAllEmpty() {
        val profile = makeProfile(
            identity = makeIdentity(name = "", firstName = "", lastName = "")
        )
        val md = CandidateProfileRenderer.renderForScoring(profile)
        assertTrue(md.contains("**Name:** "), "should show blank name")
    }

    @Test
    @DisplayName("null endDate renders as Present in table format")
    fun nullEndDateRendersAsPresentInTable() {
        val profile = makeProfile(
            background = makeBackground(careerHistory = listOf(makeCareer(endDate = null)))
        )
        val md = CandidateProfileRenderer.renderForScoring(profile)
        assertTrue(md.contains("2020-01 – Present"), "should render Present in table")
    }

    @Test
    @DisplayName("null endDate renders as Present in bullet format")
    fun nullEndDateRendersAsPresentInBullets() {
        val profile = makeProfile(
            background = makeBackground(careerHistory = listOf(makeCareer(endDate = null)))
        )
        val md = CandidateProfileRenderer.renderForTailoring(profile)
        assertTrue(md.contains("2020-01 – Present"), "should render Present in bullets")
    }

    @Test
    @DisplayName("non-empty location appears after company name in bullet format")
    fun nonEmptyLocationInBullets() {
        val profile = makeProfile(
            background = makeBackground(careerHistory = listOf(makeCareer(location = "Seattle, WA")))
        )
        val md = CandidateProfileRenderer.renderForTailoring(profile)
        assertTrue(md.contains("Acme** (2020-01 – Present) · Seattle, WA"), "should append location")
    }

    @Test
    @DisplayName("empty/blank location shows — in table format")
    fun emptyLocationShowsDashInTable() {
        val profile = makeProfile(
            background = makeBackground(careerHistory = listOf(makeCareer(location = "")))
        )
        val md = CandidateProfileRenderer.renderForScoring(profile)
        // Table row: | Role | Company | — | Dates |
        assertTrue(md.contains("| Engineer | Acme | — |"), "should show em-dash for blank location")
    }
}
