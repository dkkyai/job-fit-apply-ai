package com.jd.poller.writeback

import com.jd.poller.bridge.CompletedJob
import com.jd.poller.bridge.PollerBridgeClient
import com.jd.poller.config.PollerConfig
import com.jd.poller.gmail.GmailClient
import com.jd.poller.gmail.LabelApplier
import com.jd.poller.health.Heartbeat

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
                job.terminalLabel?.takeIf { it.isNotBlank() }?.let { LabelApplier.apply(gmail, messageId, it) }
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
        val attachments = collectAttachments(job)
        gmail.createDraftReply(messageId, to, subject, job.draftText ?: "", attachments)
    }

    private fun collectAttachments(job: CompletedJob): List<String> {
        val a = job.artifacts ?: return emptyList()
        val files = mutableListOf<String>()
        bridge.downloadArtifact(a.resumePdf, ".pdf")?.let { files.add(it.absolutePath) }
        bridge.downloadArtifact(a.coverLetterTxt, ".txt")?.let { files.add(it.absolutePath) }
        return files
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
