package com.jd.pipeline.nodes

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the CDP_FORCE_DOMAINS suffix matching that decides whether a host skips the HTTP fetch
 * and scrapes via the CDP browser directly. Subdomains must match; look-alikes must not.
 */
@DisplayName("ScrapeJdNode force-CDP domain matching")
class ScrapeJdForceCdpTest {

    private val node = ScrapeJdNode()
    private val domains = listOf("glassdoor.com", "welcometothejungle.com")

    @Test
    @DisplayName("matches the exact domain and its subdomains")
    fun matchesExactAndSubdomains() {
        assertTrue(node.matchesDomainSuffix("glassdoor.com", domains))
        assertTrue(node.matchesDomainSuffix("www.glassdoor.com", domains))
        assertTrue(node.matchesDomainSuffix("uk.glassdoor.com", domains))
        assertTrue(node.matchesDomainSuffix("welcometothejungle.com", domains))
    }

    @Test
    @DisplayName("does not match look-alikes, subdomain tricks, or unlisted hosts")
    fun doesNotMatchLookalikes() {
        assertFalse(node.matchesDomainSuffix("notglassdoor.com", domains))        // suffix without a dot boundary
        assertFalse(node.matchesDomainSuffix("glassdoor.com.evil.com", domains))  // domain as a subdomain of attacker
        assertFalse(node.matchesDomainSuffix("indeed.com", domains))              // unlisted
        assertFalse(node.matchesDomainSuffix("glassdoor.com", emptyList()))       // empty list matches nothing
    }
}
