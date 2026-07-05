package com.jdbridge.unit

import com.jdbridge.TrackDto
import com.jdbridge.TrackStatusUpdate
import com.jdbridge.TracksStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the tracks API contract. The frontend's `Track` type depends on
 * these exact snake_case field names and null handling, so these guard the wire format.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class TracksModelsTest {

    private val json = Json { explicitNulls = false; encodeDefaults = true }

    @Test
    fun `TrackDto serializes with snake_case keys the frontend expects`() {
        val dto = TrackDto(
            id = 42,
            company = "Acme",
            role_title = "Staff SDET",
            location = "Remote",
            remote_policy = "remote",
            fit_score = 82.5,
            job_url = "https://x/y",
            artifact_url = null,
            tech_stack = listOf("Kotlin", "Postgres"),
            status = "backlog",
            created_at = "2026-07-04T18:52:25.512204Z",
            duplicate = false,
        )
        val obj = Json.parseToJsonElement(json.encodeToString(TrackDto.serializer(), dto)).jsonObject

        assertEquals(42, obj["id"]!!.jsonPrimitive.content.toInt())
        assertEquals("Acme", obj["company"]!!.jsonPrimitive.content)
        assertEquals("Staff SDET", obj["role_title"]!!.jsonPrimitive.content)
        assertEquals(82.5, obj["fit_score"]!!.jsonPrimitive.content.toDouble())
        assertEquals(listOf("Kotlin", "Postgres"), obj["tech_stack"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(false, obj["duplicate"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("2026-07-04T18:52:25.512204Z", obj["created_at"]!!.jsonPrimitive.content)
    }

    @Test
    fun `null artifact_url is omitted (explicitNulls=false), matching the server config`() {
        val dto = TrackDto(
            id = 1, company = "Acme", status = "backlog",
            created_at = "2026-07-04T00:00:00Z", duplicate = false,
        )
        val obj = Json.parseToJsonElement(json.encodeToString(TrackDto.serializer(), dto)).jsonObject
        assertNull(obj["artifact_url"], "null fields should be omitted, not sent as null")
    }

    @Test
    fun `TrackStatusUpdate deserializes a status body`() {
        val req = json.decodeFromString(TrackStatusUpdate.serializer(), """{"status":"applied"}""")
        assertEquals("applied", req.status)
    }

    @Test
    fun `ALLOWED_STATUSES matches the UI's status set`() {
        assertEquals(
            setOf("backlog", "duplicate", "applied", "interested",
                  "skipped", "interviewing", "rejected", "offer"),
            TracksStore.ALLOWED_STATUSES,
        )
        assertTrue("bogus" !in TracksStore.ALLOWED_STATUSES)
    }
}
