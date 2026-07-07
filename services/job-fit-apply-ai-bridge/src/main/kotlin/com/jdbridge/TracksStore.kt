package com.jdbridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * Read/update access to the shared `tracks` table in the Postgres container
 * (docker compose `db`). This is the app-domain data the backlog UI reads — kept
 * separate from the bridge's own SQLite job queue (see [Store]).
 *
 * Raw JDBC, connection-per-call (low-traffic UI reads); mirrors the pipeline's
 * PostgresGateway so both services talk to the container the same way.
 */
object TracksStore {

    /** Status values the backlog UI uses; guards the update endpoint. */
    val ALLOWED_STATUSES = setOf(
        "backlog", "duplicate", "applied", "interested",
        "skipped", "interviewing", "rejected", "offer",
    )

    private const val SELECT_COLS =
        "id, company, role_title, location, remote_policy, fit_score, " +
        "job_url, artifact_url, tech_stack, status, created_at, duplicate"

    private data class Dsn(val jdbcUrl: String, val user: String, val password: String)

    private val dsn: Dsn by lazy {
        val uri = URI(getEnv("DATABASE_URL", "postgresql://jobfit:jobfit@localhost:5432/jobfit"))
        val creds = uri.userInfo?.split(":", limit = 2) ?: emptyList()
        val port = if (uri.port > 0) uri.port else 5432
        val db = uri.path.trimStart('/')
        // stringtype=unspecified → string params get type-inferred by Postgres.
        Dsn(
            "jdbc:postgresql://${uri.host}:$port/$db?stringtype=unspecified",
            creds.getOrElse(0) { "" },
            creds.getOrElse(1) { "" },
        )
    }

    private suspend fun <T> withConnection(block: (Connection) -> T): T =
        withContext(Dispatchers.IO) {
            DriverManager.getConnection(dsn.jdbcUrl, dsn.user, dsn.password).use(block)
        }

    /** All tracks, newest first. Fit/dupe/status filtering is done client-side by the UI. */
    suspend fun list(): List<TrackDto> = withConnection { conn ->
        conn.prepareStatement("SELECT $SELECT_COLS FROM tracks ORDER BY created_at DESC").use { ps ->
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.toTrackDto()) }
            }
        }
    }

    /** Update a track's status. Returns false when no row with that id exists. */
    suspend fun updateStatus(id: Int, status: String): Boolean = withConnection { conn ->
        conn.prepareStatement("UPDATE tracks SET status = ? WHERE id = ?").use { ps ->
            ps.setString(1, status)
            ps.setInt(2, id)
            ps.executeUpdate() > 0
        }
    }

    private fun ResultSet.toTrackDto(): TrackDto {
        val techStack = getArray("tech_stack")?.let { arr ->
            (arr.array as? Array<*>)?.mapNotNull { it?.toString() }
        }
        return TrackDto(
            id = getInt("id"),
            company = getString("company") ?: "",
            role_title = getString("role_title"),
            location = getString("location"),
            remote_policy = getString("remote_policy"),
            fit_score = getBigDecimal("fit_score")?.toDouble(),
            job_url = getString("job_url"),
            artifact_url = getString("artifact_url"),
            tech_stack = techStack,
            status = getString("status") ?: "backlog",
            created_at = getTimestamp("created_at")?.toInstant()?.toString() ?: "",
            duplicate = getBoolean("duplicate"),
        )
    }
}
