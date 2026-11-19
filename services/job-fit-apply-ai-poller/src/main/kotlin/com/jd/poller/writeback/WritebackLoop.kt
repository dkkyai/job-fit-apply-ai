package com.jd.poller.writeback

import com.jd.poller.bridge.CompletedJob
import com.jd.poller.bridge.PollerBridgeClient
import com.jd.poller.config.PollerConfig
import com.jd.poller.gmail.GmailClient
import com.jd.poller.gmail.LabelApplier
import com.jd.poller.health.Heartbeat
import java.io.File

/**
 * Write-back loop: drain the bridge's completed feed and apply each job's outcome to Gmail —
 * the terminal label (+ side effects) and, for recruiter jobs, the tailored draft reply. Each
 * job is marked written-back only AFTER Gmail succeeds; a crash before that leaves it in the
 * feed for retry (accepting the rare duplicate draft). Non-email jobs (extension/JSearch, no
 * message_id) are simply marked done to drop them from the feed.
 */
class WritebackLoop(
    private val gmail: GmailClient,
    private val bridge: PollerBridgeClient,
    private val heartbeat: Heartbeat? = null,
) {
    /** One drain pass. Returns the number of jobs marked written-back. */
    fun drainOnce(): Int {
        val jobs = bridge.fetchCompleted()
        var done = 0
        for (job in jobs) {
            val messageId = job.messageId
            if (messageId.isNullOrBlank()) {
                bridge.markWritebackDone(job.jobId)   // nothing to write back to Gmail
                done++
                continue
            }
            try {
                // Always call apply() so clearProcessingLabel() runs even when terminalLabel is blank.
                LabelApplier.apply(gmail, messageId, job.terminalLabel ?: "")
                if (job.isRecruiter && !job.draftText.isNullOrBlank()) {
                    deliverDraft(job, messageId)
                }
                bridge.markWritebackDone(job.jobId)
                done++
                println("[writeback] ${job.jobId} → ${job.terminalLabel} (recruiter=${job.isRecruiter})")
            } catch (e: Exception) {
                // Leave writeback_done=false so this job is retried on the next pass.
                System.err.println("[writeback] failed for ${job.jobId}/$messageId: ${e.message}")
            }
        }
        return done
    }

    private fun deliverDraft(job: CompletedJob, messageId: String) {
        val meta = gmail.getMessageMeta(messageId)
        val to = extractEmailAddress(meta.from)
        val subject = buildReSubject(meta.subject)
        // The downloaded artifacts are temp files owned by this call: createDraftReply() reads them
        // into the MIME body before it returns, so delete them afterwards — success or failure — or
        // they pile up in /tmp for the life of the (days-long) poller JVM.
        val attachments = mutableListOf<File>()
        try {
            collectAttachments(job, attachments)
            gmail.createDraftReply(messageId, to, subject, job.draftText ?: "", attachments.map { it.absolutePath })
        } finally {
            attachments.forEach { f ->
                if (!f.delete() && f.exists()) System.err.println("[writeback] could not delete temp file ${f.absolutePath}")
            }
        }
    }

    /** Downloads into [into] as it goes, so a failed second download still leaves the first for cleanup. */
    private fun collectAttachments(job: CompletedJob, into: MutableList<File>) {
        val a = job.artifacts ?: return
        bridge.downloadArtifact(a.resumePdf, ".pdf")?.let { into.add(it) }
        bridge.downloadArtifact(a.coverLetterTxt, ".txt")?.let { into.add(it) }
    }

    private fun extractEmailAddress(from: String): String =
        Regex("""<([^>]+)>""").find(from)?.groupValues?.get(1) ?: from.trim()

    private fun buildReSubject(subject: String): String {
        val t = subject.trim()
        return if (t.startsWith("Re:", ignoreCase = true)) t else "Re: $t"
    }

    fun runForever(intervalMs: Long = PollerConfig.WRITEBACK_POLL_INTERVAL_MS) {
        while (!Thread.currentThread().isInterrupted) {
            runCatching { drainOnce() }.onFailure { System.err.println("[writeback] drain error: ${it.message}") }
            heartbeat?.beat(System.currentTimeMillis())   // liveness: the loop is cycling
            try {
                Thread.sleep(intervalMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
