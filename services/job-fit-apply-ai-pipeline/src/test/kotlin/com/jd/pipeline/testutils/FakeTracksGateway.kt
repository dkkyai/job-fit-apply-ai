package com.jd.pipeline.testutils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.jd.pipeline.client.TracksGateway

/**
 * In-memory [TracksGateway] for node tests: records calls, can be told to fail or to report
 * itself unconfigured, and never touches a real database.
 */
class FakeTracksGateway : TracksGateway {

    private val mapper = ObjectMapper()
    private val tracks = mutableListOf<MutableMap<String, Any?>>()

    val insertCalls = mutableListOf<Pair<String, Map<String, Any?>>>()

    var shouldFailInsert = false
    var failMessage = "Fake failure"

    /** Toggle to simulate the "DATABASE_URL not configured" path. */
    var configured = true

    override fun isConfigured(): Boolean = configured

    override fun insert(table: String, record: Map<String, Any?>): JsonNode {
        if (shouldFailInsert) throw RuntimeException(failMessage)
        insertCalls.add(table to record)
        val row = record.toMutableMap().apply { put("id", tracks.size + 1) }
        if (table == "tracks") tracks.add(row)
        return mapper.valueToTree(row)
    }

    override fun query(table: String, filters: Map<String, String>, select: String, limit: Int): List<JsonNode> =
        emptyList()

    override fun delete(table: String, filterCol: String, filterVal: String) {
        if (table == "tracks") tracks.removeAll { it[filterCol]?.toString() == filterVal }
    }

    fun getTrackCount() = tracks.size
}
