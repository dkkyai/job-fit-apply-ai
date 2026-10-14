package com.jd.pipeline.state

enum class PipelineAction {
    SKIP,
    TAILOR,

    /**
     * A fit worth a look, but below AUTO_TAILOR_MIN_SCORE: scored, tracked and pinged with a Tailor
     * button instead of being tailored up front. Tapping it queues a TAILOR_REQUEST for the job.
     */
    OFFER;

    /** Serialized form used for Supabase + log output. Must match the legacy strings. */
    fun asDbValue(): String = name.lowercase()
}
