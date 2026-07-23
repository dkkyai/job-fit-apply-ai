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

    val bridgeUrl: String = get("E2E_BRIDGE_URL", "http://127.0.0.1:18765").trimEnd('/')
    val markservUrl: String = get("E2E_MARKSERV_URL", "http://127.0.0.1:18081").trimEnd('/')

    /** postgres URI form (repo convention); converted to JDBC in [pgConnection]. */
    val databaseUrl: String = get("E2E_DATABASE_URL", "postgresql://jobfit:jobfit@127.0.0.1:15432/jobfit")

    /**
     * Must match the value docker compose interpolated at `up` time — both sides read
     * the same env var, so export it once (or take both defaults).
     */
    val fakeLlmPort: Int = get("E2E_FAKE_LLM_PORT", "11436").toInt()
    val sinkPort: Int = get("E2E_SINK_PORT", "18099").toInt()

    val timeoutSeconds: Long = get("E2E_TIMEOUT_SECONDS", "300").toLong()

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
