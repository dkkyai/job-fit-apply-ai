package com.jdbridge

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement

// ── Job status enum ───────────────────────────────────────────────────────────

object JobStatusSerializer : KSerializer<JobStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("JobStatus", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: JobStatus) =
        encoder.encodeString(value.value)

    override fun deserialize(decoder: Decoder): JobStatus =
        JobStatus.fromValue(decoder.decodeString())
}

@Serializable(with = JobStatusSerializer::class)
enum class JobStatus(val value: String) {
    PENDING("pending"),
    CLAIMED("claimed"),
    DONE("done"),
    ERROR("error");

    companion object {
        fun fromValue(value: String): JobStatus =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Unknown JobStatus: $value")
    }
}

// ── Error response ────────────────────────────────────────────────────────────

@Serializable
data class ErrorResponse(val detail: String)

// ── Work-item type ──────────────────────────────────────────────────────────────

/** Discriminates queued items: a raw email needing scan/scrape vs a pre-scraped JD. */
object WorkItemType {
    const val EMAIL_RAW  = "EMAIL_RAW"    // raw email from the Poller — Processor scans/scrapes it
    const val JD_SCRAPED = "JD_SCRAPED"   // pre-structured JdRecord (extension / JSearch / digest child)
}

// ── Inbound ───────────────────────────────────────────────────────────────────

@Serializable
data class SubmitJobRequest(
    val jd_text: String,
    val role_title: String?      = null,
    val company: String?         = null,
    val location: String?        = null,
    val job_url: String?         = null,
    val source: String?          = null,          // "EMAIL" | "JSEARCH" | "MANUAL"
    val idempotency_key: String? = null,
    val intake_meta: JsonElement? = null,          // opaque — stored verbatim
)

/** Raw email submitted by the Poller; the Processor does scan + scrape server-side. */
@Serializable
data class SubmitEmailRequest(
    val message_id: String,
    val body: String,
    val subject: String          = "",
    val html_body: String?       = null,
    val from: String             = "",
    val is_recruiter_hint: Boolean = false,
    val idempotency_key: String? = null,          // defaults to message_id
)

@Serializable
data class ResultRequest(
    val pipeline_action: String,
    val fit_score: Int,
    val strengths: List<String>   = emptyList(),
    val is_duplicate: Boolean     = false,
    val output_path: String?      = null,
    val has_cover_letter: Boolean = false,
    val error: String?            = null,
    // Gmail write-back payload (Poller acts on these via the completed feed):
    val terminal_label: String?   = null,          // label the Poller should apply to message_id
    val draft_text: String?       = null,          // LLM-generated recruiter reply (Processor makes it)
    val is_recruiter: Boolean     = false,
    val message_id: String?       = null,          // present only for EMAIL_RAW jobs
)

// ── Outbound ──────────────────────────────────────────────────────────────────

@Serializable
data class SubmitJobResponse(
    val job_id: String,
    val status: String,
    val deduped: Boolean = false,
)

@Serializable
data class ClaimResponse(
    val job_id: String,
    val type: String = WorkItemType.JD_SCRAPED,   // Processor branches on this
    val jd_record: JsonElement,                   // raw stored payload (JdRecord or email)
)

/** One item from GET /api/jobs/completed — everything the Poller needs for Gmail write-back. */
@Serializable
data class CompletedJob(
    val job_id: String,
    val completed_seq: Long,
    val status: String,
    val message_id: String?      = null,
    val terminal_label: String?  = null,
    val draft_text: String?      = null,
    val is_recruiter: Boolean    = false,
    val artifacts: ArtifactUrls? = null,
    val error: String?           = null,
)

@Serializable
data class ArtifactUrls(
    val resume_pdf: String,
    val cover_letter_txt: String,
)

@Serializable
data class JobStatusResponse(
    val job_id: String,
    val status: String,
    val title: String?            = null,
    val company: String?          = null,
    val fit_score: Int?           = null,
    val pipeline_action: String?  = null,
    val artifacts: ArtifactUrls?  = null,
    val error: String?            = null,
)

// ── Tracks (backlog UI — Postgres `tracks` table) ──────────────────────────────

/** A row from `tracks` as the backlog UI consumes it (snake_case matches the UI's Track type). */
@Serializable
data class TrackDto(
    val id: Int,
    val company: String,
    val role_title: String?       = null,
    val location: String?         = null,
    val remote_policy: String?    = null,
    val fit_score: Double?        = null,
    val job_url: String?          = null,
    val artifact_url: String?     = null,
    val tech_stack: List<String>? = null,
    val status: String,
    val created_at: String,
    val duplicate: Boolean,
)

@Serializable
data class TrackStatusUpdate(val status: String)

// ── Store helpers ─────────────────────────────────────────────────────────────

/** Partial update — only non-null fields are written to the DB. */
data class JobUpdate(
    val status: JobStatus?        = null,
    val fitScore: Int?            = null,
    val pipelineAction: String?   = null,
    val artifacts: ArtifactUrls?  = null,
    val error: String?            = null,
)

/** Row returned from the DB after deserialization. */
data class JobRow(
    val id: String,
    val status: String,
    val type: String,
    val jdJson: String?,
    val jobUrl: String?,
    val fitScore: Int?,
    val pipelineAction: String?,
    val artifacts: ArtifactUrls?,
    val error: String?,
    val claimedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    // Gmail write-back fields (set by recordResult)
    val terminalLabel: String?,
    val draftText: String?,
    val isRecruiter: Boolean,
    val messageId: String?,
    val writebackDone: Boolean,
    val completedSeq: Long?,
)

/** Returned by claimNext() — enough for the Processor to act on. */
data class ClaimedJob(val id: String, val type: String, val jdJson: String)
