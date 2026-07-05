package com.jd.poller.config

import io.github.cdimascio.dotenv.Dotenv

/**
 * Central configuration for the Poller service — the only Gmail-touching service (Phase 1).
 * Mirrors the pipeline's dotenv-overlay-on-env pattern. All tunables live here.
 */
object PollerConfig {
    private val DOTENV: Dotenv = Dotenv.configure()
        .filename(System.getProperty("dotenv.file", ".env"))
        .ignoreIfMissing()
        .load()

    private fun get(key: String, default: String): String =
        DOTENV.get(key) ?: System.getenv(key) ?: default

    // ── Bridge ─────────────────────────────────────────────────────────────────
    val JD_BRIDGE_URL: String = get("JD_BRIDGE_URL", "http://127.0.0.1:8765")

    // ── Gmail ──────────────────────────────────────────────────────────────────
    val GMAIL_CREDENTIALS_FILE: String = get("GMAIL_CREDENTIALS_FILE", "gmail_credentials.json")
    val GMAIL_TOKEN_FILE: String = get("GMAIL_TOKEN_FILE", "tokens/gmail_token.json")
    val GMAIL_MAX_EMAILS: Int = get("GMAIL_MAX_EMAILS", "10").toInt()
    val GMAIL_SEARCH_QUERY: String = get(
        "GMAIL_SEARCH_QUERY",
        "newer_than:7d in:inbox -label:JD_Not_Found -label:Recruiter_Response_Required -label:JD_Processing -label:JD_Error",
    )

    // ── Poll loops ──────────────────────────────────────────────────────────────
    // The write-back loop needs no cursor: the bridge's writeback_done flag drops finished jobs
    // from the feed, so it always drains from since=0 (which also retries transient Gmail failures).
    val INTAKE_POLL_INTERVAL_MS: Long = get("INTAKE_POLL_INTERVAL_MS", "60000").toLong()
    val WRITEBACK_POLL_INTERVAL_MS: Long = get("WRITEBACK_POLL_INTERVAL_MS", "15000").toLong()

    // ── Liveness (container healthcheck) ────────────────────────────────────────
    // Both loops touch HEARTBEAT_FILE each iteration; `--health` exits 0 when it is fresher than
    // HEALTH_MAX_AGE_MS. Default age tolerates a stalled writeback loop as long as intake (60s) ticks.
    val HEARTBEAT_FILE: String = get("HEARTBEAT_FILE", "/tmp/poller-heartbeat")
    val HEALTH_MAX_AGE_MS: Long = get("HEALTH_MAX_AGE_MS", "150000").toLong()
}
