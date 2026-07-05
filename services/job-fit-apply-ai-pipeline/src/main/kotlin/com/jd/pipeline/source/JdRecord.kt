package com.jd.pipeline.source

enum class IngestionSource { EMAIL, JSEARCH, MANUAL }

data class JdRecord(
    val jdText: String,
    val company: String?,
    val roleTitle: String?,
    val location: String?,
    val jobUrl: String?,
    val source: IngestionSource,
    val idempotencyKey: String? = null,
    val intakeMeta: IntakeContext? = null,
)

data class ProcessingResult(
    val pipelineAction: String,
    val fitScore: Int,
    val strengths: List<String>,
    val isDuplicate: Boolean,
    val outputPath: String?,
    val hasCoverLetter: Boolean,
    val error: String? = null,
    val artifactUrl: String? = null,
    // Gmail write-back — the Poller acts on these via the bridge completed feed.
    // (serialized snake_case → terminal_label / draft_text / is_recruiter / message_id)
    val terminalLabel: String? = null,
    val draftText: String? = null,
    val isRecruiter: Boolean = false,
    val messageId: String? = null,
)
