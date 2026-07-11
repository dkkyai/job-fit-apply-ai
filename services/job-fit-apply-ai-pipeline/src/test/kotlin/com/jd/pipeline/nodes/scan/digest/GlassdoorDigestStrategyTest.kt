package com.jd.pipeline.nodes.scan.digest

import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("GlassdoorDigestStrategy")
class GlassdoorDigestStrategyTest {

    private val parent = JDState()
    private val baseEmail = IntakeContext.Email(
        emailId = "gd-1", from = "noreply@glassdoor.com", subject = "Jobs for you",
        rawBody = "", htmlBody = "", isRecruiter = false, isDigest = false, isInlineDigest = false,
    )

    // ── extractGlassdoorJobUrls (public helper) ──────────────────────────────

    @Nested
    @DisplayName("extractGlassdoorJobUrls")
    inner class ExtractJobUrls {

        @Test
        @DisplayName("extracts partner jobListing URLs")
        fun extractsPartnerUrls() {
            val body = """
                Check out these jobs:
                https://www.glassdoor.com/partner/jobListing.htm?pos=1&ao=123&rdforyou=true
                https://www.glassdoor.com/partner/jobListing.htm?pos=2&ao=456&rdforyou=true
            """.trimIndent()
            val urls = GlassdoorDigestStrategy.extractGlassdoorJobUrls(body)
            assertEquals(2, urls.size)
            assertTrue(urls.all { it.contains("/partner/jobListing.htm") })
        }

        @Test
        @DisplayName("returns empty list when no matching URLs found")
        fun emptyWhenNoUrls() {
            assertEquals(emptyList(), GlassdoorDigestStrategy.extractGlassdoorJobUrls("No jobs here"))
        }

        @Test
        @DisplayName("deduplicates repeated URLs")
        fun deduplicates() {
            val url = "https://www.glassdoor.com/partner/jobListing.htm?pos=1&ao=1&rdforyou=true"
            val body = "$url\n$url"
            val urls = GlassdoorDigestStrategy.extractGlassdoorJobUrls(body)
            assertEquals(1, urls.size)
        }

        @Test
        @DisplayName("caps at MAX_JOBS_PER_EMAIL")
        fun capsAtMax() {
            val body = (1..30).joinToString("\n") {
                "https://www.glassdoor.com/partner/jobListing.htm?pos=$it&ao=$it&rdforyou=true"
            }
            val urls = GlassdoorDigestStrategy.extractGlassdoorJobUrls(body)
            assertTrue(urls.size <= MAX_JOBS_PER_EMAIL)
        }
    }

    // ── expand via HTML ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("expand — HTML parsing path")
    inner class ExpandHtml {

        private fun htmlWithJob(company: String, role: String, location: String, salary: String, url: String) = """
            <html><body>
            <a href="$url">
                <p>$company</p>
                <p>$role</p>
                <p>$location</p>
                <p>$salary</p>
            </a>
            </body></html>
        """.trimIndent()

        @Test
        @DisplayName("returns empty list when HTML is blank")
        fun emptyHtml() {
            val email = baseEmail.copy(htmlBody = "")
            val jobs = GlassdoorDigestStrategy.expand(parent, email)
            assertEquals(0, jobs.size)
        }

        @Test
        @DisplayName("returns empty list when HTML has no jobListing anchors")
        fun noJobListingAnchors() {
            val email = baseEmail.copy(htmlBody = "<html><body><a href='https://glassdoor.com/home'>Home</a></body></html>")
            val jobs = GlassdoorDigestStrategy.expand(parent, email)
            assertEquals(0, jobs.size)
        }

        @Test
        @DisplayName("parses job from HTML anchor with jobListing URL")
        fun parsesHtmlJob() {
            val url = "https://www.glassdoor.com/partner/jobListing.htm?pos=1&ao=1&rdforyou=true"
            val email = baseEmail.copy(
                htmlBody = htmlWithJob("Acme Corp", "Staff SDET", "Seattle, WA", "\$120K–\$180K (Employer est.)", url)
            )
            val jobs = GlassdoorDigestStrategy.expand(parent, email)
            assertTrue(jobs.isNotEmpty())
            assertTrue(jobs[0].isJobPosting)
        }
    }

    // ── current Glassdoor job-alert format (company in <span>) ────────────────
    // Sample: "GEICO is hiring for Staff Cyber Software Engineer. Apply Now."
    // Each card is: <span>company</span> <p>role</p> <p>location</p>
    // <p>salary</p> [<p>Best Place to Work</p>] <p>age</p>. The company is in a
    // <span>, salary reads "( Employer est. )" (space before paren), and the age
    // may be "Just posted".

    @Nested
    @DisplayName("expand — current job-alert format (company in <span>)")
    inner class ExpandSpanFormat {

        private fun card(company: String, role: String, location: String, salary: String, age: String, badge: Boolean = false, url: String) = """
            <a href="$url">
                <span>$company</span>
                <p>$role</p>
                <p>$location</p>
                <p>$salary</p>
                ${if (badge) "<p>Best Place to Work</p>" else ""}
                <p>$age</p>
            </a>
        """.trimIndent()

        @Test
        @DisplayName("extracts company from <span>, not the role <p>")
        fun companyFromSpan() {
            val url = "https://www.glassdoor.com/partner/jobListing.htm?pos=105&ao=1&rdforyou=true"
            val email = baseEmail.copy(htmlBody = "<html><body>" + card(
                company = "GEICO", role = "Staff Cyber Software Engineer",
                location = "Seattle, WA", salary = "\$110K - \$230K ( Employer est. )",
                age = "9h", url = url
            ) + "</body></html>")
            val jobs = GlassdoorDigestStrategy.expand(parent, email)
            assertEquals(1, jobs.size)
            assertEquals("GEICO", jobs[0].company)
            assertEquals("Staff Cyber Software Engineer", jobs[0].roleTitle)
            assertEquals("Seattle, WA", jobs[0].location)
            assertTrue(jobs[0].salaryRange.contains("110K"), "salary should be captured: ${jobs[0].salaryRange}")
            assertTrue(jobs[0].isJobPosting)
        }

        @Test
        @DisplayName("handles the 'Best Place to Work' badge and 'Just posted' age")
        fun badgeAndJustPosted() {
            val url = "https://www.glassdoor.com/partner/jobListing.htm?pos=106&ao=2&rdforyou=true"
            val email = baseEmail.copy(htmlBody = "<html><body>" + card(
                company = "Google", role = "Software Engineer III, Engineering Productivity",
                location = "Kirkland, WA", salary = "\$147K - \$211K ( Employer est. )",
                age = "Just posted", badge = true, url = url
            ) + "</body></html>")
            val jobs = GlassdoorDigestStrategy.expand(parent, email)
            assertEquals(1, jobs.size)
            assertEquals("Google", jobs[0].company)
            assertEquals("Software Engineer III, Engineering Productivity", jobs[0].roleTitle)
        }

        @Test
        @DisplayName("extracts all jobs from a multi-card digest")
        fun multipleCards() {
            val cards = listOf(
                Triple("Amazon Kuiper Manufacturing Enterprises LLC", "System Development Engineer, Dev Test", "Redmond, WA"),
                Triple("Blue Origin", "Software Engineer, Toolpath", "Seattle, WA"),
                Triple("GEICO", "Staff Cyber Software Engineer", "Seattle, WA"),
            ).mapIndexed { i, (company, role, loc) ->
                card(company, role, loc, "\$120K - \$200K ( Employer est. )", "${i + 1}d",
                    url = "https://www.glassdoor.com/partner/jobListing.htm?pos=10$i&ao=$i&rdforyou=true")
            }.joinToString("\n")
            val email = baseEmail.copy(htmlBody = "<html><body>$cards</body></html>")
            val jobs = GlassdoorDigestStrategy.expand(parent, email)
            assertEquals(3, jobs.size)
            assertEquals(listOf("Amazon Kuiper Manufacturing Enterprises LLC", "Blue Origin", "GEICO"),
                jobs.map { it.company })
        }
    }

    // ── expand via plain text ─────────────────────────────────────────────────

    @Nested
    @DisplayName("expand — plain text fallback")
    inner class ExpandPlainText {

        @Test
        @DisplayName("returns empty when no Glassdoor URLs in plain text and HTML is blank")
        fun emptyForUnknownInput() {
            val email = baseEmail.copy(rawBody = "No jobs here.")
            val jobs = GlassdoorDigestStrategy.expand(parent, email)
            assertEquals(0, jobs.size)
        }
    }
}
