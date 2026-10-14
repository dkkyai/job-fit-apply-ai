package com.jd.watchlist.adapters

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.jd.watchlist.http.Http
import com.jd.watchlist.model.Employer
import com.jd.watchlist.model.Posting
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * Reads one ATS platform's public job feed. One adapter per platform, not per employer: the
 * employer's entry in employers.yaml supplies the board/tenant/feed parameters.
 */
interface FeedAdapter {
    /** All postings currently listed by [employer] (location/title filtering happens later). */
    fun list(employer: Employer): List<Posting>

    /** Plain-text description, fetching it when [Posting.description] is absent. Null if unavailable. */
    fun describe(employer: Employer, posting: Posting): String? = posting.description
}

/** The adapters this service ships, keyed by the `ats` value in employers.yaml. */
object Adapters {
    fun registry(http: Http, workdaySearchTerms: List<String>): Map<String, FeedAdapter> = mapOf(
        "greenhouse" to GreenhouseAdapter(http),
        "lever" to LeverAdapter(http),
        "ashby" to AshbyAdapter(http),
        "workday" to WorkdayAdapter(http, workdaySearchTerms),
        "rss" to RssAdapter(http),
    )
}

internal val json: ObjectMapper = ObjectMapper()
    .registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

/** HTML (optionally entity-escaped, as Greenhouse sends it) → readable plain text with line breaks. */
internal fun htmlToText(html: String?, escaped: Boolean = false): String? {
    if (html.isNullOrBlank()) return null
    val markup = if (escaped) Parser.unescapeEntities(html, false) else html
    val doc = Jsoup.parse(markup)
    doc.select("br").append("\\n")
    doc.select("p, li, h1, h2, h3, h4, h5, h6, div").prepend("\\n")
    return doc.text().replace("\\n", "\n")
        .lines().joinToString("\n") { it.trim() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
        .ifBlank { null }
}
