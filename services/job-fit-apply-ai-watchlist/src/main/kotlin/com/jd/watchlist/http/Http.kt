package com.jd.watchlist.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Minimal HTTP surface the adapters need — an interface so tests can serve canned feeds. */
interface Http {
    fun get(url: String): String
    fun postJson(url: String, json: String): String
}

class HttpStatusException(val url: String, val status: Int) :
    RuntimeException("HTTP $status from $url")

/** JDK-client implementation. A browser-like User-Agent: several ATS front doors reject bare clients. */
class JdkHttp(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) : Http {
    override fun get(url: String): String = send(base(url).GET().build())

    override fun postJson(url: String, json: String): String =
        send(base(url).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build())

    private fun base(url: String): HttpRequest.Builder = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofSeconds(30))
        .header("User-Agent", USER_AGENT)
        .header("Accept", "application/json, application/rss+xml, text/xml, text/html;q=0.9, */*;q=0.8")

    private fun send(req: HttpRequest): String {
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) throw HttpStatusException(req.uri().toString(), resp.statusCode())
        return resp.body()
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129 Safari/537.36"
    }
}
