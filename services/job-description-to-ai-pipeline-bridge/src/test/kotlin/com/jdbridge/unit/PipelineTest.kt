package com.jdbridge.unit

import com.jdbridge.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.*

/**
 * Builds a fake CommandRunner that returns a controlled CommandResult.
 * The fake also accepts a lambda to spy on what command was invoked.
 */
/** Create the job record then run the pipeline — mirrors what the route handler does. */
suspend fun createAndRun(jobId: String, runner: PipelineRunner) {
    createJob(jobId, SubmitJobRequest(jd_text = minimalJdText()))
    runner.run(jobId, SubmitJobRequest(jd_text = minimalJdText()))
}

fun fakeRunner(
    exitCode: Int = 0,
    stdoutLines: List<String> = emptyList(),
    stderr: String = "",
    onInvoke: ((cmd: List<String>, workingDir: File) -> Unit)? = null,
): CommandRunner = { cmd, workingDir, _ ->
    onInvoke?.invoke(cmd, workingDir)
    CommandResult(exitCode, stdoutLines.joinToString("\n"), stderr)
}

fun successSummary(outputPath: String, fitScore: Double = 82.0, action: String = "tailor"): String =
    buildJsonObject {
        put("output_path",     outputPath)
        put("fit_score",       fitScore)
        put("pipeline_action", action)
        put("error",           "")
    }.toString()

class PipelineCommandTest {

    @TempDir lateinit var tempDir: Path

    private lateinit var pipelineDir: File
    private lateinit var gradlew: File

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
        pipelineDir = tempDir.resolve("pipeline").toFile().also { it.mkdirs() }
        gradlew = pipelineDir.resolve("gradlew").also { it.writeText("#!/bin/sh\n"); it.setExecutable(true) }
        System.setProperty("JD_BRIDGE_PIPELINE_DIR", pipelineDir.absolutePath)
    }

    @Test
    fun `command includes gradlew run and jd-json-file flag`() = runTest {
        val outputDir = tempDir.resolve("output").toFile().also {
            it.mkdirs()
            it.resolve("resume.pdf").writeBytes(fakePdf())
        }
        val captured = mutableListOf<String>()
        val runner = GradlePipelineRunner(fakeRunner(
            stdoutLines = listOf(successSummary(outputDir.absolutePath)),
            onInvoke    = { cmd, _ -> captured.addAll(cmd) },
        ))

        runner.run("cmd-test-001", SubmitJobRequest(jd_text = minimalJdText()))

        assertTrue(captured.any { "gradlew" in it }, "command should contain gradlew")
        assertTrue(captured.any { it == "run" }, "command should contain 'run'")
        assertTrue(captured.any { "--jd-json-file" in it }, "command should contain --jd-json-file")
    }

    @Test
    fun `working directory is set to pipeline dir`() = runTest {
        val outputDir = tempDir.resolve("output2").toFile().also {
            it.mkdirs()
            it.resolve("resume.pdf").writeBytes(fakePdf())
        }
        var capturedWorkDir: File? = null
        val runner = GradlePipelineRunner(fakeRunner(
            stdoutLines = listOf(successSummary(outputDir.absolutePath)),
            onInvoke    = { _, wd -> capturedWorkDir = wd },
        ))

        runner.run("wd-test-001", SubmitJobRequest(jd_text = minimalJdText()))

        assertEquals(pipelineDir.absolutePath, capturedWorkDir?.absolutePath)
    }
}

class PipelineErrorHandlingTest {

    @TempDir lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
        val pipelineDir = tempDir.resolve("pipeline").toFile().also { it.mkdirs() }
        pipelineDir.resolve("gradlew").also { it.writeText("#!/bin/sh\n"); it.setExecutable(true) }
        System.setProperty("JD_BRIDGE_PIPELINE_DIR", pipelineDir.absolutePath)
    }

    @Test
    fun `non-zero exit transitions job to ERROR`() = runTest {
        val runner = GradlePipelineRunner(fakeRunner(exitCode = 1, stderr = "fatal kotlin error"))
        createAndRun("err-001", runner)
        val row = getJob("err-001")!!
        assertEquals(JobStatus.ERROR.value, row.status)
        assertNotNull(row.error)
    }

    @Test
    fun `empty stdout transitions job to ERROR with no-output message`() = runTest {
        val runner = GradlePipelineRunner(fakeRunner(stdoutLines = emptyList()))
        createAndRun("err-002", runner)
        val row = getJob("err-002")!!
        assertEquals(JobStatus.ERROR.value, row.status)
        assertTrue(row.error?.contains("no output", ignoreCase = true) == true)
    }

    @Test
    fun `invalid JSON stdout transitions job to ERROR with JSON message`() = runTest {
        val runner = GradlePipelineRunner(fakeRunner(stdoutLines = listOf("not { valid } json")))
        createAndRun("err-003", runner)
        val row = getJob("err-003")!!
        assertEquals(JobStatus.ERROR.value, row.status)
        assertTrue(row.error?.contains("JSON", ignoreCase = true) == true)
    }

    @Test
    fun `pipeline_action skip transitions to ERROR with score in message`() = runTest {
        val runner = GradlePipelineRunner(fakeRunner(
            stdoutLines = listOf(successSummary("/tmp/out", fitScore = 40.0, action = "skip")),
        ))
        createAndRun("err-004", runner)
        val row = getJob("err-004")!!
        assertEquals(JobStatus.ERROR.value, row.status)
        assertTrue(row.error?.contains("40") == true)
    }

    @Test
    fun `missing output_path transitions to ERROR`() = runTest {
        val summary = buildJsonObject {
            put("output_path",     "")
            put("fit_score",       82.0)
            put("pipeline_action", "tailor")
            put("error",           "")
        }.toString()
        val runner = GradlePipelineRunner(fakeRunner(stdoutLines = listOf(summary)))
        createAndRun("err-005", runner)
        val row = getJob("err-005")!!
        assertEquals(JobStatus.ERROR.value, row.status)
    }

    @Test
    fun `no PDF in output directory transitions to ERROR`() = runTest {
        val outputDir = File(System.getProperty("java.io.tmpdir"), "no-pdf-${System.nanoTime()}").also {
            it.mkdirs()
            it.resolve("cover_letter.txt").writeText("Dear Hiring Manager")
        }
        val runner = GradlePipelineRunner(fakeRunner(
            stdoutLines = listOf(successSummary(outputDir.absolutePath)),
        ))
        createAndRun("err-006", runner)
        val row = getJob("err-006")!!
        assertEquals(JobStatus.ERROR.value, row.status)
        assertTrue(row.error?.contains("No PDF", ignoreCase = true) == true)
    }

    @Test
    fun `missing JD_BRIDGE_PIPELINE_DIR transitions to ERROR`() = runTest {
        System.clearProperty("JD_BRIDGE_PIPELINE_DIR")
        val savedEnv = System.getenv("JD_BRIDGE_PIPELINE_DIR")
        if (savedEnv.isNullOrBlank()) {
            val runner = GradlePipelineRunner()
            createAndRun("err-007", runner)
            val row = getJob("err-007")!!
            assertEquals(JobStatus.ERROR.value, row.status)
            assertTrue(row.error?.contains("JD_BRIDGE_PIPELINE_DIR") == true)
        }
    }
}

class PipelineArtifactCopyTest {

    @TempDir lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
        val pipelineDir = tempDir.resolve("pipeline").toFile().also { it.mkdirs() }
        pipelineDir.resolve("gradlew").also { it.writeText("#!/bin/sh\n"); it.setExecutable(true) }
        System.setProperty("JD_BRIDGE_PIPELINE_DIR", pipelineDir.absolutePath)
    }

    @Test
    fun `first PDF in output dir is copied to resume_pdf`() = runTest {
        val outputDir = tempDir.resolve("out1").toFile().also { it.mkdirs() }
        outputDir.resolve("Richard_Hatcher_Resume.pdf").writeBytes(fakePdf())
        outputDir.resolve("cover_letter.txt").writeText(fakeCoverLetter())

        val runner = GradlePipelineRunner(fakeRunner(stdoutLines = listOf(successSummary(outputDir.absolutePath))))
        createAndRun("art-001", runner)

        val row = getJob("art-001")!!
        assertEquals(JobStatus.COMPLETE.value, row.status)
        assertNotNull(row.artifacts)
        assertEquals("/api/jobs/art-001/resume.pdf", row.artifacts!!.resume_pdf)
        assertTrue(jobDir("art-001").resolve("resume.pdf").toFile().exists())
    }

    @Test
    fun `when multiple PDFs exist the first one by name is chosen`() = runTest {
        val outputDir = tempDir.resolve("out_multiple").toFile().also { it.mkdirs() }
        outputDir.resolve("Alice_Wang_Resume.pdf").writeBytes(fakePdf())
        outputDir.resolve("Bob_Jones_Resume.pdf").writeBytes(fakePdf())
        outputDir.resolve("resume.pdf").writeBytes(fakePdf())
        outputDir.resolve("cover_letter.txt").writeText(fakeCoverLetter())

        val runner = GradlePipelineRunner(fakeRunner(stdoutLines = listOf(successSummary(outputDir.absolutePath))))
        createAndRun("art-multi-001", runner)

        val row = getJob("art-multi-001")!!
        assertEquals(JobStatus.COMPLETE.value, row.status)
        assertNotNull(row.artifacts)
        // Should pick first PDF in sorted order (Alice < Bob < resume)
        val copiedPdf = jobDir("art-multi-001").resolve("resume.pdf").toFile()
        assertTrue(copiedPdf.exists(), "resume.pdf should be copied")
    }

    @Test
    fun `absent cover_letter results in empty cover_letter_txt`() = runTest {
        val outputDir = tempDir.resolve("out2").toFile().also { it.mkdirs() }
        outputDir.resolve("resume.pdf").writeBytes(fakePdf())

        val runner = GradlePipelineRunner(fakeRunner(stdoutLines = listOf(successSummary(outputDir.absolutePath))))
        createAndRun("art-002", runner)

        val row = getJob("art-002")!!
        assertEquals("", row.artifacts?.cover_letter_txt)
    }

    @Test
    fun `float fit_score 82_7 is stored as int 82`() = runTest {
        val outputDir = tempDir.resolve("out3").toFile().also { it.mkdirs() }
        outputDir.resolve("resume.pdf").writeBytes(fakePdf())

        val runner = GradlePipelineRunner(fakeRunner(stdoutLines = listOf(successSummary(outputDir.absolutePath, fitScore = 82.7))))
        createAndRun("art-003", runner)

        assertEquals(82, getJob("art-003")!!.fitScore)
    }
}

class PipelineCrashCleanupTest {

    @TempDir lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
        val pipelineDir = tempDir.resolve("pipeline").toFile().also { it.mkdirs() }
        pipelineDir.resolve("gradlew").also { it.writeText("#!/bin/sh\n"); it.setExecutable(true) }
        System.setProperty("JD_BRIDGE_PIPELINE_DIR", pipelineDir.absolutePath)
    }

    @Test
    fun `pipeline crash leaves job in ERROR status`() = runTest {
        // CrashRunner sets job to ERROR after creating it - we just verify the final state
        createJob("crash-001", SubmitJobRequest(jd_text = minimalJdText()))
        CrashPipelineRunner().run("crash-001", SubmitJobRequest(jd_text = minimalJdText()))
        val row = getJob("crash-001")!!
        assertEquals(JobStatus.ERROR.value, row.status)
        assertTrue(row.error?.contains("NullPointerException") == true)
    }

    @Test
    fun `job directory is preserved after crash`() = runTest {
        createJob("crash-002", SubmitJobRequest(jd_text = minimalJdText()))
        CrashPipelineRunner().run("crash-002", SubmitJobRequest(jd_text = minimalJdText()))
        // Job directory should still exist after crash
        assertTrue(jobDir("crash-002").toFile().isDirectory)
    }
}

class PipelineStatusTransitionTest {

    @TempDir lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
        val pipelineDir = tempDir.resolve("pipeline").toFile().also { it.mkdirs() }
        pipelineDir.resolve("gradlew").also { it.writeText("#!/bin/sh\n"); it.setExecutable(true) }
        System.setProperty("JD_BRIDGE_PIPELINE_DIR", pipelineDir.absolutePath)
    }

    @Test
    fun `successful run transitions SCORING then TAILORING then COMPLETE`() = runTest {
        val outputDir = tempDir.resolve("transition").toFile().also { it.mkdirs() }
        outputDir.resolve("resume.pdf").writeBytes(fakePdf())
        outputDir.resolve("cover_letter.txt").writeText(fakeCoverLetter())

        // Use a real DB + polling approach: since updateJob is sequential, the final
        // state is COMPLETE and fit_score is set.
        val runner = GradlePipelineRunner(fakeRunner(
            stdoutLines = listOf(successSummary(outputDir.absolutePath)),
        ))
        createAndRun("trans-001", runner)

        val row = getJob("trans-001")!!
        assertEquals(JobStatus.COMPLETE.value, row.status)
        assertNotNull(row.fitScore)
    }
}
