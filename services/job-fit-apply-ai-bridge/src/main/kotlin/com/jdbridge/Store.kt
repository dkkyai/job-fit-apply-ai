package com.jdbridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories

private val log = LoggerFactory.getLogger("com.jdbridge.Store")

// ── Configurable store root (override in tests) ───────────────────────────────

var STORE_DIR: Path = Paths.get(System.getProperty("user.home"), ".openclaw", "jd-bridge")
    set(value) {
        field = value
        _database = null  // force re-init when path changes
    }

val DB_PATH get() = STORE_DIR.resolve("jobs.db")
val JOBS_DIR get() = STORE_DIR.resolve("jobs")

private var _database: Database? = null

// ── Exposed table definition ──────────────────────────────────────────────────

internal object Jobs : Table("jobs") {
    val id              = text("id")
    val status          = text("status").default("queued")
    val title           = text("title").nullable()
    val company         = text("company").nullable()
    val jdJson          = text("jd_json").nullable()
    val progressMessage = text("progress_message").nullable()
    val fitScore        = integer("fit_score").nullable()
    val artifactsJson   = text("artifacts_json").nullable()
    val error           = text("error").nullable()
    val createdAt       = long("created_at")
    val updatedAt       = long("updated_at")

    override val primaryKey = PrimaryKey(id)
}

// ── DB helpers ────────────────────────────────────────────────────────────────

private suspend fun <T> dbQuery(block: Transaction.() -> T): T =
    withContext(Dispatchers.IO) {
        transaction(_database!!) { block() }
    }

// ── Public API ────────────────────────────────────────────────────────────────

suspend fun initDb() = withContext(Dispatchers.IO) {
    STORE_DIR.createDirectories()
    JOBS_DIR.createDirectories()
    _database = Database.connect(
        url    = "jdbc:sqlite:$DB_PATH",
        driver = "org.sqlite.JDBC",
    )
    transaction(_database!!) {
        SchemaUtils.create(Jobs)
    }
    log.info("Store initialised at $DB_PATH")
}

suspend fun createJob(jobId: String, payload: SubmitJobRequest) {
    val now = System.currentTimeMillis() / 1000L
    dbQuery {
        Jobs.insert {
            it[id]        = jobId
            it[status]    = JobStatus.QUEUED.value
            it[title]     = payload.role_title
            it[company]   = payload.company
            it[jdJson]    = Json.encodeToString(payload)
            it[createdAt] = now
            it[updatedAt] = now
        }
    }
}

suspend fun updateJob(jobId: String, update: JobUpdate) {
    val now = System.currentTimeMillis() / 1000L
    dbQuery {
        Jobs.update({ Jobs.id eq jobId }) { row ->
            update.status?.let          { row[Jobs.status] = it.value }
            update.progressMessage?.let { row[Jobs.progressMessage] = it }
            update.fitScore?.let        { row[Jobs.fitScore] = it }
            update.error?.let           { row[Jobs.error] = it }
            update.artifacts?.let       { row[Jobs.artifactsJson] = Json.encodeToString(it) }
            row[Jobs.updatedAt] = now
        }
    }
}

suspend fun getJob(jobId: String): JobRow? = dbQuery {
    Jobs.selectAll().where { Jobs.id eq jobId }
        .singleOrNull()
        ?.toJobRow()
}

fun jobDir(jobId: String): Path {
    val dir = JOBS_DIR.resolve(jobId)
    dir.createDirectories()
    return dir
}

// ── Row mapping ───────────────────────────────────────────────────────────────

private fun ResultRow.toJobRow(): JobRow {
    val artifactsJson = this[Jobs.artifactsJson]
    val artifacts = artifactsJson?.let {
        runCatching { Json.decodeFromString<ArtifactUrls>(it) }.getOrNull()
    }
    return JobRow(
        id              = this[Jobs.id],
        status          = this[Jobs.status],
        title           = this[Jobs.title],
        company         = this[Jobs.company],
        jdJson          = this[Jobs.jdJson],
        progressMessage = this[Jobs.progressMessage],
        fitScore        = this[Jobs.fitScore],
        artifacts       = artifacts,
        error           = this[Jobs.error],
        createdAt       = this[Jobs.createdAt],
        updatedAt       = this[Jobs.updatedAt],
    )
}
