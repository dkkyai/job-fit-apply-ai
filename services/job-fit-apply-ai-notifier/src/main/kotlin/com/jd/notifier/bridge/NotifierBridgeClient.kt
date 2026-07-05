package com.jd.notifier.bridge

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.jd.notifier.config.Config
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.core5.http.io.entity.EntityUtils

/** A completed-job event from the bridge stream (the fields a Notifier needs). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CompletedEvent(
    val jobId: String,
    val completedSeq: Long = 0,
    val status: String = "",
    val company: String? = null,
    val roleTitle: String? = null,
    val fitScore: Int? = null,
    val pipelineAction: String? = null,
    val jobUrl: String? = null,
    val artifactUrl: String? = null,
    val error: String? = null,
)

/**
 * Reads the bridge's completed-feed as an event stream (`?all=true`, cursor by `completed_seq`).
 */
open class NotifierBridgeClient(
    private val baseUrl: String = Config.JD_BRIDGE_URL,
) {
    private val http = HttpClients.createDefault()
    private val mapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    /** GET /api/jobs/completed?since=&limit=&all=true — completed events with seq > since, ASC. */
    open fun fetchEvents(since: Long, limit: Int = 50): List<CompletedEvent> {
        val req = HttpGet("$baseUrl/api/jobs/completed?since=$since&limit=$limit&all=true")
        return http.execute(req) { resp ->
            val body = EntityUtils.toString(resp.entity, Charsets.UTF_8)
            check(resp.code == 200) { "GET /api/jobs/completed → ${resp.code}: $body" }
            mapper.readValue(body, Array<CompletedEvent>::class.java).toList()
        }
    }

    /** GET /api/jobs/completed/head — current max completed_seq (for cold-start cursor seeding). */
    open fun headSeq(): Long {
        val req = HttpGet("$baseUrl/api/jobs/completed/head")
        return http.execute(req) { resp ->
            val body = EntityUtils.toString(resp.entity, Charsets.UTF_8)
            check(resp.code == 200) { "GET /api/jobs/completed/head → ${resp.code}: $body" }
            mapper.readTree(body).get("max_seq").asLong(0L)
        }
    }
}
