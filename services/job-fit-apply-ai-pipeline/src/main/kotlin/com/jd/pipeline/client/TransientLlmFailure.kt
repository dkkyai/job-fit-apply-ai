package com.jd.pipeline.client

/** Raised for an LLM/provider failure that is safe to retry from the durable bridge queue. */
class TransientLlmFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Keeps retry classification at the processor/LLM boundary rather than treating every pipeline
 * exception as transient.  Wrapped exceptions are inspected because node frameworks commonly add
 * context while preserving the underlying provider failure.
 */
object TransientFailureClassifier {
    fun isRetryable(error: Throwable): Boolean = generateSequence(error) { it.cause }
        .take(12)
        .any { cause ->
            cause is TransientLlmFailure || cause.message.orEmpty().let(::isRetryableMessage)
        }

    internal fun isRetryableMessage(message: String): Boolean {
        val normalized = message.lowercase()
        return Regex("\\bhttp\\s+(429|5\\d\\d)\\b").containsMatchIn(normalized) ||
            "request timed out" in normalized ||
            "timed out" in normalized ||
            "hard timeout" in normalized ||
            "timeout" in normalized && "llm" in normalized
    }
}