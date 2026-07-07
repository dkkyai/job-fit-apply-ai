package com.jd.pipeline.nodes.scan.digest

import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.isDigest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("BuiltInDigestStrategy")
class BuiltInDigestStrategyTest {

    private val baseEmail = IntakeContext.Email(
        emailId = "bi-1", from = "Built In <support@builtin.com>", subject = "New Test Engineer Job Matches",
        rawBody = "", htmlBody = "", isRecruiter = false, isDigest = false, isInlineDigest = false,
    )
    private val parent = JDState(intake = baseEmail)
    private fun email(rawBody: String = "", htmlBody: String = "") = baseEmail.copy(rawBody = rawBody, htmlBody = htmlBody)

    /** Build an AWS SES tracking redirect that wraps a builtin.com job URL, as the real emails do. */
    private fun awstrack(jobUrl: String): String {
        val encoded = java.net.URLEncoder.encode(jobUrl, Charsets.UTF_8).replace("+", "%20")
        return "https://cb4sdw3d.r.us-west-2.awstrack.me/L0/$encoded/1/0101019eb887f34c-deadbeef-000000/sig=474"
    }

    /** A single Built In job card, mirroring the real email markup (unquoted inline styles included). */
    private fun card(company: String, role: String, remote: String, location: String, salary: String, jobUrl: String) = """
        <tr><td><a href="${awstrack(jobUrl)}" target=_blank>
          <div style=vertical-align:middle;width:70%;display:inline-block>
            <div style=margin-bottom:8px;font-size:16px>$company</div>
            <div style=margin-bottom:8px;font-size:20px;font-weight:700>$role</div>
            <div style=margin-bottom:4px;font-size:12px>
              <div style=display:inline-block><img src=Remote-HybridIcon.png style=vertical-align:middle><span style=vertical-align:middle>$remote</span></div>
              <div style=display:inline-block><img src=LocationIcon.png style=vertical-align:middle><span style=vertical-align:middle>$location</span></div>
            </div>
            <div style=font-size:12px><img src=SalaryIcon.png style=vertical-align:middle><span style=vertical-align:middle>$salary</span></div>
          </div>
        </a></td></tr>
    """.trimIndent()

    @Nested
    @DisplayName("expand — HTML cards")
    inner class ExpandHtml {

        @Test
        @DisplayName("parses two job cards with company/role/location/salary and unwrapped URLs")
        fun parsesTwoCards() {
            val html = "<html><body><table>" +
                card("Stoke Space", "Software Developer Engineer in Test", "In Office", "Kent, WA", "\$97,125-\$182,070",
                    "https://builtin.com/job/software-developer-engineer-test/9694258?i=abc&utm_source=ses") +
                card("Capital Technology Group", "Software Test Engineer", "Remote", "US", "\$80,000-\$125,000",
                    "https://builtin.com/job/software-test-engineer/9666813?i=abc&utm_source=ses") +
                "</table></body></html>"

            val jobs = BuiltInDigestStrategy.expand(parent, email(htmlBody = html))
            assertEquals(2, jobs.size)

            assertEquals("Stoke Space", jobs[0].company)
            assertEquals("Software Developer Engineer in Test", jobs[0].roleTitle)
            assertEquals("Kent, WA", jobs[0].location)
            assertEquals("\$97,125-\$182,070", jobs[0].salaryRange)
            assertEquals("onsite", jobs[0].remotePolicy)
            // Tracking redirect unwrapped + query stripped → canonical job URL
            assertEquals("https://builtin.com/job/software-developer-engineer-test/9694258", jobs[0].jobUrl)
            assertTrue(jobs[0].isJobPosting)
            assertTrue(jobs[0].isDigest)

            assertEquals("Capital Technology Group", jobs[1].company)
            assertEquals("remote", jobs[1].remotePolicy)
            assertEquals("https://builtin.com/job/software-test-engineer/9666813", jobs[1].jobUrl)
        }

        @Test
        @DisplayName("ignores non-job links (profile, jobs index, unsubscribe)")
        fun ignoresNonJobLinks() {
            val html = """
                <html><body>
                  <a href="${awstrack("https://builtin.com/jobs?i=abc")}">All jobs</a>
                  <a href="${awstrack("https://builtin.com/profile/job-preferences?i=abc")}">Preferences</a>
                  <a href="https://builtin.com/newsletter/unsubscribe/xyz">Unsubscribe</a>
                </body></html>
            """.trimIndent()
            assertEquals(0, BuiltInDigestStrategy.expand(parent, email(htmlBody = html)).size)
        }

        @Test
        @DisplayName("deduplicates the same job URL appearing twice")
        fun deduplicates() {
            val card = card("Acme", "QA Engineer", "Remote", "US", "\$100,000",
                "https://builtin.com/job/qa-engineer/111?i=abc")
            val html = "<html><body><table>$card$card</table></body></html>"
            assertEquals(1, BuiltInDigestStrategy.expand(parent, email(htmlBody = html)).size)
        }

        @Test
        @DisplayName("returns empty when HTML is blank")
        fun emptyHtml() {
            assertEquals(0, BuiltInDigestStrategy.expand(parent, email()).size)
        }
    }

    @Nested
    @DisplayName("unwrapTrackingUrl")
    inner class Unwrap {

        @Test
        @DisplayName("unwraps awstrack redirect and strips query/fragment")
        fun unwrapsAwstrack() {
            val wrapped = awstrack("https://builtin.com/job/x/123?i=abc&utm_medium=email")
            assertEquals("https://builtin.com/job/x/123", BuiltInDigestStrategy.unwrapTrackingUrl(wrapped))
        }

        @Test
        @DisplayName("returns a non-wrapped URL de-tracked")
        fun passthroughDirect() {
            assertEquals("https://builtin.com/job/x/123",
                BuiltInDigestStrategy.unwrapTrackingUrl("https://builtin.com/job/x/123?utm_source=ses"))
        }
    }

    @Nested
    @DisplayName("expand — plain text fallback")
    inner class ExpandPlainText {

        @Test
        @DisplayName("extracts canonical job URLs from awstrack links in plain text")
        fun plainTextFallback() {
            val body = "Your matches: ${awstrack("https://builtin.com/job/sdet/9001?i=abc")} and more"
            val jobs = BuiltInDigestStrategy.expand(parent, email(rawBody = body))
            assertEquals(1, jobs.size)
            assertEquals("https://builtin.com/job/sdet/9001", jobs[0].jobUrl)
        }

        @Test
        @DisplayName("returns empty when no builtin job URLs present")
        fun noUrls() {
            assertEquals(0, BuiltInDigestStrategy.expand(parent, email(rawBody = "nothing here")).size)
        }
    }
}
