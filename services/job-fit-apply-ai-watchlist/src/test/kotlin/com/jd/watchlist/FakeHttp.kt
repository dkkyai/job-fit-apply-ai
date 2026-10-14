package com.jd.watchlist

import com.jd.watchlist.http.Http
import com.jd.watchlist.http.HttpStatusException

/**
 * Canned HTTP for adapter tests: GETs by exact URL (or, failing that, [getHandler] — for adapters
 * that build long query strings), POSTs by URL with a body-matching responder.
 */
class FakeHttp(
    private val gets: Map<String, String> = emptyMap(),
    private val posts: Map<String, (String) -> String> = emptyMap(),
    private val getHandler: (String) -> String? = { null },
) : Http {
    val requests = mutableListOf<String>()

    override fun get(url: String): String {
        requests += "GET $url"
        return gets[url] ?: getHandler(url) ?: throw HttpStatusException(url, 404)
    }

    override fun postJson(url: String, json: String): String {
        requests += "POST $url $json"
        return posts[url]?.invoke(json) ?: throw HttpStatusException(url, 404)
    }
}

fun resource(name: String): String =
    FakeHttp::class.java.getResource("/fixtures/$name")?.readText() ?: error("missing fixture $name")
