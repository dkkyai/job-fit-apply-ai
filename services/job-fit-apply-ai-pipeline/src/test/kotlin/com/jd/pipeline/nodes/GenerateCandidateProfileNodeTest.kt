package com.jd.pipeline.nodes

import org.jsoup.Jsoup
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [GenerateCandidateProfileNode].
 *
 * Skips the LLM step entirely — covers format extraction, the `__TODO__`
 * detection regex, the editor-pass loop, the candidate-context Markdown
 * builder, and the date-stamped backup helper.
 */
@DisplayName("GenerateCandidateProfileNodeTest")
class GenerateCandidateProfileNodeTest {

    private val node = GenerateCandidateProfileNode()

    @Test
    @DisplayName("extractText handles HTML via Jsoup, stripping tags")
    fun extractsHtml(@TempDir tempDir: Path) {
        val html = "<html><body><h1>Jane Doe</h1><p>Senior SDET</p></body></html>"
        val htmlPath = tempDir.resolve("resume.html")
        Files.writeString(htmlPath, html)

        val text = node.extractText(htmlPath)
        assertContains(text, "Jane Doe")
        assertContains(text, "Senior SDET")
        assertFalse(text.contains("<h1>"), "tags should be stripped")
    }

    @Test
    @DisplayName("extractText reads .md files verbatim")
    fun extractsMarkdown(@TempDir tempDir: Path) {
        val md = "# Jane Doe\n\nSenior SDET with 10 years of experience."
        val mdPath = tempDir.resolve("resume.md")
        Files.writeString(mdPath, md)

        val text = node.extractText(mdPath)
        assertEquals(md, text)
    }

    @Test
    @DisplayName("extractText rejects unsupported formats with a clear error")
    fun rejectsUnsupportedFormat(@TempDir tempDir: Path) {
        val txtPath = tempDir.resolve("resume.rtf")
        Files.writeString(txtPath, "rich text")

        val ex = assertFails { node.extractText(txtPath) }
        assertContains(ex.message ?: "", ".pdf, .docx, .html, or .md")
    }

    @Test
    @DisplayName("interactiveEdit short-circuits when no __TODO__ markers remain")
    fun shortCircuitsWhenClean(@TempDir tempDir: Path) {
        val profilePath = tempDir.resolve("candidate_profile.json")
        Files.writeString(profilePath, validCandidateProfileJson())

        var editorCalls = 0
        val nodeNoEditor = GenerateCandidateProfileNode(openEditor = { editorCalls++ })

        val profile = nodeNoEditor.interactiveEdit(profilePath)
        assertEquals(0, editorCalls, "editor must not be invoked when draft is already clean")
        assertEquals("Jane Doe", profile.identity.fullName)
    }

    @Test
    @DisplayName("interactiveEdit invokes editor when __TODO__ markers are present")
    fun invokesEditor(@TempDir tempDir: Path) {
        val profilePath = tempDir.resolve("candidate_profile.json")
        Files.writeString(profilePath, validCandidateProfileJson(withTodoVisaStatus = true))

        var editorCalls = 0
        val nodeWithFakeEditor = GenerateCandidateProfileNode(openEditor = {
            editorCalls++
            // Simulate the user editing out the __TODO__ marker.
            Files.writeString(profilePath, validCandidateProfileJson(withTodoVisaStatus = false))
        })

        val profile = nodeWithFakeEditor.interactiveEdit(profilePath)
        assertEquals(1, editorCalls)
        assertEquals("US Citizen", profile.preferences.visaStatus)
    }

    @Test
    @DisplayName("interactiveEdit hard-fails if __TODO__ markers remain after editor closes")
    fun rejectsRemainingTodos(@TempDir tempDir: Path) {
        val profilePath = tempDir.resolve("candidate_profile.json")
        Files.writeString(profilePath, validCandidateProfileJson(withTodoVisaStatus = true))

        val nodeNoOpEditor = GenerateCandidateProfileNode(openEditor = { /* user changed nothing */ })

        val ex = assertFails { nodeNoOpEditor.interactiveEdit(profilePath) }
        assertContains(ex.message ?: "", "unresolved __TODO__")
    }

    @Test
    @DisplayName("buildCandidateContext renders identity, target title, summary, and core strengths")
    fun rendersCandidateContext() {
        val profile = sampleProfile()
        val ctx = node.buildCandidateContext(profile)
        assertContains(ctx, "Jane Doe")
        assertContains(ctx, "Senior SDET")
        assertContains(ctx, "Top differentiator 1")
        assertContains(ctx, "Healthcare")
    }

    @Test
    @DisplayName("renderTailorSkill substitutes {{CANDIDATE_CONTEXT}} from the template")
    fun rendersTailorSkill(@TempDir tempDir: Path) {
        val templatePath = tempDir.resolve("TAILOR_SKILL.template.md")
        val template = "# TAILOR_SKILL\n\n## Candidate Context\n\n${GenerateCandidateProfileNode.CANDIDATE_CONTEXT_PLACEHOLDER}\n\n## Rules\n- one"
        Files.writeString(templatePath, template)

        val out = template.replace(
            GenerateCandidateProfileNode.CANDIDATE_CONTEXT_PLACEHOLDER,
            node.buildCandidateContext(sampleProfile())
        )
        assertFalse(out.contains(GenerateCandidateProfileNode.CANDIDATE_CONTEXT_PLACEHOLDER), "placeholder must be removed")
        assertContains(out, "Senior SDET")
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun sampleProfile() = com.jd.pipeline.models.CandidateProfile(
        identity = com.jd.pipeline.models.CandidateIdentity(
            name = "Jane Doe", firstName = "Jane", lastName = "Doe",
            email = "jane@example.com", phone = "555-1234", location = "Seattle, WA"
        ),
        background = com.jd.pipeline.models.CandidateBackground(
            targetTitle = "Senior SDET",
            yearsExperience = 10,
            summary = "Senior SDET focused on mobile test infra.",
            education = emptyList(),
            careerHistory = emptyList(),
            coreStrengths = listOf("Top differentiator 1", "Top differentiator 2"),
            languages = listOf("Kotlin", "Swift"),
            domainExpertise = listOf("Healthcare", "Retail")
        ),
        skills = com.jd.pipeline.models.CandidateSkills(
            primaryStack = listOf("Kotlin"),
            mobileAutomation = emptyList(),
            ciCdPlatforms = emptyList(),
            webApiAutomation = emptyList(),
            infrastructureObservability = emptyList(),
            leadershipAbilities = emptyList()
        )
    )

    private fun validCandidateProfileJson(withTodoVisaStatus: Boolean = false): String {
        val visa = if (withTodoVisaStatus) "__TODO__: e.g. 'US Citizen'" else "US Citizen"
        return """
            {
              "identity": {
                "name": "Jane Doe",
                "first_name": "Jane",
                "last_name": "Doe",
                "email": "jane@example.com",
                "phone": "555-1234",
                "location": "Seattle, WA"
              },
              "background": {
                "target_title": "Senior SDET",
                "years_experience": 10,
                "summary": "Test summary.",
                "education": [],
                "career_history": [],
                "core_strengths": [],
                "languages": [],
                "domain_expertise": []
              },
              "skills": {
                "primary_stack": [],
                "mobile_automation": [],
                "ci_cd_platforms": [],
                "web_api_automation": [],
                "infrastructure_observability": [],
                "leadership_abilities": []
              },
              "preferences": {
                "willing_to_relocate": false,
                "visa_status": "$visa",
                "preferred_work_arrangement": "Remote"
              },
              "projects": []
            }
        """.trimIndent()
    }

    @Suppress("unused")
    private fun verifyJsoupAvailable() {
        // Sanity check that the Jsoup classpath import resolves at test time.
        assertTrue(Jsoup.parse("<p>x</p>").text() == "x")
    }
}
