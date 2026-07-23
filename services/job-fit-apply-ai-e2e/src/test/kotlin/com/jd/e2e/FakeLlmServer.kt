package com.jd.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

/**
 * Minimal OpenAI-compatible fake serving `POST /v1/chat/completions` for the whole
 * ProcessingPipeline. The processor reaches it via host.docker.internal (compose override
 * points MLX_LOCAL_BASE_URL here).
 *
 * Dispatch is on `messages[0].content` — the pipeline sends no system message. Design
 * constraints it deliberately honours:
 *  - `response_format` is IGNORED: summary_rewrite requests json_object but requires prose.
 *  - Markers are matched with `contains`, never `startsWith` — qwen3-family calls get a
 *    `/no_think\n` prefix.
 *  - bullet_rewrite responses are built by echoing role/company/start_date (the join keys)
 *    parsed out of the prompt's roles JSON — a hardcoded response would silently be
 *    discarded by the fold-back join.
 *  - An unmatched prompt is a loud HTTP 500, never a default response: prompt-marker
 *    drift must fail the run visibly.
 *
 * Every served call is recorded in [calls] (`:refine` / `:retry` suffixed when the prompt
 * carries those markers) so tests can assert the exact call sequence.
 */
class FakeLlmServer(private val port: Int, private val fixturesDir: Path) {
    private val mapper = ObjectMapper().registerKotlinModule()
    private var engine: ApplicationEngine? = null

    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    fun start() {
        try {
            engine = embeddedServer(Netty, port = port, host = "0.0.0.0") {
                routing {
                    post("/v1/chat/completions") {
                        val body = mapper.readTree(call.receiveText())
                        val prompt = body.path("messages").path(0).path("content").asText("")
                        val model = body.path("model").asText("")
                        val routed = dispatch(prompt, model)
                        if (routed == null) {
                            val head = prompt.take(300).replace('\n', ' ')
                            System.err.println("[fake-llm] UNMATCHED PROMPT (model=$model): $head")
                            call.respondText(
                                mapper.writeValueAsString(
                                    mapOf("error" to "FakeLlmServer: no dispatch marker matched this prompt (marker drift?). Prompt head: $head")
                                ),
                                ContentType.Application.Json,
                                HttpStatusCode.InternalServerError,
                            )
                        } else {
                            val (route, content) = routed
                            calls += route
                            println("[fake-llm] served $route (model=$model)")
                            call.respondText(
                                mapper.writeValueAsString(
                                    mapOf("choices" to listOf(mapOf("message" to mapOf("content" to content))))
                                ),
                                ContentType.Application.Json,
                            )
                        }
                    }
                }
            }.start(wait = false)
        } catch (e: Exception) {
            throw IllegalStateException(
                "FakeLlmServer failed to bind 0.0.0.0:$port — is a real local model server (oMLX) using it? " +
                    "Either run with REAL_LLM=1 or export E2E_FAKE_LLM_PORT=<free port> for BOTH `make e2e-up` and the test run.",
                e,
            )
        }
    }

    fun stop() {
        engine?.stop(500, 2000)
    }

    private fun dispatch(prompt: String, model: String): Pair<String, String>? {
        val suffix = buildString {
            if (prompt.contains("PREVIOUS VALIDATION FEEDBACK (revision pass")) append(":refine")
            if (prompt.contains("YOUR PREVIOUS OUTPUT WAS INVALID") || prompt.contains("REJECTED DRAFT")) append(":retry")
        }
        fun name(n: String) = n + suffix
        return when {
            prompt.contains("Write a professional yet casual cover letter") || model.contains("gemma") ->
                name("cover_letter") to fixture("cover_letter.txt")
            prompt.contains("JD_EXTRACTION_SKILL") || prompt.contains("<job_description>") ->
                name("jd_extraction") to fixture("jd_extraction.json")
            prompt.contains("GAP_ANALYSIS_SKILL") ->
                name("gap_analysis") to fixture("gap_analysis.json")
            prompt.contains("SUMMARY_REWRITE_SKILL") ->
                name("summary_rewrite") to fixture("summary.txt")
            prompt.contains("BULLET_REWRITE_SKILL") ->
                name("bullet_rewrite") to bulletEcho(prompt)
            prompt.contains("SKILLS_RESTRUCTURE_SKILL") ->
                name("skills_restructure") to fixture("skills_restructure.json")
            prompt.contains("ATS_VALIDATION_SKILL") ->
                name("ats_validation") to fixture("ats_validation.json")
            prompt.contains("SCORE_SKILL") || prompt.contains("\n\nJOB DESCRIPTION:\n") ->
                name("score_fit") to fixture("score_fit.json")
            else -> null
        }
    }

    private fun fixture(name: String): String =
        Files.readString(fixturesDir.resolve("llm").resolve(name)).trim()

    /**
     * Parse the roles JSON the prompt ends with (after the CANDIDATE ROLES marker) and
     * echo every role's join keys and bullets back, `rewritten` == original text —
     * deterministic, LaTeX-safe, and never discarded by the role-key join.
     */
    private fun bulletEcho(prompt: String): String {
        val marker = prompt.indexOf("CANDIDATE ROLES")
        val arrStart = prompt.indexOf('[', if (marker >= 0) marker else 0)
        check(arrStart >= 0) { "bullet_rewrite prompt carries no roles JSON array" }
        val roles = mapper.readTree(prompt.substring(arrStart))
        val out = roles.map { role ->
            mapOf(
                "role" to role.path("role").asText(),
                "company" to role.path("company").asText(),
                "start_date" to role.path("start_date").asText(),
                "bullets" to role.path("bullets").mapIndexed { idx, b ->
                    mapOf(
                        "original" to b.path("text").asText(),
                        "category" to b.path("category").asText(),
                        "rewritten" to b.path("text").asText(),
                        "must_have_hits" to emptyList<String>(),
                        "quantified" to false,
                        "seniority_signal" to (idx == 0),
                    )
                },
            )
        }
        return mapper.writeValueAsString(out)
    }
}
