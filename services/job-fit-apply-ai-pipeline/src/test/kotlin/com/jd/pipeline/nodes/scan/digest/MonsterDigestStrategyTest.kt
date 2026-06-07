package com.jd.pipeline.nodes.scan.digest

import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("MonsterDigestStrategy")
class MonsterDigestStrategyTest {

    private val parent = JDState()
    private fun email(rawBody: String = "", htmlBody: String = "") = IntakeContext.Email(
        emailId = "mn-1", from = "noreply@monster.com", subject = "Jobs for you",
        rawBody = rawBody, htmlBody = htmlBody, isRecruiter = false, isDigest = false, isInlineDigest = false,
    )

    private fun monsterHtml(role: String, url: String) = """
        <html><body>
          <a href="$url"><strong>$role</strong></a>
        </body></html>
    """.trimIndent()

    private fun monsterBody(role: String, company: String, city: String, state: String) =
        "$role $company - $city - $state VIEW JOB"

    @Nested
    @DisplayName("parsing well-formed Monster digest")
    inner class WellFormedDigest {

        @Test
        @DisplayName("parses role, company, and location from plain text")
        fun parsesJobFromPlainText() {
            val role = "QA Automation Engineer"
            val html = monsterHtml(role, "https://www.monster.com/job-openings/qa-1")
            val body = monsterBody(role, "Acme Corp", "Seattle", "WA")
            val jobs = MonsterDigestStrategy.expand(parent, email(rawBody = body, htmlBody = html))
            assertEquals(1, jobs.size)
            assertEquals(role, jobs[0].roleTitle)
            assertEquals("Acme Corp", jobs[0].company)
            assertEquals("Seattle, WA", jobs[0].location)
        }

        @Test
        @DisplayName("handles NULL state — uses city only")
        fun nullState() {
            val role = "Data Analyst"
            val html = monsterHtml(role, "https://www.monster.com/job-openings/da-1")
            val body = "$role SomeCorp - Chicago - NULL VIEW JOB"
            val jobs = MonsterDigestStrategy.expand(parent, email(rawBody = body, htmlBody = html))
            assertEquals(1, jobs.size)
            assertEquals("Chicago", jobs[0].location)
        }

        @Test
        @DisplayName("handles NULL city — uses state only")
        fun nullCity() {
            val role = "Product Manager"
            val html = monsterHtml(role, "https://www.monster.com/job-openings/pm-1")
            val body = "$role CorpX - NULL - CA VIEW JOB"
            val jobs = MonsterDigestStrategy.expand(parent, email(rawBody = body, htmlBody = html))
            assertEquals(1, jobs.size)
            assertEquals("CA", jobs[0].location)
        }

        @Test
        @DisplayName("marked as job posting with digest flag")
        fun markedCorrectly() {
            val role = "DevOps Engineer"
            val html = monsterHtml(role, "https://www.monster.com/job-openings/devops-1")
            val body = monsterBody(role, "TechCorp", "Austin", "TX")
            val jobs = MonsterDigestStrategy.expand(parent, email(rawBody = body, htmlBody = html))
            assertTrue(jobs.all { it.isJobPosting })
        }
    }

    @Nested
    @DisplayName("deduplication")
    inner class Deduplication {

        @Test
        @DisplayName("does not emit the same company+role twice")
        fun deduplicatesSameCompanyRole() {
            val role = "Engineer"
            val html = monsterHtml(role, "https://www.monster.com/job-openings/eng-1")
            val body = "$role CorpA - Seattle - WA VIEW JOB\n$role CorpA - Seattle - WA VIEW JOB"
            val jobs = MonsterDigestStrategy.expand(parent, email(rawBody = body, htmlBody = html))
            assertEquals(1, jobs.size)
        }
    }

    @Nested
    @DisplayName("edge cases")
    inner class EdgeCases {

        @Test
        @DisplayName("returns empty list when HTML has no <strong> titles")
        fun emptyWhenNoTitles() {
            val jobs = MonsterDigestStrategy.expand(parent, email(rawBody = "nothing", htmlBody = "<html></html>"))
            assertEquals(0, jobs.size)
        }

        @Test
        @DisplayName("filters out noise company values matching role title")
        fun filtersNoiseCompanies() {
            val role = "Engineer"
            val html = monsterHtml(role, "https://www.monster.com/job-openings/e1")
            // company same as role → should be filtered
            val body = "$role $role - Seattle - WA VIEW JOB"
            val jobs = MonsterDigestStrategy.expand(parent, email(rawBody = body, htmlBody = html))
            assertEquals(0, jobs.size)
        }
    }
}
