package com.jd.pipeline.cli.commands

import com.jd.pipeline.cli.BatchNotificationService
import com.jd.pipeline.cli.ScoredJob
import com.jd.pipeline.client.BridgeClient
import com.jd.pipeline.client.gmail.GmailAuth
import com.jd.pipeline.nodes.CreateDraftReplyNode
import com.jd.pipeline.pipeline.ProcessingPipeline
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.source.JdRecord
import com.jd.pipeline.source.ProcessingResult
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.PipelineAction

object WorkerCommandHandler {

    fun run(
        bridge: BridgeClient = BridgeClient(),
        pipeline: ProcessingPipeline = ProcessingPipeline(),
        notificationService: BatchNotificationService = BatchNotificationService(),
        // Non-interactive Gmail token check. The worker must NEVER trigger interactive OAuth
        // (it has no stdin) — a missing/expired token would otherwise hang the whole loop.
        gmailTokenValid: () -> Boolean = { GmailAuth.checkTokenStatus().status == GmailAuth.TokenStatus.VALID },
    ) {
        println("[worker] Starting — polling ${System.getenv("JD_BRIDGE_URL") ?: "http://127.0.0.1:8765"}")
        notificationService.logConfigStatus()

        while (true) {
            val claimed = try {
                bridge.claim()
            } catch (e: Exception) {
                System.err.println("[worker] claim() failed: ${e.message} — retrying in 5s")
                Thread.sleep(5_000)
                continue
            }

            if (claimed == null) {
                Thread.sleep(2_000)
                continue
            }

            println("[worker] Processing job ${claimed.jobId} — ${claimed.jdRecord.roleTitle} @ ${claimed.jdRecord.company}")

            val jobStartedAt = System.currentTimeMillis()
            val result: ProcessingResult = try {
                pipeline.invoke(claimed.jdRecord)
            } catch (e: Exception) {
                System.err.println("[worker] pipeline threw for ${claimed.jobId}: ${e.message}")
                ProcessingResult(
                    pipelineAction = PipelineAction.SKIP.name,
                    fitScore       = 0,
                    strengths      = emptyList(),
                    isDuplicate    = false,
                    outputPath     = null,
                    hasCoverLetter = false,
                    error          = e.message,
                )
            }

            try {
                val files = buildList {
                    result.outputPath?.let { dir ->
                        java.io.File(dir).listFiles { f -> f.extension == "pdf" }
                            ?.firstOrNull()
                            ?.let { add(it) }
                        val cl = java.io.File(dir, "cover_letter.txt")
                        if (cl.exists()) add(cl)
                    }
                }
                if (files.isNotEmpty()) bridge.uploadArtifacts(claimed.jobId, files)
                bridge.postResult(claimed.jobId, result)
                println("[worker] Job ${claimed.jobId} complete — ${result.pipelineAction}, score=${result.fitScore}")
            } catch (e: Exception) {
                System.err.println("[worker] Failed to post result for ${claimed.jobId}: ${e.message}")
                runCatching {
                    bridge.postResult(claimed.jobId, result.copy(error = "Failed to post result: ${e.message}"))
                }
            }

            // Durable structured record for the run analyzer (see tuner/run-analyzer).
            com.jd.pipeline.utils.RunReport.record(
                claimed.jobId, claimed.jdRecord, result, System.currentTimeMillis() - jobStartedAt,
            )

            notificationService.notifyJobResult(ScoredJob(
                company        = claimed.jdRecord.company ?: "",
                roleTitle      = claimed.jdRecord.roleTitle ?: "",
                fitScore       = result.fitScore,
                pipelineAction = result.pipelineAction,
                error          = result.error,
                artifactUrl    = result.artifactUrl?.takeIf { it.isNotBlank() },
                jobUrl         = claimed.jdRecord.jobUrl?.takeIf { it.isNotBlank() },
            ))

            if (result.pipelineAction == PipelineAction.TAILOR.name && result.error == null) {
                val intake = claimed.jdRecord.intakeMeta
                if (intake is IntakeContext.Email && intake.isRecruiter) {
                    tryCreateDraft(claimed.jdRecord, result, intake, gmailTokenValid)
                }
            }
        }
    }

    private fun tryCreateDraft(
        record: JdRecord,
        result: ProcessingResult,
        intake: IntakeContext.Email,
        gmailTokenValid: () -> Boolean,
    ) {
        // Guard against the worker blocking on interactive OAuth: CreateDraftReplyNode →
        // GmailTransport.getCredentials() launches a blocking browser/stdin flow when the token
        // is missing/expired. Skip (keeping the Recruiter_Response_Required label) instead of hanging.
        if (!gmailTokenValid()) {
            System.err.println(
                "[worker] draft_reply SKIPPED for ${record.company} — Gmail token not valid; run --reauth. " +
                "Email keeps its Recruiter_Response_Required label for manual follow-up."
            )
            return
        }
        try {
            val outputDir = result.outputPath ?: return
            val pdfFile = java.io.File(outputDir).listFiles { f -> f.extension == "pdf" }?.firstOrNull()
            val state = JDState(
                intake           = intake,
                isJobPosting     = true,
                company          = record.company ?: "",
                roleTitle        = record.roleTitle ?: "",
                location         = record.location ?: "",
                fitScore         = result.fitScore.toFloat(),
                strengths        = result.strengths,
                outputPath       = outputDir,
                resumeHtmlPdf    = pdfFile?.absolutePath ?: "",
                candidateProfile = JDState.loadCandidateProfile(),
            )
            CreateDraftReplyNode().process(state)
        } catch (e: Exception) {
            System.err.println("[worker] draft_reply failed: ${e.message}")
        }
    }
}
