package com.jd.pipeline.nodes

import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.PipelineAction
import java.nio.file.Files
import java.nio.file.Path

/**
 * Node: render_resume_pdf
 *
 * Renders tailored_resume.html to a PDF using Playwright headless Chromium.
 * Playwright is already a project dependency (driver-bundle excluded; browsers
 * installed separately via `playwright install`).
 */
class RenderResumePdfNode : Node<JDState> {

    override fun process(input: JDState): JDState {
        if (input.pipelineAction != PipelineAction.TAILOR || input.outputPath.isNullOrEmpty()) return input

        val outputPath = validateOutputPath(input.outputPath)
            ?: return input.copy(error = "render_pdf: invalid output path")

        val htmlPath = outputPath.resolve("tailored_resume.html")
        if (!Files.exists(htmlPath)) {
            return input.copy(error = "render_pdf: tailored_resume.html not found at $htmlPath")
        }

        val roleTitle = input.roleTitle ?: "Resume"
        val safeRoleTitle = com.jd.pipeline.utils.OutputUtils.sanitizeFileName(roleTitle, lowercase = false)
        val authorName = input.candidateProfile?.identity?.fullName?.ifBlank { "Resume" } ?: "Resume"
        val safeAuthor = com.jd.pipeline.utils.OutputUtils.sanitizeFileName(authorName, lowercase = false)
        val pdfFileName = "${safeAuthor}_${safeRoleTitle}.pdf"
        val pdfPath = outputPath.resolve(pdfFileName)

        println("[render_pdf] Rendering PDF for: $roleTitle @ ${input.company}")

        return try {
            Playwright.create().use { pw ->
                pw.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
                    browser.newPage().use { page ->
                        page.navigate("file://${htmlPath.toAbsolutePath()}")
                        page.pdf(Page.PdfOptions()
                            .setPath(pdfPath)
                            .setPrintBackground(true))
                    }
                }
            }
            println("[render_pdf] PDF saved to: $pdfPath")
            input.copy(resumeHtmlPdf = pdfPath.toString())
        } catch (e: Exception) {
            input.copy(error = "render_pdf: ${e.message}")
        }
    }

    /**
     * Validate and normalize the output path to prevent path traversal.
     * Returns a normalized path or null if invalid.
     */
    private fun validateOutputPath(outputPath: String): Path? {
        return try {
            val path = Path.of(outputPath).normalize()
            // Ensure the path doesn't escape the project directory
            val projectDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
            val resolved = path.toAbsolutePath().normalize()
            // Allow paths within project dir or in temp directories
            if (!resolved.startsWith(projectDir) && !resolved.startsWith(Path.of(System.getProperty("java.io.tmpdir")))) {
                System.err.println("[render_pdf] WARN: output path escapes project directory: $resolved")
                return null
            }
            path
        } catch (e: Exception) {
            System.err.println("[render_pdf] WARN: invalid output path: ${e.message}")
            null
        }
    }
}
