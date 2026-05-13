package com.jd.pipeline.cli.commands

import com.jd.pipeline.cli.CliOutput
import com.jd.pipeline.state.emailIntake
import com.jd.pipeline.cli.Command
import com.jd.pipeline.cli.EmailLabelingServiceImpl
import com.jd.pipeline.client.gmail.GmailTransport
import com.jd.pipeline.pipeline.JDPipeline
import com.jd.pipeline.state.PipelineAction
import com.jd.pipeline.state.isDigest
import com.jd.pipeline.state.isInlineDigest
import com.jd.pipeline.utils.NodeTimer
import java.time.Instant

object BatchCommandHandler {
    fun run(cmd: Command.Batch) {
        println("[INFO] Processing batch (max ${cmd.maxEmails} emails)...")

        NodeTimer.reset()
        val batchStartTime = Instant.now()

        try {
            val client = GmailTransport()
            val emails = client.fetchJdEmails(cmd.maxEmails, cmd.debug)

            if (emails.isEmpty()) {
                println("[WARN] No emails found matching query.")
                return
            }

            val pipeline = JDPipeline()
            var processed = 0
            var jobs = 0
            var tailored = 0
            var skipped = 0
            var duplicate = 0
            val scoredJobs = mutableListOf<com.jd.pipeline.state.JDState>()

            for (email in emails) {
                processed++
                println("\n[Processing $processed/${emails.size}] ${email.emailIntake?.subject?.take(60) ?: ""}")

                val result = pipeline.invoke(email)

                if (result.isJobPosting) {
                    if (result.isDigest || result.isInlineDigest) {
                        // Count each child job, not the parent email
                        jobs += result.digestJobs.size
                        // Collect scored child jobs and count tailored/skipped/duplicate
                        for (childJob in result.digestJobs) {
                            if (childJob.isJobPosting) {
                                scoredJobs.add(childJob)
                                when {
                                    childJob.isDuplicate -> duplicate++
                                    childJob.pipelineAction == PipelineAction.TAILOR -> tailored++
                                    else -> skipped++
                                }
                            }
                        }
                    } else {
                        jobs++
                        // Collect scored job and count tailored/skipped/duplicate
                        scoredJobs.add(result)
                        when {
                            result.isDuplicate -> duplicate++
                            result.pipelineAction == PipelineAction.TAILOR -> tailored++
                            else -> skipped++
                        }
                    }
                    CliOutput.printResult(result)
                } else {
                    println("  ↳ Not a job posting — skipped")
                }

                // Label and handle post-processing
                val labelingService = EmailLabelingServiceImpl()
                val labelingResult = labelingService.applyLabeling(result, client)

                when (labelingResult.labelApplied) {
                    "Recruiter_Response_Required" -> println("  ↳ Draft reply queued — labeled Recruiter_Response_Required, starred, kept unread")
                    "JD_Not_Found" -> println("  ↳ JD_Not_Found — labeled, kept in inbox, marked unread")
                }
            }

            CliOutput.printBatchSummary(processed, jobs, tailored, skipped, duplicate, batchStartTime, scoredJobs)
            CliOutput.printScrapeBatchWarnings(pipeline)

        } catch (e: Exception) {
            System.err.println("[ERROR] ${e.message}")
        }
    }
}
