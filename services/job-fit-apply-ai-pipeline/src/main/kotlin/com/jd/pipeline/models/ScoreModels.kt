package com.jd.pipeline.models

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * A scored strength or gap with a citation back to the JD text that supports it.
 * Populated by ScoreFitNode; `claim` is also exposed via JDState.strengths/gaps
 * as plain strings for backwards compatibility with downstream nodes.
 */
data class EvidenceItem(
    @JsonProperty("claim")
    val claim: String,

    @JsonProperty("jd_evidence")
    val jdEvidence: String = ""
)
