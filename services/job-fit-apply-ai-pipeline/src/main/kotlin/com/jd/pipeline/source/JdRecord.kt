package com.jd.pipeline.source

import com.fasterxml.jackson.annotation.JsonIgnore

enum class IngestionSource { EMAIL, JSEARCH, MANUAL, EXTENSION }

data class JdRecord(
    val jdText: String,
    val company: String?,
    val roleTitle: String?,
    val location: String?,
    val jobUrl: String?,
    val source: IngestionSource,
    val idempotencyKey: String? = null,
    val intakeMeta: IntakeContext? = null,
    // Scrape-extracted fields carried across the ingestion → processing queue boundary.
    // Nullable so the bridge JSON round-trip stays backward compatible with older records.
    val salaryRange: String? = null,
    val remotePolicy: String? = null,
    val employmentType: String? = null,
    val seniorityLevel: String? = null,
    val yoeRequired: Int? = null,
    val techStack: List<String>? = null,
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
    // Processed-posting identity — persisted by the bridge for completed-feed consumers (Notifier).
    // (serialized snake_case → company / role_title / job_url / artifact_url)
    val company: String? = null,
    val roleTitle: String? = null,
    val jobUrl: String? = null,
    // Gmail write-back — the Poller acts on these via the bridge completed feed.
    // (serialized snake_case → terminal_label / draft_text / is_recruiter / message_id)
    val terminalLabel: String? = null,
    val draftText: String? = null,
    val isRecruiter: Boolean = false,
    val messageId: String? = null,
    // How the JD text was obtained ("http", "cdp_profile", "cdp_forced", "cdp_fallback",
    // "captured", "blocked", "empty"). Internal-only: carried to the run_log for the analyzer,
    // never posted to the bridge feed (@get:JsonIgnore keeps it out of the completed-event DTO).
    @get:JsonIgnore val scrapePath: String = "",
)
