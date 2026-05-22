package com.jd.pipeline.nodes

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.jd.pipeline.client.LlmClient
import com.jd.pipeline.config.Config
import com.jd.pipeline.models.CandidateProfile
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.xwpf.extractor.XWPFWordExtractor
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.jsoup.Jsoup
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Onboarding node for `--init-profile`.
 *
 * Parses a candidate's resume (PDF, DOCX, HTML, MD), produces a populated
 * `config/candidate_profile.json`, and renders the two downstream personalised
 * files (`generated_resume.html`, `TAILOR_SKILL.md`) from their committed
 * templates.
 *
 * Flow:
 *  1. Extract plain text from the resume (format-dispatched).
 *  2. Call PROFILE_GEN_MODEL with PROFILE_GEN_SKILL.md → JSON draft.
 *  3. Validate by deserialising into [CandidateProfile].
 *  4. Write `config/candidate_profile.json` (date-stamped backup of any prior copy).
 *  5. Open the user's `$EDITOR` so they can replace the `__TODO__` sentinels.
 *  6. Re-validate; refuse to proceed while any `__TODO__` strings remain.
 *  7. Render `generated_resume.html` from `base_resume.template.html` + profile.
 *  8. Render `TAILOR_SKILL.md` from `TAILOR_SKILL.template.md` + a context block.
 */
class GenerateCandidateProfileNode(
    private val openEditor: (Path) -> Unit = ::openEditorImpl
) {

    private val mapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .enable(SerializationFeature.INDENT_OUTPUT)

    private val llm = LlmClient.fromModelString(
        Config.PROFILE_GEN_MODEL,
        jsonMode = true,
        temperature = 0.0,
        nodeKey = "profile_gen"
    )

    private val resumeRenderer = GenerateResumeHtmlNode()

    /** Public entry point. Returns the path of the populated `candidate_profile.json`. */
    fun init(resumePath: Path): Path {
        require(Files.exists(resumePath)) { "Resume not found: $resumePath" }

        println("[init_profile] Extracting text from ${resumePath.fileName}…")
        val resumeText = extractText(resumePath)
        require(resumeText.isNotBlank()) { "No text extracted from $resumePath" }

        println("[init_profile] Calling ${Config.PROFILE_GEN_MODEL} to parse profile JSON…")
        val draftProfile = parseProfile(resumeText)

        val profilePath = Config.CANDIDATE_PROFILE_PATH
        backupIfExists(profilePath)
        Files.writeString(profilePath, draftJsonFor(draftProfile))
        println("[init_profile] Wrote draft to $profilePath")

        val finalProfile = interactiveEdit(profilePath)

        renderGeneratedResume(finalProfile, resumeText)
        renderTailorSkill(finalProfile)

        printSummary(finalProfile, profilePath)
        return profilePath
    }

    // ── Resume extraction ────────────────────────────────────────────────────

    internal fun extractText(path: Path): String {
        val name = path.fileName.toString().lowercase()
        return when {
            name.endsWith(".pdf") -> extractPdf(path)
            name.endsWith(".docx") -> extractDocx(path)
            name.endsWith(".html") || name.endsWith(".htm") -> extractHtml(path)
            name.endsWith(".md") || name.endsWith(".markdown") -> Files.readString(path)
            else -> throw IllegalArgumentException(
                "Unsupported format: ${path.fileName} — pass .pdf, .docx, .html, or .md"
            )
        }
    }

    internal fun extractPdf(path: Path): String =
        Loader.loadPDF(path.toFile()).use { PDFTextStripper().getText(it) }

    internal fun extractDocx(path: Path): String =
        XWPFDocument(Files.newInputStream(path)).use { XWPFWordExtractor(it).text }

    internal fun extractHtml(path: Path): String =
        Jsoup.parse(Files.readString(path)).text()

    // ── LLM call + JSON validation ───────────────────────────────────────────

    internal fun parseProfile(resumeText: String): CandidateProfile {
        val skillPrompt = loadSkillPrompt()
        val prompt = """
            $skillPrompt

            === RESUME TEXT ===
            $resumeText
        """.trimIndent()
        val response = llm.call(prompt)
        return validateOrDumpDraft(response)
    }

    private fun loadSkillPrompt(): String =
        if (Files.exists(Config.PROFILE_GEN_SKILL)) Files.readString(Config.PROFILE_GEN_SKILL)
        else error("Missing prompt file: ${Config.PROFILE_GEN_SKILL}")

    private fun validateOrDumpDraft(rawResponse: String): CandidateProfile {
        val cleaned = rawResponse.replace(Regex("```(?:json)?"), "").trim()
        return try {
            mapper.readValue(cleaned, CandidateProfile::class.java)
        } catch (e: Exception) {
            val draftPath = Config.CANDIDATE_PROFILE_PATH.resolveSibling("candidate_profile.draft.json")
            Files.writeString(draftPath, cleaned)
            throw IllegalStateException(
                "Failed to parse LLM response as CandidateProfile: ${e.message}\n" +
                "Raw response written to $draftPath — fix and re-run with --init-profile.",
                e
            )
        }
    }

    private fun draftJsonFor(profile: CandidateProfile): String =
        mapper.writeValueAsString(profile)

    // ── Interactive editor pass ──────────────────────────────────────────────

    internal fun interactiveEdit(path: Path): CandidateProfile {
        val initial = Files.readString(path)
        val pendingFields = findTodoFields(initial)
        if (pendingFields.isEmpty()) return mapper.readValue(initial, CandidateProfile::class.java)

        println()
        println("[init_profile] ${pendingFields.size} preference field(s) need your input:")
        pendingFields.forEach { println("  - $it") }
        println("[init_profile] Opening $path in your \$EDITOR — replace each __TODO__ value, save, and exit.")
        println()

        openEditor(path)

        val edited = Files.readString(path)
        val stillPending = findTodoFields(edited)
        if (stillPending.isNotEmpty()) {
            throw IllegalStateException(
                "Profile still has unresolved __TODO__ fields after editor closed:\n" +
                stillPending.joinToString("\n") { "  - $it" } + "\n" +
                "Edit $path manually and re-run --init-profile."
            )
        }
        return try {
            mapper.readValue(edited, CandidateProfile::class.java)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Edited profile is not valid JSON / does not match schema: ${e.message}\n" +
                "Fix $path and re-run.", e
            )
        }
    }

    /** Returns JSON keys whose value is a `__TODO__` sentinel string. */
    private fun findTodoFields(json: String): List<String> {
        val regex = Regex("\"([^\"]+)\"\\s*:\\s*\"__TODO__[^\"]*\"")
        return regex.findAll(json).map { it.groupValues[1] }.toList()
    }

    // ── Downstream renders ───────────────────────────────────────────────────

    internal fun renderGeneratedResume(profile: CandidateProfile, resumeText: String) {
        val out = Config.GENERATED_RESUME_HTML_PATH
        backupIfExists(out)
        // Reuse RESUME_GEN_SKILL flow: template + source-resume-text → HTML.
        // The template lives at Config.BASE_RESUME_TEMPLATE_PATH; the LLM uses
        // candidateProfile + the original resume text to fill it in.
        val rendered = resumeRenderer.renderFromText(
            templatePath = Config.BASE_RESUME_TEMPLATE_PATH,
            sourceText = "CANDIDATE_PROFILE (authoritative):\n${mapper.writeValueAsString(profile)}\n\n" +
                    "ORIGINAL RESUME TEXT (context only):\n$resumeText"
        )
        Files.writeString(out, rendered)
        println("[init_profile] Wrote $out")
    }

    internal fun renderTailorSkill(profile: CandidateProfile) {
        val out = Config.TAILOR_SKILL_PATH
        backupIfExists(out)
        val template = Files.readString(Config.TAILOR_SKILL_TEMPLATE_PATH)
        val context = buildCandidateContext(profile)
        Files.writeString(out, template.replace(CANDIDATE_CONTEXT_PLACEHOLDER, context))
        println("[init_profile] Wrote $out")
    }

    /** Markdown block dropped into TAILOR_SKILL.md's `{{CANDIDATE_CONTEXT}}` slot. */
    internal fun buildCandidateContext(profile: CandidateProfile): String = buildString {
        val name = profile.identity.fullName.ifBlank { "The candidate" }
        val target = profile.background.targetTitle.trim().ifBlank { "(target role unspecified)" }
        append("**$name** is targeting **$target** roles.\n\n")
        if (profile.background.summary.isNotBlank()) {
            append("**Summary:** ${profile.background.summary}\n\n")
        }
        if (profile.background.coreStrengths.isNotEmpty()) {
            append("**Core differentiators to emphasise:**\n")
            profile.background.coreStrengths.forEach { append("- $it\n") }
            append("\n")
        }
        if (profile.background.domainExpertise.isNotEmpty()) {
            append("**Domain expertise:** ${profile.background.domainExpertise.joinToString(", ")}\n")
        }
    }.trimEnd()

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun backupIfExists(path: Path) {
        if (!Files.exists(path)) return
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val name = path.fileName.toString()
        val dot = name.lastIndexOf('.')
        val backupName = if (dot > 0) {
            "${name.substring(0, dot)}.$timestamp${name.substring(dot)}.bak"
        } else {
            "$name.$timestamp.bak"
        }
        val backupPath = path.resolveSibling(backupName)
        Files.copy(path, backupPath, StandardCopyOption.REPLACE_EXISTING)
        println("[init_profile] Backed up existing ${path.fileName} → ${backupPath.fileName}")
    }

    private fun printSummary(profile: CandidateProfile, profilePath: Path) {
        println()
        println("✓ Wrote $profilePath, ${Config.GENERATED_RESUME_HTML_PATH.fileName}, ${Config.TAILOR_SKILL_PATH.fileName}.")
        println("  - Name:           ${profile.identity.fullName}")
        println("  - Target title:   ${profile.background.targetTitle.trim()}")
        println("  - Career entries: ${profile.background.careerHistory.size}")
        println("  - Skills:         ${profile.skills.primaryStack.size} primary, ${profile.skills.mobileAutomation.size + profile.skills.ciCdPlatforms.size + profile.skills.webApiAutomation.size + profile.skills.infrastructureObservability.size + profile.skills.leadershipAbilities.size} other")
        println("  - Work pref:      ${profile.preferences.preferredWorkArrangement}")
        println()
        println("Run `./gradlew run --args=\"--test\"` to smoke-test the pipeline.")
    }

    companion object {
        const val CANDIDATE_CONTEXT_PLACEHOLDER = "{{CANDIDATE_CONTEXT}}"

        private fun openEditorImpl(path: Path) {
            val editor = System.getenv("VISUAL")
                ?: System.getenv("EDITOR")
                ?: if (System.getProperty("os.name").lowercase().contains("win")) "notepad" else "vi"
            val process = ProcessBuilder(editor, path.toString())
                .inheritIO()
                .start()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw IllegalStateException(
                    "$editor exited with code $exitCode while editing $path"
                )
            }
        }
    }
}
