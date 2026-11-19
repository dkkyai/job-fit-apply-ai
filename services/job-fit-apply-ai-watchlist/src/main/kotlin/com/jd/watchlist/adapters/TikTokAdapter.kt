package com.jd.watchlist.adapters

import com.jd.watchlist.http.Http
import com.jd.watchlist.model.Employer
import com.jd.watchlist.model.Posting

/**
 * TikTok's public job search, on either of its two portals: Life at TikTok (TikTok / ByteDance
 * Seattle–Bellevue) or the TikTok USDS Joint Venture board (careers.tiktokusds.com), which posts
 * separately — the same role appears once per city there, never on Life at TikTok. POSTed per
 * search term with city codes from [Employer.location] (comma-separated, e.g. "CT_157" = Seattle,
 * the same code on both portals); the API only answers with the portal's `website-path` header
 * (`tiktok`, or [Employer.websitePath] — "usds" — when [Employer.host] names the USDS host).
 * Description and requirements come inline.
 */
class TikTokAdapter(
    private val http: Http,
    private val searchTerms: List<String>,
    private val pause: () -> Unit = {},
    private val maxPagesPerTerm: Int = 5,
) : FeedAdapter {

    override fun list(employer: Employer): List<Posting> {
        val cities = employer.location?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: listOf(SEATTLE)
        val seen = LinkedHashMap<String, Posting>()
        var calls = 0
        for (term in employer.search ?: searchTerms) {
            for (page in 0 until maxPagesPerTerm) {
                if (calls++ > 0) pause()
                val body = json.writeValueAsString(
                    mapOf(
                        "keyword" to term, "limit" to PAGE, "offset" to page * PAGE, "location_code_list" to cities,
                        "job_category_id_list" to emptyList<String>(), "subject_id_list" to emptyList<String>(),
                        "recruitment_id_list" to emptyList<String>(),
                    )
                )
                val data = json.readTree(http.postJson(searchUrl(employer), body, headers(employer))).path("data")
                val posts = data.path("job_post_list")
                for (p in posts) {
                    val id = p.path("id").asText("")
                    if (id.isBlank() || id in seen) continue
                    val description = listOf(
                        p.path("description").asText(""),
                        p.path("requirement").asText("").takeIf { it.isNotBlank() }?.let { "Qualifications\n$it" }.orEmpty(),
                    ).filter { it.isNotBlank() }.joinToString("\n\n").ifBlank { null }
                    seen[id] = Posting(
                        company = employer.company,
                        externalId = id,
                        title = p.path("title").asText(""),
                        location = p.path("city_info").path("en_name").asText(""),
                        url = postingUrl(employer, id),
                        description = description,
                    )
                }
                if (posts.size() < PAGE || (page + 1) * PAGE >= data.path("count").asInt(0)) break
            }
        }
        return seen.values.toList()
    }

    /** The USDS portal serves its search from the careers host itself, not the supplier API host. */
    private fun searchUrl(employer: Employer): String =
        employer.host?.let { "https://$it/api/v1/search/job/posts" } ?: SEARCH_URL

    private fun headers(employer: Employer): Map<String, String> {
        val origin = employer.host?.let { "https://$it" } ?: "https://lifeattiktok.com"
        return mapOf(
            "website-path" to (employer.websitePath ?: "tiktok"),
            "Origin" to origin,
            "Referer" to "$origin/",
        )
    }

    private fun postingUrl(employer: Employer, id: String): String =
        if (employer.host != null && employer.websitePath != null)
            "https://${employer.host}/${employer.websitePath}/position/$id/detail"
        else "https://lifeattiktok.com/search/$id"

    companion object {
        const val SEATTLE = "CT_157"
        private const val PAGE = 50
        private const val SEARCH_URL = "https://api.lifeattiktok.com/api/v1/public/supplier/search/job/posts"
    }
}
