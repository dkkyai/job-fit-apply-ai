package com.jd.pipeline.utils

import com.jd.pipeline.models.Bullet
import com.jd.pipeline.models.CandidateBackground
import com.jd.pipeline.models.CandidateIdentity
import com.jd.pipeline.models.CandidateProfile
import com.jd.pipeline.models.CareerEntry
import com.jd.pipeline.models.EducationEntry
import com.jd.pipeline.models.SkillGroup
import com.jd.pipeline.nodes.tailor.TailoredProfile
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/**
 * Snapshot-style tests for the deterministic [ResumeHtmlRenderer]. Assert the output
 * reproduces the `generated_resume.html` structure exactly (header, per-role tables,
 * categorised bullets, date/`–`/`·` formatting, page-broken Skills), with no LLM.
 */
@DisplayName("ResumeHtmlRendererTest")
class ResumeHtmlRendererTest {

    private fun profile(projects: List<CareerEntry> = emptyList()) = CandidateProfile(
        identity = CandidateIdentity(
            name = "Richard Hatcher", firstName = "Richard", lastName = "Hatcher",
            email = "rich@example.com", phone = "555-0000", location = "Seattle, WA"
        ),
        background = CandidateBackground(
            targetTitle = "Staff SDET", yearsExperience = 15,
            summary = "Staff engineer & leader.",   // '&' exercises HTML escaping
            education = listOf(
                EducationEntry(
                    degree = "Bachelor of Science", fieldOfStudy = "Computer Engineering",
                    school = "Purdue University", location = "West Lafayette, IN", year = "2005"
                )
            ),
            careerHistory = listOf(
                CareerEntry(
                    role = "Staff Software Engineer", company = "Swiftly Systems", location = "Seattle, WA",
                    startDate = "2024-09", endDate = "2026-01",
                    bullets = listOf(Bullet("Technical Leadership & Strategy", "Led a team of 3."))
                ),
                CareerEntry(
                    role = "Engineer", company = "Acme", location = "Remote",
                    startDate = "2018-06", endDate = null,
                    bullets = listOf(Bullet("Delivery", "Shipped things."))
                )
            ),
            coreStrengths = emptyList(), languages = emptyList(), domainExpertise = emptyList()
        ),
        skills = listOf(
            SkillGroup("Core", listOf("Android", "Kotlin")),
            SkillGroup("CI/CD & DevOps", listOf("GitHub Actions"))
        ),
        projects = projects
    )

    @Test
    @DisplayName("renders the full generated_resume.html structure from a profile")
    fun rendersStructure() {
        val html = ResumeHtmlRenderer.render(profile())

        // Document skeleton, sentinel replaced
        assertContains(html, "<!DOCTYPE html>")
        assertFalse(html.contains("<!-- RESUME_BODY -->"), "body sentinel must be replaced")

        // Header
        assertContains(html, "<span class=\"resume-name\">Richard Hatcher</span>")
        assertContains(html, "<a href=\"mailto:rich@example.com\">rich@example.com</a>")

        // Sections
        assertContains(html, "<h2>Summary</h2>")
        assertContains(html, "<h2>Experience</h2>")
        assertContains(html, "<h2>Education</h2>")
        assertContains(html, "<div class=\"page-break\">")
        assertContains(html, "<h2>Skills</h2>")

        // Role table + categorised bullet + date formatting (YYYY-MM → M/YYYY, en-dash)
        assertContains(html, "<table class=\"full-page-table\">")
        assertContains(html, "<td class=\"left-align bold-text\">Staff Software Engineer</td>")
        assertContains(html, "9/2024 – 1/2026")
        assertContains(html, "<li><strong>Technical Leadership &amp; Strategy:</strong> Led a team of 3.</li>")

        // Active role → "Present"
        assertContains(html, "6/2018 – Present")

        // Education "Degree | Field"
        assertContains(html, "Bachelor of Science | Computer Engineering")

        // Skills group line with middot separators
        assertContains(html, "<p><strong>Core:</strong> Android · Kotlin</p>")

        // '&' escaped in summary
        assertContains(html, "Staff engineer &amp; leader.")

        // No projects section when empty
        assertFalse(html.contains("Independent Projects"), "projects section omitted when empty")
    }

    @Test
    @DisplayName("renders an Independent Projects section only when projects are present")
    fun rendersProjectsWhenPresent() {
        val html = ResumeHtmlRenderer.render(
            profile(projects = listOf(
                CareerEntry(
                    role = "Maintainer", company = "OpenTest", startDate = "2022-06", endDate = null,
                    bullets = listOf(Bullet("OSS", "Built a runner."))
                )
            ))
        )
        assertContains(html, "<h2>Independent Projects</h2>")
        assertContains(html, "<td class=\"left-align bold-text\">OpenTest</td>")
    }

    @Test
    @DisplayName("tailored skill groups lead each group with the JD-matched skills")
    fun tailoredSkillGroupsLeadWithJdMatches() {
        val base = profile()
        val tailored = TailoredProfile(
            base = base,
            summary = "Tailored summary.",
            careerHistory = base.background.careerHistory,
            projects = emptyList(),
            skillGroups = linkedMapOf("Languages" to listOf("Java", "Kotlin", "Swift")),
            jdMatchedSkills = listOf("Kotlin")
        )

        val html = ResumeHtmlRenderer.render(tailored)

        // Kotlin (JD-matched) leads, remaining items keep their order
        assertContains(html, "<p><strong>Languages:</strong> Kotlin · Java · Swift</p>")
        assertContains(html, "Tailored summary.")
    }
}
