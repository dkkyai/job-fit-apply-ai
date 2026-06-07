package com.jd.pipeline.nodes.scan.digest

import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("LinkedInDigestStrategy")
class LinkedInDigestStrategyTest {

    private val digestEmail = IntakeContext.Email(
        emailId = "li-1", from = "jobs@linkedin.com", subject = "Jobs for you",
        rawBody = "", htmlBody = "", isRecruiter = false, isDigest = false, isInlineDigest = false,
    )
    // Parent must carry an email intake so withEmailFlags can propagate the isDigest flag
    private val parentWithIntake = JDState(intake = digestEmail)

    private fun expand(rawBody: String) = LinkedInDigestStrategy.expand(
        parent = parentWithIntake,
        email  = digestEmail.copy(rawBody = rawBody),
    )

    private fun jobBlock(role: String, company: String, location: String, url: String) = """
        --------------------
        $role
        $company
        $location
        View job: $url
        --------------------
    """.trimIndent()

    @Nested
    @DisplayName("valid plain-text digest")
    inner class ValidDigest {

        @Test
        @DisplayName("parses single job block")
        fun singleBlock() {
            val body = jobBlock("Staff SDET", "Acme Corp", "Seattle, WA", "https://www.linkedin.com/jobs/view/111")
            val jobs = expand(body)
            assertEquals(1, jobs.size)
            assertEquals("Staff SDET", jobs[0].roleTitle)
            assertEquals("Acme Corp", jobs[0].company)
            assertEquals("Seattle, WA", jobs[0].location)
            assertEquals("https://www.linkedin.com/jobs/view/111", jobs[0].jobUrl)
        }

        @Test
        @DisplayName("parses multiple job blocks")
        fun multipleBlocks() {
            val body = jobBlock("Engineer", "Corp A", "Remote", "https://www.linkedin.com/jobs/view/1") + "\n" +
                       jobBlock("Manager", "Corp B", "New York, NY", "https://www.linkedin.com/jobs/view/2")
            val jobs = expand(body)
            assertEquals(2, jobs.size)
        }

        @Test
        @DisplayName("each parsed job is marked as a job posting")
        fun markedAsJobPosting() {
            val body = jobBlock("Engineer", "Corp", "Remote", "https://www.linkedin.com/jobs/view/3")
            val jobs = expand(body)
            assertTrue(jobs.all { it.isJobPosting })
        }

        @Test
        @DisplayName("each parsed job has isDigest flag set")
        fun hasDigestFlag() {
            val body = jobBlock("Engineer", "Corp", "Remote", "https://www.linkedin.com/jobs/view/4")
            val jobs = expand(body)
            assertTrue(jobs.all { it.intake is IntakeContext.Email && (it.intake as IntakeContext.Email).isDigest })
        }
    }

    @Nested
    @DisplayName("filtering noise lines")
    inner class FilteringNoise {

        @Test
        @DisplayName("skips blocks with noise-only detail lines")
        fun skipsNoiseBlocks() {
            val body = """
                --------------------
                Apply with resume & profile
                Your job alert for Software Engineers
                New jobs match your preferences.
                View job: https://www.linkedin.com/jobs/view/99
                --------------------
            """.trimIndent()
            val jobs = expand(body)
            assertEquals(0, jobs.size)
        }
    }

    @Nested
    @DisplayName("edge cases")
    inner class EdgeCases {

        @Test
        @DisplayName("returns empty list for empty input")
        fun emptyInput() {
            assertEquals(0, expand("").size)
        }

        @Test
        @DisplayName("returns empty list when no 'View job:' line is present")
        fun noViewJobLine() {
            val body = "----\nEngineer\nCorp\nRemote\n----"
            assertEquals(0, expand(body).size)
        }

        @Test
        @DisplayName("respects MAX_JOBS_PER_EMAIL cap")
        fun capsAtMaxJobs() {
            val body = (1..30).joinToString("\n") {
                jobBlock("Engineer $it", "Corp $it", "Remote", "https://www.linkedin.com/jobs/view/$it")
            }
            val jobs = expand(body)
            assertTrue(jobs.size <= MAX_JOBS_PER_EMAIL)
        }
    }
}
