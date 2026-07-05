package com.jd.jsearch.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.jd.jsearch.config.Config
import com.jd.jsearch.model.JobListing
import com.jd.jsearch.search.JSearchConfig
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Client for the JSearch RapidAPI (jsearch.p.rapidapi.com). Pure HTTP (JDK client) + JSON — no
 * Chrome, no LLM. [callCount] tracks API calls made (for the monthly-quota log).
 */
class JSearchClient(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    apiKey: String? = null,
) {
    private val apiKey = apiKey?.takeIf { it.isNotBlank() }
        ?: Config.JSEARCH_API_KEY.takeIf { it.isNotEmpty() }
        ?: throw IllegalStateException("JSEARCH_API_KEY is not configured — set it in .env or environment")

    private val mapper = ObjectMapper()
    var callCount: Int = 0
        private set

    fun search(configs: List<JSearchConfig>): List<JobListing> {
        val seen = LinkedHashSet<String>()
        val results = mutableListOf<JobListing>()
        for (config in configs) {
            val (jobCity, jobState) = config.location?.takeIf { it.isNotBlank() }?.let { loc ->
                val parts = loc.split(",", limit = 2)
                parts.getOrNull(0)?.trim() to parts.getOrNull(1)?.trim()
            } ?: (null to null)

            for (query in config.queries) {
                for (page in 1..config.numPages) {
                    for (listing in fetchPage(query, page, config, jobCity, jobState)) {
                        if (seen.add(listing.jobId)) results.add(listing)
                    }
                }
            }
        }
        return results
    }

    private fun fetchPage(
        query: String,
        page: Int,
        config: JSearchConfig,
        jobCity: String?,
        jobState: String?,
    ): List<JobListing> {
        val enc = { s: String -> URLEncoder.encode(s, "UTF-8") }
        val qs = buildString {
            append("query=${enc(query)}")
            append("&page=$page")
            append("&num_pages=1")
            append("&date_posted=${config.datePosted}")
            append("&country=us")
            if (config.remoteJobsOnly) append("&remote_jobs_only=true")
            if (!jobCity.isNullOrBlank()) append("&job_city=${enc(jobCity)}")
            if (!jobState.isNullOrBlank()) append("&job_state=${enc(jobState)}")
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://jsearch.p.rapidapi.com/search?$qs"))
            .header("X-RapidAPI-Key", apiKey)
            .header("X-RapidAPI-Host", "jsearch.p.rapidapi.com")
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build()

        callCount++
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        println("[JSearchClient] HTTP ${response.statusCode()}, body ${response.body()?.length ?: 0} bytes (call #$callCount)")
        if (response.statusCode() != 200) {
            println("[JSearchClient] ERROR response: ${response.body()}")
            return emptyList()
        }
        val data = mapper.readTree(response.body()).path("data")
        if (!data.isArray) {
            println("[JSearchClient] 'data' missing/not-array")
            return emptyList()
        }
        return data.mapNotNull { node ->
            try { mapper.treeToValue(node, JobListing::class.java) } catch (e: Exception) {
                println("[JSearchClient] deserialize failed: ${e.message}"); null
            }
        }
    }
}
