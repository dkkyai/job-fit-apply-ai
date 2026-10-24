package com.jd.jobbot.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Test-only. jobbot-mcp no longer calls a model: Hermes plans the fills. These stand-ins let the scenarios that used
 * to script the model (Google sign-in, passwords, refused submits, hand-offs) keep running, now against FillSession.
 */
fun interface Llm {
    fun json(system: String, user: String): JsonObject
}

object OpenAiCompatibleLlm {
    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The first JSON object in a model reply, tolerating a fenced block. */
    fun parseJsonObject(text: String): JsonObject {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        require(start >= 0 && end > start) { "no JSON object in model reply: ${text.take(120)}" }
        return JSON.parseToJsonElement(text.substring(start, end + 1)).jsonObject
    }
}
