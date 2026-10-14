package com.jd.watchlist.config

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ConfigTest {
    @Test
    fun `malformed numbers fall back to the default instead of crashing startup`() {
        assertEquals(86_400_000L, Config.parseLong("WATCHLIST_INTERVAL_MS", "abc", 86_400_000L))
        assertEquals(750L, Config.parseLong("WATCHLIST_INTER_EMPLOYER_DELAY_MS", "", 750L))
        assertEquals(3_600_000L, Config.parseLong("WATCHLIST_INTERVAL_MS", " 3600000 ", 86_400_000L))
    }
}
