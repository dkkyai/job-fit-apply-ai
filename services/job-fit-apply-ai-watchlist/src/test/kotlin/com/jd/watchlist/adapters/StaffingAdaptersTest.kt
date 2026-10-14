package com.jd.watchlist.adapters

import com.jd.watchlist.FakeHttp
import com.jd.watchlist.model.Employer
import com.jd.watchlist.resource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KforceAdapterTest {
    private val kforce = Employer(company = "Kforce", ats = "kforce", location = "WA")

    private fun http(searchCalls: MutableList<String>) = FakeHttp(
        gets = mapOf(
            "https://www.kforce.com/find-work/search-jobs/" to resource("kforce_page.html"),
            "https://kforcewebeast.azureedge.net/scripts/dist/js/app.min.js?v=1" to resource("kforce_app.js"),
        ),
        getHandler = { url -> if (url.startsWith("https://kforcewebeast.search.windows.net/")) { searchCalls += url; resource("kforce_search.json") } else null },
    )

    @Test
    fun `reads the public query key from the app script, searches the state, dedupes across terms`() {
        val calls = mutableListOf<String>()
        val h = http(calls)
        val postings = KforceAdapter(h, listOf("test", "quality")).list(kforce)
        assertEquals(listOf("2191038-TVT", "XYZ"), postings.map { it.externalId }, "reference code, else the index id")
        assertEquals(2, calls.size)
        assertTrue(calls.all { "%24filter=State%20eq%20%27WA%27" in it || "\$filter=State%20eq%20%27WA%27" in it }, calls.first())
        assertEquals("0123456789ABCDEF0123456789ABCDEF", h.getHeaders.last()["api-key"], "the prod key, not the other environment's")
        val qa = postings[0]
        assertEquals("https://www.kforce.com/Jobs/1696~TVT~2191038T1~99/", qa.url, "the public job page, not ApplyOnline")
        assertEquals("Seattle, WA", qa.location)
        assertTrue("Run regression suites on lab hardware." in qa.description!! && "Python" in qa.description!!)
        assertNull(postings[1].description)
    }

    @Test
    fun `a missing key or state is an error, not an empty board`() {
        val noKey = FakeHttp(gets = mapOf(
            "https://www.kforce.com/find-work/search-jobs/" to resource("kforce_page.html"),
            "https://kforcewebeast.azureedge.net/scripts/dist/js/app.min.js?v=1" to "var nothing=1;",
        ))
        assertFailsWith<IllegalStateException> { KforceAdapter(noKey, listOf("test")).list(kforce) }
        assertFailsWith<IllegalArgumentException> { KforceAdapter(noKey, listOf("test")).list(kforce.copy(location = null)) }
    }
}

class RobertHalfAdapterTest {
    private val rh = Employer(company = "Robert Half (technology)", ats = "roberthalf", feed = "https://www.roberthalf.com/us/en/jobs/all/seattle-wa?lobid=RHT")

    @Test
    fun `decodes the JS-string initialResults and maps each job`() {
        val http = FakeHttp(gets = mapOf(rh.feed!! to resource("roberthalf_page.html")))
        val postings = RobertHalfAdapter(http).list(rh)
        assertEquals(listOf("04410-0013521193-usen", "04410-2"), postings.map { it.externalId })
        val qa = postings[0]
        assertEquals("QA Automation Engineer", qa.title)
        assertEquals("Seattle, WA | Remote", qa.location)
        assertEquals("https://www.roberthalf.com/us/en/job/seattle-wa/qa-automation-engineer/04410-0013521193-usen", qa.url, "\\/ and \\u002D decoded")
        assertEquals("Own Selenium & API tests – contract.", qa.description)
        assertEquals("Seattle, WA", postings[1].location)
    }

    @Test
    fun `a page without initialResults is an error`() {
        assertFailsWith<IllegalStateException> { RobertHalfAdapter(FakeHttp(getHandler = { "<html></html>" })).list(rh) }
    }
}

class JobDivaAdapterTest {
    private val aditi = Employer(company = "Aditi Consulting", ats = "jobdiva", board = "abc123token")
    private val base = "https://www2.jobdiva.com/candidates/myjobs/getjobsrssfeed.jsp?a=abc123token&SearchString="

    @Test
    fun `reads the RSS feed once per term, splits the title, keys on the posting id`() {
        var pauses = 0
        val h = FakeHttp(gets = mapOf(base + "test" to resource("jobdiva_test.xml"), base + "quality%20assurance" to resource("jobdiva_qa.xml")))
        val postings = JobDivaAdapter(h, listOf("test", "quality assurance")) { pauses++ }.list(aditi)

        assertEquals(listOf("29401111", "29400001", "29400002", "29455555"), postings.map { it.externalId },
            "a posting found by two terms is listed once; an item without an id is dropped")
        assertEquals(1, pauses, "a pause between the two feed reads, none before the first")
        val metrology = postings[0]
        assertEquals("Optical Metrology and Test Engineer", metrology.title)
        assertEquals("Redmond, WA", metrology.location)
        assertEquals("https://www2.jobdiva.com/candidates/myjobs/openjob_outside.jsp?a=abc123token&id=29401111", metrology.url,
            "no per-search or timestamp parameters, so the URL is stable")
        assertTrue("Build automated test fixtures" in metrology.description!!, metrology.description)
        assertEquals("Waterloo, IA", postings[1].location)
        assertEquals("QA Lead - Remote", postings[2].title, "a title without the req/state/city shape is kept whole")
        assertEquals("", postings[2].location)
        assertEquals("REDMOND, WA", postings[3].location)
    }

    @Test
    fun `a missing portal token is an error, not an empty board`() {
        assertFailsWith<IllegalArgumentException> { JobDivaAdapter(FakeHttp(), listOf("qa")).list(aditi.copy(board = null)) }
    }
}
