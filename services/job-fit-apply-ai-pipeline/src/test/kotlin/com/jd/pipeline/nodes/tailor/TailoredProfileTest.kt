package com.jd.pipeline.nodes.tailor

import com.jd.pipeline.models.Bullet
import com.jd.pipeline.models.CandidateBackground
import com.jd.pipeline.models.CandidateIdentity
import com.jd.pipeline.models.CandidateProfile
import com.jd.pipeline.models.CareerEntry
import com.jd.pipeline.models.SkillGroup
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
                CareerEntry(role = "SDET", company = "Acme", startDate = "2020-01", bullets = listOf(Bullet("Impact", "Built X")))
            ),
            projects = listOf(
                CareerEntry(role = "Maintainer", company = "OpenTest", startDate = "2022-06", bullets = listOf(Bullet("OSS", "OSS test runner")))
            )
        )

        val tailored = TailoredProfile.untailored(profile)

        assertEquals("Original summary text.", tailored.summary)
        assertEquals(profile.background.careerHistory, tailored.careerHistory)
        assertEquals(profile.projects, tailored.projects)
        assertTrue(tailored.jdMatchedSkills.isEmpty())
    }

    @Test
    @DisplayName("untailored() carries labelled skill groups through by label, in order")
    fun untailoredMapsSkillGroups() {
        val profile = sampleProfile(
            skills = listOf(
                SkillGroup("Primary Stack", listOf("Kotlin", "Swift")),
                SkillGroup("Mobile Automation", listOf("Espresso", "XCUITest")),
                SkillGroup("Leadership", listOf("Mentoring"))
            )
        )

        val groups = TailoredProfile.untailored(profile).skillGroups

        assertEquals(listOf("Primary Stack", "Mobile Automation", "Leadership"), groups.keys.toList())
        assertEquals(listOf("Kotlin", "Swift"), groups["Primary Stack"])
        assertEquals(listOf("Espresso", "XCUITest"), groups["Mobile Automation"])
    }

    @Test
    @DisplayName("untailored() skips empty groups and emits an empty map when all groups are empty")
    fun untailoredHandlesEmptySkills() {
        val profile = sampleProfile(skills = listOf(SkillGroup("Primary Stack", emptyList())))
        assertTrue(TailoredProfile.untailored(profile).skillGroups.isEmpty())
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun sampleProfile(
        summary: String = "Default summary.",
        careerHistory: List<CareerEntry> = emptyList(),
        projects: List<CareerEntry> = emptyList(),
        skills: List<SkillGroup> = listOf(SkillGroup("Primary Stack", listOf("Kotlin")))
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
