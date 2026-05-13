package com.jd.pipeline.nodes

import com.jd.pipeline.client.SupabaseClient
import com.jd.pipeline.fixtures.TestJDStateFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for CheckDuplicateNode.
 * 
 * Note: When Supabase is configured, the node queries Supabase instead of using
 * the in-memory fallback. Tests that rely on the fallback behavior will be skipped
 * when Supabase is configured.
 */
@DisplayName("CheckDuplicateNodeTest")
class CheckDuplicateNodeTest {

    private lateinit var node: CheckDuplicateNode

    @BeforeEach
    fun setUp() {
        node = CheckDuplicateNode()
        // Reset the in-memory fallback set before each test
        CheckDuplicateNode.resetFallback()
    }

    @AfterEach
    fun tearDown() {
        CheckDuplicateNode.resetFallback()
    }

    @Test
    @DisplayName("New job should not be marked as duplicate")
    fun testNewJobNotMarkedAsDuplicate() {
        // Given: a fresh job posting
        val input = TestJDStateFactory.createFullJobPostingState().copy(
            company = "NewCompany",
            roleTitle = "Software Engineer"
        )

        // When
        val result = node.process(input)

        // Then: should not be marked as duplicate
        // When Supabase is configured, the job won't be found in DB (no prior record)
        // When Supabase is NOT configured, fallback set starts empty
        assertFalse(result.isDuplicate)
    }

    @Test
    @DisplayName("State fields should be preserved on non-duplicate")
    fun testFieldsPreservedOnNonDuplicate() {
        // Given
        val input = TestJDStateFactory.createFullJobPostingState().copy(
            company = "PreserveTest",
            roleTitle = "Senior Engineer",
            fitScore = 85.0f,
            outputPath = "/existing/path"
        )

        // When
        val result = node.process(input)

        // Then: all fields should be preserved
        assertEquals(input.company, result.company)
        assertEquals(input.roleTitle, result.roleTitle)
        assertEquals(input.fitScore, result.fitScore)
        assertEquals(input.outputPath, result.outputPath)
        assertFalse(result.isDuplicate)
    }

    @Test
    @DisplayName("Skip reason should contain company name when duplicate")
    fun testSkipReasonContainsCompany() {
        // Skip this test when Supabase is configured since fallback won't be used
        assumeTrue(!SupabaseClient.isConfigured(), "Skipped: Supabase is configured - fallback not used")
        
        // Given: unique company/role
        val input = TestJDStateFactory.createFullJobPostingState().copy(
            company = "UniqueTestCompany",
            roleTitle = "UniqueRole"
        )

        // First pass
        val firstResult = node.process(input)
        assertFalse(firstResult.isDuplicate)
        
        // Second pass - should be duplicate (in fallback)
        val secondResult = node.process(input)

        // Then
        assertTrue(secondResult.isDuplicate)
        assertTrue(secondResult.skippedReason.contains("UniqueTestCompany"))
        assertTrue(secondResult.skippedReason.contains("UniqueRole"))
    }

    @Test
    @DisplayName("Different roles should not be duplicates")
    fun testDifferentRolesNotDuplicates() {
        // Skip this test when Supabase is configured
        assumeTrue(!SupabaseClient.isConfigured(), "Skipped: Supabase is configured - fallback not used")
        
        // Given: same company, different roles
        val job1 = TestJDStateFactory.createFullJobPostingState().copy(
            company = "SameCompany",
            roleTitle = "Engineer",
            location = "Seattle, WA"
        )
        val job2 = TestJDStateFactory.createFullJobPostingState().copy(
            company = "SameCompany",
            roleTitle = "Senior Engineer",
            location = "Seattle, WA"
        )

        // When
        node.process(job1)  // Register job1
        val result = node.process(job2)

        // Then: different role should not be duplicate
        assertFalse(result.isDuplicate, "Different role should not be duplicate")
    }

    @Test
    @DisplayName("Different locations should not be duplicates")
    fun testDifferentLocationsNotDuplicates() {
        // Skip this test when Supabase is configured
        assumeTrue(!SupabaseClient.isConfigured(), "Skipped: Supabase is configured - fallback not used")
        
        // Given: same company/role, different locations
        val job1 = TestJDStateFactory.createFullJobPostingState().copy(
            company = "MultiLocationCo",
            roleTitle = "Engineer",
            location = "Seattle, WA"
        )
        val job2 = TestJDStateFactory.createFullJobPostingState().copy(
            company = "MultiLocationCo",
            roleTitle = "Engineer",
            location = "Portland, OR"
        )

        // When
        node.process(job1)  // Register job1
        val result = node.process(job2)

        // Then: different location should not be duplicate
        assertFalse(result.isDuplicate, "Different location should not be duplicate")
    }

    @Test
    @DisplayName("Fallback set should grow with new jobs")
    fun testFallbackSetGrows() {
        // Skip this test when Supabase is configured
        assumeTrue(!SupabaseClient.isConfigured(), "Skipped: Supabase is configured - fallback not used")
        
        // Given: multiple unique jobs
        val job1 = TestJDStateFactory.createFullJobPostingState().copy(
            company = "FirstUnique",
            roleTitle = "Role1"
        )
        val job2 = TestJDStateFactory.createFullJobPostingState().copy(
            company = "SecondUnique",
            roleTitle = "Role2"
        )

        // When
        val result1 = node.process(job1)
        val result2 = node.process(job2)
        val result3 = node.process(job1)  // Should now be duplicate

        // Then: first two should not be duplicate, third should be
        assertFalse(result1.isDuplicate)
        assertFalse(result2.isDuplicate)
        assertTrue(result3.isDuplicate, "First job should now be in fallback set")
    }
}
