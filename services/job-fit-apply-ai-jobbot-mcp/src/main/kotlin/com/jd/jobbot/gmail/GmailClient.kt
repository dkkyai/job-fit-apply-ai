package com.jd.jobbot.gmail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * The few Gmail calls JobBot makes, on the poller's account. Deliberately narrow: read one
 * message or thread, and move one message in or out of the inbox. No delete, no trash, no
 * filters, no label creation.
 */
open class GmailClient(
    private val auth: GmailAuth,
    private val baseUrl: String = "https://gmail.googleapis.com/gmail/v1/users/me",
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) {
    class GmailException(val status: Int, message: String) : RuntimeException(message)

    data class Message(
        val id: String,
        val threadId: String?,
        val labels: List<String>,
        val from: String?,
        val to: String?,
        val subject: String?,
        val date: String?,
        val snippet: String?,
        val body: String,
    )

    open fun labels(messageId: String): List<String> =
        call("GET", "/messages/${enc(messageId)}?format=minimal")["labelIds"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

    /** Add and/or remove label ids on one message; returns the labels after the change. */
    open fun modify(messageId: String, add: List<String> = emptyList(), remove: List<String> = emptyList()): List<String> {
        require(add.none { it in FORBIDDEN_LABELS } && remove.none { it in FORBIDDEN_LABELS }) { "label not allowed" }
        val body = buildJsonObject {
            put("addLabelIds", JsonArray(add.map { JsonPrimitive(it) }))
            put("removeLabelIds", JsonArray(remove.map { JsonPrimitive(it) }))
        }
        return call("POST", "/messages/${enc(messageId)}/modify", body.toString())["labelIds"]?.jsonArray
            ?.map { it.jsonPrimitive.content } ?: emptyList()
    }

    open fun message(messageId: String): Message = parse(call("GET", "/messages/${enc(messageId)}?format=full"))

    open fun thread(threadId: String): List<Message> =
        call("GET", "/threads/${enc(threadId)}?format=full")["messages"]?.jsonArray?.map { parse(it.jsonObject) } ?: emptyList()

    internal fun call(method: String, path: String, body: String? = null, retried: Boolean = false): JsonObject {
        val token = auth.token(forceRefresh = retried)
        val builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $token")
        val req = if (body != null) {
            builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body)).build()
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody()).build()
        }
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() == 401 && !retried) return call(method, path, body, retried = true)
        if (resp.statusCode() !in 200..299) {
            throw GmailException(resp.statusCode(), "Gmail $method $path → ${resp.statusCode()}: ${resp.body().take(200)}")
        }
        return JSON.parseToJsonElement(resp.body().ifBlank { "{}" }).jsonObject
    }

    private fun parse(m: JsonObject): Message {
        val payload = m["payload"]?.jsonObject
        val headers = payload?.get("headers")?.jsonArray?.associate {
            val h = it.jsonObject
            (h["name"]?.jsonPrimitive?.content ?: "").lowercase() to (h["value"]?.jsonPrimitive?.content ?: "")
        } ?: emptyMap()
        return Message(
            id = m["id"]?.jsonPrimitive?.content ?: "",
            threadId = m["threadId"]?.jsonPrimitive?.content,
            labels = m["labelIds"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            from = headers["from"], to = headers["to"], subject = headers["subject"], date = headers["date"],
            snippet = m["snippet"]?.jsonPrimitive?.content,
            body = payload?.let { textOf(it) }.orEmpty(),
        )
    }

    /** The message's plain-text body: the first text/plain part, else the first text/html part, stripped. */
    internal fun textOf(part: JsonObject): String? =
        findPart(part, "text/plain")?.let { decode(it) } ?: findPart(part, "text/html")?.let { stripHtml(decode(it)) }

    private fun findPart(part: JsonObject, mime: String): String? {
        val data = part["body"]?.jsonObject?.get("data")?.jsonPrimitive?.content
        if (part["mimeType"]?.jsonPrimitive?.content == mime && data != null) return data
        return part["parts"]?.jsonArray?.firstNotNullOfOrNull { findPart(it.jsonObject, mime) }
    }

    private fun decode(data: String) = String(Base64.getUrlDecoder().decode(data.trimEnd('=')), Charsets.UTF_8)

    private fun stripHtml(html: String) = html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .lines().joinToString("\n") { it.trim() }.replace(Regex("\n{3,}"), "\n\n").trim()

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /** System labels JobBot must never touch: deletion and spam are not archive. */
        val FORBIDDEN_LABELS = setOf("TRASH", "SPAM")
    }
}
