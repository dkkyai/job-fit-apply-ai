package com.jd.pipeline.client

import com.jd.pipeline.utils.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * parseClaimTree branches on the work-item type: JD_SCRAPED → a JdRecord, EMAIL_RAW → a
 * ClaimedEmail (snake_case payload from the bridge). Missing type defaults to JD_SCRAPED.
 */
class BridgeClientClaimTest {

    private val mapper = Json.mapper

    @Test
    fun `JD_SCRAPED claim parses a JdRecord`() {
        val dto = parseClaimTree(mapper.readTree(
            """{"job_id":"j1","type":"JD_SCRAPED","jd_record":
               {"jd_text":"hello","company":"Acme","role_title":"SDET","location":null,"job_url":null,"source":"EMAIL"}}"""
        ))
        assertEquals("j1", dto.jobId)
        assertEquals(WorkItemType.JD_SCRAPED, dto.type)
        assertNotNull(dto.jdRecord)
        assertEquals("Acme", dto.jdRecord!!.company)
        assertNull(dto.email)
    }

    @Test
    fun `JD_SCRAPED claim from the watchlist parses with source CAREER_SITE`() {
        val dto = parseClaimTree(mapper.readTree(
            """{"job_id":"j2","type":"JD_SCRAPED","jd_record":
               {"jd_text":"hello","company":"Costco IT","role_title":"Quality Engineer","location":"Issaquah, WA",
                "job_url":"https://example.com/1","source":"CAREER_SITE","idempotency_key":"watchlist:rss:phf.tbe.taleo.net:10965"}}"""
        ))
        assertEquals(com.jd.pipeline.source.IngestionSource.CAREER_SITE, dto.jdRecord!!.source)
        assertEquals("watchlist:rss:phf.tbe.taleo.net:10965", dto.jdRecord!!.idempotencyKey)
    }

    @Test
    fun `EMAIL_RAW claim parses a ClaimedEmail (snake_case)`() {
        val dto = parseClaimTree(mapper.readTree(
            """{"job_id":"j2","type":"EMAIL_RAW","jd_record":
               {"message_id":"m1","subject":"Staff SDET","body":"hi","html_body":null,"from":"r@x.com","is_recruiter_hint":true}}"""
        ))
        assertEquals(WorkItemType.EMAIL_RAW, dto.type)
        assertNotNull(dto.email)
        assertEquals("m1", dto.email!!.messageId)
        assertEquals("r@x.com", dto.email!!.from)
        assertTrue(dto.email!!.isRecruiterHint)
        assertNull(dto.jdRecord)
    }

    @Test
    fun `missing type defaults to JD_SCRAPED`() {
        val dto = parseClaimTree(mapper.readTree(
            """{"job_id":"j3","jd_record":
               {"jd_text":"x","company":null,"role_title":null,"location":null,"job_url":null,"source":"JSEARCH"}}"""
        ))
        assertEquals(WorkItemType.JD_SCRAPED, dto.type)
        assertNotNull(dto.jdRecord)
    }
}
