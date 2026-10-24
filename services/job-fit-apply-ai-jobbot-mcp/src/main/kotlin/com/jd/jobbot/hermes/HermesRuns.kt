package com.jd.jobbot.hermes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Hermes' durable `/v1/runs` API: one run is one agent task in its own session, independent of the Telegram chat, so
 * a long application fill never blocks (or is interrupted by) a conversation. Authenticated with the shared API key.
 */
open class HermesRuns(
    baseUrl: String,
    private val apiKey: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) {
    class HermesException(val status: Int, message: String) : Exception(message)

    data class RunStatus(val status: String, val output: String?, val error: String?) {
        val terminal: Boolean get() = status in TERMINAL
    }

    private val base = baseUrl.trimEnd('/')

    /** Starts a run and returns its id. [model] is optional: omitted, the run uses Hermes' own configured model. */
    open fun start(instructions: String, input: String, sessionId: String, model: String?): String {
        val body = buildJsonObject {
            put("input", input)
            put("instructions", instructions)
            put("session_id", sessionId)
            model?.takeIf { it.isNotBlank() }?.let { put("model", it) }
        }
        val response = send("POST", "/v1/runs", body.toString())
        return Json.parseToJsonElement(response).jsonObject["run_id"]?.jsonPrimitive?.contentOrNull
            ?: throw HermesException(502, "Hermes accepted the run but returned no run_id: ${response.take(200)}")
    }

    open fun status(runId: String): RunStatus {
        val o = Json.parseToJsonElement(send("GET", "/v1/runs/${enc(runId)}", null)).jsonObject
        return RunStatus(
            status = o["status"]?.jsonPrimitive?.contentOrNull ?: "unknown",
            output = o["output"]?.jsonPrimitive?.contentOrNull,
            error = (o["error"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull,
        )
    }

    /** Asks Hermes to interrupt a run. Best effort: a run that already ended is not an error. */
    open fun stop(runId: String) {
        runCatching { send("POST", "/v1/runs/${enc(runId)}/stop", "{}") }
    }

    private fun send(method: String, path: String, body: String?): String {
        val request = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw HermesException(response.statusCode(), "Hermes answered ${response.statusCode()}: ${response.body().take(300)}")
        return response.body()
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8)

    companion object {
        val TERMINAL = setOf("completed", "failed", "cancelled", "interrupted")
    }
}
