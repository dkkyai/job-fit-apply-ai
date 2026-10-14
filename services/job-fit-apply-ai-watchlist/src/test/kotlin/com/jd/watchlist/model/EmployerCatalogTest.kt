package com.jd.watchlist.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmployerCatalogTest {

    @Test
    fun `parses snake_case YAML and defaults enabled to true`() {
        val list = EmployerCatalog.parse(
            """
            - company: "Costco IT"
              ats: "rss"
              platform: "taleo_tbe"
              feed: "https://phf.tbe.taleo.net/phf02/ats/servlet/Rss?org=COSTCO&cws=41"
              detail_selector: ".x"
              qe_fit: "high"
            - company: "Walmart Global Tech"
              ats: "workday"
              enabled: false
            """.trimIndent()
        )
        assertEquals(2, list.size)
        assertEquals(".x", list[0].detailSelector)
        assertTrue(list[0].enabled)
        assertEquals("phf.tbe.taleo.net", list[0].boardKey)
        assertEquals(false, list[1].enabled)
    }

    @Test
    fun `boardKey prefers board, then workday tenant and site`() {
        assertEquals("acme", Employer(company = "Acme", ats = "greenhouse", board = "acme").boardKey)
        assertEquals("boeing/EXTERNAL_CAREERS", Employer(company = "Boeing", ats = "workday", tenant = "boeing", site = "EXTERNAL_CAREERS").boardKey)
        assertEquals("some-co", Employer(company = "Some Co", ats = "custom").boardKey)
    }

    @Test
    fun `bundled employers yaml loads and every enabled adapter entry has its parameters`() {
        val all = EmployerCatalog.load("")
        assertTrue(all.size >= 200, "the whole watchlist is in the file (${all.size})")
        val required = mapOf(
            "greenhouse" to { e: Employer -> e.board }, "lever" to { e: Employer -> e.board }, "ashby" to { e: Employer -> e.board },
            "workday" to { e: Employer -> e.host?.let { e.tenant }?.let { e.site } }, "rss" to { e: Employer -> e.feed },
        )
        val broken = all.filter { it.enabled && it.ats in required && required.getValue(it.ats)(it).isNullOrBlank() }
        assertEquals(emptyList(), broken.map { it.company })
        assertEquals(all.size, all.map { it.company }.toSet().size, "company names are unique")
    }
}
