package com.jd.pipeline.nodes

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.client.LlmClient
import com.jd.pipeline.config.Config
import com.jd.pipeline.nodes.tailor.TailoredProfile
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.xwpf.extractor.XWPFWordExtractor
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.nio.file.Files
import java.nio.file.Path

/**
 * Generates a formatted HTML resume from a DOCX or PDF source file.
 *
 * Reads [Config.BASE_RESUME_TEMPLATE_PATH] as the structural template, extracts plain text
 * from the input document, then uses [Config.RESUME_GEN_MODEL] to produce an HTML file
 * that matches the template's structure and CSS exactly — with content replaced by the
 * source resume's data.
 *
 * Output is written to the same directory as base_resume.template.html, named
 * `<source-filename-without-extension>-generated.html`.
 */
class GenerateResumeHtmlNode(
    private val llm: LlmCaller = LlmClient.fromModelString(
        Config.RESUME_GEN_MODEL,
        jsonMode = false,
        temperature = 0.0,
        timeoutSeconds = 300,
        nodeKey = "resume_gen"
    )
) {

    private val mapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .enable(SerializationFeature.INDENT_OUTPUT)

    /** Build the JSON payload sent to the LLM. Pulls identity, education, etc. from
     *  `base` and the five tailored fields from the top level so the prompt makes
     *  the "tailored takes precedence" rule structurally obvious. */
    private fun buildPayload(tailored: TailoredProfile): Map<String, Any?> {
        val base = tailored.base
        return linkedMapOf(
            "identity" to base.identity,
            "education" to base.background.education,
            "languages" to base.background.languages,
            "domain_expertise" to base.background.domainExpertise,
            "core_strengths" to base.background.coreStrengths,
            "target_title" to base.background.targetTitle,
            "years_experience" to base.background.yearsExperience,
            // ── Tailored (override) fields ───────────────────────────────────
            "tailored_summary" to tailored.summary,
            "tailored_career_history" to tailored.careerHistory,
            "tailored_projects" to tailored.projects,
            "tailored_skill_groups" to tailored.skillGroups,
            "jd_matched_skills" to tailored.jdMatchedSkills
        )
    }

    fun generate(inputPath: Path): Path {
        val sourceText = extractText(inputPath)
        val generatedHtml = renderFromText(Config.BASE_RESUME_TEMPLATE_PATH, sourceText)
        val outputPath = Config.BASE_RESUME_TEMPLATE_PATH.parent
            .resolve("${fileNameWithoutExtension(inputPath)}-generated.html")
        Files.writeString(outputPath, generatedHtml)
        return outputPath
    }

    /**
     * Render an HTML resume from a structured [TailoredProfile] (the output of
     * `ResumeTailoringSubgraph`). The five tailored fields take precedence over
     * the corresponding fields in `tailored.base`:
     *   - `tailored.summary` overrides `base.background.summary`
     *   - `tailored.careerHistory` overrides `base.background.careerHistory`
     *   - `tailored.projects` overrides `base.projects`
     *   - `tailored.skillGroups` is rendered as the Skills section (overrides the
     *     six fixed `base.skills.*` buckets)
     *   - `tailored.jdMatchedSkills` is rendered first within each skill group
     *
     * Returns the LLM's HTML output without writing it to disk — callers decide
     * the destination path.
     */
    fun renderFromProfile(tailored: TailoredProfile): String {
        val payload = mapper.writeValueAsString(buildPayload(tailored))
        return renderFromText(
            templatePath = Config.BASE_RESUME_TEMPLATE_PATH,
            sourceText = "CANDIDATE PROFILE — TAILORED (authoritative; the five tailored fields below take precedence over the base profile)\n\n$payload"
        )
    }

    /**
     * Render an HTML resume by combining a structural template with arbitrary
     * source text (parsed-resume text, a serialized candidate profile, or both).
     * Returns the LLM's HTML output without writing it to disk — callers decide
     * the destination path.
     */
    fun renderFromText(templatePath: Path, sourceText: String): String {
        val templateHtml = Files.readString(templatePath)
        val skillPrompt = loadSkillPrompt()
        val prompt = """
            $skillPrompt

            === TEMPLATE HTML ===
            $templateHtml

            === SOURCE RESUME TEXT ===
            $sourceText
        """.trimIndent()
        println("[resume_gen] Calling ${Config.RESUME_GEN_MODEL} to generate HTML…")
        return llm.call(prompt)
    }

    private fun extractText(path: Path): String = when {
        path.toString().endsWith(".docx", ignoreCase = true) -> extractDocx(path)
        path.toString().endsWith(".pdf",  ignoreCase = true) -> extractPdf(path)
        else -> throw IllegalArgumentException(
            "Unsupported format: ${path.fileName} — pass a .docx or .pdf file"
        )
    }

    internal fun extractDocx(path: Path): String {
        XWPFDocument(Files.newInputStream(path)).use { doc ->
            return XWPFWordExtractor(doc).text
        }
    }

    internal fun extractPdf(path: Path): String {
        Loader.loadPDF(path.toFile()).use { doc ->
            return PDFTextStripper().getText(doc)
        }
    }

    private fun loadSkillPrompt(): String = runCatching {
        if (Files.exists(Config.RESUME_GEN_SKILL)) Files.readString(Config.RESUME_GEN_SKILL)
        else DEFAULT_PROMPT
    }.getOrDefault(DEFAULT_PROMPT)

    private fun fileNameWithoutExtension(path: Path): String {
        val name = path.fileName.toString()
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    companion object {
        private val DEFAULT_PROMPT = """
            You will receive an HTML template resume and a plain-text source resume.
            Produce a complete HTML document with the EXACT SAME structure and CSS as the
            template, but with all personal content replaced by the source resume's content.
            DO NOT modify the <style> block. Output ONLY the HTML starting with <!DOCTYPE html>.
        """.trimIndent()
    }
}
