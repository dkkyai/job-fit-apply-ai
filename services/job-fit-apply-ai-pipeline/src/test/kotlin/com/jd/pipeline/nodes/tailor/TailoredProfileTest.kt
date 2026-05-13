package com.jd.pipeline.nodes.tailor

import com.jd.pipeline.models.CandidateBackground
import com.jd.pipeline.models.CandidateIdentity
import com.jd.pipeline.models.CandidateProfile
import com.jd.pipeline.models.CandidateSkills
import com.jd.pipeline.models.CareerEntry
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("TailoredProfileTest")
class TailoredProfileTest {

    @Test
    @DisplayName("untailored() mirrors base summary, career_history, and projects verbatim")
    fun untailoredMirrorsBase() {
        val profile = sampleProfile(
            summary = "Original summary text.",
            careerHistory = listOf(
                CareerEntry(role = "SDET", company = "Acme", startDate = "2020-01", bullets = listOf("Built X"))
            ),
            projects = listOf(
                CareerEntry(role = "Maintainer", company = "OpenTest", startDate = "2022-06", bullets = listOf("OSS test runner"))
            )
        )

        val tailored = TailoredProfile.untailored(profile)

        assertEquals("Original summary text.", tailored.summary)
        assertEquals(profile.background.careerHistory, tailored.careerHistory)
        assertEquals(profile.projects, tailored.projects)
        assertTrue(tailored.jdMatchedSkills.isEmpty())
    }

    @Test
    @DisplayName("untailored() maps populated skill buckets to friendly category names")
    fun untailoredMapsSkillBuckets() {
        val profile = sampleProfile(
            skills = CandidateSkills(
                primaryStack = listOf("Kotlin", "Swift"),
                mobileAutomation = listOf("Espresso", "XCUITest"),
                ciCdPlatforms = emptyList(),
                webApiAutomation = emptyList(),
                infrastructureObservability = emptyList(),
                leadershipAbilities = listOf("Mentoring")
            )
        )

        val groups = TailoredProfile.untailored(profile).skillGroups

        // Empty buckets are skipped; populated buckets get friendly names
        assertEquals(setOf("Primary Stack", "Mobile Automation", "Leadership"), groups.keys)
        assertEquals(listOf("Kotlin", "Swift"), groups["Primary Stack"])
        assertEquals(listOf("Espresso", "XCUITest"), groups["Mobile Automation"])
    }

    @Test
    @DisplayName("untailored() emits an empty skillGroups map when every bucket is empty")
    fun untailoredHandlesEmptySkills() {
        val profile = sampleProfile(skills = CandidateSkills(
            primaryStack = emptyList(),
            mobileAutomation = emptyList(),
            ciCdPlatforms = emptyList(),
            webApiAutomation = emptyList(),
            infrastructureObservability = emptyList(),
            leadershipAbilities = emptyList()
        ))
        assertTrue(TailoredProfile.untailored(profile).skillGroups.isEmpty())
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun sampleProfile(
        summary: String = "Default summary.",
        careerHistory: List<CareerEntry> = emptyList(),
        projects: List<CareerEntry> = emptyList(),
        skills: CandidateSkills = CandidateSkills(
            primaryStack = listOf("Kotlin"),
            mobileAutomation = emptyList(),
            ciCdPlatforms = emptyList(),
            webApiAutomation = emptyList(),
            infrastructureObservability = emptyList(),
            leadershipAbilities = emptyList()
        )
    ) = CandidateProfile(
        identity = CandidateIdentity(
            name = "Jane Doe", firstName = "Jane", lastName = "Doe",
            email = "jane@example.com", phone = "555-1234", location = "Seattle, WA"
        ),
        background = CandidateBackground(
            targetTitle = "SDET",
            yearsExperience = 5,
            summary = summary,
            education = emptyList(),
            careerHistory = careerHistory,
            coreStrengths = emptyList(),
            languages = emptyList(),
            domainExpertise = emptyList()
        ),
        skills = skills,
        projects = projects
    )
}
