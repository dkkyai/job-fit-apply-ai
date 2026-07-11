package com.jd.pipeline.nodes

import com.jd.pipeline.config.Config
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.PipelineAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * Node: render_resume_pdf
 *
 * Renders tailored_resume.yaml (written by the tailoring subgraph) to a PDF via LaTeX:
 * yaml_to_tex.py fills resume_template.tex.jinja → tailored_resume.tex, copies the
 * bundled fonts/ next to it, and compiles with tectonic (`--pdf`). No browser involved —
 * this replaced the HTML → headless-Chromium print path so the Processor container needs
 * no Chromium. The .tex is kept in the job dir for debugging; the per-job fonts/ copy is
 * removed after a successful compile so markserv-served artifact dirs stay clean.
 */
class RenderResumePdfNode(
    private val pythonBin: String = Config.PYTHON_BIN,
    private val script: Path = Config.YAML_TO_TEX_SCRIPT,
    private val timeoutMs: Long = Config.RENDER_PDF_TIMEOUT_MS,
) : Node<JDState> {

    override fun process(input: JDState): JDState {
        if (input.pipelineAction != PipelineAction.TAILOR || input.outputPath.isNullOrEmpty()) return input

        val outputPath = validateOutputPath(input.outputPath)
            ?: return input.copy(error = "render_pdf: invalid output path")

        val yamlPath = outputPath.resolve("tailored_resume.yaml")
        if (!Files.exists(yamlPath)) {
            return input.copy(error = "render_pdf: tailored_resume.yaml not found at $yamlPath")
        }
        if (!Files.exists(script)) {
            return input.copy(error = "render_pdf: yaml_to_tex.py not found at $script")
        }

        val roleTitle = input.roleTitle ?: "Resume"
        val safeRoleTitle = com.jd.pipeline.utils.OutputUtils.sanitizeFileName(roleTitle, lowercase = false)
        val authorName = input.candidateProfile?.identity?.fullName?.ifBlank { "Resume" } ?: "Resume"
        val safeAuthor = com.jd.pipeline.utils.OutputUtils.sanitizeFileName(authorName, lowercase = false)
        val pdfFileName = "${safeAuthor}_${safeRoleTitle}.pdf"
        val pdfPath = outputPath.resolve(pdfFileName)

        println("[render_pdf] Rendering PDF (LaTeX) for: $roleTitle @ ${input.company}")

        return try {
            runYamlToTex(outputPath)
            val compiled = outputPath.resolve("tailored_resume.pdf")
            if (!Files.exists(compiled)) {
                return input.copy(error = "render_pdf: tectonic produced no tailored_resume.pdf in $outputPath")
            }
            Files.move(compiled, pdfPath, StandardCopyOption.REPLACE_EXISTING)
            deleteRecursively(outputPath.resolve("fonts"))
            println("[render_pdf] PDF saved to: $pdfPath")
            // Field name predates the LaTeX path (the PDF used to be printed from HTML).
            input.copy(resumeHtmlPdf = pdfPath.toString())
        } catch (e: Exception) {
            input.copy(error = "render_pdf: ${e.message}")
        }
    }

    /**
     * Run `yaml_to_tex.py tailored_resume.yaml -o tailored_resume.tex --pdf` with [dir] as
     * cwd (the script resolves its jinja template + fonts/ from its own location and runs
     * tectonic in the output's parent). Output goes to render_pdf.log in the job dir —
     * kept on failure for debugging, removed on success.
     */
    private fun runYamlToTex(dir: Path) {
        val logFile = dir.resolve("render_pdf.log").toFile()
        val process = ProcessBuilder(
            pythonBin,
            script.toAbsolutePath().toString(),
            "tailored_resume.yaml",
            "-o", "tailored_resume.tex",
            "--pdf",
        )
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(logFile)
            .start()

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw RuntimeException(
                "yaml_to_tex.py timed out after $timeoutMs ms (log: $logFile)"
            )
        }
        if (process.exitValue() != 0) {
            val tail = runCatching { logFile.readText() }.getOrDefault("")
                .lines().takeLast(15).joinToString("\n")
            throw RuntimeException("yaml_to_tex.py exited ${process.exitValue()}:\n$tail")
        }
        logFile.delete()
    }

    /** Remove the per-job fonts/ copy the script leaves next to the .tex. Best-effort. */
    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root)) return
        runCatching {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
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
