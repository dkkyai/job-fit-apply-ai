package com.jd.pipeline.client

import com.jd.pipeline.config.Config
import com.jd.pipeline.nodes.SupabaseTrackNode
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.PipelineAction
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises [PostgresGateway] against a REAL Postgres container (docker compose `db`).
 * Skips automatically when the container is unreachable, so it is safe in CI.
 *
 * Covers the full path the pipeline uses: insert (with a text[] column) → dedup query
 * (PostgREST-style eq./gte. filters translated to SQL) → delete cleanup.
 */
class PostgresGatewayLiveTest {

    private val sentinelEmail = "__pg_gateway_selftest__"

    private fun containerReachable(): Boolean = runCatching {
        // Config.DATABASE_URL defaults to the compose container on localhost:5432.
        val uri = java.net.URI(Config.DATABASE_URL)
        val user = uri.userInfo.split(":", limit = 2)
        val port = if (uri.port > 0) uri.port else 5432
        DriverManager.getConnection(
            "jdbc:postgresql://${uri.host}:$port/${uri.path.trimStart('/')}",
            user[0], user.getOrElse(1) { "" }
        ).use { it.isValid(2) }
    }.getOrDefault(false)

    @Test
    fun `insert, dedup query, and delete round-trip against Postgres`() {
        assumeTrue(containerReachable(), "Postgres container not reachable — skipping live test")

        // clean any leftover sentinel first
        PostgresGateway.delete("tracks", "email_id", sentinelEmail)

        val record = mapOf(
            "email_id" to sentinelEmail,
            "company" to "SelftestCo",
            "role_title" to "Gateway Verifier",
            "location" to "Remote",
            "fit_score" to 77.5f,
            "pipeline_action" to "tailor",
            "tech_stack" to listOf("Kotlin", "Postgres"),
        )

        // insert → returns the new row (RETURNING *)
        val row = PostgresGateway.insert("tracks", record)
        val id = row.path("id").asInt(0)
        assertTrue(id > 0, "expected a generated id, got $id")
        // text[] column round-trips as a JSON array
        assertEquals(2, row.path("tech_stack").size(), "tech_stack should have 2 elements")
        assertEquals("Kotlin", row.path("tech_stack").get(0).asText())

        // dedup query — same shape CheckDuplicateNode builds
        val cutoff = Instant.now().minus(1, ChronoUnit.DAYS).toString()
        val hits = PostgresGateway.query(
            table = "tracks",
            filters = mapOf(
                "company" to "eq.SelftestCo",
                "role_title" to "eq.Gateway Verifier",
                "location" to "eq.Remote",
                "created_at" to "gte.$cutoff",
            ),
            select = "id",
            limit = 1,
        )
        assertTrue(hits.isNotEmpty(), "dedup query should find the just-inserted row")

        // delete cleanup → query now empty
        PostgresGateway.delete("tracks", "email_id", sentinelEmail)
        val afterDelete = PostgresGateway.query(
            table = "tracks",
            filters = mapOf("email_id" to "eq.$sentinelEmail"),
            select = "id",
            limit = 1,
        )
        assertTrue(afterDelete.isEmpty(), "row should be gone after delete")
    }

    @Test
    fun `SupabaseTrackNode writes a full row to Postgres via the gateway`() {
        assumeTrue(containerReachable(), "Postgres container not reachable — skipping live test")
        PostgresGateway.delete("tracks", "email_id", sentinelEmail)

        val node = SupabaseTrackNode(PostgresGateway) // exercises buildRecord → insert end-to-end
        val state = JDState(
            intake = IntakeContext.Email(
                emailId = sentinelEmail,
                subject = "Gateway self-test",
                from = "selftest@example.com",
                rawBody = "body",
                htmlBody = "",
                isRecruiter = false,
                isDigest = false,
                isInlineDigest = false,
            ),
            isJobPosting = true,
            roleTitle = "Gateway Verifier",
            company = "SelftestCo",
            location = "Remote",
            remotePolicy = "remote",
            fitScore = 77.5f,
            pipelineAction = PipelineAction.TAILOR,
            techStack = listOf("Kotlin", "Postgres"),
            strengths = listOf("depth"),
            gaps = listOf("none"),
            fitReasoning = "strong",
            jdText = "jd",
            outputPath = "/tmp/out",
            artifactUrl = "https://example.com/a/",
        )

        val result = node.process(state)
        assertTrue(result.isSupabaseTracked, "node should report tracked: ${result.error}")
        assertNotNull(result.trackId, "node should parse a generated id")

        // confirm the row actually landed with the mapped columns
        val hits = PostgresGateway.query(
            table = "tracks",
            filters = mapOf("email_id" to "eq.$sentinelEmail"),
            select = "id, company, role_title, pipeline_action, fit_score, tech_stack",
            limit = 1,
        )
        assertEquals(1, hits.size)
        assertEquals("SelftestCo", hits[0].path("company").asText())
        assertEquals("tailor", hits[0].path("pipeline_action").asText())
        assertEquals(2, hits[0].path("tech_stack").size())

        PostgresGateway.delete("tracks", "email_id", sentinelEmail)
    }
}
