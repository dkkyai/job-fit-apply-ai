package com.jd.pipeline.nodes.scan.digest

import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("WelcomeToTheJungleDigestStrategy")
class WelcomeToTheJungleDigestStrategyTest {

    private val digestEmail = IntakeContext.Email(
        emailId = "wttj-1", from = "jobs@welcometothejungle.com", subject = "New jobs",
        rawBody = "", htmlBody = "", isRecruiter = false, isDigest = false, isInlineDigest = false,
    )
    private val parent = JDState(intake = digestEmail)
    private fun expand(html: String) = WelcomeToTheJungleDigestStrategy.expand(parent, digestEmail.copy(htmlBody = html))

    private fun anchorHtml(company: String, role: String, extra: String, url: String) = """
        <html><body>
        <a href="$url">
          <strong>$company</strong>
          <strong>$role</strong>
          $extra
        </a>
        </body></html>
    """.trimIndent()

    @Nested
    @DisplayName("valid anchor")
    inner class ValidAnchor {

        @Test
        @DisplayName("parses company and role from the first two strong tags")
        fun parsesCompanyAndRole() {
            val html = anchorHtml(
                company = "Acme Corp",
                role = "Staff Engineer",
                extra = "Remote (within the US) Salary: \$120K-150K",
                url = "https://sendgrid.net/ls/click?upn=abc",
            )
            val jobs = expand(html)
            assertEquals(1, jobs.size)
            assertEquals("Acme Corp", jobs[0].company)
            assertEquals("Staff Engineer", jobs[0].roleTitle)
            assertEquals("https://sendgrid.net/ls/click?upn=abc", jobs[0].jobUrl)
        }

        @Test
        @DisplayName("extracts salary and location from the anchor text")
        fun extractsSalaryAndLocation() {
            val html = anchorHtml(
                company = "Acme",
                role = "Backend Engineer",
                extra = "Remote (within the US) Salary: \$120K-150K",
                url = "https://sendgrid.net/ls/click?upn=def",
            )
            val jobs = expand(html)
            assertEquals(1, jobs.size)
            assertTrue(jobs[0].salaryRange.contains("120K"))
            assertEquals("Remote (within the US)", jobs[0].location)
        }

        @Test
        @DisplayName("extracts a city, state location (greedy match includes preceding text up to the comma)")
        fun extractsCityStateLocation() {
            val html = anchorHtml(
                company = "Acme",
                role = "Frontend Engineer",
                extra = "Seattle, WA Salary: \$110K-140K",
                url = "https://sendgrid.net/ls/click?upn=ghi",
            )
            val jobs = expand(html)
            assertEquals(1, jobs.size)
            // The location regex has no left boundary, so it greedily matches from the start
            // of the anchor's text through to the first ", XX" — this is documenting actual
            // behavior, not a spec.
            assertTrue(jobs[0].location.endsWith("Seattle, WA"))
        }

        @Test
        @DisplayName("marks parsed jobs as job postings with the digest flag")
        fun marksAsJobPosting() {
            val html = anchorHtml("Acme", "Engineer", "Remote (within the US)", "https://sendgrid.net/ls/click?upn=jkl")
            val jobs = expand(html)
            assertTrue(jobs.all { it.isJobPosting })
            assertTrue(jobs.all { (it.intake as IntakeContext.Email).isDigest })
        }

        @Test
        @DisplayName("deduplicates jobs with the same role title")
        fun deduplicatesByRoleTitle() {
            val html = """
                <html><body>
                <a href="https://sendgrid.net/ls/click?upn=1">
                  <strong>Acme</strong><strong>Engineer</strong> Remote (within the US)
                </a>
                <a href="https://sendgrid.net/ls/click?upn=2">
                  <strong>OtherCo</strong><strong>Engineer</strong> Remote (within the US)
                </a>
                </body></html>
            """.trimIndent()
            val jobs = expand(html)
            assertEquals(1, jobs.size)
        }
    }

    @Nested
    @DisplayName("edge cases")
    inner class EdgeCases {

        @Test
        @DisplayName("returns empty list for blank HTML")
        fun blankHtml() {
            assertEquals(0, expand("").size)
        }

        @Test
        @DisplayName("returns empty list when no sendgrid click anchors are present")
        fun noAnchors() {
            val html = "<html><body><a href='https://google.com'>Go</a></body></html>"
            assertEquals(0, expand(html).size)
        }

        @Test
        @DisplayName("skips anchors with fewer than two strong tags")
        fun skipsAnchorsWithoutTwoStrongs() {
            val html = """
                <html><body>
                <a href="https://sendgrid.net/ls/click?upn=only-one">
                  <strong>OnlyOneStrong</strong> some text
                </a>
                </body></html>
            """.trimIndent()
            assertEquals(0, expand(html).size)
        }

        @Test
        @DisplayName("respects MAX_JOBS_PER_EMAIL cap")
        fun capsAtMaxJobs() {
            val anchors = (1..30).joinToString("\n") {
                """<a href="https://sendgrid.net/ls/click?upn=$it">
                     <strong>Company $it</strong><strong>Role $it</strong> Remote (within the US)
                   </a>"""
            }
            val html = "<html><body>$anchors</body></html>"
            val jobs = expand(html)
            assertTrue(jobs.size <= MAX_JOBS_PER_EMAIL)
        }
    }
}
