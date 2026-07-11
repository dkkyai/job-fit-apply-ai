package com.jd.poller.model

/**
 * A raw email the Poller fetched from Gmail, to submit to the bridge as an EMAIL_RAW work item.
 * The Processor scans/scrapes it — the Poller does no LLM work.
 */
data class RawEmail(
    val messageId: String,
    val subject: String,
    val from: String,
    val body: String,
    val htmlBody: String,
    val isRecruiterHint: Boolean = false,
)
