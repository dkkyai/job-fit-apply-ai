package com.jd.jsearch.model

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("JobListing parsing")
class JobListingParseTest {

    private val mapper = ObjectMapper()

    @Test
    @DisplayName("parses a JSearch data element (snake_case) into JobListing")
    fun parsesElement() {
        val json = """
            {"job_id":"abc123","job_title":"Staff SDET","employer_name":"Acme",
             "job_city":"Seattle","job_state":"WA","job_is_remote":false,
             "job_description":"Long description here","job_apply_link":"https://acme.co/apply",
             "job_publisher":"LinkedIn","unexpected_field":"ignored"}
        """.trimIndent()
        val l = mapper.readValue(json, JobListing::class.java)
        assertEquals("abc123", l.jobId)
        assertEquals("Staff SDET", l.jobTitle)
        assertEquals("Acme", l.employerName)
        assertEquals("Seattle", l.jobCity)
        assertEquals("WA", l.jobState)
        assertEquals("https://acme.co/apply", l.jobApplyLink)
        assertTrue(!l.jobIsRemote)
    }

    @Test
    @DisplayName("tolerates a minimal remote listing with missing optional fields")
    fun parsesMinimalRemote() {
        val l = mapper.readValue(
            """{"job_id":"r1","job_title":"QA","employer_name":"Beta","job_is_remote":true}""",
            JobListing::class.java,
        )
        assertTrue(l.jobIsRemote)
        assertNull(l.jobCity)
        assertNull(l.jobDescription)
    }

    @Test
    @DisplayName("defaults job_is_remote to false when the key is entirely absent")
    fun defaultsRemoteFlagWhenAbsent() {
        val l = mapper.readValue(
            """{"job_id":"n1","job_title":"QA","employer_name":"Gamma"}""",
            JobListing::class.java,
        )
        assertTrue(!l.jobIsRemote)
        assertNull(l.jobApplyLink)
        assertNull(l.jobPublisher)
    }

    @Test
    @DisplayName("parses the data[] array of a full JSearch response")
    fun parsesDataArray() {
        val resp = """{"status":"OK","data":[
            {"job_id":"a","job_title":"SDET","employer_name":"A"},
            {"job_id":"b","job_title":"QE","employer_name":"B","job_is_remote":true}]}"""
        val data = mapper.readTree(resp).path("data")
        val listings = data.map { mapper.treeToValue(it, JobListing::class.java) }
        assertEquals(listOf("a", "b"), listings.map { it.jobId })
    }
}
