package com.jd.pipeline.nodes

import com.jd.pipeline.config.Config
import com.jd.pipeline.models.CandidateProfile
import com.jd.pipeline.models.CandidateProfileConfig
import com.jd.pipeline.models.ProfileLoader
import com.jd.pipeline.models.ResumeYaml
import com.jd.pipeline.utils.ResumeHtmlRenderer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Onboarding node for `--init-profile`.
 *
 * The résumé is now authored as structured YAML (`resume.yaml`), so this no longer runs an
 * LLM or parses PDF/DOCX/HTML. It:
 *
 *  1. Validates the passed résumé YAML parses as [ResumeYaml].
 *  2. Installs it as the canonical `resume.yaml` (date-stamped backup of any prior copy).
 *  3. Scaffolds a slim `candidate_profile.yaml` from the committed template — the preferences
 *     + scoring aids a résumé can't supply (backing up any prior copy).
 *  4. Opens `$EDITOR` so the user replaces the `__TODO__` sentinels; refuses to proceed
 *     while any remain.
 *  5. Renders `generated_resume.html` (deterministically) + `TAILOR_SKILL.md` from the
 *     merged profile.
 */
class GenerateCandidateProfileNode(
    private val openEditor: (Path) -> Unit = ::openEditorImpl
) {

    /** Public entry point. Returns the path of the populated `candidate_profile.yaml`. */
    fun init(resumePath: Path): Path {
        require(Files.exists(resumePath)) { "Résumé not found: $resumePath" }

        println("[init_profile] Validating résumé YAML: ${resumePath.fileName}…")
        val resume = parseResume(resumePath)
        println("[init_profile] Parsed ${resume.experience.size} role(s), ${resume.skills.size} skill group(s).")

        installResume(resumePath)
        val profilePath = scaffoldProfileConfig(resume)

        val finalConfig = interactiveEdit(profilePath)

        val profile = ProfileLoader.merge(resume, finalConfig)
        renderGeneratedResume(profile)
        renderTailorSkill(profile)

        printSummary(profile, profilePath)
        return profilePath
    }

    // ── résumé parsing + install ───────────────────────────────────────────────

    internal fun parseResume(path: Path): ResumeYaml =
        try {
            ProfileLoader.YAML.readValue(Files.readString(path), ResumeYaml::class.java)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to parse résumé YAML at $path: ${e.message}", e)
        }

    private fun installResume(resumePath: Path) {
        val target = Config.RESUME_YAML_PATH
        if (resumePath.toAbsolutePath().normalize() == target.toAbsolutePath().normalize()) return
        Files.createDirectories(target.parent)
        backupIfExists(target)
        Files.copy(resumePath, target, StandardCopyOption.REPLACE_EXISTING)
        println("[init_profile] Installed résumé → $target")
    }

    // ── candidate_profile.yaml scaffold ────────────────────────────────────────

    private fun scaffoldProfileConfig(resume: ResumeYaml): Path {
        val out = Config.CANDIDATE_PROFILE_PATH
        Files.createDirectories(out.parent)
        backupIfExists(out)
        Files.writeString(out, Files.readString(Config.CANDIDATE_PROFILE_TEMPLATE_PATH))
        println("[init_profile] Wrote $out (from template)")
        println("[init_profile] years_experience is auto-computed from your résumé dates (${ProfileLoader.computeYearsExperience(resume)}).")
        return out
    }

    // ── interactive editor pass ────────────────────────────────────────────────

    internal fun interactiveEdit(path: Path): CandidateProfileConfig {
        val pending = findTodoFields(Files.readString(path))
        if (pending.isEmpty()) return parseConfig(path)

        println()
        println("[init_profile] ${pending.size} field(s) need your input:")
        pending.forEach { println("  - $it") }
        println("[init_profile] Opening $path in your \$EDITOR — replace each __TODO__ value, save, and exit.")
        println()

        openEditor(path)

        val stillPending = findTodoFields(Files.readString(path))
        if (stillPending.isNotEmpty()) {
            throw IllegalStateException(
                "Profile still has unresolved __TODO__ fields after editor closed:\n" +
                stillPending.joinToString("\n") { "  - $it" } + "\n" +
                "Edit $path manually and re-run --init-profile."
            )
        }
        return parseConfig(path)
    }

    private fun parseConfig(path: Path): CandidateProfileConfig =
        try {
            ProfileLoader.YAML.readValue(Files.readString(path), CandidateProfileConfig::class.java)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Edited $path is not valid YAML / does not match the schema: ${e.message}\nFix it and re-run.", e
            )
        }

    /** YAML keys whose value still contains a `__TODO__` sentinel. */
    internal fun findTodoFields(yaml: String): List<String> =
        yaml.lineSequence()
            .filter { it.contains("__TODO__") }
            .map { it.substringBefore(':').trim().trimStart('-', ' ') }
            .filter { it.isNotEmpty() }
            .toList()

    // ── downstream renders ─────────────────────────────────────────────────────

    internal fun renderGeneratedResume(profile: CandidateProfile) {
        val out = Config.GENERATED_RESUME_HTML_PATH
        backupIfExists(out)
        Files.writeString(out, ResumeHtmlRenderer.render(profile))
        println("[init_profile] Wrote $out")
    }

    internal fun renderTailorSkill(profile: CandidateProfile) {
        val out = Config.TAILOR_SKILL_PATH
        backupIfExists(out)
        val template = Files.readString(Config.TAILOR_SKILL_TEMPLATE_PATH)
        Files.writeString(out, template.replace(CANDIDATE_CONTEXT_PLACEHOLDER, buildCandidateContext(profile)))
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

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun backupIfExists(path: Path) {
        if (!Files.exists(path)) return
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val name = path.fileName.toString()
        val dot = name.lastIndexOf('.')
        val backupName = if (dot > 0) "${name.substring(0, dot)}.$timestamp${name.substring(dot)}.bak" else "$name.$timestamp.bak"
        Files.copy(path, path.resolveSibling(backupName), StandardCopyOption.REPLACE_EXISTING)
        println("[init_profile] Backed up existing ${path.fileName} → $backupName")
    }

    private fun printSummary(profile: CandidateProfile, profilePath: Path) {
        val skillCount = profile.skills.sumOf { it.items.size }
        println()
        println("✓ Wrote $profilePath, ${Config.GENERATED_RESUME_HTML_PATH.fileName}, ${Config.TAILOR_SKILL_PATH.fileName}.")
        println("  - Name:           ${profile.identity.fullName}")
        println("  - Target title:   ${profile.background.targetTitle.trim()}")
        println("  - Career entries: ${profile.background.careerHistory.size}")
        println("  - Skills:         $skillCount across ${profile.skills.size} group(s)")
        println("  - Work pref:      ${profile.preferences.preferredWorkArrangement}")
        println()
        println("Run `./gradlew run --args=\"--test-resume\"` to smoke-test the pipeline.")
    }

    companion object {
        const val CANDIDATE_CONTEXT_PLACEHOLDER = "{{CANDIDATE_CONTEXT}}"

        private fun openEditorImpl(path: Path) {
            val editor = System.getenv("VISUAL")
                ?: System.getenv("EDITOR")
                ?: if (System.getProperty("os.name").lowercase().contains("win")) "notepad" else "vi"
            val process = ProcessBuilder(editor, path.toString()).inheritIO().start()
            val exitCode = process.waitFor()
            if (exitCode != 0) throw IllegalStateException("$editor exited with code $exitCode while editing $path")
        }
    }
}
