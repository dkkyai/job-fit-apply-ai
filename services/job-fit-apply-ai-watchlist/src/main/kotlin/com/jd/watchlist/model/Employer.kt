package com.jd.watchlist.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Files
import java.nio.file.Path

/**
 * One watched employer, as listed in employers.yaml. [ats] picks the adapter; the remaining
 * fields are that adapter's parameters. Employers on platforms without an adapter yet stay in the
 * file (so it is the whole watchlist) and are skipped at run time.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Employer(
    val company: String,
    val ats: String,
    /** Greenhouse / Lever / Ashby board token. */
    val board: String? = null,
    /** Workday: e.g. boeing.wd1.myworkdayjobs.com */
    val host: String? = null,
    /** Workday tenant, e.g. boeing. */
    val tenant: String? = null,
    /** Workday career site, e.g. EXTERNAL_CAREERS. */
    val site: String? = null,
    /** RSS feed URL. */
    val feed: String? = null,
    /** RSS: CSS selector for the description on the posting page, used when the feed item has none. */
    val detailSelector: String? = null,
    /** Human-facing careers page (not polled). */
    val careersUrl: String? = null,
    /** high / medium / low — how often this employer hires QE in the Seattle area. */
    val qeFit: String? = null,
    val enabled: Boolean = true,
) {
    /** Stable per-employer scope for idempotency keys: survives a company rename. */
    val boardKey: String
        get() = listOfNotNull(board, tenant?.let { "$it/$site" }, feed?.let { feedKey(it) })
            .firstOrNull() ?: company.lowercase().replace(Regex("[^a-z0-9]+"), "-")

    private fun feedKey(url: String): String =
        url.substringAfter("://").substringBefore("?").substringBefore("/")
}

object EmployerCatalog {
    private val yaml = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun parse(text: String): List<Employer> = yaml.readValue(text)

    /** Load [file] if given, else the bundled employers.yaml. */
    fun load(file: String): List<Employer> {
        val text = if (file.isNotBlank()) Files.readString(Path.of(file))
        else EmployerCatalog::class.java.getResource("/employers.yaml")?.readText()
            ?: error("bundled employers.yaml missing from the classpath")
        return parse(text)
    }
}
