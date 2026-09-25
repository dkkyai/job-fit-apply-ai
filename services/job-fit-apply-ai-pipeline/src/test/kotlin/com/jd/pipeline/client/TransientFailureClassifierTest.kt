package com.jd.pipeline.client

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransientFailureClassifierTest {
    @Test
    fun `classifies 429 timeout and temporary upstream errors as retryable`() {
        assertTrue(TransientFailureClassifier.isRetryable(RuntimeException("LLM HTTP 429 from Ollama Cloud")))
        assertTrue(TransientFailureClassifier.isRetryable(RuntimeException("LLM call request timed out")))
        assertTrue(TransientFailureClassifier.isRetryable(RuntimeException("LLM HTTP 503 upstream unavailable")))
        assertTrue(TransientFailureClassifier.isRetryable(RuntimeException("node failed", TransientLlmFailure("hard timeout"))))
    }

    @Test
    fun `does not classify permanent provider failures as retryable`() {
        assertFalse(TransientFailureClassifier.isRetryable(RuntimeException("LLM HTTP 400 invalid request")))
        assertFalse(TransientFailureClassifier.isRetryable(RuntimeException("pipeline validation failed")))
    }
}