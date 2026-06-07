package com.jdbridge.e2e

import com.jdbridge.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

/**
 * E2E tests use a real fake-gradlew shell script on disk.
 * This exercises the actual subprocess delegation path end-to-end.
 */
class FullFlowE2ETest {

    @TempDir lateinit var tempDir: Path

    private lateinit var fakePipelineDir: Path

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
        fakePipelineDir = tempDir.resolve("fake-pipeline")
        fakePipelineDir.toFile().mkdirs()
        fakePipelineDir.toFile().resolve("build").mkdirs()
        writeFakeGradlew(fakePipelineDir, exitCode = 0, fitScore = 82, action = "tailor")
        System.setProperty("JD_BRIDGE_PIPELINE_DIR", fakePipelineDir.toFile().absolutePath)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun writeFakeGradlew(
        dir: Path,
        exitCode: Int   = 0,
        fitScore: Int   = 82,
        action: String  = "tailor",
        stderr: String  = "",
    ) {
        val script = """
            #!/bin/bash
            set -e

            # Extract --jd-json-file path from --args=...
            ARGS=""
            for arg in "${'$'}@"; do
              case "${'$'}arg" in
                --args=*) ARGS="${'$'}{arg#--args=}" ;;
              esac
            done

            JD_FILE=""
            for arg in ${'$'}ARGS; do
              case "${'$'}arg" in
                --jd-json-file=*) JD_FILE="${'$'}{arg#--jd-json-file=}" ;;
              esac
            done

            OUTPUT_DIR="${'$'}{JD_FILE%/*}/e2e_output_${'$'}(date +%s%N)"
            mkdir -p "${'$'}OUTPUT_DIR"

            printf '%%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\nxref\n0 2\n%%%%EOF' > "${'$'}OUTPUT_DIR/Richard_Hatcher_Fake.pdf"
            printf 'Dear Hiring Manager,\n\nThank you for the opportunity.' > "${'$'}OUTPUT_DIR/cover_letter.txt"

            ${if (stderr.isNotEmpty()) """echo "$stderr" >&2""" else ""}
            ${if (exitCode != 0) "exit $exitCode" else ""}

            echo "{\"output_path\":\"${'$'}OUTPUT_DIR\",\"fit_score\":$fitScore,\"pipeline_action\":\"$action\",\"error\":\"\"}"
            exit 0
        """.trimIndent()
        val gradlew = dir.resolve("gradlew").toFile()
        gradlew.writeText(script)
        gradlew.setExecutable(true)
    }

    private fun writeSkipGradlew(dir: Path) {
        val script = """
            #!/bin/bash
            echo '{"output_path":"/tmp","fit_score":40,"pipeline_action":"skip","error":""}'
            exit 0
        """.trimIndent()
        val gradlew = dir.resolve("gradlew").toFile()
        gradlew.writeText(script)
        gradlew.setExecutable(true)
    }

    private fun writeCrashGradlew(dir: Path) {
        val script = """
            #!/bin/bash
            echo "fatal error in pipeline" >&2
            exit 1
        """.trimIndent()
        val gradlew = dir.resolve("gradlew").toFile()
        gradlew.writeText(script)
        gradlew.setExecutable(true)
    }

    private suspend fun pollUntilTerminal(
        client: io.ktor.client.HttpClient,
        jobId: String,
        maxAttempts: Int = 60,
    ): JsonObject {
        repeat(maxAttempts) {
            delay(500)
            val r = client.get("/api/jobs/$jobId")
            val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
            val status = body["status"]!!.jsonPrimitive.content
            if (status == "complete" || status == "error") return body
        }
        error("Job $jobId did not reach terminal state within ${maxAttempts * 500}ms")
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `full happy-path flow mirrors Chrome extension usage`() = testApplication {
        application { configureApplication(GradlePipelineRunner()) }

        // 1. Submit
        val req = SubmitJobRequest(
            jd_text    = sampleJdText(),
            role_title = "Staff SDET",
            company    = "Acme Corp",
            location   = "Seattle, WA",
            job_url    = "https://example.com/jobs/123",
            site       = "greenhouse",
        )
        val submitResp = client.post("/api/jobs") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(req))
        }
        assertEquals(HttpStatusCode.Accepted, submitResp.status)
        val jobId = Json.parseToJsonElement(submitResp.bodyAsText()).jsonObject["job_id"]!!.jsonPrimitive.content

        // 2. Poll to complete
        val statusBody = runBlocking { pollUntilTerminal(client, jobId) }
        assertEquals("complete", statusBody["status"]!!.jsonPrimitive.content,
            "Pipeline error: ${statusBody["error"]?.jsonPrimitive?.content}")

        // 3. Verify status fields
        assertEquals(82, statusBody["fit_score"]!!.jsonPrimitive.int)
        val artifacts = statusBody["artifacts"]!!.jsonObject
        assertTrue(artifacts["resume_pdf"]!!.jsonPrimitive.content.contains(jobId))
        assertTrue(artifacts["cover_letter_txt"]!!.jsonPrimitive.content.contains(jobId))

        // 4. Download resume PDF
        val pdfResp = client.get("/api/jobs/$jobId/resume.pdf")
        assertEquals(HttpStatusCode.OK, pdfResp.status)
        assertEquals(ContentType.Application.Pdf, pdfResp.contentType()?.withoutParameters())
        assertTrue(pdfResp.readBytes().decodeToString().startsWith("%PDF"))

        // 5. Download cover letter
        val clResp = client.get("/api/jobs/$jobId/cover_letter.txt")
        assertEquals(HttpStatusCode.OK, clResp.status)
        assertTrue(clResp.bodyAsText().contains("Dear Hiring Manager"))

        // 6. Verify files on disk
        val pdfOnDisk = STORE_DIR.resolve("jobs/$jobId/resume.pdf").toFile()
        val clOnDisk  = STORE_DIR.resolve("jobs/$jobId/cover_letter.txt").toFile()
        assertTrue(pdfOnDisk.exists(), "resume.pdf must exist on disk")
        assertTrue(clOnDisk.exists(),  "cover_letter.txt must exist on disk")
    }

    @Test
    fun `minimal payload E2E reaches complete`() = testApplication {
        application { configureApplication(GradlePipelineRunner()) }
        val submitResp = client.post("/api/jobs") {
            contentType(ContentType.Application.Json)
            setBody("""{"jd_text":"${sampleJdText().replace("\"", "\\\"")}"}""")
        }
        assertEquals(HttpStatusCode.Accepted, submitResp.status)
        val jobId = Json.parseToJsonElement(submitResp.bodyAsText()).jsonObject["job_id"]!!.jsonPrimitive.content
        val statusBody = runBlocking { pollUntilTerminal(client, jobId) }
        assertEquals("complete", statusBody["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `three concurrent jobs all complete with distinct artifact paths`() = testApplication {
        application { configureApplication(GradlePipelineRunner()) }

        val jobIds = (1..3).map { i ->
            val r = client.post("/api/jobs") {
                contentType(ContentType.Application.Json)
                setBody("""{"jd_text":"${"x".repeat(200)}","company":"Company$i"}""")
            }
            assertEquals(HttpStatusCode.Accepted, r.status)
            Json.parseToJsonElement(r.bodyAsText()).jsonObject["job_id"]!!.jsonPrimitive.content
        }

        val artifactPaths = jobIds.map { jobId ->
            val body = runBlocking { pollUntilTerminal(client, jobId) }
            assertEquals("complete", body["status"]!!.jsonPrimitive.content, "Job $jobId failed: ${body["error"]}")
            body["artifacts"]!!.jsonObject["resume_pdf"]!!.jsonPrimitive.content
        }

        assertEquals(3, artifactPaths.toSet().size, "Each job must have a distinct resume_pdf path")
    }

    @Test
    fun `score-too-low flow results in error with score in message`() = testApplication {
        writeSkipGradlew(fakePipelineDir)
        application { configureApplication(GradlePipelineRunner()) }

        val submitResp = client.post("/api/jobs") {
            contentType(ContentType.Application.Json)
            setBody("""{"jd_text":"${"x".repeat(200)}"}""")
        }
        val jobId = Json.parseToJsonElement(submitResp.bodyAsText()).jsonObject["job_id"]!!.jsonPrimitive.content
        val body = runBlocking { pollUntilTerminal(client, jobId) }

        assertEquals("error", body["status"]!!.jsonPrimitive.content)
        val error = body["error"]!!.jsonPrimitive.content
        assertTrue(error.contains("40"), "error message should mention the score")

        val pdfOnDisk = STORE_DIR.resolve("jobs/$jobId/resume.pdf").toFile()
        assertFalse(pdfOnDisk.exists(), "No PDF should be written for a skipped job")
    }

    @Test
    fun `subprocess failure flow results in error with non-empty message`() = testApplication {
        writeCrashGradlew(fakePipelineDir)
        application { configureApplication(GradlePipelineRunner()) }

        val submitResp = client.post("/api/jobs") {
            contentType(ContentType.Application.Json)
            setBody("""{"jd_text":"${"x".repeat(200)}"}""")
        }
        val jobId = Json.parseToJsonElement(submitResp.bodyAsText()).jsonObject["job_id"]!!.jsonPrimitive.content
        val body = runBlocking { pollUntilTerminal(client, jobId) }

        assertEquals("error", body["status"]!!.jsonPrimitive.content)
        val error = body["error"]?.jsonPrimitive?.content
        assertNotNull(error)
        assertTrue(error.isNotEmpty())
    }
}
