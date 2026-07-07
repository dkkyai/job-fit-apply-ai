package com.jd.poller.gmail

/** The terminal Gmail label names the Processor emits (mirrors pipeline TerminalLabel). */
object TerminalLabels {
    const val JD_ERROR            = "JD_Error"
    const val RECRUITER           = "Recruiter_Response_Required"
    const val JD_PROCESSED_DIGEST = "JD_Processed_Digest"
    const val JD_NOT_FOUND        = "JD_Not_Found"
    const val JD_PROCESSED        = "JD_Processed"
    const val JD_PROCESSING       = "JD_Processing"   // in-flight, applied at intake, cleared here
}

/**
 * Applies a completed job's terminal label to its Gmail message, replicating the side effects of
 * the old EmailLabelingService (archive / star / mark-unread) keyed by the label name. The
 * in-flight JD_Processing label is always cleared. Pure orchestration over [GmailClient] — the
 * decision (which label) was already made by the Processor.
 */
object LabelApplier {

    fun apply(client: GmailClient, messageId: String, terminalLabel: String) {
        when (terminalLabel) {
            TerminalLabels.JD_ERROR -> {
                label(client, messageId, TerminalLabels.JD_ERROR)
                client.markUnread(messageId)
                // Note: JD_Error intentionally does NOT clear a prior JD_Error.
            }
            TerminalLabels.RECRUITER -> {
                label(client, messageId, TerminalLabels.RECRUITER)
                client.starEmail(messageId)
                client.markUnread(messageId)
                clearErrorLabel(client, messageId)
            }
            TerminalLabels.JD_PROCESSED_DIGEST -> {
                label(client, messageId, TerminalLabels.JD_PROCESSED_DIGEST)
                client.archiveEmail(messageId)
                clearErrorLabel(client, messageId)
            }
            TerminalLabels.JD_NOT_FOUND -> {
                label(client, messageId, TerminalLabels.JD_NOT_FOUND)
                client.markUnread(messageId)
                clearErrorLabel(client, messageId)
            }
            TerminalLabels.JD_PROCESSED -> {
                label(client, messageId, TerminalLabels.JD_PROCESSED)
                client.archiveEmail(messageId)
                clearErrorLabel(client, messageId)
            }
            else -> {
                // Unknown/blank label: apply as-is if non-blank, still clear in-flight state.
                if (terminalLabel.isNotBlank()) label(client, messageId, terminalLabel)
                clearErrorLabel(client, messageId)
            }
        }
        clearProcessingLabel(client, messageId)
    }

    private fun label(client: GmailClient, messageId: String, name: String) {
        client.labelEmail(messageId, client.getOrCreateLabel(name))
    }

    private fun clearErrorLabel(client: GmailClient, messageId: String) {
        client.findLabelId(TerminalLabels.JD_ERROR)?.let {
            client.applyLabels(messageId, addLabels = emptyList(), removeLabels = listOf(it))
        }
    }

    private fun clearProcessingLabel(client: GmailClient, messageId: String) {
        client.findLabelId(TerminalLabels.JD_PROCESSING)?.let {
            client.applyLabels(messageId, addLabels = emptyList(), removeLabels = listOf(it))
        }
    }
}
