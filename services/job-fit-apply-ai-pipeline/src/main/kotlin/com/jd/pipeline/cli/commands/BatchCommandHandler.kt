package com.jd.pipeline.cli.commands

import com.jd.pipeline.cli.BatchNotificationService
import com.jd.pipeline.cli.Command
import com.jd.pipeline.cli.EmailLabelingServiceImpl
import com.jd.pipeline.client.BridgeClient
import com.jd.pipeline.client.gmail.GmailTransport
import com.jd.pipeline.pipeline.IngestionPipeline
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.emailIntake
import com.jd.pipeline.state.isDigest
import com.jd.pipeline.state.isInlineDigest
import com.jd.pipeline.state.isRecruiterEmail
import com.jd.pipeline.utils.NodeTimer
import java.time.Instant

object BatchCommandHandler {
    fun run(
        cmd: Command.Batch,
        gmailTransport: GmailTransport = GmailTransport(),
        ingestionPipeline: IngestionPipeline = IngestionPipeline(),
        bridge: BridgeClient = BridgeClient(),
        labelingService: EmailLabelingServiceImpl = EmailLabelingServiceImpl(),
        notificationService: BatchNotificationService = BatchNotificationService(),
    ) {
        println("[INFO] Processing batch (max ${cmd.maxEmails} emails)...")
        val batchStartTime = Instant.now()
        NodeTimer.reset()
        notificationService.logConfigStatus()

        try {
            val client = gmailTransport
            val emails = client.fetchJdEmails(cmd.maxEmails, cmd.debug)

            if (emails.isEmpty()) {
                println("[WARN] No emails found matching query.")
                return
            }

            // ── Submit pass ───────────────────────────────────────────────────
            // email state → list of (jobId, isRecruiter, emailIntakeId, company, roleTitle)
            data class Submission(
                val jobId: String,
                val emailId: String,
                val company: String,
                val roleTitle: String,
            )
            // Pre-pipeline state (key) → post-pipeline ingState + submissions.
            // ingState carries the digest flags set by the pipeline; emailState does not.
            data class ProcessedEmail(val ingState: JDState, val submissions: List<Submission>)
            val emailSubmissions = mutableMapOf<JDState, ProcessedEmail>()

            for (emailState in emails) {
                val subject = emailState.emailIntake?.subject?.take(60) ?: ""
                println("\n[Ingesting] $subject")

                val ingState = try {
                    ingestionPipeline.invoke(emailState)
                } catch (e: Exception) {
                    System.err.println("[ingestion] ERROR for $subject: ${e.message}")
                    // Non-job or failed scan — label immediately without polling.
                    labelingService.applyLabeling(emailState.copy(error = e.message ?: "ingestion error"), client)
                    continue
                }

                val emailId = emailState.emailIntake?.emailId ?: continue
                val submissions = mutableListOf<Submission>()

                if (ingState.isDigest || ingState.isInlineDigest) {
                    // Each digest child is submitted as its own job.
                    for (childState in ingState.digestJobs.filter { it.isJobPosting }) {
                        try {
                            val record = ingestionPipeline.toJdRecord(childState)
                            val jobId  = bridge.submit(record)
                            submissions.add(Submission(jobId, emailId, record.company ?: "", record.roleTitle ?: ""))
                        } catch (e: Exception) {
                            System.err.println("[submit] Failed digest child for $emailId: ${e.message}")
                        }
                    }
                } else if (ingState.isJobPosting) {
                    try {
                        val record = ingestionPipeline.toJdRecord(ingState, idempotencyKey = emailId)
                        val jobId  = bridge.submit(record)
                        submissions.add(Submission(jobId, emailId, record.company ?: "", record.roleTitle ?: ""))
                    } catch (e: Exception) {
                        System.err.println("[submit] Failed submit for $emailId: ${e.message}")
                    }
                } else {
                    // Not a job posting — label immediately.
                    labelingService.applyLabeling(ingState, client)
                    continue
                }

                // Apply JD_Processing label immediately — jd-worker handles terminal state.
                if (submissions.isNotEmpty()) {
                    try {
                        labelingService.applyProcessing(emailId, client)
                    } catch (e: Exception) {
                        System.err.println("[label] applyProcessing failed for $emailId: ${e.message}")
                    }
                }

                emailSubmissions[emailState] = ProcessedEmail(ingState, submissions)
            }

            // ── Summary (fire-and-forget — no bridge polling) ─────────────────
            var jobs = 0

            for ((_, processed) in emailSubmissions) {
                val (ingState, submissions) = processed
                if (submissions.isEmpty()) {
                    labelingService.applyLabeling(ingState, client)
                    continue
                }
                jobs += submissions.size
                // A direct (non-digest) recruiter email that yielded a job posting must be kept
                // in the inbox for a personal response, not archived as JD_Processed. The worker
                // creates the draft reply asynchronously; labeling is the handler's responsibility.
                val isRecruiterResponse =
                    ingState.isRecruiterEmail && !ingState.isDigest && !ingState.isInlineDigest
                val labelResult = labelingService.applyLabeling(
                    ingState.copy(isJobPosting = true, isRecruiterResponseRequired = isRecruiterResponse),
                    client,
                )
                when (labelResult.labelApplied) {
                    "JD_Not_Found" -> println("  ↳ JD_Not_Found — labeled, kept in inbox, marked unread")
                    "Recruiter_Response_Required" ->
                        println("  ↳ Recruiter_Response_Required — labeled, starred, kept in inbox")
                }
            }

            println("\n[Batch done] $jobs job(s) submitted to bridge")

            notificationService.notify(
                BatchNotificationService.BatchSummary(
                    emailsProcessed = emails.size,
                    jobs            = jobs,
                    tailored        = 0,
                    skipped         = 0,
                    duplicate       = 0,
                    startTime       = batchStartTime,
                    scoredJobs      = emptyList(),
                )
            )

            if (ingestionPipeline.batchLinkedInSessionExpired()) {
                println("[WARN] LinkedIn session expired — re-authenticate Chrome profile to enable LinkedIn scraping")
            }
            val blocked = ingestionPipeline.batchBlockedDomains()
            if (blocked.isNotEmpty()) {
                println("[WARN] Sites that blocked scraping this batch: ${blocked.joinToString(", ")}")
            }

        } catch (e: Exception) {
            System.err.println("[ERROR] ${e.message}")
        }
    }

}
