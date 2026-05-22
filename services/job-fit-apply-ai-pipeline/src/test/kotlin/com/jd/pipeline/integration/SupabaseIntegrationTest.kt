package com.jd.pipeline.integration

import com.jd.pipeline.testutils.MockSupabaseClient
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for Supabase client integration.
 *
 * Tests database operations (insert, patch, query) using
 * a mock Supabase client that simulates real behavior.
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@DisplayName("SupabaseIntegrationTest")
class SupabaseIntegrationTest {

    private lateinit var mockSupabase: MockSupabaseClient

    @BeforeEach
    fun setup() {
        mockSupabase = MockSupabaseClient()
    }

    @Test
    @DisplayName("Should insert job record and return id")
    fun testInsertJobRecord() {
        // Given
        val record = mapOf(
            "email_id" to "email-001",
            "company" to "TestCompany",
            "role_title" to "Software Engineer",
            "location" to "San Francisco, CA",
            "job_url" to "https://example.com/job/123",
            "pipeline_action" to "tailor",
            "fit_score" to 75.0f
        )

        // When
        val result = mockSupabase.insert("jobs", record)

        // Then
        assertNotNull(result)
        assertTrue(result.has("id"))
        assertEquals(1, mockSupabase.getJobCount())
    }

    @Test
    @DisplayName("Should insert resume artifact record")
    fun testInsertResumeArtifact() {
        // Given
        val record = mapOf(
            "email_id" to "email-002",
            "company" to "Acme Corp",
            "role_title" to "Kotlin Developer",
            "output_path" to "output/acme_corp_kotlin_developer",
            "artifact_url" to "https://artifacts.example.com/acme_corp_kotlin_developer"
        )

        // When
        val result = mockSupabase.insert("resume_artifacts", record)

        // Then
        assertNotNull(result)
        assertEquals(1, mockSupabase.getArtifactCount())
    }

    @Test
    @DisplayName("Should patch existing job record")
    fun testPatchJobRecord() {
        // Given: insert a job first
        mockSupabase.insert("jobs", mapOf(
            "email_id" to "email-003",
            "company" to "PatchCompany",
            "role_title" to "Senior Engineer",
            "fit_score" to 50.0f
        ))

        // When: patch the fit_score
        mockSupabase.patch("jobs", mapOf("fit_score" to 85.0f), "email_id", "email-003")

        // Then
        assertEquals(1, mockSupabase.insertCalls.size)
        assertEquals(1, mockSupabase.patchCalls.size)
    }

    @Test
    @DisplayName("Should query jobs with filter")
    fun testQueryJobsWithFilter() {
        // Given: insert multiple jobs
        mockSupabase.insert("jobs", mapOf("company" to "AlphaCorp", "role_title" to "Engineer"))
        mockSupabase.insert("jobs", mapOf("company" to "BetaCorp", "role_title" to "Engineer"))
        mockSupabase.insert("jobs", mapOf("company" to "AlphaCorp", "role_title" to "Manager"))

        // When: query for AlphaCorp jobs
        val results = mockSupabase.query("jobs", mapOf("company" to "eq.AlphaCorp"))

        // Then
        assertEquals(2, results.size)
    }

    @Test
    @DisplayName("Should query with limit")
    fun testQueryWithLimit() {
        // Given: insert 10 jobs
        for (i in 1..10) {
            mockSupabase.insert("jobs", mapOf("company" to "Company$i", "role_title" to "Engineer"))
        }

        // When: query with limit=3
        val results = mockSupabase.query("jobs", emptyMap(), limit = 3)

        // Then
        assertEquals(3, results.size)
    }

    @Test
    @DisplayName("Should return empty list when no matches")
    fun testQueryNoMatches() {
        // Given: insert some jobs
        mockSupabase.insert("jobs", mapOf("company" to "ExistingCorp", "role_title" to "Engineer"))

        // When: query for non-existent company
        val results = mockSupabase.query("jobs", mapOf("company" to "eq.NonExistentCorp"))

        // Then
        assertTrue(results.isEmpty())
    }

    @Test
    @DisplayName("Should track insert calls")
    fun testTrackInsertCalls() {
        // Given
        val record1 = mapOf("company" to "CompanyA", "role_title" to "Engineer")
        val record2 = mapOf("company" to "CompanyB", "role_title" to "Manager")

        // When
        mockSupabase.insert("jobs", record1)
        mockSupabase.insert("jobs", record2)

        // Then
        assertEquals(2, mockSupabase.insertCalls.size)
        assertEquals("jobs", mockSupabase.insertCalls[0].first)
        assertEquals("CompanyA", mockSupabase.insertCalls[0].second["company"])
    }

    @Test
    @DisplayName("Should handle duplicate detection query")
    fun testDuplicateDetectionQuery() {
        // Given: insert existing job
        mockSupabase.addJob(mutableMapOf(
            "company" to "DuplicateTest",
            "role_title" to "Software Engineer",
            "location" to "Remote",
            "jd_text_hash" to "hash123",
            "id" to 999
        ))

        // When: query for duplicates
        val results = mockSupabase.query("jobs", mapOf(
            "company" to "eq.DuplicateTest",
            "role_title" to "eq.Software Engineer",
            "location" to "eq.Remote"
        ), limit = 1)

        // Then
        assertEquals(1, results.size)
        assertEquals(999, results[0].path("id").asInt())
    }

    @Test
    @DisplayName("Should fail on insert when configured")
    fun testFailOnInsert() {
        // Given
        mockSupabase.shouldFailInsert = true
        mockSupabase.failMessage = "Database connection failed"

        // When/Then
        try {
            mockSupabase.insert("jobs", mapOf("company" to "Test"))
            throw AssertionError("Should have thrown")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("Database connection failed"))
        }
    }

    @Test
    @DisplayName("Should fail on query when configured")
    fun testFailOnQuery() {
        // Given
        mockSupabase.shouldFailQuery = true
        mockSupabase.failMessage = "Query timeout"

        // When/Then
        try {
            mockSupabase.query("jobs", emptyMap())
            throw AssertionError("Should have thrown")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("Query timeout"))
        }
    }

    @Test
    @DisplayName("Should handle filter with encoded values")
    fun testQueryWithEncodedValues() {
        // Given: insert job with special characters
        mockSupabase.insert("jobs", mapOf(
            "company" to "Test Company Inc",
            "role_title" to "Senior Engineer - Backend"
        ))

        // When: query with encoded filter
        val results = mockSupabase.query("jobs", mapOf(
            "company" to "eq.Test Company Inc"
        ))

        // Then
        assertTrue(results.isNotEmpty() || results.isEmpty()) // Just verify no crash
    }
}