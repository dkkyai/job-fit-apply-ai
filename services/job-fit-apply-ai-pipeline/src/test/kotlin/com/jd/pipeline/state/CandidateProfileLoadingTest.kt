package com.jd.pipeline.state

import com.jd.pipeline.models.*
import com.jd.pipeline.source.IntakeContext
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * Tests for the two-file YAML load + merge ([ProfileLoader]) and candidateProfile integration.
 */
@DisplayName("CandidateProfileLoadingTest")
class CandidateProfileLoadingTest {

    @Nested
    @DisplayName("ProfileLoader merge (resume.yaml + candidate_profile.yaml)")
    inner class ProfileLoaderTests {

        @Test
        @DisplayName("merge combines résumé content with config scoring aids + preferences")
        fun testMergeResumeAndConfig(@TempDir tempDir: Path) {
            val resumePath = tempDir.resolve("resume.yaml")
            Files.writeString(resumePath, """
                demographics:
                  first_name: Jane
                  last_name: Doe
                  email: jane@example.com
                  phone: "555-123-4567"
                  location: "Seattle, WA"
                  linkedin_url: "https://linkedin.com/in/janedoe"
                summary: "Senior SDET with 10 years building test infrastructure."
                experience:
                  - role: SDET
                    company: TestCo
                    start: 2014-01
                    end: 2024-01
                    bullets:
                      - category: Framework
                        text: Built test framework
                education:
                  - degree: B.S.
                    field: Computer Science
                    school: Test University
                    location: "Test City, TC"
                    year: 2014
                projects:
                  - role: Maintainer
                    company: OpenTest
                    start: 2022-06
                    bullets:
                      - category: OSS
                        text: OSS test runner used by 2k repos
                skills:
                  - label: Languages
                    items: [Kotlin, Java]
                  - label: Mobile
                    items: [Espresso, XCUITest]
                  - label: Domain Expertise
                    items: [Healthcare]
            """.trimIndent())

            val configPath = tempDir.resolve("candidate_profile.yaml")
            Files.writeString(configPath, """
                scoring:
                  target_title: "Senior SDET"
                  core_strengths: [Automation, "CI/CD"]
                preferences:
                  willing_to_relocate: false
                  visa_status: "US Citizen"
                  preferred_work_arrangement: "Remote"
            """.trimIndent())

            val resume = ProfileLoader.loadResume(resumePath)
            assertNotNull(resume)
            val profile = ProfileLoader.merge(resume, ProfileLoader.loadConfig(configPath))

            // Identity + résumé content come from resume.yaml
            assertEquals("Jane Doe", profile.identity.fullName)
            assertEquals("jane@example.com", profile.identity.email)
            assertEquals("https://linkedin.com/in/janedoe", profile.identity.linkedinUrl)
            assertTrue(profile.background.summary.isNotBlank())
            assertEquals("Test University", profile.background.education[0].school)
            assertEquals("2014", profile.background.education[0].year)
            assertEquals("TestCo", profile.background.careerHistory[0].company)
            assertEquals("2014-01", profile.background.careerHistory[0].startDate)
            assertEquals("2024-01", profile.background.careerHistory[0].endDate)
            assertEquals("Framework", profile.background.careerHistory[0].bullets[0].category)
            assertEquals("Built test framework", profile.background.careerHistory[0].bullets[0].text)
            assertEquals(1, profile.projects.size)
            assertEquals("OpenTest", profile.projects[0].company)
            assertEquals(listOf("Languages", "Mobile", "Domain Expertise"), profile.skills.map { it.label })
            assertEquals(listOf("Kotlin", "Java"), profile.skills[0].items)

            // years_experience is DERIVED from the résumé dates (2014 → 2024), not from the config
            assertEquals(10, profile.background.yearsExperience)
            // Scoring aids + preferences come from candidate_profile.yaml
            assertEquals("Senior SDET", profile.background.targetTitle)
            assertEquals(listOf("Automation", "CI/CD"), profile.background.coreStrengths)
            assertFalse(profile.preferences.willingToRelocate)
            assertEquals("US Citizen", profile.preferences.visaStatus)
            // languages + domain expertise are PARSED from the résumé's skill groups (label contains "language"/"domain")
            assertEquals(listOf("Kotlin", "Java"), profile.background.languages)
            assertEquals(listOf("Healthcare"), profile.background.domainExpertise)
        }

        @Test
        @DisplayName("loadResume returns null when the résumé file is missing")
        fun testLoadMissingResumeReturnsNull(@TempDir tempDir: Path) {
            assertNull(ProfileLoader.loadResume(tempDir.resolve("nonexistent.yaml")))
        }

        @Test
        @DisplayName("loadConfig falls back to defaults when the config file is missing")
        fun testLoadMissingConfigDefaults(@TempDir tempDir: Path) {
            val config = ProfileLoader.loadConfig(tempDir.resolve("nonexistent.yaml"))
            assertEquals("", config.scoring.targetTitle)
            assertTrue(config.scoring.coreStrengths.isEmpty())
        }

        @Test
        @DisplayName("computeYearsExperience spans earliest start to latest end")
        fun testComputeYearsExperience(@TempDir tempDir: Path) {
            val path = tempDir.resolve("resume.yaml")
            Files.writeString(path, """
                experience:
                  - role: Senior SDET
                    company: Acme
                    start: 2020-01
                    end: 2024-01
                  - role: SDET
                    company: Beta
                    start: 2016-01
                    end: 2020-01
            """.trimIndent())

            val resume = ProfileLoader.loadResume(path)
            assertNotNull(resume)
            assertEquals(8, ProfileLoader.computeYearsExperience(resume))       // 2024 − 2016
            assertEquals(0, ProfileLoader.computeYearsExperience(ResumeYaml())) // no experience → 0
        }
    }

    @Nested
    @DisplayName("CandidateIdentity.fullName fallback")
    inner class FullNameTests {

        @Test
        @DisplayName("fullName should fall back to name field when first/last are blank")
        fun testFullNameFallback() {
            val nameOnly = CandidateIdentity(
                name = "Legacy Name", firstName = "", lastName = "",
                email = "test@example.com", phone = "555-0000", location = "Remote"
            )
            assertEquals("Legacy Name", nameOnly.fullName)

            val firstLast = CandidateIdentity(
                name = "Legacy Name", firstName = "Jane", lastName = "Doe",
                email = "test@example.com", phone = "555-0000", location = "Remote"
            )
            assertEquals("Jane Doe", firstLast.fullName)
        }

        @Test
        @DisplayName("fullName should return empty string when all name fields are blank")
        fun testFullNameEmptyFallback() {
            val identity = CandidateIdentity(
                name = "", firstName = "", lastName = "",
                email = "test@example.com", phone = "555-0000", location = "Remote"
            )
            assertEquals("", identity.fullName)
        }
    }

    @Nested
    @DisplayName("JDState with candidateProfile Tests")
    inner class JDStateWithCandidateProfileTests {

        @Test
        @DisplayName("JDState constructor should accept candidateProfile")
        fun testJDStateAcceptsCandidateProfile() {
            val state = JDState(
                company = "TestCo",
                roleTitle = "Engineer",
                candidateProfile = createMinimalCandidateProfile()
            )
            assertNotNull(state.candidateProfile)
            assertEquals("John", state.candidateProfile?.identity?.firstName)
            assertEquals("Doe", state.candidateProfile?.identity?.lastName)
        }

        @Test
        @DisplayName("JDState copy should preserve candidateProfile")
        fun testCopyPreservesCandidateProfile() {
            val original = JDState(company = "TestCo", candidateProfile = createMinimalCandidateProfile())
            val copy = original.copy(fitScore = 95.0f)

            assertNotNull(copy.candidateProfile)
            assertEquals("John Doe", copy.candidateProfile?.identity?.fullName)
            assertEquals("TestCo", copy.company)
            assertEquals(95.0f, copy.fitScore)
        }

        @Test
        @DisplayName("fromEmail() should not crash regardless of whether the SINGLETON profile is loaded")
        fun testFromEmailIncludesCandidateProfile() {
            val state = JDState.fromEmail(
                emailId = "test-001",
                subject = "Job Opportunity",
                from = "recruiter@example.com",
                body = "We have a role for you"
            )
            val email = state.emailIntake
            assertNotNull(email)
            assertEquals("test-001", email.emailId)
            assertEquals("Job Opportunity", email.subject)
        }

        private fun createMinimalCandidateProfile(): CandidateProfile = CandidateProfile(
            identity = CandidateIdentity(
                name = "John Doe", firstName = "John", lastName = "Doe",
                email = "john@example.com", phone = "555-1234", location = "Seattle, WA"
            ),
            background = CandidateBackground(
                targetTitle = "Engineer",
                yearsExperience = 5,
                education = emptyList(),
                careerHistory = emptyList(),
                coreStrengths = emptyList(),
                languages = emptyList(),
                domainExpertise = emptyList()
            ),
            skills = emptyList()
        )
    }
}
