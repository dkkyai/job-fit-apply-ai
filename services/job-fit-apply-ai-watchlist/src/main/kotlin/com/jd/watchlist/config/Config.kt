package com.jd.watchlist.config

import io.github.cdimascio.dotenv.Dotenv

/**
 * Configuration for the watchlist ingestion service: once a day, poll the public job feeds of a
 * curated list of employers (employers.yaml) and submit pre-filtered postings to the bridge as
 * JD_SCRAPED with source CAREER_SITE. No browser, no LLM.
 */
object Config {
    private val DOTENV: Dotenv = Dotenv.configure()
        .filename(System.getProperty("dotenv.file", ".env"))
        .ignoreIfMissing()
        .load()

    private fun get(key: String, default: String): String =
        DOTENV.get(key)?.takeIf { it.isNotBlank() } ?: System.getenv(key)?.takeIf { it.isNotBlank() } ?: default

    /** A malformed number falls back to [default] with a warning instead of crashing startup. */
    private fun getLong(key: String, default: Long): Long = parseLong(key, get(key, default.toString()), default)

    internal fun parseLong(key: String, raw: String, default: Long): Long =
        raw.trim().toLongOrNull() ?: default.also { System.err.println("[config] $key=\"$raw\" is not a number — using $default") }

    val JD_BRIDGE_URL: String = get("JD_BRIDGE_URL", "http://127.0.0.1:8765")

    /** Minimum spacing between real polls — the last-run gate. Default 24h. */
    val WATCHLIST_INTERVAL_MS: Long = getLong("WATCHLIST_INTERVAL_MS", 86_400_000L)

    /** Persisted state (last_run) — a small mounted volume so restarts don't re-poll. */
    val STATE_FILE: String = get("WATCHLIST_STATE_FILE", "/state/watchlist.json")

    /** Per-employer outcome of the last run, for doctor.sh and humans. */
    val HEALTH_FILE: String = get("WATCHLIST_HEALTH_FILE", "/state/watchlist-health.json")

    /** Employer list on disk; blank = the bundled classpath resource employers.yaml. */
    val EMPLOYERS_FILE: String = get("WATCHLIST_EMPLOYERS_FILE", "")

    /** Titles must match this (case-insensitive) to reach the bridge — the LLM only sees QE-ish roles. */
    val TITLE_INCLUDE: String = get(
        "WATCHLIST_TITLE_INCLUDE",
        """\b(qa|qe|sdet|quality|test|tester|testing|verification|validation)\b""",
    )

    /** Titles matching this are dropped even when they match [TITLE_INCLUDE]. */
    val TITLE_EXCLUDE: String = get(
        "WATCHLIST_TITLE_EXCLUDE",
        """\b(intern|internship|technician|clinical|nurse|driver|warehouse|asic|fpga|rtl|soc|silicon|design verification|formal verification|microbiolog\w*|laboratory|aml)\b""",
    )

    /**
     * Platforms without a cheap "list everything" call (Workday, Eightfold, Jibe, Amazon) are
     * searched by these terms; titles are still matched client-side afterwards.
     * WATCHLIST_WORKDAY_SEARCH_TERMS is the original, Workday-only name and still honoured.
     */
    val SEARCH_TERMS: List<String> =
        get("WATCHLIST_SEARCH_TERMS", get("WATCHLIST_WORKDAY_SEARCH_TERMS", "test,quality,sdet,qa"))
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** Pause between paged requests to one employer. */
    val PAGE_DELAY_MS: Long = getLong("WATCHLIST_PAGE_DELAY_MS", 300L)

    /** Eightfold (Microsoft, Starbucks…) rate-limits per IP over a long window: pace it slower. */
    val EIGHTFOLD_PAGE_DELAY_MS: Long = getLong("WATCHLIST_EIGHTFOLD_PAGE_DELAY_MS", 2_000L)

    /** Pause between employers so the daily sweep stays polite. */
    val INTER_EMPLOYER_DELAY_MS: Long = getLong("WATCHLIST_INTER_EMPLOYER_DELAY_MS", 750L)
}
