package com.jd.jsearch.search

/**
 * A JSearch search profile — a location + a set of query strings. [DEFAULT_LIST] is the standing
 * SDET/QA search (Seattle-local + remote). Each (config × query × page) is one API call:
 * DEFAULT_LIST = 2 configs × 2 queries × 1 page = 4 calls per run.
 */
data class JSearchConfig(
    val queries: List<String>,
    val numPages: Int = 1,
    val datePosted: String = "today",   // all, today, 3days, week, month
    val location: String? = "Seattle, WA",
    val radius: Int = 30,
    val remoteJobsOnly: Boolean = false,
) {
    companion object {
        val DEFAULT_LIST: List<JSearchConfig> = listOf(
            JSearchConfig(
                location = "Seattle, WA",
                radius = 30,
                queries = listOf(
                    """SDET OR "test engineer" OR "QE" OR "QA engineer" mobile OR iOS OR Android""",
                    """SDET OR "test engineer" OR "QE" OR "QA engineer" -mobile -iOS -Android""",
                ),
            ),
            JSearchConfig(
                location = null,
                remoteJobsOnly = true,
                queries = listOf(
                    """SDET OR "test engineer" OR "QE" OR "QA engineer" mobile OR iOS OR Android""",
                    """SDET OR "test engineer" OR "QE" OR "QA engineer" -mobile -iOS -Android""",
                ),
            ),
        )
    }
}
