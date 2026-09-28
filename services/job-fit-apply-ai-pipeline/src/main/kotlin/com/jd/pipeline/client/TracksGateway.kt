package com.jd.pipeline.client

import com.fasterxml.jackson.databind.JsonNode

/**
 * The database operations nodes depend on, so tests can inject a fake. The production
 * implementation is [PostgresGateway] (direct JDBC to the compose `db` container).
 *
 * Filters in [query] are "op.value" strings ("eq.Acme", "gte.2026-01-01T00:00:00Z");
 * [PostgresGateway] translates them into parameterized SQL.
 */
interface TracksGateway {
    /** False when DATABASE_URL is unset: dedup falls back to memory and tracking is skipped. */
    fun isConfigured(): Boolean
    fun insert(table: String, record: Map<String, Any?>): JsonNode
    fun query(
        table: String,
        filters: Map<String, String>,
        select: String = "*",
        limit: Int = 10
    ): List<JsonNode>
    fun delete(table: String, filterCol: String, filterVal: String)
}
