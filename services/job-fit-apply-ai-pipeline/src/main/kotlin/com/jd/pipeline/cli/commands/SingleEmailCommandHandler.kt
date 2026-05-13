package com.jd.pipeline.cli.commands

import com.jd.pipeline.cli.CliOutput
import com.jd.pipeline.cli.Command
import com.jd.pipeline.cli.EmailLabelingServiceImpl
import com.jd.pipeline.client.gmail.GmailTransport
import com.jd.pipeline.pipeline.JDPipeline

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

            val pipeline = JDPipeline()
            val result = pipeline.invoke(emailState)

            CliOutput.printResult(result)

            // Label and handle post-processing
            val labelingService = EmailLabelingServiceImpl()
            val labelingResult = labelingService.applyLabeling(result, client)

            when (labelingResult.labelApplied) {
                "Recruiter_Response_Required" -> println("[INFO] Draft reply queued — labeled Recruiter_Response_Required, starred, kept unread.")
                "JD_Not_Found" -> println("[INFO] JD_Not_Found — labeled, kept in inbox, marked unread.")
                else -> println("[INFO] Labeled and archived.")
            }

        } catch (e: Exception) {
            System.err.println("[ERROR] ${e.message}")
        }
    }
}
