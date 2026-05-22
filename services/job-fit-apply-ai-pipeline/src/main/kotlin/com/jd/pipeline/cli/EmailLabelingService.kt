package com.jd.pipeline.cli

import com.jd.pipeline.client.gmail.GmailTransport
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.emailIntake
import com.jd.pipeline.state.isDigest
import com.jd.pipeline.state.isInlineDigest

/**
 * Service interface for applying post-pipeline labeling decisions to emails.
 */
interface EmailLabelingService {
    /**
     * Apply post-pipeline labeling decisions to an email.
     * Returns a summary of actions taken.
     */
    fun applyLabeling(state: JDState, client: GmailTransport): LabelingResult
}

/**
 * Result of applying labeling actions to an email.
 */
data class LabelingResult(
    val labelApplied: String,
    val wasArchived: Boolean,
    val wasStarred: Boolean,
    val wasMarkedUnread: Boolean,
    val wasKeptInInbox: Boolean
)

/**
 * Default implementation of EmailLabelingService.
 * Extracts the labeling logic from Main.kt runEmail() and runBatch() methods.
 */
class EmailLabelingServiceImpl : EmailLabelingService {
    override fun applyLabeling(state: JDState, client: GmailTransport): LabelingResult {
        val emailId = state.emailIntake?.emailId ?: ""
        return when {
            state.error.isNotEmpty() -> {
                val labelId = client.getOrCreateLabel("JD_Error")
                client.labelEmail(emailId, labelId)
                client.markUnread(emailId)
                LabelingResult(
                    labelApplied = "JD_Error",
                    wasArchived = false,
                    wasStarred = false,
                    wasMarkedUnread = true,
                    wasKeptInInbox = true
                )
            }
            state.isRecruiterResponseRequired -> {
                val labelId = client.getOrCreateLabel("Recruiter_Response_Required")
                client.labelEmail(emailId, labelId)
                client.starEmail(emailId)
                client.markUnread(emailId)
                LabelingResult(
                    labelApplied = "Recruiter_Response_Required",
                    wasArchived = false,
                    wasStarred = true,
                    wasMarkedUnread = true,
                    wasKeptInInbox = true
                )
            }
            !state.isJobPosting -> {
                val labelId = client.getOrCreateLabel("JD_Not_Found")
                client.labelEmail(emailId, labelId)
                client.markUnread(emailId)
                LabelingResult(
                    labelApplied = "JD_Not_Found",
                    wasArchived = false,
                    wasStarred = false,
                    wasMarkedUnread = true,
                    wasKeptInInbox = true
                )
            }
            state.isDigest || state.isInlineDigest -> {
                val labelId = client.getOrCreateLabel("JD_Processed_Digest")
                client.labelEmail(emailId, labelId)
                client.archiveEmail(emailId)
                LabelingResult(
                    labelApplied = "JD_Processed_Digest",
                    wasArchived = true,
                    wasStarred = false,
                    wasMarkedUnread = false,
                    wasKeptInInbox = false
                )
            }
            else -> {
                val labelId = client.getOrCreateLabel("JD_Processed")
                client.labelEmail(emailId, labelId)
                client.archiveEmail(emailId)
                LabelingResult(
                    labelApplied = "JD_Processed",
                    wasArchived = true,
                    wasStarred = false,
                    wasMarkedUnread = false,
                    wasKeptInInbox = false
                )
            }
        }
    }
}
