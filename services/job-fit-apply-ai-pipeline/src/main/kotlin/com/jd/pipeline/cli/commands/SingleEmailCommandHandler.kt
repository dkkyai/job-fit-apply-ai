package com.jd.pipeline.cli.commands

import com.jd.pipeline.cli.Command
import com.jd.pipeline.cli.EmailLabelingServiceImpl
import com.jd.pipeline.client.BridgeClient
import com.jd.pipeline.client.gmail.GmailTransport
import com.jd.pipeline.pipeline.IngestionPipeline
import com.jd.pipeline.state.PipelineAction
import com.jd.pipeline.state.emailIntake
import com.jd.pipeline.state.isRecruiterEmail

object SingleEmailCommandHandler {
    fun run(cmd: Command.SingleEmail) {
        val emailValue = cmd.subject
        if (emailValue.isEmpty()) {
            println("[ERROR] --email: email subject is null or empty")
            return
        }
        println("[INFO] Fetching email with subject: $emailValue")

        try {
            val client = GmailTransport()
            val emailState = client.fetchEmailBySubject(emailValue, cmd.debug)

            if (emailState == null) {
                println("[ERROR] No email found matching subject: $emailValue")
                return
            }

            val ingestionPipeline = IngestionPipeline()
            val bridge            = BridgeClient()
            val labelingService   = EmailLabelingServiceImpl()
            val emailId           = emailState.emailIntake?.emailId ?: ""

            // 1. Ingestion: scan → scrape → save
            val ingState = try {
                ingestionPipeline.invoke(emailState)
            } catch (e: Exception) {
                System.err.println("[ingestion] ERROR: ${e.message}")
                labelingService.applyLabeling(emailState.copy(error = e.message ?: "ingestion error"), client)
                return
            }

            if (!ingState.isJobPosting) {
                println("  ↳ Not a job posting — skipped")
                labelingService.applyLabeling(ingState, client)
                return
            }

            // 2. Submit to bridge
            val record = ingestionPipeline.toJdRecord(ingState, idempotencyKey = emailId)
            val jobId = try {
                bridge.submit(record)
            } catch (e: Exception) {
                System.err.println("[submit] ERROR: ${e.message}")
                labelingService.applyLabeling(ingState.copy(error = e.message ?: "submit failed"), client)
                return
            }

            // 3. Apply JD_Processing label while worker runs
            runCatching { labelingService.applyProcessing(emailId, client) }
            println("  ↳ Submitted job $jobId — waiting for worker...")

            // 4. Poll until terminal
            val finalStatus = try {
                bridge.pollUntilTerminal(jobId)
            } catch (e: Exception) {
                System.err.println("[poll] Timeout/error for $jobId: ${e.message}")
                labelingService.applyLabeling(ingState.copy(error = e.message ?: "poll timeout"), client)
                return
            }

            println("  ↳ Job $jobId done — ${finalStatus.pipeline_action}, score=${finalStatus.fit_score}")

            // 5. Recruiter draft is created by the worker (CreateDraftReplyNode) for
            // every recruiter TAILOR job — see WorkerCommandHandler.tryCreateDraft.
            // The handler must NOT create its own draft (that produced a duplicate);
            // it only records that a draft exists so the email is labeled correctly.
            val workerCreatedDraft = finalStatus.status == "done" &&
                ingState.isRecruiterEmail &&
                finalStatus.pipeline_action == PipelineAction.TAILOR.name

            // 6. Apply terminal label
            val labelState = ingState.copy(
                error                       = finalStatus.error ?: "",
                isRecruiterResponseRequired = workerCreatedDraft,
            )
            val labelResult = labelingService.applyLabeling(labelState, client)

            when (labelResult.labelApplied) {
                "Recruiter_Response_Required" -> println("[INFO] Draft reply queued — labeled Recruiter_Response_Required, starred, kept unread.")
                "JD_Not_Found"               -> println("[INFO] JD_Not_Found — labeled, kept in inbox, marked unread.")
                else                         -> println("[INFO] Labeled and archived.")
            }

        } catch (e: Exception) {
            System.err.println("[ERROR] ${e.message}")
        }
    }
}
