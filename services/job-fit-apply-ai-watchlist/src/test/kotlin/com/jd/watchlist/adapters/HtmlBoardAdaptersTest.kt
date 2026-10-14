package com.jd.watchlist.adapters

import com.jd.watchlist.FakeHttp
import com.jd.watchlist.model.Employer
import com.jd.watchlist.resource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IcimsClassicAdapterTest {
    private val feed = "https://careers-acme.icims.com/jobs/search?ss=1&searchKeyword=software"
    private val acme = Employer(company = "Acme Labs", ats = "icims", feed = feed)

    @Test
    fun `reads job cards in the iframe view and stops when a page repeats`() {
        val http = FakeHttp(getHandler = { url -> if (url.startsWith("https://careers-acme.icims.com/jobs/search")) resource("icims_list.html") else null })
        val postings = IcimsClassicAdapter(http).list(acme)
        assertEquals(listOf("1901", "1902", "1903"), postings.map { it.externalId })
        assertEquals("US-WA-Seattle", postings[2].location, "header-tag layout (e.g. Fred Hutch)")
        assertEquals("Software QA Engineer", postings[0].title)
        assertEquals("US-WA-Seattle", postings[0].location, "the value span, not the sr-only label")
        assertEquals("https://careers-acme.icims.com/jobs/1901/software-qa-engineer/job", postings[0].url, "candidate link drops in_iframe")
        assertEquals(
            listOf(
                "GET https://careers-acme.icims.com/jobs/search?ss=1&searchKeyword=software&in_iframe=1&pr=0",
                "GET https://careers-acme.icims.com/jobs/search?ss=1&searchKeyword=software&in_iframe=1&pr=1",
            ),
            http.requests, "page 1 repeats page 0 → stop",
        )
    }

    @Test
    fun `describe reads the job content block in the iframe view`() {
        val http = FakeHttp(getHandler = { url ->
            when {
                "/jobs/search" in url -> resource("icims_list.html")
                url == "https://careers-acme.icims.com/jobs/1901/software-qa-engineer/job?in_iframe=1" -> resource("icims_detail.html")
                else -> null
            }
        })
        val adapter = IcimsClassicAdapter(http)
        val text = adapter.describe(acme, adapter.list(acme).first())!!
        assertTrue("Own automated regression for lab data systems." in text && "Python" in text)
        assertTrue("Menu" !in text && "Privacy" !in text, "only the job content")
    }

    @Test
    fun `withParam sets or replaces one query parameter`() {
        assertEquals("https://x/jobs/search?ss=1&pr=2", withParam("https://x/jobs/search?ss=1&pr=1", "pr", "2"))
        assertEquals("https://x/jobs/search?in_iframe=1", withParam("https://x/jobs/search", "in_iframe", "1"))
        assertEquals("https://x/a?b=c&k=v", withParam("https://x/a?b=c#frag", "k", "v"))
    }
}

class JobviteAdapterTest {
    @Test
    fun `reads the job table, treats 'N Locations' as unknown, and describes from the detail page`() {
        val http = FakeHttp(
            gets = mapOf(
                "https://jobs.jobvite.com/kymetacorp/jobs" to resource("jobvite_list.html"),
                "https://jobs.jobvite.com/kymetacorp/job/ok0HAfwl" to resource("jobvite_detail.html"),
            ),
        )
        val kymeta = Employer(company = "Kymeta", ats = "jobvite", board = "kymetacorp")
        val adapter = JobviteAdapter(http)
        val postings = adapter.list(kymeta)
        assertEquals(listOf("ok0HAfwl", "oCOKAfwu"), postings.map { it.externalId })
        assertEquals("https://jobs.jobvite.com/kymetacorp/job/ok0HAfwl", postings[0].url)
        assertEquals("Redmond, Washington", postings[0].location)
        assertEquals("", postings[1].location)
        assertEquals("Description\nValidate satellite terminals in the lab.", adapter.describe(kymeta, postings[0]))
    }
}
