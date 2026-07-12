package com.jd.pipeline.nodes.scan.digest

import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("LensaDigestStrategy")
class LensaDigestStrategyTest {

    private val digestEmail = IntakeContext.Email(
        emailId = "lz-1", from = "jobs@email.mg3.lensa.com", subject = "New jobs for you",
        rawBody = "", htmlBody = "", isRecruiter = false, isDigest = false, isInlineDigest = false,
    )
    private val parent = JDState(intake = digestEmail)
    private fun expand(html: String) = LensaDigestStrategy.expand(parent, digestEmail.copy(htmlBody = html))

    private fun cardHtml(company: String, titleAndLocation: String, url: String, salary: String, extraRow: String = "") = """
        <html><body>
        <table style="border-radius: 8px">
          <tr><td>$company</td></tr>
          <tr><td><a href="$url">$titleAndLocation</a></td></tr>
          $extraRow
          <tr><td>$salary</td></tr>
        </table>
        </body></html>
    """.trimIndent()

    @Nested
    @DisplayName("valid card")
    inner class ValidCard {

        @Test
        @DisplayName("parses company, title, location and salary from a card")
        fun parsesFullCard() {
            val html = cardHtml(
                company = "Acme Corp",
                titleAndLocation = "Senior Backend Engineer Seattle, WA or Remote",
                url = "https://email.mg3.lensa.com/c/abc123",
                salary = "\$120K / yr.",
            )
            val jobs = expand(html)
            assertEquals(1, jobs.size)
            assertEquals("Acme Corp", jobs[0].company)
            assertEquals("Senior Backend Engineer", jobs[0].roleTitle)
            assertEquals("Seattle, WA or Remote", jobs[0].location)
            assertTrue(jobs[0].salaryRange.contains("\$120K"))
            assertEquals("https://email.mg3.lensa.com/c/abc123", jobs[0].jobUrl)
        }

        @Test
        @DisplayName("marks parsed jobs as job postings with the digest flag")
        fun marksAsJobPosting() {
            val html = cardHtml("Acme", "Engineer Remote", "https://email.mg3.lensa.com/c/1", "\$100K / yr.")
            val jobs = expand(html)
            assertTrue(jobs.all { it.isJobPosting })
            assertTrue(jobs.all { (it.intake as IntakeContext.Email).isDigest })
        }

        @Test
        @DisplayName("strips a trailing (Hybrid) suffix from the title")
        fun stripsHybridSuffix() {
            val html = cardHtml(
                company = "HybridCo",
                titleAndLocation = "Platform Engineer (Hybrid) Austin, TX",
                url = "https://email.mg3.lensa.com/c/hyb",
                salary = "\$140K / yr.",
            )
            val jobs = expand(html)
            assertEquals(1, jobs.size)
            assertTrue(!jobs[0].roleTitle.contains("Hybrid"))
        }
    }

    @Nested
    @DisplayName("company detection skips noise cells")
    inner class CompanyDetection {

        @Test
        @DisplayName("skips a purely numeric rating cell and a star-rating cell before finding the company")
        fun skipsRatingCells() {
            val html = """
                <html><body>
                <table style="border-radius: 8px">
                  <tr><td>4.5</td></tr>
                  <tr><td>4.5 &#9733;</td></tr>
                  <tr><td>Real Company Inc</td></tr>
                  <tr><td><a href="https://email.mg3.lensa.com/c/xyz">Data Engineer Remote</a></td></tr>
                  <tr><td>${'$'}130K / yr.</td></tr>
                </table>
                </body></html>
            """.trimIndent()
            val jobs = expand(html)
            assertEquals(1, jobs.size)
            assertEquals("Real Company Inc", jobs[0].company)
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
        @DisplayName("returns empty list when no lensa card anchors are present")
        fun noCards() {
            val html = "<html><body><a href='https://google.com'>Go</a></body></html>"
            assertEquals(0, expand(html).size)
        }

        @Test
        @DisplayName("skips a card with no salary information")
        fun skipsCardWithoutSalary() {
            val html = cardHtml(
                company = "NoSalaryCo",
                titleAndLocation = "Engineer Remote",
                url = "https://email.mg3.lensa.com/c/nosalary",
                salary = "",
            )
            val jobs = expand(html)
            assertEquals(0, jobs.size)
        }

        @Test
        @DisplayName("respects MAX_JOBS_PER_EMAIL cap")
        fun capsAtMaxJobs() {
            val cards = (1..30).joinToString("\n") {
                """<table style="border-radius: 8px">
                     <tr><td>Company $it</td></tr>
                     <tr><td><a href="https://email.mg3.lensa.com/c/$it">Engineer $it Remote</a></td></tr>
                     <tr><td>$100K / yr.</td></tr>
                   </table>"""
            }
            val html = "<html><body>$cards</body></html>"
            val jobs = expand(html)
            assertTrue(jobs.size <= MAX_JOBS_PER_EMAIL)
        }
    }
}
