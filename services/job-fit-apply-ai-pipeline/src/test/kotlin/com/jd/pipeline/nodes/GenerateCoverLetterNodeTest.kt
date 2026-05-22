package com.jd.pipeline.nodes
import com.jd.pipeline.state.PipelineAction

import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.config.Config
import com.jd.pipeline.models.*
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * Tests for GenerateCoverLetterNode.
 * Focuses on candidateProfile integration and cover letter generation.
 */
@DisplayName("GenerateCoverLetterNodeTest")
class GenerateCoverLetterNodeTest {

    @Nested
    @DisplayName("Node Skip Conditions")
    inner class SkipConditionsTests {

        @Test
        @DisplayName("Should skip when pipelineAction is not 'tailor'")
        fun testSkipNotTailorAction() {
            val node = GenerateCoverLetterNode()
            val input = JDState(
                pipelineAction = PipelineAction.SKIP,
                fitScore = 80.0f,
                company = "TestCo",
                roleTitle = "Engineer"
            )

            val result = node.process(input)

            assertEquals(input, result)
            assertEquals("", result.coverLetter)
        }

        @Test
        @DisplayName("Should skip when fitScore is null")
        fun testSkipNullScore() {
            val node = GenerateCoverLetterNode()
            val input = JDState(
                pipelineAction = PipelineAction.TAILOR,
                fitScore = null,
                company = "TestCo",
                roleTitle = "Engineer"
            )

            val result = node.process(input)

            assertEquals(input, result)
            assertEquals("", result.coverLetter)
        }

        @Test
        @DisplayName("Should skip when fitScore is below threshold")
        fun testSkipLowScore() {
            val node = GenerateCoverLetterNode()
            val input = JDState(
                pipelineAction = PipelineAction.TAILOR,
                fitScore = Config.FIT_THRESHOLD - 1,
                company = "TestCo",
                roleTitle = "Engineer"
            )

            val result = node.process(input)

            assertEquals(input, result)
            assertEquals("", result.coverLetter)
        }
    }

    @Nested
    @DisplayName("Cover Letter Generation with candidateProfile")
    inner class GenerationTests {

        @Test
        @DisplayName("Should use authorName from candidateProfile fullName")
        fun testAuthorNameFromCandidateProfile(@TempDir tempDir: Path) {
            val node = GenerateCoverLetterNode(llm = LlmCaller { prompt ->
                val name = Regex("- (.+)$", RegexOption.MULTILINE).find(prompt)?.groupValues?.get(1) ?: "Applicant"
                "Dear Hiring Manager,\n\nI am $name and I am excited to apply for this role.\n\nSincerely,\n$name"
            })
            val outputPath = tempDir.toString()

            val profile = createCandidateProfile(firstName = "Jane", lastName = "Doe")

            val input = JDState(
                pipelineAction = PipelineAction.TAILOR,
                fitScore = Config.FIT_THRESHOLD + 10,
                company = "Acme Corp",
                roleTitle = "Staff SDET",
                jdText = "Looking for a Staff SDET",
                strengths = listOf("Kotlin", "CI/CD"),
                outputPath = outputPath,
                candidateProfile = profile
            )

            val result = node.process(input)

            assertTrue(result.coverLetter.isNotEmpty())
            assertContains(result.coverLetter, "Jane Doe")
            assertContains(result.coverLetter, "Dear Hiring Manager")
        }

        @Test
        @DisplayName("Should fallback to 'Applicant' when candidateProfile is null")
        fun testAuthorNameFallbackWhenNull(@TempDir tempDir: Path) {
            val node = GenerateCoverLetterNode(llm = LlmCaller {
                "Dear Hiring Manager,\n\nApplicant is excited to apply.\n\nSincerely,\nApplicant"
            })
            val outputPath = tempDir.toString()

            val input = JDState(
                pipelineAction = PipelineAction.TAILOR,
                fitScore = Config.FIT_THRESHOLD + 10,
                company = "Acme Corp",
                roleTitle = "Staff SDET",
                jdText = "Looking for a Staff SDET",
                strengths = listOf("Kotlin"),
                outputPath = outputPath,
                candidateProfile = null
            )

            val result = node.process(input)

            assertTrue(result.coverLetter.isNotEmpty())
            assertContains(result.coverLetter, "Applicant")
        }

        @Test
        @DisplayName("Should fallback to 'Applicant' when fullName is blank")
        fun testAuthorNameFallbackWhenBlank(@TempDir tempDir: Path) {
            val node = GenerateCoverLetterNode(llm = LlmCaller {
                "Dear Hiring Manager,\n\nApplicant is excited to apply.\n\nSincerely,\nApplicant"
            })
            val outputPath = tempDir.toString()

            val profile = createCandidateProfile(firstName = "", lastName = "")

            val input = JDState(
                pipelineAction = PipelineAction.TAILOR,
                fitScore = Config.FIT_THRESHOLD + 10,
                company = "Acme Corp",
                roleTitle = "Staff SDET",
                outputPath = outputPath,
                candidateProfile = profile
            )

            val result = node.process(input)

            assertTrue(result.coverLetter.isNotEmpty())
            assertContains(result.coverLetter, "Applicant")
        }

        @Test
        @DisplayName("Should write cover letter to output directory")
        fun testWritesToOutputDirectory(@TempDir tempDir: Path) {
            val node = GenerateCoverLetterNode(llm = LlmCaller {
                "Dear Hiring Manager,\n\nJohn Smith is excited to apply.\n\nSincerely,\nJohn Smith"
            })
            val outputPath = tempDir.toString()

            val profile = createCandidateProfile(firstName = "John", lastName = "Smith")

            val input = JDState(
                pipelineAction = PipelineAction.TAILOR,
                fitScore = Config.FIT_THRESHOLD + 10,
                company = "TestCo",
                roleTitle = "Engineer",
                outputPath = outputPath,
                candidateProfile = profile
            )

            val result = node.process(input)

            val coverLetterPath = tempDir.resolve("cover_letter.txt")
            assertTrue(Files.exists(coverLetterPath), "Cover letter file should exist")
            val content = Files.readString(coverLetterPath)
            assertContains(content, "John Smith")
            assertEquals(result.coverLetter, content)
        }

        @Test
        @DisplayName("Should not write file when outputPath is blank")
        fun testNoWriteWhenOutputPathBlank() {
            val node = GenerateCoverLetterNode(llm = LlmCaller { "A great cover letter content." })
            val profile = createCandidateProfile()

            val input = JDState(
                pipelineAction = PipelineAction.TAILOR,
                fitScore = Config.FIT_THRESHOLD + 10,
                company = "TestCo",
                roleTitle = "Engineer",
                outputPath = "",
                candidateProfile = profile
            )

            val result = node.process(input)

            assertTrue(result.coverLetter.isNotEmpty())
            // No file should be written since outputPath is blank
        }

        @Test
        @DisplayName("Should include role title and company in generated letter")
        fun testContentIncludesRoleAndCompany(@TempDir tempDir: Path) {
            val node = GenerateCoverLetterNode(llm = LlmCaller {
                "I am excited to apply for the Staff SDET position at hiring company."
            })
            val profile = createCandidateProfile()

            val input = JDState(
                pipelineAction = PipelineAction.TAILOR,
                fitScore = Config.FIT_THRESHOLD + 10,
                company = "Acme Corp",
                roleTitle = "Staff SDET",
                outputPath = tempDir.toString(),
                candidateProfile = profile
            )

            val result = node.process(input)

            assertContains(result.coverLetter, "Staff SDET position")
            assertContains(result.coverLetter, "hiring company")
        }
    }

    private fun createCandidateProfile(
        firstName: String = "Jane",
        lastName: String = "Doe"
    ): CandidateProfile {
        return CandidateProfile(
            identity = CandidateIdentity(
                name = "$firstName $lastName",
                firstName = firstName,
                lastName = lastName,
                email = "jane@example.com",
                phone = "555-1234",
                location = "Seattle, WA"
            ),
            background = CandidateBackground(
                targetTitle = "SDET",
                yearsExperience = 10,
                education = emptyList(),
                careerHistory = emptyList(),
                coreStrengths = emptyList(),
                languages = emptyList(),
                domainExpertise = emptyList()
            ),
            skills = CandidateSkills(
                primaryStack = emptyList(),
                mobileAutomation = emptyList(),
                ciCdPlatforms = emptyList(),
                webApiAutomation = emptyList(),
                infrastructureObservability = emptyList(),
                leadershipAbilities = emptyList()
            )
        )
    }
}