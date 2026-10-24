package com.jd.jobbot.hermes

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The client for Hermes' /v1/runs API, against a stand-in that answers the way Hermes does. */
class HermesRunsTest {
    private class Seen(val method: String, val path: String, val rawPath: String, val auth: String?, val type: String?, val body: String)

    private val seen = CopyOnWriteArrayList<Seen>()
    private var reply: (String, String) -> Pair<Int, String> = { _, _ -> 200 to "{}" }
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            seen += Seen(ex.requestMethod, ex.requestURI.path, ex.requestURI.rawPath, ex.requestHeaders.getFirst("Authorization"), ex.requestHeaders.getFirst("Content-Type"), body)
            val (code, text) = reply(ex.requestMethod, ex.requestURI.path)
            val bytes = text.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val url get() = "http://127.0.0.1:${server.address.port}"
    private val runs get() = HermesRuns("$url/", "secret-key")

    @AfterTest fun stop() = server.stop(0)

    @Test
    fun `a run starts with the instructions, the task, its own session and the key`() {
        reply = { _, _ -> 202 to """{"run_id":"run_abc123","status":"started"}""" }
        val id = runs.start("be careful", "fill it in", "fill-tok", "deepseek-v4.1-flash:cloud")
        assertEquals("run_abc123", id)
        val r = seen.single()
        assertEquals("POST", r.method); assertEquals("/v1/runs", r.path)
        assertEquals("Bearer secret-key", r.auth); assertEquals("application/json", r.type)
        val body = Json.parseToJsonElement(r.body).jsonObject
        assertEquals("fill it in", body["input"]!!.jsonPrimitive.content)
        assertEquals("be careful", body["instructions"]!!.jsonPrimitive.content)
        assertEquals("fill-tok", body["session_id"]!!.jsonPrimitive.content)
        assertEquals("deepseek-v4.1-flash:cloud", body["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no model is sent when none is configured`() {
        reply = { _, _ -> 202 to """{"run_id":"r1"}""" }
        runs.start("i", "x", "s", null)
        runs.start("i", "x", "s", "  ")
        assertTrue(seen.none { "\"model\"" in it.body }, "Hermes uses its own configured model")
    }

    @Test
    fun `text with quotes, newlines and unicode survives the trip`() {
        reply = { _, _ -> 202 to """{"run_id":"r1"}""" }
        val task = "Fill \"Acme\" — Staff SDET\nline two \\ backslash é 日本"
        runs.start("rules\n\"quoted\"", task, "s", null)
        assertEquals(task, Json.parseToJsonElement(seen.single().body).jsonObject["input"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an accepted run with no id is an error`() {
        reply = { _, _ -> 202 to """{"status":"started"}""" }
        val e = assertFailsWith<HermesRuns.HermesException> { runs.start("i", "x", "s", null) }
        assertTrue("no run_id" in e.message!!, e.message)
    }

    @Test
    fun `a refusal carries its status so the caller can react`() {
        reply = { _, _ -> 401 to """{"error":{"message":"Invalid gateway API key"}}""" }
        val e = assertFailsWith<HermesRuns.HermesException> { runs.start("i", "x", "s", null) }
        assertEquals(401, e.status)
        assertTrue("Invalid gateway API key" in e.message!!)
        reply = { _, _ -> 429 to """{"error":"busy"}""" }
        assertEquals(429, assertFailsWith<HermesRuns.HermesException> { runs.start("i", "x", "s", null) }.status)
    }

    @Test
    fun `status reads the state, the output and the error`() {
        reply = { _, _ -> 200 to """{"run_id":"r1","status":"completed","output":"All done.","error":null,"session_id":"fill-x"}""" }
        val s = runs.status("r1")
        assertEquals("completed", s.status); assertEquals("All done.", s.output); assertEquals(null, s.error)
        assertTrue(s.terminal)
        assertEquals("/v1/runs/r1", seen.single().path); assertEquals("GET", seen.single().method)
    }

    @Test
    fun `which statuses are final`() {
        for (done in listOf("completed", "failed", "cancelled", "interrupted")) assertTrue(HermesRuns.RunStatus(done, null, null).terminal, done)
        for (going in listOf("queued", "running", "stopping", "started", "unknown")) assertFalse(HermesRuns.RunStatus(going, null, null).terminal, going)
    }

    @Test
    fun `a failed run's error is kept`() {
        reply = { _, _ -> 200 to """{"status":"failed","error":"model unavailable"}""" }
        assertEquals("model unavailable", runs.status("r1").error)
    }

    @Test
    fun `a status without a state reads as unknown, never as final`() {
        reply = { _, _ -> 200 to "{}" }
        assertFalse(runs.status("r1").terminal)
    }

    @Test
    fun `a run id cannot change the path it is sent to`() {
        reply = { _, _ -> 200 to """{"status":"running"}""" }
        runs.status("a/../../admin?x=1 y")
        assertEquals("/v1/runs/a%2F..%2F..%2Fadmin%3Fx%3D1+y", seen.single().rawPath, "slashes, dots and a query in an id stay inside one path segment")
    }

    @Test
    fun `stop asks Hermes to interrupt, and never throws`() {
        reply = { _, _ -> 200 to """{"run_id":"r1","status":"stopping"}""" }
        runs.stop("r1")
        assertEquals("/v1/runs/r1/stop", seen.single().path); assertEquals("POST", seen.single().method)
        reply = { _, _ -> 409 to """{"error":"run_not_active"}""" }
        runs.stop("r1")
        HermesRuns("http://127.0.0.1:1", "k").stop("r1")
    }

    @Test
    fun `an unreachable Hermes is an exception the caller can handle`() {
        assertFailsWith<java.io.IOException> { HermesRuns("http://127.0.0.1:1", "k").start("i", "x", "s", null) }
    }
}
