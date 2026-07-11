package com.jdbridge.integration

import com.jdbridge.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration test for the tracks API against the real Dockerized Postgres
 * (docker compose `db`). Runs in an isolated, self-provisioned `jobfit_test`
 * database so it never touches real data, and applies the project's actual
 * `db/init/001_schema.sql` (also validating that file). Skips automatically when
 * the container is unreachable, so it is CI-safe.
 *
 * (Testcontainers would be the usual choice, but the CI sandbox here blocks the
 *  test JVM's Docker socket while allowing TCP to the mapped port — so we target
 *  the running container directly, matching the pipeline's PostgresGatewayLiveTest.)
 */
class TracksApiTest {

    companion object {
        private const val TEST_DB = "jobfit_test"

        private val base: URI = URI(System.getenv("DATABASE_URL")
            ?: "postgresql://jobfit:jobfit@localhost:5432/jobfit")
        private val creds = base.userInfo?.split(":", limit = 2) ?: listOf("jobfit", "jobfit")
        private val host = base.host
        private val port = if (base.port > 0) base.port else 5432
        private val adminDb = base.path.trimStart('/')

        private fun jdbc(db: String) = "jdbc:postgresql://$host:$port/$db?stringtype=unspecified"
        fun conn(db: String): Connection =
            DriverManager.getConnection(jdbc(db), creds[0], creds.getOrElse(1) { "" })

        @BeforeAll
        @JvmStatic
        fun provision() {
            val reachable = runCatching { conn(adminDb).use { it.isValid(2) } }.getOrDefault(false)
            assumeTrue(reachable, "Postgres container not reachable — skipping tracks integration test")

            // Create the isolated test database if it doesn't exist yet.
            conn(adminDb).use { c ->
                val exists = c.createStatement().use { st ->
                    st.executeQuery("SELECT 1 FROM pg_database WHERE datname = '$TEST_DB'").use { it.next() }
                }
                if (!exists) c.createStatement().use { it.execute("CREATE DATABASE $TEST_DB") }
            }

            // Apply the real schema (idempotent — 001_schema.sql uses IF NOT EXISTS).
            val schema = listOf(File("../../db/init/001_schema.sql"), File("db/init/001_schema.sql"))
                .firstOrNull { it.exists() }
                ?: error("Could not locate db/init/001_schema.sql from ${File(".").absolutePath}")
            conn(TEST_DB).use { c -> c.createStatement().use { it.execute(schema.readText()) } }

            // Point TracksStore at the test database before it is first used.
            System.setProperty("DATABASE_URL",
                "postgresql://${creds[0]}:${creds.getOrElse(1) { "" }}@$host:$port/$TEST_DB")
        }
    }

    @BeforeEach
    fun setup() {
        assumeTrue(runCatching { conn(TEST_DB).use { it.isValid(2) } }.getOrDefault(false))
        // SQLite side (the app module wires its own job queue on startup).
        useTempStoreDir()
        initTestDb()
        // Fresh, deterministic tracks each test.
        conn(TEST_DB).use { c ->
            c.createStatement().use { st ->
                st.execute("TRUNCATE tracks RESTART IDENTITY CASCADE;")
                st.execute(
                    """
                    INSERT INTO tracks
                      (id, company, role_title, location, remote_policy, fit_score,
                       job_url, artifact_url, tech_stack, status, duplicate)
                    VALUES
                      (1,'Acme','Staff SDET','Remote','remote',82.5,'https://x','',
                       '{Kotlin,Postgres}','backlog',false),
                      (2,'Globex','Engineer','NYC','onsite',70,'https://y','',
                       '{Java}','backlog',false);
                    """.trimIndent(),
                )
            }
        }
    }

    // ── TracksStore (JDBC) directly ────────────────────────────────────────────

    @Test
    fun `TracksStore list returns rows with typed fields`() = runBlocking {
        val rows = TracksStore.list()
        assertEquals(2, rows.size)
        val acme = rows.first { it.id == 1 }
        assertEquals("Acme", acme.company)
        assertEquals(82.5, acme.fit_score)
        assertEquals(listOf("Kotlin", "Postgres"), acme.tech_stack)
        assertEquals(false, acme.duplicate)
        assertTrue(acme.created_at.isNotBlank())
    }

    @Test
    fun `TracksStore updateStatus changes status and reports missing rows`() = runBlocking {
        assertTrue(TracksStore.updateStatus(1, "applied"))
        assertEquals("applied", TracksStore.list().first { it.id == 1 }.status)
        assertEquals(false, TracksStore.updateStatus(999999, "applied"))
    }

    // ── HTTP routes ────────────────────────────────────────────────────────────

    @Test
    fun `GET api tracks returns the seeded rows`() = testApplication {
        application { configureApplication() }
        val res = client.get("/api/tracks")
        assertEquals(HttpStatusCode.OK, res.status)
        val arr = Json.parseToJsonElement(res.bodyAsText()).jsonArray
        assertEquals(2, arr.size)
        val first = arr.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.int == 1 }
        assertEquals("Acme", first["company"]!!.jsonPrimitive.content)
        assertEquals(2, first["tech_stack"]!!.jsonArray.size)
    }

    @Test
    fun `POST status updates the row`() = testApplication {
        application { configureApplication() }
        val res = client.post("/api/tracks/1/status") {
            contentType(ContentType.Application.Json)
            setBody("""{"status":"interested"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("interested", runBlocking { TracksStore.list().first { it.id == 1 }.status })
    }

    @Test
    fun `POST invalid status returns 422`() = testApplication {
        application { configureApplication() }
        val res = client.post("/api/tracks/1/status") {
            contentType(ContentType.Application.Json)
            setBody("""{"status":"bogus"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, res.status)
    }

    @Test
    fun `POST status for unknown id returns 404`() = testApplication {
        application { configureApplication() }
        val res = client.post("/api/tracks/999999/status") {
            contentType(ContentType.Application.Json)
            setBody("""{"status":"applied"}""")
        }
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    @Test
    fun `POST status for non-integer id returns 400`() = testApplication {
        application { configureApplication() }
        val res = client.post("/api/tracks/abc/status") {
            contentType(ContentType.Application.Json)
            setBody("""{"status":"applied"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }
}
