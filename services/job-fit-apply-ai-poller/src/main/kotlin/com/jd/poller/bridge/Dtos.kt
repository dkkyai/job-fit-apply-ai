package com.jd.poller.bridge

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Poller-local duplicates of the bridge DTOs (services intentionally do not share a module).
 * Fields are camelCase; [Json.mapper] maps them to/from the bridge's snake_case wire format.
 */

@JsonIgnoreProperties(ignoreUnknown = true)
data class SubmitEmailRequest(
    val messageId: String,
    val body: String,
    val subject: String = "",
    val htmlBody: String? = null,
    val from: String = "",
    val isRecruiterHint: Boolean = false,
    val idempotencyKey: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SubmitJobResponse(
    val jobId: String = "",
    val status: String = "",
    val deduped: Boolean = false,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ArtifactUrls(
    val resumePdf: String = "",
    val coverLetterTxt: String = "",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CompletedJob(
    val jobId: String,
    val completedSeq: Long = 0,
    val status: String = "",
    val messageId: String? = null,
    val terminalLabel: String? = null,
    val draftText: String? = null,
    val isRecruiter: Boolean = false,
    val artifacts: ArtifactUrls? = null,
    val error: String? = null,
)
