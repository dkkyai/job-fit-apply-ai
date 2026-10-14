package com.jd.watchlist.filter

import com.jd.watchlist.model.Posting

/**
 * Cheap pre-filter that runs before anything reaches the bridge (and so before any LLM tokens are
 * spent): the title must look like a QE role, and the location must be the Seattle area, somewhere
 * in Washington, remote, or unknown. Unknown passes because the Processor's WA-state location gate
 * makes the final call and fails open the same way.
 */
class PostingFilter(titleInclude: String, titleExclude: String) {
    private val include = Regex(titleInclude, RegexOption.IGNORE_CASE)
    private val exclude = titleExclude.takeIf { it.isNotBlank() }?.let { Regex(it, RegexOption.IGNORE_CASE) }

    fun titleMatches(title: String): Boolean =
        include.containsMatchIn(title) && exclude?.containsMatchIn(title) != true

    fun locationMatches(location: String): Boolean {
        if (location.isBlank()) return true
        return location.split('|', ';').any { part ->
            (AREA.containsMatchIn(part) && !DC.containsMatchIn(part)) || REMOTE.containsMatchIn(part)
        }
    }

    fun matches(p: Posting): Boolean = titleMatches(p.title) && locationMatches(p.location)

    companion object {
        private val AREA = Regex(
            """seattle|bellevue|redmond|kirkland|bothell|issaquah|everett|renton|\bkent\b|tacoma|tukwila|lynnwood|""" +
                """mukilteo|woodinville|sammamish|auburn|federal way|seatac|mountlake|snoqualmie|arlington, wa|""" +
                """\bWA\b|washington""",
            RegexOption.IGNORE_CASE,
        )
        private val DC = Regex("""\bD\.?C\b|district of columbia""", RegexOption.IGNORE_CASE)
        private val REMOTE = Regex("""\bremote\b""", RegexOption.IGNORE_CASE)
    }
}
