package com.jdbridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

private val log = LoggerFactory.getLogger("com.jdbridge.Pipeline")

// ── Command abstraction (injectable for tests) ────────────────────────────────

data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

typealias CommandRunner = (command: List<String>, workingDir: File, timeoutSeconds: Long) -> CommandResult

fun defaultCommandRunner(command: List<String>, workingDir: File, timeoutSeconds: Long): CommandResult {
    val process = ProcessBuilder(command)
        .directory(workingDir)
        .redirectErrorStream(false)
        .start()

    val stdout = process.inputStream.bufferedReader().readText()
    val stderr = process.errorStream.bufferedReader().readText()

    val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        throw RuntimeException("jd-pipeline-kotlin timed out after ${timeoutSeconds}s")
    }
    return CommandResult(exitCode = process.exitValue(), stdout = stdout, stderr = stderr)
}

// ── PipelineRunner interface ──────────────────────────────────────────────────

interface PipelineRunner {
    suspend fun run(jobId: String, payload: SubmitJobRequest)
}

// ── Real implementation ───────────────────────────────────────────────────────

class GradlePipelineRunner(
    private val commandRunner: CommandRunner = ::defaultCommandRunner,
) : PipelineRunner {

    override suspend fun run(jobId: String, payload: SubmitJobRequest) {
        try {
            execute(jobId, payload)
        } catch (e: Exception) {
            log.error("[$jobId] pipeline failed", e)
            updateJob(jobId, JobUpdate(
                status          = JobStatus.ERROR,
                error           = e.message,
                progressMessage = "Pipeline failed — check server logs.",
            ))
        }
    }

    private suspend fun execute(jobId: String, payload: SubmitJobRequest) {
        val pipelineDir = getEnv("JD_BRIDGE_PIPELINE_DIR")
        if (pipelineDir.isBlank()) {
            throw RuntimeException(
                "JD_BRIDGE_PIPELINE_DIR is not set. " +
                "Point it to your jd-pipeline-kotlin project directory."
            )
        }

        val gradlew = Path.of(pipelineDir, "gradlew")
        if (!gradlew.exists()) {
            throw RuntimeException("gradlew not found at $gradlew")
        }

        val dir = jobDir(jobId)

        // Write JD payload to temp JSON file
        val tmpJson = dir.resolve("_jd_input.json").toFile()
        val payloadJson = buildJsonObject {
            put("jd_text",    payload.jd_text)
            payload.role_title?.let { put("role_title", it) }
            payload.company?.let   { put("company",    it) }
            payload.location?.let  { put("location",   it) }
            payload.job_url?.let   { put("job_url",    it) }
            put("site", payload.site ?: "")
        }.toString()
        tmpJson.writeText(payloadJson, Charsets.UTF_8)

        // Stage 1: invoke pipeline
        updateJob(jobId, JobUpdate(
            status          = JobStatus.SCORING,
            progressMessage = "Running jd-pipeline-kotlin (scoring + tailoring)…",
        ))

        val result = withContext(Dispatchers.IO) {
            commandRunner(
                listOf(gradlew.toString(), "run", "--args=--jd-json-file=${tmpJson.absolutePath}"),
                File(pipelineDir),
                600L,
            )
        }

        if (result.exitCode != 0) {
            val stderr = result.stderr.trim()
            log.error("[$jobId] jd-pipeline-kotlin exited ${result.exitCode}: ${stderr.take(500)}")
            throw RuntimeException(
                "jd-pipeline-kotlin failed (exit ${result.exitCode}): ${stderr.take(300)}"
            )
        }

        // Parse last non-empty stdout line as JSON summary
        val summaryLine = result.stdout.lines()
            .filter { it.isNotBlank() }
            .lastOrNull()
            ?: throw RuntimeException("jd-pipeline-kotlin produced no output on stdout")

        val summary = runCatching { Json.parseToJsonElement(summaryLine).jsonObject }
            .getOrElse { throw RuntimeException("Could not parse jd-pipeline summary JSON: ${it.message}") }

        log.info("[$jobId] summary: $summary")

        val pipelineAction = summary["pipeline_action"]?.jsonPrimitive?.content ?: "skip"
        val fitScore       = summary["fit_score"]?.jsonPrimitive?.double ?: 0.0
        val outputPath     = summary["output_path"]?.jsonPrimitive?.content ?: ""

        if (pipelineAction == "skip") {
            updateJob(jobId, JobUpdate(
                status          = JobStatus.ERROR,
                fitScore        = fitScore.toInt(),
                error           = "Score too low (${fitScore.toInt()}) — JD not tailored.",
                progressMessage = "Score ${fitScore.toInt()}/100 — below threshold, skipped.",
            ))
            return
        }

        // Stage 2: copy artifacts
        updateJob(jobId, JobUpdate(
            status          = JobStatus.TAILORING,
            fitScore        = fitScore.toInt(),
            progressMessage = "Score ${fitScore.toInt()}/100 — copying artifacts…",
        ))

        if (outputPath.isBlank()) {
            throw RuntimeException("jd-pipeline-kotlin returned no output_path")
        }

        val srcDir = File(outputPath)

        val pdfFiles = srcDir.listFiles { f -> f.extension == "pdf" }?.toList() ?: emptyList()
        if (pdfFiles.isEmpty()) {
            throw RuntimeException("No PDF found in $srcDir")
        }
        pdfFiles.first().copyTo(dir.resolve("resume.pdf").toFile(), overwrite = true)

        val artifacts: ArtifactUrls
        val clSrc = srcDir.resolve("cover_letter.txt")
        if (clSrc.exists()) {
            clSrc.copyTo(dir.resolve("cover_letter.txt").toFile(), overwrite = true)
            artifacts = ArtifactUrls(
                resume_pdf       = "/api/jobs/$jobId/resume.pdf",
                cover_letter_txt = "/api/jobs/$jobId/cover_letter.txt",
            )
        } else {
            log.info("[$jobId] No cover_letter.txt in output")
            artifacts = ArtifactUrls(
                resume_pdf       = "/api/jobs/$jobId/resume.pdf",
                cover_letter_txt = "",
            )
        }

        updateJob(jobId, JobUpdate(
            status          = JobStatus.COMPLETE,
            progressMessage = "Done.",
            artifacts       = artifacts,
        ))
        log.info("[$jobId] pipeline complete → $dir")
    }
}
