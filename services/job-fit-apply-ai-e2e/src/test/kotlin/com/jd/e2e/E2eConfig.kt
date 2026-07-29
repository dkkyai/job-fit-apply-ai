package com.jd.e2e

import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager

/**
 * Every endpoint the suite touches is an env var with a localhost default matching the
 * docker-compose.e2e.yml override (alternate ports so the e2e slice coexists with a
 * production stack on the same host). Point these at any deployed stack to reuse the
 * suite as a synthetic monitor.
 */
object E2eConfig {
    private fun get(key: String, default: String): String =
        System.getenv(key)?.takeIf { it.isNotBlank() } ?: default

    /** Numeric env vars, with the offending var named on a bad value. */
    private fun getInt(key: String, default: String): Int {
        val raw = get(key, default)
        return raw.toIntOrNull() ?: error("$key must be an integer, got '$raw'")
    }

    private fun getLong(key: String, default: String): Long {
        val raw = get(key, default)
        return raw.toLongOrNull() ?: error("$key must be an integer, got '$raw'")
    }

    val bridgeUrl: String = get("E2E_BRIDGE_URL", "http://127.0.0.1:18765").trimEnd('/')
    val markservUrl: String = get("E2E_MARKSERV_URL", "http://127.0.0.1:18081").trimEnd('/')

    /** postgres URI form (repo convention); converted to JDBC in [pgConnection]. */
    val databaseUrl: String = get("E2E_DATABASE_URL", "postgresql://jobfit:jobfit@127.0.0.1:15432/jobfit")

    /**
     * Must match the value docker compose interpolated at `up` time — both sides read
     * the same env var, so export it once (or take both defaults).
     *
     * The default is deliberately NOT 11436: that is the production oMLX port, which
     * docker-compose.yml also points the production processor at. Sharing it is unsafe
     * in both directions — the fake binds 0.0.0.0 successfully even while oMLX holds
     * 127.0.0.1:11436 (SO_REUSEADDR), but the more-specific loopback socket wins, so
     * (a) the e2e run would silently hit real oMLX and time out, and (b) with oMLX down
     * the fake would answer the *production* processor with fixture data. Under
     * REAL_LLM=1 the Makefile sets this to 11436 on purpose (the fake never starts).
     */
    val fakeLlmPort: Int = getInt("E2E_FAKE_LLM_PORT", "21436")
    val sinkPort: Int = getInt("E2E_SINK_PORT", "18099")

    /**
     * The dummy notification credentials docker-compose.e2e.yml gives the notifier.
     * The sink asserts the inbound paths carry these, so a client that posts to a
     * wrong-but-plausible URL is caught instead of silently accepted.
     */
    val discordChannelId: String = get("E2E_DISCORD_CHANNEL_ID", "e2e-channel")
    val telegramBotToken: String = get("E2E_TELEGRAM_BOT_TOKEN", "e2e-telegram-token")

    val timeoutSeconds: Long = getLong("E2E_TIMEOUT_SECONDS", "300")

    /** 1 = don't start the fake; the container's MLX port is a real local model server. */
    val realLlm: Boolean = get("E2E_REAL_LLM", "0") == "1"

    /** Host-side view of the processor's /app/output mount. Relative to this module dir. */
    val outputDir: Path = Paths.get(get("E2E_OUTPUT_DIR", "../../.e2e/output")).toAbsolutePath().normalize()

    val fixturesDir: Path = Paths.get(get("E2E_FIXTURES_DIR", "fixtures")).toAbsolutePath().normalize()

    fun pgConnection(): Connection {
        val uri = URI(databaseUrl.removePrefix("jdbc:"))
        val userInfo = (uri.userInfo ?: "jobfit:jobfit").split(":", limit = 2)
        val port = if (uri.port > 0) uri.port else 5432
        val jdbc = "jdbc:postgresql://${uri.host}:$port${uri.path}"
        return DriverManager.getConnection(jdbc, userInfo[0], userInfo.getOrElse(1) { "" })
    }
}
