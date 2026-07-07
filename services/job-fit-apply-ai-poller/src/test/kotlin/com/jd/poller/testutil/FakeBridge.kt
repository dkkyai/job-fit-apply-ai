package com.jd.poller.testutil

import com.jd.poller.bridge.CompletedJob
import com.jd.poller.bridge.Json
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * An in-process stand-in for the bridge HTTP API, backed by the JDK HttpServer (no extra deps).
 * Lets the Poller's real HTTP client + loops run against a real socket without touching the
 * shared production bridge. Implements only the endpoints the Poller calls.
 */
class FakeBridge {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val mapper = Json.mapper

    /** Email payloads received via POST /api/emails (parsed as maps). */
    val submitted = CopyOnWriteArrayList<Map<String, Any?>>()

    /** Jobs currently in the completed feed (test pushes them via [complete]). */
    private val completed = CopyOnWriteArrayList<CompletedJob>()
    private val writtenBack = ConcurrentHashMap.newKeySet<String>()

    /** Artifact bytes keyed by bridge-relative path, e.g. "/api/jobs/j/resume.pdf". */
    val artifacts = ConcurrentHashMap<String, ByteArray>()

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    fun complete(job: CompletedJob) = completed.add(job)
    fun feedRemaining(): List<CompletedJob> = completed.filter { it.jobId !in writtenBack }
    fun wasWrittenBack(jobId: String): Boolean = jobId in writtenBack

    fun start(): FakeBridge {
        server.createContext("/api/emails") { ex -> handleEmails(ex) }
        server.createContext("/api/jobs/completed") { ex -> handleCompleted(ex) }
        server.createContext("/api/jobs/") { ex -> handleJobPath(ex) }
        server.executor = Executors.newSingleThreadExecutor()
        server.start()
        return this
    }

    fun stop() = server.stop(0)

    private fun handleEmails(ex: HttpExchange) {
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        @Suppress("UNCHECKED_CAST")
        val parsed = mapper.readValue(body, Map::class.java) as Map<String, Any?>
        submitted.add(parsed)
        val messageId = parsed["message_id"] as? String ?: "unknown"
        respond(ex, 200, mapOf("job_id" to "job-$messageId", "status" to "pending", "deduped" to false))
    }

    private fun handleCompleted(ex: HttpExchange) {
        val since = queryParam(ex, "since")?.toLongOrNull() ?: 0L
        val out = feedRemaining().filter { it.completedSeq > since }
        respondRaw(ex, 200, mapper.writeValueAsBytes(out))
    }

    private fun handleJobPath(ex: HttpExchange) {
        val path = ex.requestURI.path
        when {
            path.endsWith("/writeback-done") -> {
                val jobId = path.removePrefix("/api/jobs/").removeSuffix("/writeback-done")
                writtenBack.add(jobId)
                respond(ex, 200, mapOf("status" to "ok"))
            }
            artifacts.containsKey(path) -> respondRaw(ex, 200, artifacts[path]!!)
            else -> respond(ex, 404, mapOf("detail" to "not found"))
        }
    }

    private fun queryParam(ex: HttpExchange, name: String): String? =
        ex.requestURI.query?.split("&")?.firstOrNull { it.startsWith("$name=") }?.substringAfter("=")

    private fun respond(ex: HttpExchange, code: Int, body: Any) = respondRaw(ex, code, mapper.writeValueAsBytes(body))

    private fun respondRaw(ex: HttpExchange, code: Int, bytes: ByteArray) {
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
