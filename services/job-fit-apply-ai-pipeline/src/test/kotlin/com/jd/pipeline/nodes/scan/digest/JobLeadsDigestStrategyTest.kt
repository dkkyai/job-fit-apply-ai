package com.jd.pipeline.nodes.scan.digest

import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("JobLeadsDigestStrategy")
class JobLeadsDigestStrategyTest {

    private val baseEmail = IntakeContext.Email(
        emailId = "jl-1", from = "noreply@jobleads.com", subject = "Jobs for you",
        rawBody = "", htmlBody = "", isRecruiter = false, isDigest = false, isInlineDigest = false,
    )
    // Parent must carry an email intake so withEmailFlags can propagate isDigest
    private val parent = JDState(intake = baseEmail)
    private fun email(rawBody: String = "", htmlBody: String = "") = IntakeContext.Email(
        emailId = "jl-1", from = "noreply@jobleads.com", subject = "Jobs for you",
        rawBody = rawBody, htmlBody = htmlBody, isRecruiter = false, isDigest = false, isInlineDigest = false,
    )

    /** Wrap card in a table so Jsoup preserves the td element. */
    private fun cardHtml(title: String, company: String, location: String, salary: String = ""): String {
        val salaryDiv = if (salary.isNotBlank()) "<div>$salary</div>" else ""
        return """
            <table><tbody><tr>
            <td style="padding: 16px">
              <div>$title</div>
              <div>$company</div>
              <div>$location</div>
              $salaryDiv
            </td>
            </tr></tbody></table>
        """.trimIndent()
    }

    @Nested
    @DisplayName("parsing well-formed JobLeads HTML digest")
    inner class WellFormedDigest {

        @Test
        @DisplayName("parses title, company, and location from card")
        fun parsesCard() {
            val url = "https://www.jobleads.com/job/123"
            val html = "<html><body>${cardHtml("Staff SDET", "Acme Corp", "Remote")}</body></html>"
            val body = "View job: $url"
            val jobs = JobLeadsDigestStrategy.expand(parent, email(rawBody = body, htmlBody = html))
            assertEquals(1, jobs.size)
            assertEquals("Staff SDET", jobs[0].roleTitle)
            assertEquals("Acme Corp", jobs[0].company)
            assertEquals("Remote", jobs[0].location)
        }

        @Test
        @DisplayName("assigns job URL from 'View job:' line in plain text")
        fun assignsUrl() {
            val url = "https://www.jobleads.com/job/456"
            val html = "<html><body>${cardHtml("Engineer", "Corp", "Seattle, WA")}</body></html>"
            val body = "View job: $url"
            val jobs = JobLeadsDigestStrategy.expand(parent, email(rawBody = body, htmlBody = html))
            assertEquals(url, jobs[0].jobUrl)
        }

        @Test
        @DisplayName("parses salary when USD prefix present")
        fun parsesSalary() {
            val url = "https://www.jobleads.com/job/789"
            val html = "<html><body>${cardHtml("Analyst", "Corp", "Remote", "USD 90000 - 120000")}</body></html>"
            val body = "View job: $url"
            val jobs = JobLeadsDigestStrategy.expand(parent, email(rawBody = body, htmlBody = html))
            assertEquals(1, jobs.size)
            assertEquals("USD 90000 - 120000", jobs[0].salaryRange)
        }

        @Test
        @DisplayName("all parsed jobs are marked as job postings with digest flag")
        fun markedCorrectly() {
            val url = "https://www.jobleads.com/job/101"
            val html = "<html><body>${cardHtml("Developer", "Corp", "Remote")}</body></html>"
            val body = "View job: $url"
            val jobs = JobLeadsDigestStrategy.expand(parent, email(rawBody = body, htmlBody = html))
            assertTrue(jobs.all { it.isJobPosting })
            assertTrue(jobs.all { (it.intake as? IntakeContext.Email)?.isDigest == true })
        }

        @Test
        @DisplayName("parses multiple cards with matching URLs")
        fun multipleCards() {
            val cards = (1..3).joinToString("") { i ->
                cardHtml("Role $i", "Corp $i", "City $i, WA")
            }
            val urls = (1..3).joinToString("\n") { "View job: https://www.jobleads.com/job/$it" }
            val html = "<html><body>$cards</body></html>"
            val jobs = JobLeadsDigestStrategy.expand(parent, email(rawBody = urls, htmlBody = html))
            assertEquals(3, jobs.size)
        }
    }

    @Nested
    @DisplayName("edge cases")
    inner class EdgeCases {

        @Test
        @DisplayName("returns empty list when HTML is blank")
        fun emptyHtml() {
            assertEquals(0, JobLeadsDigestStrategy.expand(parent, email()).size)
        }

        @Test
        @DisplayName("returns empty list when no cards match the selector")
        fun noMatchingCards() {
            val html = "<html><body><td>No padding style</td></body></html>"
            assertEquals(0, JobLeadsDigestStrategy.expand(parent, email(htmlBody = html)).size)
        }

        @Test
        @DisplayName("skips cards without a location-looking div")
        fun skipsCardsWithoutLocation() {
            val html = """
                <html><body>
                <td style="padding: 16px"><div>Title</div><div>Company</div><div>Not a location</div></td>
                </body></html>
            """.trimIndent()
            assertEquals(0, JobLeadsDigestStrategy.expand(parent, email(rawBody = "", htmlBody = html)).size)
        }
    }
}
