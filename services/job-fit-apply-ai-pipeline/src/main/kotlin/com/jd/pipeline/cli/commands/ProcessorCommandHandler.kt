package com.jd.pipeline.cli.commands

import com.jd.pipeline.cli.BatchNotificationService
import com.jd.pipeline.cli.ScoredJob
import com.jd.pipeline.client.BridgeClient
import com.jd.pipeline.client.ClaimDto
import com.jd.pipeline.client.WorkItemType
import com.jd.pipeline.pipeline.EmailDisposition
import com.jd.pipeline.pipeline.EmailResolution
import com.jd.pipeline.pipeline.IngestionPipeline
import com.jd.pipeline.pipeline.ProcessingPipeline
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.source.JdRecord
import com.jd.pipeline.source.ProcessingResult
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.PipelineAction

/**
 * The Processor loop (formerly `--worker`). Gmail-free: it claims work items from the bridge,
 * scans/scrapes raw emails, scores + tailors, and posts the result — including the write-back
 * fields ([ProcessingResult.terminalLabel], `draftText`, `messageId`) that the Poller applies
 * to Gmail. This service never touches Gmail or OAuth.
 */
object ProcessorCommandHandler {

    fun run(
        bridge: BridgeClient = BridgeClient(),
        pipeline: ProcessingPipeline = ProcessingPipeline(),
        notificationService: BatchNotificationService = BatchNotificationService(),
        ingestion: IngestionPipeline = IngestionPipeline(),
    ) {
        println("[processor] Starting — polling ${System.getenv("JD_BRIDGE_URL") ?: "http://127.0.0.1:8765"}")
        notificationService.logConfigStatus()

        while (true) {
            val claimed = try {
                bridge.claim()
            } catch (e: Exception) {
                System.err.println("[processor] claim() failed: ${e.message} — retrying in 5s")
                Thread.sleep(5_000)
                continue
            }

            if (claimed == null) {
                Thread.sleep(2_000)
                continue
            }

            // Work-item branch: EMAIL_RAW is scanned/scraped here (digest children re-enqueued);
            // JD_SCRAPED (extension / JSearch / digest child) goes straight to processing.
            val jdRecord: JdRecord
            if (claimed.type == WorkItemType.EMAIL_RAW) {
                val resolved = resolveEmail(claimed, bridge, ingestion)
                if (resolved == null) continue   // terminal (digest/non-job/error) already completed
                jdRecord = resolved
            } else {
                val rec = claimed.jdRecord
                if (rec == null) {
                    System.err.println("[processor] claim ${claimed.jobId} (${claimed.type}) has no jd_record — skipping")
                    continue
                }
                jdRecord = rec
            }

            println("[processor] Processing job ${claimed.jobId} — ${jdRecord.roleTitle} @ ${jdRecord.company}")

            val jobStartedAt = System.currentTimeMillis()
            val result: ProcessingResult = try {
                pipeline.invoke(jdRecord)
            } catch (e: Exception) {
                System.err.println("[processor] pipeline threw for ${claimed.jobId}: ${e.message}")
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
                println("[processor] Job ${claimed.jobId} complete — ${result.pipelineAction}, score=${result.fitScore}")
            } catch (e: Exception) {
                System.err.println("[processor] Failed to post result for ${claimed.jobId}: ${e.message}")
                runCatching {
                    bridge.postResult(claimed.jobId, result.copy(error = "Failed to post result: ${e.message}"))
                }
            }

            // Durable structured record for the run analyzer (see tuner/run-analyzer).
            com.jd.pipeline.utils.RunReport.record(
                claimed.jobId, jdRecord, result, System.currentTimeMillis() - jobStartedAt,
            )

            notificationService.notifyJobResult(ScoredJob(
                company        = jdRecord.company ?: "",
                roleTitle      = jdRecord.roleTitle ?: "",
                fitScore       = result.fitScore,
                pipelineAction = result.pipelineAction,
                error          = result.error,
                artifactUrl    = result.artifactUrl?.takeIf { it.isNotBlank() },
                jobUrl         = jdRecord.jobUrl?.takeIf { it.isNotBlank() },
            ))
        }
    }

    /**
     * Scan/scrape a claimed raw email into a [JdRecord] to process. Returns null when the item
     * is terminal here — digest (children re-enqueued as JD_SCRAPED), not-a-job, or an ingestion
     * error — in which case the bridge job has already been completed via postResult.
     */
    private fun resolveEmail(claimed: ClaimDto, bridge: BridgeClient, ingestion: IngestionPipeline): JdRecord? {
        val email = claimed.email
        if (email == null) {
            bridge.postResult(claimed.jobId, skipResult("EMAIL_RAW claim missing email payload"))
            return null
        }
        val emailState = JDState(
            intake = IntakeContext.Email(
                emailId        = email.messageId,
                subject        = email.subject,
                from           = email.from,
                rawBody        = email.body,
                htmlBody       = email.htmlBody ?: "",
                isRecruiter    = email.isRecruiterHint,
                isDigest       = false,
                isInlineDigest = false,
            ),
        )
        val ingState = try {
            ingestion.invoke(emailState)   // scan → digest fan-out → scrape
        } catch (e: Exception) {
            System.err.println("[processor] ingestion failed for ${claimed.jobId}: ${e.message}")
            bridge.postResult(claimed.jobId, skipResult("ingestion: ${e.message}"))
            return null
        }

        return when (val disposition = EmailResolution.classify(ingState)) {
            is EmailDisposition.ReEnqueueChildren -> {
                for (child in disposition.children) {
                    runCatching { bridge.submit(ingestion.toJdRecord(child)) }
                        .onFailure { System.err.println("[processor] digest child submit failed: ${it.message}") }
                }
                bridge.postResult(claimed.jobId, skipResult(null))   // parent digest complete
                null
            }
            EmailDisposition.SkipNotJob -> {
                bridge.postResult(claimed.jobId, skipResult(null))   // not a job posting
                null
            }
            EmailDisposition.Process ->
                ingestion.toJdRecord(ingState, idempotencyKey = email.messageId)
        }
    }

    private fun skipResult(error: String?): ProcessingResult = ProcessingResult(
        pipelineAction = PipelineAction.SKIP.name,
        fitScore       = 0,
        strengths      = emptyList(),
        isDuplicate    = false,
        outputPath     = null,
        hasCoverLetter = false,
        error          = error,
    )
}
