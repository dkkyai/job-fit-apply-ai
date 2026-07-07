package com.jd.pipeline.nodes
import com.jd.pipeline.state.PipelineAction

import com.jd.pipeline.fixtures.TestJDStateFactory
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import com.jd.pipeline.testutils.MockSupabaseClient
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for SupabaseTrackNode.
 *
 * The node talks to Supabase through the injected [com.jd.pipeline.client.SupabaseGateway],
 * so these tests supply a [MockSupabaseClient] and never touch the real database — no test
 * rows are written to the live `tracks` table, so there is nothing to clean up.
 */
@DisplayName("SupabaseTrackNodeTest")
class SupabaseTrackNodeTest {

    private lateinit var supabase: MockSupabaseClient
    private lateinit var node: SupabaseTrackNode

    @BeforeEach
    fun setUp() {
        supabase = MockSupabaseClient()
        node = SupabaseTrackNode(supabase)
    }

    @Test
    @DisplayName("Should return error when Supabase is not configured")
    fun testReturnsErrorWhenNotConfigured() {
        supabase.configured = false

        val result = node.process(TestJDStateFactory.createFullJobPostingState())

        assertTrue(result.error.contains("SUPABASE_URL"))
        assertTrue(result.error.contains("not configured"))
        assertFalse(result.isSupabaseTracked)
        assertEquals(0, supabase.getTrackCount())
    }

    @Test
    @DisplayName("Should preserve input fields when insert fails")
    fun testPreservesFieldsOnError() {
        supabase.shouldFailInsert = true
        supabase.failMessage = "insert blew up"

        val input = TestJDStateFactory.createFullJobPostingState().copy(
            company = "ErrorTestCo",
            roleTitle = "Senior Engineer",
            fitScore = 85.0f,
            techStack = listOf("Kotlin", "Java"),
            outputPath = "/existing/path"
        )

        val result = node.process(input)

        // Insert failed, so nothing tracked, but core fields are preserved.
        assertFalse(result.isSupabaseTracked)
        assertTrue(result.error.contains("supabase_track"))
        assertEquals("ErrorTestCo", result.company)
        assertEquals("Senior Engineer", result.roleTitle)
        assertEquals(85.0f, result.fitScore)
    }

    @Test
    @DisplayName("Should build correct record from state")
    fun testBuildRecordFromState() {
        val input = TestJDStateFactory.createFullJobPostingState().copy(
            IntakeContext.Email(
                emailId = "track-test-001",
                from = "",
                subject = "Job Opportunity",
                rawBody = "",
                htmlBody = "",
                isRecruiter = false,
                isDigest = false,
                isInlineDigest = false
            ),
            company = "TrackTest Co",
            roleTitle = "Software Engineer",
            location = "Seattle, WA",
            jobUrl = "https://example.com/jobs/123",
            remotePolicy = "remote",
            fitScore = 90.0f,
            pipelineAction = PipelineAction.TAILOR,
            techStack = listOf("Kotlin", "AWS"),
            strengths = listOf("Expert in Kotlin"),
            gaps = listOf("Limited Go experience"),
            redFlags = listOf("Low salary range"),
            fitReasoning = "Strong technical match",
            jdText = "Job description text...",
            outputPath = "/output/track-test",
            artifactUrl = "https://example.com/artifact/track-test"
        )

        val result = node.process(input)

        // Tracking succeeded against the mock.
        assertTrue(result.isSupabaseTracked)
        assertNotNull(result.trackId)

        // The node mapped state fields into a single insert on the tracks table.
        assertEquals(1, supabase.insertCalls.size)
        val (table, record) = supabase.insertCalls[0]
        assertEquals("tracks", table)
        assertEquals("track-test-001", record["email_id"])
        assertEquals("TrackTest Co", record["company"])
        assertEquals("Software Engineer", record["role_title"])
        assertEquals("Seattle, WA", record["location"])
        assertEquals(90.0f, record["fit_score"])
    }

    @Test
    @DisplayName("Should handle minimal state")
    fun testHandlesMinimalState() {
        val input = JDState(
            intake = IntakeContext.Email(
                emailId = "minimal-001",
                from = "",
                subject = "",
                rawBody = "",
                htmlBody = "",
                isRecruiter = false,
                isDigest = false,
                isInlineDigest = false
            ),
            company = "MinCo",
            roleTitle = "Engineer",
            isJobPosting = true
        )

        val result = node.process(input)

        assertTrue(result.isSupabaseTracked)
        assertEquals("MinCo", result.company)
    }

    @Test
    @DisplayName("Should handle empty tech stack and lists")
    fun testHandlesEmptyLists() {
        val input = TestJDStateFactory.createFullJobPostingState().copy(
            techStack = emptyList(),
            strengths = emptyList(),
            gaps = emptyList(),
            redFlags = emptyList()
        )

        val result = node.process(input)

        assertTrue(result.isSupabaseTracked)
        assertEquals(1, supabase.insertCalls.size)
    }

    @Test
    @DisplayName("Should handle null fit score")
    fun testHandlesNullFitScore() {
        val input = TestJDStateFactory.createFullJobPostingState().copy(
            fitScore = null,
            pipelineAction = PipelineAction.SKIP
        )

        val result = node.process(input)

        assertNull(result.fitScore)
        assertEquals(PipelineAction.SKIP, result.pipelineAction)
    }

    @Test
    @DisplayName("Should not modify input state on success")
    fun testDoesNotModifyInputOnSuccess() {
        val input = TestJDStateFactory.createFullJobPostingState().copy(
            company = "PreserveCo",
            roleTitle = "Engineer"
        )

        val result = node.process(input)

        assertTrue(result.isSupabaseTracked)
        assertEquals("PreserveCo", result.company)
        assertEquals("Engineer", result.roleTitle)
    }
}
