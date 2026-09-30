package com.jd.notifier.config

import io.github.cdimascio.dotenv.Dotenv

internal fun resolveCredential(primary: String?, legacy: String?): String =
    primary?.trim()?.takeIf { it.isNotEmpty() } ?: legacy?.trim().orEmpty()

/**
 * Notifier configuration. A completed-feed event consumer: polls the bridge event stream and sends
 * Discord (per job) + Telegram (high-fit) messages. Tracks its own cursor.
 */
object Config {
    private val DOTENV: Dotenv = Dotenv.configure()
        .filename(System.getProperty("dotenv.file", ".env"))
        .ignoreIfMissing()
        .load()

    private fun get(key: String, default: String): String =
        DOTENV.get(key) ?: System.getenv(key) ?: default

    val JD_BRIDGE_URL: String = get("JD_BRIDGE_URL", "http://127.0.0.1:8765")

    // ── Messaging channels (silently disabled when creds are blank) ──────────────
    val DISCORD_BOT_TOKEN: String  = get("DISCORD_BOT_TOKEN", "")
    val DISCORD_CHANNEL_ID: String = get("DISCORD_CHANNEL_ID", "")
    val TELEGRAM_BOT_TOKEN: String = resolveCredential(
        get("NOTIFIER_TELEGRAM_BOT_TOKEN", ""),
        get("TELEGRAM_BOT_TOKEN", ""),
    )
    val TELEGRAM_CHAT_ID: String = resolveCredential(
        get("NOTIFIER_TELEGRAM_CHAT_ID", ""),
        get("TELEGRAM_CHAT_ID", ""),
    )

    /** API hosts, overridable so tests/e2e can point at a local sink. */
    val DISCORD_API_BASE: String  = get("DISCORD_API_BASE", "https://discord.com")
    val TELEGRAM_API_BASE: String = get("TELEGRAM_API_BASE", "https://api.telegram.org")

    /**
     * Inline buttons on the Telegram high-fit ping (View Report / View Resume / Apply).
     * Off by default so a rollout is a config flip, not a code change.
     */
    val TELEGRAM_BUTTONS_ENABLED: Boolean =
        get("NOTIFIER_TELEGRAM_BUTTONS", "false").equals("true", ignoreCase = true)

    /**
     * Looking up `tailored_resume_url` needs one fetch of the job's metadata.json. On when
     * buttons are on; disable separately to send link buttons without that round-trip.
     */
    val ARTIFACT_LINKS_ENABLED: Boolean =
        get("NOTIFIER_ARTIFACT_LINKS", TELEGRAM_BUTTONS_ENABLED.toString())
            .equals("true", ignoreCase = true)

    /** Budget for the metadata.json lookup — must not stall the notifier loop. */
    val ARTIFACT_LINKS_TIMEOUT_MS: Int = get("NOTIFIER_ARTIFACT_TIMEOUT_MS", "4000").toInt()

    /**
     * Writing Apply registrations into the agent's pending-actions file.
     *
     * Off by default and only meaningful when the file is reachable: the notifier runs in a
     * container, so this path must be a mounted volume. Blank disables it.
     */
    val JOBBOT_PENDING_ACTIONS_PATH: String = get("JOBBOT_PENDING_ACTIONS", "")

    val APPLY_REGISTRAR_ENABLED: Boolean =
        get("NOTIFIER_APPLY_REGISTRAR", "false").equals("true", ignoreCase = true) &&
            JOBBOT_PENDING_ACTIONS_PATH.isNotBlank()

    /** Apply labels older than this are pruned, matching the agent's 7-day expiry. */
    val APPLY_LABEL_TTL_SECONDS: Long = get("NOTIFIER_APPLY_TTL_SECONDS", "604800").toLong()


    /**
     * Public base for bridge-relative artifact links. The bridge exposes the same resume bytes
     * at `/api/jobs/<id>/resume.pdf`, reachable over the tailnet.
     */
    val BRIDGE_PUBLIC_URL: String = get(
        "BRIDGE_PUBLIC_URL",
        "http://richards-macbook-m1-max.tail02d0e.ts.net:8765",
    )

    /** Telegram high-fit ping fires when fit_score >= this — the processor's tailoring threshold. */
    val FIT_THRESHOLD: Int = get("FIT_THRESHOLD", "50").toFloat().toInt()

    // ── Loop + state ─────────────────────────────────────────────────────────────
    val POLL_INTERVAL_MS: Long = get("NOTIFIER_POLL_INTERVAL_MS", "20000").toLong()
    val CURSOR_FILE: String = get("NOTIFIER_CURSOR_FILE", "/state/notifier-cursor.txt")

    /** Retryable-delivery policy. After MAX_DELIVERY_ATTEMPTS the event is dead-lettered (logged
     *  loudly and skipped) so one poisoned event cannot block every later notification. */
    val MAX_DELIVERY_ATTEMPTS: Int = get("NOTIFIER_MAX_DELIVERY_ATTEMPTS", "5").toInt()
    val DELIVERY_BACKOFF_BASE_MS: Long = get("NOTIFIER_DELIVERY_BACKOFF_BASE_MS", "1000").toLong()
    val DELIVERY_BACKOFF_CAP_MS: Long = get("NOTIFIER_DELIVERY_BACKOFF_CAP_MS", "60000").toLong()
    val HEARTBEAT_FILE: String = get("HEARTBEAT_FILE", "/tmp/notifier-heartbeat")
    val HEALTH_MAX_AGE_MS: Long = get("HEALTH_MAX_AGE_MS", "120000").toLong()
}
