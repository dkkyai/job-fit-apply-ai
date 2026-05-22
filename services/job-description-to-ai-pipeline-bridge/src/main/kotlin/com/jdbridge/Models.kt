package com.jdbridge

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

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
    QUEUED("queued"),
    SCORING("scoring"),
    TAILORING("tailoring"),
    WRITING_CL("writing_cover_letter"),
    CONVERTING("converting_pdf"),
    COMPLETE("complete"),
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

// ── Inbound ───────────────────────────────────────────────────────────────────

@Serializable
data class SubmitJobRequest(
    val jd_text: String,
    val role_title: String? = null,
    val company: String? = null,
    val location: String? = null,
    val job_url: String? = null,
    val site: String? = null,
)

// ── Outbound ──────────────────────────────────────────────────────────────────

@Serializable
data class SubmitJobResponse(val job_id: String)

@Serializable
data class ArtifactUrls(
    val resume_pdf: String,
    val cover_letter_txt: String,
)

@Serializable
data class JobStatusResponse(
    val job_id: String,
    val status: String,
    val title: String? = null,
    val company: String? = null,
    val progress_message: String? = null,
    val fit_score: Int? = null,
    val artifacts: ArtifactUrls? = null,
    val error: String? = null,
)

// ── Store helpers ─────────────────────────────────────────────────────────────

/** Partial update — only non-null fields are written to the DB. */
data class JobUpdate(
    val status: JobStatus? = null,
    val progressMessage: String? = null,
    val fitScore: Int? = null,
    val artifacts: ArtifactUrls? = null,
    val error: String? = null,
)

/** Row returned from the DB after deserialization. */
data class JobRow(
    val id: String,
    val status: String,
    val title: String?,
    val company: String?,
    val jdJson: String?,
    val progressMessage: String?,
    val fitScore: Int?,
    val artifacts: ArtifactUrls?,
    val error: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
