package com.jd.pipeline.cli.commands

import com.jd.pipeline.client.BridgeClient
import com.jd.pipeline.client.ClaimDto
import com.jd.pipeline.client.WorkItemType
import com.jd.pipeline.config.Config
import com.jd.pipeline.utils.Heartbeat
import com.jd.pipeline.pipeline.EmailDisposition
import com.jd.pipeline.pipeline.EmailResolution
import com.jd.pipeline.pipeline.IngestionPipeline
import com.jd.pipeline.pipeline.ProcessingPipeline
import com.jd.pipeline.pipeline.TerminalLabel
import com.jd.pipeline.source.IngestionSource
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
        ingestion: IngestionPipeline = IngestionPipeline(),
        heartbeat: Heartbeat = Heartbeat.fromConfig(Config.HEARTBEAT_FILE),
    ) {
        // Messaging (Discord/Telegram) is now a separate Notifier service that consumes the bridge's
        // completed-event stream. The Processor just posts results.
        println("[processor] Starting — polling ${System.getenv("JD_BRIDGE_URL") ?: "http://127.0.0.1:8765"}")

        while (true) {
            // Liveness for the container healthcheck (`--health`). Beats only between jobs, so
            // the freshness window (HEALTH_MAX_AGE_MIN) must tolerate a long job.
            heartbeat.beat()

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
            // JD_PAGE_RAW (browser extension) is LLM-extracted from captured page text here;
            // JD_SCRAPED (JSearch / digest child) goes straight to processing.
            val jdRecord: JdRecord
            when (claimed.type) {
                WorkItemType.EMAIL_RAW -> {
                    val resolved = resolveEmail(claimed, bridge, ingestion)
                    if (resolved == null) continue   // terminal (digest/non-job/error) already completed
                    jdRecord = resolved
                }
                WorkItemType.JD_PAGE_RAW -> {
                    val resolved = resolvePageCapture(claimed, bridge, ingestion)
                    if (resolved == null) continue   // not a job / extraction failed — already completed
                    jdRecord = resolved
                }
                else -> {
                    val rec = claimed.jdRecord
                    if (rec == null) {
                        System.err.println("[processor] claim ${claimed.jobId} (${claimed.type}) has no jd_record — skipping")
                        continue
                    }
                    jdRecord = rec
                }
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
                    // Carry identity so the completed-event (JD_Error) still shows what failed.
                    company        = jdRecord.company,
                    roleTitle      = jdRecord.roleTitle,
                    jobUrl         = jdRecord.jobUrl,
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
            bridge.postResult(claimed.jobId, skipResult("EMAIL_RAW claim missing email payload", TerminalLabel.JD_ERROR))
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
            bridge.postResult(claimed.jobId, skipResult("ingestion: ${e.message}", TerminalLabel.JD_ERROR))
            return null
        }

        return when (val disposition = EmailResolution.classify(ingState)) {
            is EmailDisposition.Error -> {
                // Scan/scrape FAILED (e.g. a transient LLM 507) — not a verdict that this isn't a
                // job. Label JD_Error, never JD_Not_Found: mislabeling a real recruiter email as
                // "not found" silently drops it (the intake query excludes JD_Not_Found) and the
                // user has no signal it needs a retry.
                System.err.println("[processor] ingestion error for ${claimed.jobId}: ${disposition.message}")
                bridge.postResult(claimed.jobId, skipResult(disposition.message, TerminalLabel.JD_ERROR))
                null
            }
            is EmailDisposition.ReEnqueueChildren -> {
                for (child in disposition.children) {
                    runCatching { bridge.submit(ingestion.toJdRecord(child)) }
                        .onFailure { System.err.println("[processor] digest child submit failed: ${it.message}") }
                }
                bridge.postResult(claimed.jobId, skipResult(null, TerminalLabel.JD_PROCESSED_DIGEST))   // parent digest complete → archive
                null
            }
            EmailDisposition.SkipNotJob -> {
                bridge.postResult(claimed.jobId, skipResult(null, TerminalLabel.JD_NOT_FOUND))   // not a job posting
                null
            }
            EmailDisposition.Process ->
                ingestion.toJdRecord(ingState, idempotencyKey = email.messageId)
        }
    }

    /**
     * LLM-extract a JD from a claimed raw page capture into a [JdRecord] to process. The page was
     * rendered in the user's authenticated browser, so [ScrapeJdNode] skips fetching and extracts
     * straight from the captured text. Returns null when the page isn't a job posting or extraction
     * fails — in which case the bridge job has already been completed via postResult (a SKIP).
     */
    private fun resolvePageCapture(claimed: ClaimDto, bridge: BridgeClient, ingestion: IngestionPipeline): JdRecord? {
        val cap = claimed.pageCapture
        if (cap == null) {
            bridge.postResult(claimed.jobId, skipResult("JD_PAGE_RAW claim missing page payload"))
            return null
        }
        val state = JDState(
            intake       = IntakeContext.WebCapture(url = cap.url, title = cap.title),
            jobUrl       = cap.url,
            capturedText = cap.text,
        )
        val extracted = try {
            ingestion.scrapeNode.process(state)   // dual-mode: extracts from capturedText, no fetch
        } catch (e: Exception) {
            System.err.println("[processor] page extraction failed for ${claimed.jobId}: ${e.message}")
            bridge.postResult(claimed.jobId, skipResult("extraction: ${e.message}"))
            return null
        }

        // The scrape prompt has no explicit is-job flag, so gate on the load-bearing jd_text:
        // if the LLM couldn't extract a usable JD, treat the page as "not a job posting".
        if (extracted.error.isNotBlank() || extracted.jdText.length < 150) {
            bridge.postResult(
                claimed.jobId,
                skipResult("This page doesn't look like a job posting (no JD could be extracted)"),
            )
            return null
        }

        return JdRecord(
            jdText         = extracted.jdText,
            company        = extracted.company.ifBlank { null },
            roleTitle      = extracted.roleTitle.ifBlank { null },
            location       = extracted.location.ifBlank { null },
            jobUrl         = extracted.jobUrl.ifBlank { null },
            source         = IngestionSource.EXTENSION,
            idempotencyKey = cap.url,
            intakeMeta     = extracted.intake,
        )
    }

    /**
     * A terminal SKIP result for an item that never reaches [ProcessingPipeline]. [terminalLabel]
     * is the Gmail label the Poller must apply (mirrors [TerminalLabel.forState] for the states we
     * short-circuit past): without it the email carries no terminal label, so the Poller can't move
     * it out of the intake query and it loops — re-fetched, re-submitted (deduped to this already
     * written-back job) and re-labeled JD_Processing forever. The Poller reads message_id from the
     * bridge row, which preserves the enqueue-time value, so this result need not repeat it.
     */
    private fun skipResult(error: String?, terminalLabel: String? = null): ProcessingResult = ProcessingResult(
        pipelineAction = PipelineAction.SKIP.name,
        fitScore       = 0,
        strengths      = emptyList(),
        isDuplicate    = false,
        outputPath     = null,
        hasCoverLetter = false,
        error          = error,
        terminalLabel  = terminalLabel,
    )
}
