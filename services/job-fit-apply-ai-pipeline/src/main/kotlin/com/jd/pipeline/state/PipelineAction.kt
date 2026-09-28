package com.jd.pipeline.state

enum class PipelineAction {
    SKIP,
    TAILOR;

    /** Serialized form used for the tracks table + log output. Must match the legacy strings. */
    fun asDbValue(): String = name.lowercase()
}
