package com.jd.poller.config

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@DisplayName("PollerConfig")
class PollerConfigTest {
    @Test
    @DisplayName("rejects an intake batch size below one")
    fun rejectsInvalidBatchSize() {
        val error = assertFailsWith<IllegalArgumentException> {
            PollerConfig.positiveInt("INTAKE_BATCH_SIZE", "0")
        }

        assertEquals("INTAKE_BATCH_SIZE must be >= 1, got 0", error.message)
    }

    @Test
    @DisplayName("rejects a negative inter-submit delay")
    fun rejectsNegativeDelay() {
        val error = assertFailsWith<IllegalArgumentException> {
            PollerConfig.nonNegativeLong("INTAKE_INTER_SUBMIT_DELAY_MS", "-1")
        }

        assertEquals("INTAKE_INTER_SUBMIT_DELAY_MS must be >= 0, got -1", error.message)
    }
}