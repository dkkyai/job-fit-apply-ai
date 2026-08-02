package com.jd.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertTrue
import kotlin.test.fail

data class TrackEvidence(
    val emailId: String,
    val roleTitle: String,
    val pipelineAction: String,
    val artifactUrl: String?,
    val outputPath: String?,
    val jobUrl: String,
    val jdText: String,
)

data class ScenarioResult(
    val jobId: String,
    val company: String,
    val completedCursor: Long,
    val finalStatus: JsonNode,
    val completedEvents: List<JsonNode>,
    val artifactUrl: String?,
    val outputDir: Path?,
    val track: TrackEvidence,
    val apiTrack: JsonNode,
    val discordMessages: List<String>,
    val telegramMessages: List<String>,
    val llmCalls: List<String>,
) {
    val completedEvent: JsonNode get() = completedEvents.single()
}

/** Shared black-box transaction harness. One instance is owned by each E2E test class. */
class E2eScenarioHarness {
    val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()
    val fakeLlm = FakeLlmServer(E2eConfig.fakeLlmPort, E2eConfig.fixturesDir)
    val sink = MockNotificationSink(E2eConfig.sinkPort)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    fun start() {
        if (!E2eConfig.realLlm) fakeLlm.start()
        sink.start()
        preflight()
    }

    fun stop() {
        fakeLlm.stop()
        sink.stop()
    }

    fun fixture(relativePath: String, replacements: Map<String, String> = emptyMap()): String =
        replacements.entries.fold(Files.readString(E2eConfig.fixturesDir.resolve(relativePath)).trim()) { text, entry ->
            text.replace("{{${entry.key}}}", entry.value)
        }

    fun runScenario(
        company: String,
        responses: Map<String, List<String>> = emptyMap(),
        submit: E2eScenarioHarness.() -> JsonNode,
    ): ScenarioResult {
        if (!E2eConfig.realLlm) fakeLlm.reset(responses)
        sink.reset()

        val completedCursor = mapper.readTree(getString("${E2eConfig.bridgeUrl}/api/jobs/completed/head"))
            .path("max_seq").asLong(0)
        val submitResponse = submit()
        val jobId = submitResponse.path("job_id").asText("")
        assertTrue(jobId.isNotBlank(), "submit returned no job_id: $submitResponse")
        assertTrue(!submitResponse.path("deduped").asBoolean(false), "unique scenario input unexpectedly deduped: $submitResponse")
        println("[e2e] submitted job $jobId as '$company'")

        val finalStatus = pollUntil(E2eConfig.timeoutSeconds, 2000, "job $jobId to reach done") {
            val status = mapper.readTree(getString("${E2eConfig.bridgeUrl}/api/jobs/$jobId"))
            when (status.path("status").asText()) {
                "done" -> status
                "error" -> fail(
                    "job $jobId ended in status=error: ${status.path("error").asText()} " +
                        "(fake-llm calls so far: ${fakeLlm.calls})",
                )
                else -> null
            }
        }
        println("[e2e] job done: $finalStatus")

        val completedEvents = pollUntil(
            30,
            500,
            "job $jobId to appear once in the completed feed since seq=$completedCursor",
        ) {
            val feed = mapper.readTree(
                getString("${E2eConfig.bridgeUrl}/api/jobs/completed?since=$completedCursor&limit=200&all=true"),
            )
            feed.filter { it.path("job_id").asText() == jobId }.takeIf { it.isNotEmpty() }
        }

        val event = completedEvents.singleOrNull()
            ?: fail("expected exactly one completed event for $jobId, got ${completedEvents.size}: $completedEvents")
        val artifactUrl = event.path("artifact_url").asText("").ifBlank { null }
        val outputDir = artifactUrl?.let {
            val dirName = URLDecoder.decode(it.trimEnd('/').substringAfterLast('/'), Charsets.UTF_8)
            E2eConfig.outputDir.resolve(dirName)
        }

        val discordMessages = pollUntil(
            90,
            1000,
            { "notifier to deliver Discord for '$company' (sink saw: ${sink.describe()})" },
        ) {
            sink.discordTexts().filter { it.contains(company) }.takeIf { it.isNotEmpty() }
        }

        val telegramCompany = company.replace("&", "&amp;")
        val telegramMessages = if (finalStatus.path("fit_score").asInt() >= 50) {
            pollUntil(
                30,
                1000,
                { "notifier to deliver Telegram for '$company' (sink saw: ${sink.describe()})" },
            ) {
                sink.telegramTexts().filter { it.contains(telegramCompany) }.takeIf { it.isNotEmpty() }
            }
        } else {
            sink.telegramTexts().filter { it.contains(telegramCompany) }
        }

        val track = loadTrack(company)
        val apiTrack = mapper.readTree(getString("${E2eConfig.bridgeUrl}/api/tracks"))
            .firstOrNull { it.path("company").asText() == company }
            ?: fail("/api/tracks has no row for '$company'")

        return ScenarioResult(
            jobId = jobId,
            company = company,
            completedCursor = completedCursor,
            finalStatus = finalStatus,
            completedEvents = completedEvents,
            artifactUrl = artifactUrl,
            outputDir = outputDir,
            track = track,
            apiTrack = apiTrack,
            discordMessages = discordMessages,
            telegramMessages = telegramMessages,
            llmCalls = if (E2eConfig.realLlm) emptyList() else fakeLlm.calls.toList(),
        )
    }

    fun submitScrapedJob(
        company: String,
        roleTitle: String,
        jdText: String,
        idempotencyKey: String,
        location: String = "Remote (US)",
    ): JsonNode = postJson(
        "${E2eConfig.bridgeUrl}/api/jobs",
        mapper.writeValueAsString(
            mapOf(
                "jd_text" to jdText,
                "company" to company,
                "role_title" to roleTitle,
                "location" to location,
                "source" to "MANUAL",
                "idempotency_key" to idempotencyKey,
            ),
        ),
    )

    fun submitPage(url: String, title: String, text: String, idempotencyKey: String): JsonNode = postJson(
        "${E2eConfig.bridgeUrl}/api/pages",
        mapper.writeValueAsString(
            mapOf("url" to url, "title" to title, "text" to text, "idempotency_key" to idempotencyKey),
        ),
    )

    fun submitEmail(
        messageId: String,
        subject: String,
        body: String,
        from: String,
        idempotencyKey: String,
    ): JsonNode = postJson(
        "${E2eConfig.bridgeUrl}/api/emails",
        mapper.writeValueAsString(
            mapOf(
                "message_id" to messageId,
                "subject" to subject,
                "body" to body,
                "html_body" to null,
                "from" to from,
                "is_recruiter_hint" to true,
                "idempotency_key" to idempotencyKey,
            ),
        ),
    )

    fun getString(url: String): String {
        val response = request(url, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            "GET $url → ${response.statusCode()}: ${response.body().take(300)}"
        }
        return response.body()
    }

    fun getBytes(url: String): ByteArray {
        val response = request(url, HttpResponse.BodyHandlers.ofByteArray())
        check(response.statusCode() in 200..299) { "GET $url → ${response.statusCode()}" }
        return response.body()
    }

    fun statusOf(url: String): Int = request(url, HttpResponse.BodyHandlers.discarding()).statusCode()

    private fun preflight() {
        val health = runCatching { getString("${E2eConfig.bridgeUrl}/health") }
        if (health.isFailure) {
            fail(
                "Bridge not reachable at ${E2eConfig.bridgeUrl} — is the e2e slice running? " +
                    "Start it with `make e2e-up`. (${health.exceptionOrNull()?.message})",
            )
        }
    }

    private fun postJson(url: String, body: String): JsonNode {
        val response = request(url, HttpResponse.BodyHandlers.ofString(), body)
        check(response.statusCode() in 200..299) {
            "POST $url → ${response.statusCode()}: ${response.body().take(300)}"
        }
        return mapper.readTree(response.body())
    }

    private fun loadTrack(company: String): TrackEvidence = E2eConfig.pgConnection().use { connection ->
        connection.prepareStatement(
            """
            SELECT email_id, role_title, pipeline_action, artifact_url, output_path, job_url, jd_text
            FROM tracks WHERE company = ? ORDER BY id DESC LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, company)
            statement.executeQuery().use { result ->
                assertTrue(result.next(), "no tracks row for company '$company'")
                TrackEvidence(
                    emailId = result.getString("email_id").orEmpty(),
                    roleTitle = result.getString("role_title").orEmpty(),
                    pipelineAction = result.getString("pipeline_action").orEmpty(),
                    artifactUrl = result.getString("artifact_url"),
                    outputPath = result.getString("output_path"),
                    jobUrl = result.getString("job_url").orEmpty(),
                    jdText = result.getString("jd_text").orEmpty(),
                )
            }
        }
    }

    private fun <T> request(
        url: String,
        handler: HttpResponse.BodyHandler<T>,
        body: String? = null,
    ): HttpResponse<T> {
        val builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
        if (body == null) {
            builder.GET()
        } else {
            builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
        }
        return http.send(builder.build(), handler)
    }

    private fun <T> pollUntil(
        deadlineSeconds: Long,
        intervalMs: Long,
        what: String,
        probe: () -> T?,
    ): T = pollUntil(deadlineSeconds, intervalMs, { what }, probe)

    private fun <T> pollUntil(
        deadlineSeconds: Long,
        intervalMs: Long,
        what: () -> String,
        probe: () -> T?,
    ): T {
        val deadline = System.nanoTime() + deadlineSeconds * 1_000_000_000L
        var lastTransient: Exception? = null
        while (true) {
            try {
                probe()?.let { return it }
                lastTransient = null
            } catch (error: IOException) {
                lastTransient = error
            } catch (error: IllegalStateException) {
                lastTransient = error
            }
            if (System.nanoTime() > deadline) {
                fail(
                    "timed out after ${deadlineSeconds}s waiting for ${what()}" +
                        (lastTransient?.let { " (last transport failure: $it)" } ?: ""),
                )
            }
            Thread.sleep(intervalMs)
        }
    }
}
