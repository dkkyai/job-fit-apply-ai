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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Captures the notifier's outbound Discord + Telegram posts. The compose override points
 * DISCORD_API_BASE / TELEGRAM_API_BASE at this server, so the paths arrive in the real
 * shapes: `/api/v10/channels/{id}/messages` and `/bot{token}/sendMessage`.
 */
class MockNotificationSink(private val port: Int) {
    data class Received(val channel: String, val path: String, val body: JsonNode)

    private val mapper = ObjectMapper()
    private var engine: ApplicationEngine? = null

    /**
     * CopyOnWriteArrayList, not synchronizedList: the accessors below iterate this while
     * the Netty handler thread appends (the notifier posts Discord then Telegram
     * back-to-back), and a synchronizedList's iterator is not itself synchronized — a
     * plain `filter` would intermittently throw ConcurrentModificationException and abort
     * the whole suite from @BeforeAll.
     */
    val received: MutableList<Received> = CopyOnWriteArrayList()

    fun start() {
        // 0.0.0.0 so the notifier container reaches us via host.docker.internal; this is
        // LAN/tailnet-visible for the run, and only ever returns {"ok":true}.
        engine = embeddedServer(Netty, port = port, host = "0.0.0.0") {
            routing {
                post("{...}") {
                    val path = call.request.path()
                    val body = mapper.readTree(call.receiveText())
                    // Classify strictly against the credentials compose actually gave the
                    // notifier. A post to a plausible-but-wrong channel id or bot token
                    // lands in "unknown" and is reported, rather than being accepted as a
                    // healthy delivery.
                    val channel = when (path) {
                        "/api/v10/channels/${E2eConfig.discordChannelId}/messages" -> "discord"
                        "/bot${E2eConfig.telegramBotToken}/sendMessage" -> "telegram"
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

    /**
     * Everything that arrived at an unrecognised path — a wrong channel id, a wrong bot
     * token, or a changed URL shape. Tests fold this into their timeout messages so the
     * symptom ("no Discord message") names the cause instead of pointing at the notifier.
     */
    fun unknownPaths(): List<String> =
        received.filter { it.channel == "unknown" }.map { it.path }.distinct()

    /** Diagnostic summary for failure messages: what actually landed here. */
    fun describe(): String =
        received.groupingBy { it.channel }.eachCount().toString() +
            unknownPaths().takeIf { it.isNotEmpty() }?.let { " unexpected paths=$it" }.orEmpty()
}
