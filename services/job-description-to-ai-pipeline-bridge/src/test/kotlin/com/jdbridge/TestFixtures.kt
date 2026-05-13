package com.jdbridge

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

// ── Store path management ─────────────────────────────────────────────────────

/** Create a fresh temp directory and wire it as the store root. Call in @BeforeEach. */
fun useTempStoreDir(): Path {
    val dir = Files.createTempDirectory("jd-bridge-test-")
    STORE_DIR = dir
    return dir
}

// ── Artifact fixtures ─────────────────────────────────────────────────────────

fun fakePdf(): ByteArray =
    "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\nxref\n0 2\n%%EOF".toByteArray()

fun fakeCoverLetter(): String =
    "Dear Hiring Manager,\n\nThank you for the opportunity."

fun sampleJdText(): String =
    "Staff SDET Opportunity – Mobile Platform\n\n" +
    "We have a Staff SDET opening on our Mobile Platform team. Responsibilities include " +
    "owning iOS and Android BVT automation frameworks, building and maintaining CI/CD " +
    "pipelines on Bitrise and GitHub Actions, and driving test infrastructure strategy " +
    "across mobile teams. Requirements: 6+ years in mobile test automation, deep " +
    "experience with XCUITest and Espresso, CI/CD pipeline ownership (Bitrise preferred), " +
    "Kotlin or Swift fluency. Total compensation: \$220K–\$280K."

fun minimalJdText(): String = "x".repeat(150)

// ── Fake pipeline runner ──────────────────────────────────────────────────────

/**
 * Writes real artifacts to the job directory and transitions the job through
 * SCORING → TAILORING → COMPLETE, mirroring GradlePipelineRunner exactly.
 */
class FakePipelineRunner(
    private val fitScore: Int = 82,
    private val includeCoverLetter: Boolean = true,
) : PipelineRunner {
    override suspend fun run(jobId: String, payload: SubmitJobRequest) {
        val dir = jobDir(jobId)

        updateJob(jobId, JobUpdate(
            status          = JobStatus.SCORING,
            progressMessage = "Running jd-pipeline-kotlin (scoring + tailoring)…",
        ))

        updateJob(jobId, JobUpdate(
            status          = JobStatus.TAILORING,
            fitScore        = fitScore,
            progressMessage = "Score $fitScore/100 — copying artifacts…",
        ))

        dir.resolve("resume.pdf").toFile().writeBytes(fakePdf())

        val artifacts = if (includeCoverLetter) {
            dir.resolve("cover_letter.txt").toFile().writeText(fakeCoverLetter())
            ArtifactUrls(
                resume_pdf       = "/api/jobs/$jobId/resume.pdf",
                cover_letter_txt = "/api/jobs/$jobId/cover_letter.txt",
            )
        } else {
            ArtifactUrls(
                resume_pdf       = "/api/jobs/$jobId/resume.pdf",
                cover_letter_txt = "",
            )
        }

        updateJob(jobId, JobUpdate(
            status          = JobStatus.COMPLETE,
            progressMessage = "Done.",
            artifacts       = artifacts,
        ))
    }
}

/** Runner that transitions the job to ERROR (score too low). */
class SkipPipelineRunner(private val fitScore: Int = 40) : PipelineRunner {
    override suspend fun run(jobId: String, payload: SubmitJobRequest) {
        updateJob(jobId, JobUpdate(
            status          = JobStatus.ERROR,
            fitScore        = fitScore,
            error           = "Score too low ($fitScore) — JD not tailored.",
            progressMessage = "Score $fitScore/100 — below threshold, skipped.",
        ))
    }
}

/** Runner that transitions the job to ERROR (pipeline crash). */
class CrashPipelineRunner : PipelineRunner {
    override suspend fun run(jobId: String, payload: SubmitJobRequest) {
        updateJob(jobId, JobUpdate(
            status          = JobStatus.ERROR,
            error           = "Kotlin NullPointerException at line 42",
            progressMessage = "Pipeline failed — check server logs.",
        ))
    }
}

// ── DB init helper ────────────────────────────────────────────────────────────

fun initTestDb() = runBlocking { initDb() }
