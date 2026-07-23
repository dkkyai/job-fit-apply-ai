package com.jd.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.util.Collections

/**
 * Captures the notifier's outbound Discord + Telegram posts. The compose override points
 * DISCORD_API_BASE / TELEGRAM_API_BASE at this server, so the paths arrive in the real
 * shapes: `/api/v10/channels/{id}/messages` and `/bot{token}/sendMessage`.
 */
class MockNotificationSink(private val port: Int) {
    data class Received(val channel: String, val path: String, val body: JsonNode)

    private val mapper = ObjectMapper()
    private var engine: ApplicationEngine? = null

    val received: MutableList<Received> = Collections.synchronizedList(mutableListOf())

    fun start() {
        engine = embeddedServer(Netty, port = port, host = "0.0.0.0") {
            routing {
                post("{...}") {
                    val path = call.request.path()
                    val body = mapper.readTree(call.receiveText())
                    val channel = when {
                        path.contains("/channels/") && path.endsWith("/messages") -> "discord"
                        path.endsWith("/sendMessage") -> "telegram"
                        else -> "unknown"
                    }
                    received += Received(channel, path, body)
                    println("[sink] $channel ← $path")
                    call.respondText("""{"ok":true}""", ContentType.Application.Json)
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        engine?.stop(500, 2000)
    }

    fun discordTexts(): List<String> =
        received.filter { it.channel == "discord" }.map { it.body.path("content").asText() }

    fun telegramTexts(): List<String> =
        received.filter { it.channel == "telegram" }.map { it.body.path("text").asText() }
}
