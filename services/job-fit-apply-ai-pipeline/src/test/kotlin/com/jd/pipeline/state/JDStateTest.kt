package com.jd.pipeline.state
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.PipelineAction

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for JDState data class.
 */
@DisplayName("JDStateTest")
class JDStateTest {

    @Nested
    @DisplayName("JDState.fromEmail() Tests")
    inner class FromEmailTests {

        @Test
        @DisplayName("fromEmail() should create minimal JDState")
        fun testFromEmailCreatesMinimalState() {
            val state = JDState.fromEmail(
                emailId = "email-001",
                subject = "Job Opportunity: SDET",
                from = "recruiter@company.com",
                body = "We have a great opportunity..."
            )

            val email = state.emailIntake
            assertNotNull(email)
            assertEquals("email-001", email.emailId)
            assertEquals("Job Opportunity: SDET", email.subject)
            assertEquals("recruiter@company.com", email.from)
            assertEquals("We have a great opportunity...", email.rawBody)
            assertEquals("", state.company)
            assertEquals("", state.roleTitle)
            assertFalse(state.isJobPosting)
        }

        @Test
        @DisplayName("fromEmail() should handle empty strings")
        fun testFromEmailHandlesEmptyStrings() {
            val state = JDState.fromEmail("", "", "", "")

            val email = state.emailIntake
            assertNotNull(email)
            assertEquals("", email.emailId)
            assertEquals("", email.subject)
            assertEquals("", email.from)
            assertEquals("", email.rawBody)
        }
    }

    @Nested
    @DisplayName("JDState Immutability Tests")
    inner class ImmutabilityTests {

        @Test
        @DisplayName("copy() should create new instance with updated fields")
        fun testCopyCreatesNewInstance() {
            val original = JDState(
                company = "Original Co",
                roleTitle = "Engineer",
                fitScore = 80.0f
            )

            val copy = original.copy(fitScore = 90.0f)

            assertEquals(80.0f, original.fitScore)
            assertEquals(90.0f, copy.fitScore)
            assertEquals("Original Co", copy.company)
        }

        @Test
        @DisplayName("copy() with no changes should equal original")
        fun testCopyWithNoChangesEqualsOriginal() {
            val original = JDState(
                company = "Test Co",
                roleTitle = "Engineer",
                techStack = listOf("Kotlin")
            )

            val copy = original.copy()

            assertEquals(original, copy)
        }

        @Test
        @DisplayName("Default values should be correct")
        fun testDefaultValues() {
            val state = JDState()

            assertNull(state.intake)
            assertFalse(state.isRecruiterEmail)
            assertFalse(state.isDigest)
            assertFalse(state.isInlineDigest)
            assertFalse(state.isJobPosting)
            assertEquals("unknown", state.remotePolicy)
            assertEquals(PipelineAction.SKIP, state.pipelineAction)
            assertNull(state.fitScore)
            assertNull(state.trackId)
            assertNull(state.duplicateId)
            assertNull(state.yoeRequired)
            assertTrue(state.techStack.isEmpty())
            assertTrue(state.strengths.isEmpty())
            assertTrue(state.gaps.isEmpty())
            assertTrue(state.redFlags.isEmpty())
            assertTrue(state.benefits.isEmpty())
        }
    }

    @Nested
    @DisplayName("JDState Equality Tests")
    inner class EqualityTests {

        @Test
        @DisplayName("Two JDStates with same values should be equal")
        fun testEqualStates() {
            val state1 = JDState(
                company = "Test Co",
                roleTitle = "Engineer",
                fitScore = 85.0f
            )
            val state2 = JDState(
                company = "Test Co",
                roleTitle = "Engineer",
                fitScore = 85.0f
            )

            assertEquals(state1, state2)
        }

        @Test
        @DisplayName("Two JDStates with different values should not be equal")
        fun testUnequalStates() {
            val state1 = JDState(company = "Co A")
            val state2 = JDState(company = "Co B")

            assertTrue(state1 != state2)
        }
    }
}
