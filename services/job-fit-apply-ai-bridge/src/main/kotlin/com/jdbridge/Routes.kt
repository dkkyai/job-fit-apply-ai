package com.jdbridge

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.UUID

private val log = LoggerFactory.getLogger("com.jdbridge.Routes")

fun Routing.configureRoutes(runner: PipelineRunner) {

    // ── Health ────────────────────────────────────────────────────────────────

    get("/health") {
        call.respond(mapOf("status" to "ok", "service" to "jd-bridge"))
    }

    // ── Submit job ────────────────────────────────────────────────────────────

    post("/api/jobs") {
        val body = call.receive<SubmitJobRequest>()

        if (body.jd_text.length < 150) {
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                ErrorResponse("jd_text must be at least 150 characters"),
            )
            return@post
        }

        val jobId = UUID.randomUUID().toString()
        createJob(jobId, body)

        application.launch {
            runner.run(jobId, body)
        }

        log.info("Job accepted: $jobId (${body.role_title} @ ${body.company})")
        call.respond(HttpStatusCode.Accepted, SubmitJobResponse(job_id = jobId))
    }

    // ── Job status ────────────────────────────────────────────────────────────

    get("/api/jobs/{job_id}") {
        val jobId = call.parameters["job_id"]!!
        val row = getJob(jobId)
            ?: run {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Job not found"))
                return@get
            }

        val artifacts = if (row.status == JobStatus.COMPLETE.value) row.artifacts else null

        call.respond(
            JobStatusResponse(
                job_id           = jobId,
                status           = row.status,
                title            = row.title,
                company          = row.company,
                progress_message = row.progressMessage,
                fit_score        = row.fitScore,
                artifacts        = artifacts,
                error            = row.error,
            )
        )
    }

    // ── Artifact downloads ────────────────────────────────────────────────────

    get("/api/jobs/{job_id}/resume.pdf") {
        val jobId = call.parameters["job_id"]!!
        val file = STORE_DIR.resolve("jobs/$jobId/resume.pdf").toFile()
        if (!file.exists()) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("resume.pdf not found for job $jobId"))
            return@get
        }
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"resume.pdf\""
        )
        call.respondFile(file)
    }

    get("/api/jobs/{job_id}/cover_letter.txt") {
        val jobId = call.parameters["job_id"]!!
        val file = STORE_DIR.resolve("jobs/$jobId/cover_letter.txt").toFile()
        if (!file.exists()) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("cover_letter.txt not found for job $jobId"))
            return@get
        }
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"cover_letter.txt\""
        )
        call.respondFile(file)
    }
}
