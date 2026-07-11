package com.jd.jsearch.config

import io.github.cdimascio.dotenv.Dotenv

/**
 * Configuration for the JSearch ingestion service. A lightweight, once-a-day intake source:
 * fetch job listings from the JSearch RapidAPI and submit them to the bridge as JD_SCRAPED.
 */
object Config {
    private val DOTENV: Dotenv = Dotenv.configure()
        .filename(System.getProperty("dotenv.file", ".env"))
        .ignoreIfMissing()
        .load()

    private fun get(key: String, default: String): String =
        DOTENV.get(key) ?: System.getenv(key) ?: default

    val JD_BRIDGE_URL: String = get("JD_BRIDGE_URL", "http://127.0.0.1:8765")

    /** RapidAPI key for jsearch.p.rapidapi.com. Required at fetch time. */
    val JSEARCH_API_KEY: String = get("JSEARCH_API_KEY", "")

    /** Minimum spacing between real fetches — the last-run gate. Default 24h. */
    val JSEARCH_INTERVAL_MS: Long = get("JSEARCH_INTERVAL_MS", "86400000").toLong()

    /** Persisted state (last_run) — a small mounted volume so restarts don't re-fetch. */
    val STATE_FILE: String = get("JSEARCH_STATE_FILE", "/state/jsearch.json")
}
