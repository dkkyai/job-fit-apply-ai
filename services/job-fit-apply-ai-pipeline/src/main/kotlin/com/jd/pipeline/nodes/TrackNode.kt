package com.jd.pipeline.nodes

import com.jd.pipeline.client.PostgresGateway
import com.jd.pipeline.client.TracksGateway
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.PipelineAction
import com.jd.pipeline.state.emailIntake

/**
 * Node: track
 *
 * Inserts the current job into the Postgres `tracks` table and stores the generated
 * row id in trackId for downstream reference.
 */
class TrackNode(
    private val gateway: TracksGateway = PostgresGateway
) : Node<JDState> {

    override fun process(input: JDState): JDState {
        println("[track] Tracking: ${input.roleTitle} @ ${input.company}")

        if (!gateway.isConfigured()) {
            return input.copy(error = "DATABASE_URL not configured")
        }

        return try {
            val record = buildRecord(input)
            val row = gateway.insert("tracks", record)
            val id = row.path("id").asInt(0).takeIf { it > 0 }

            println("[track] Tracked successfully (id=${id ?: "unknown"})")

            input.copy(
                isTracked = true,
                trackId = id
            )
        } catch (e: Exception) {
            System.err.println("[track] ERROR: ${e.message}")
            input.copy(error = "track: ${e.message}")
        }
    }

    private fun buildRecord(input: JDState): Map<String, Any?> = mapOf(
        "email_id"        to (input.emailIntake?.emailId ?: ""),
        "email_subject"   to (input.emailIntake?.subject ?: ""),
        "company"         to input.company,
        "role_title"      to input.roleTitle,
        "location"        to input.location,
        "job_url"         to input.jobUrl,
        "remote_policy"   to input.remotePolicy,
        "fit_score"       to input.fitScore,
        "pipeline_action" to input.pipelineAction.asDbValue(),
        "tech_stack"      to input.techStack,
        "strengths"       to input.strengths,
        "gaps"            to input.gaps,
        "red_flags"       to input.redFlags,
        "fit_reasoning"   to input.fitReasoning,
        "jd_text"         to input.jdText,
        "output_path"     to input.outputPath,
        "artifact_url"    to input.artifactUrl
    )
}
