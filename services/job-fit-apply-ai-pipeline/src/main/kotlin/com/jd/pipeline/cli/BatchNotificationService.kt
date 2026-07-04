package com.jd.pipeline.cli

import com.jd.pipeline.client.NotificationClient
import com.jd.pipeline.config.Config
import com.jd.pipeline.utils.NodeTimer
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ScoredJob(
    val company: String,
    val roleTitle: String,
    val fitScore: Int?,
    val pipelineAction: String?,
    val error: String?,
    val artifactUrl: String? = null,
    val jobUrl: String? = null,
)

/**
 * Formats and dispatches post-batch notifications to Discord and Telegram.
 *
 * Discord receives three messages: batch summary, node timings, and the scored-jobs list.
 * Telegram receives a high-fit ping only when at least one job clears [fitThreshold].
 *
 * Silently skips when [NotificationClient.discordConfigured] and
 * [NotificationClient.telegramConfigured] are both false, or when no emails were processed.
 */
class BatchNotificationService(
    private val client: NotificationClient = NotificationClient(),
    private val fitThreshold: Int = Config.NOTIFICATION_FIT_THRESHOLD,
) {

    private val log = LoggerFactory.getLogger(BatchNotificationService::class.java)

    /**
     * Logs which notification channels are active. Call once at startup (worker / batch)
     * so a silent misconfiguration — blank tokens leaving both channels disabled —
     * is visible in the logs instead of failing quietly. Returns true if any channel
     * is configured.
     */
    fun logConfigStatus(): Boolean {
        val channels = buildList {
            if (client.discordConfigured) add("Discord")
            if (client.telegramConfigured) add("Telegram")
        }
        return if (channels.isEmpty()) {
            log.warn(
                "Notifications DISABLED — no Discord/Telegram credentials configured; nothing will be sent. " +
                "Set DISCORD_BOT_TOKEN/DISCORD_CHANNEL_ID and/or TELEGRAM_BOT_TOKEN/TELEGRAM_CHAT_ID in .env."
            )
            false
        } else {
            log.info("Notifications enabled: ${channels.joinToString(", ")} (high-fit threshold ≥$fitThreshold)")
            true
        }
    }

    data class BatchSummary(
        val emailsProcessed: Int,
        val jobs: Int,
        val tailored: Int,
        val skipped: Int,
        val duplicate: Int,
        val startTime: Instant,
        val scoredJobs: List<ScoredJob>,
    )

    fun notify(summary: BatchSummary) {
        if (!client.discordConfigured && !client.telegramConfigured) return
        if (summary.emailsProcessed == 0) return

        val dateStr = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm z")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())

        postBatchSummary(summary, dateStr)
        postNodeTimings()
        postScoredJobs(summary.scoredJobs)
        pingHighFit(summary.scoredJobs)
    }

    // ── Discord messages ──────────────────────────────────────────────────────

    private fun postBatchSummary(summary: BatchSummary, dateStr: String) {
        val elapsed  = Duration.between(summary.startTime, Instant.now())
        val mins     = elapsed.toMinutes()
        val secs     = elapsed.seconds % 60
        val runTime  = if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
        val threshold = Config.FIT_THRESHOLD.toInt()

        val rows = listOf(
            "Emails processed"        to summary.emailsProcessed.toString(),
            "Job postings"            to summary.jobs.toString(),
            "Tailored (≥ $threshold)" to summary.tailored.toString(),
            "Skipped"                 to summary.skipped.toString(),
            "Duplicate"               to summary.duplicate.toString(),
            "Run time"                to runTime,
        )
        val labelW = rows.maxOf { it.first.length }
        val table  = rows.joinToString("\n") { (k, v) -> "${k.padEnd(labelW)}  $v" }

        client.postDiscord("\n**JD Pipeline Complete** — $dateStr\n```\n$table\n```")
    }

    private fun postNodeTimings() {
        val entries = NodeTimer.summary()
        if (entries.isEmpty()) return

        fun fmt(sec: Double): String {
            val s = sec.toLong()
            return if (s < 60) "%.1fs".format(sec) else "${s / 60}m${"${s % 60}".padStart(2, '0')}s"
        }

        val nodeW  = maxOf("Node".length,  entries.maxOf { it.displayName.length })
        val modelW = maxOf("Model".length, entries.maxOf { it.model.length })
        val nW     = maxOf(1, entries.maxOf { it.count.toString().length })
        val tW     = maxOf("Avg".length, entries.maxOf { e ->
            maxOf(fmt(e.avgSec).length, fmt(e.minSec).length, fmt(e.maxSec).length)
        })

        val sep = "  "
        val header  = "${"Node".padEnd(nodeW)}$sep${"Model".padEnd(modelW)}$sep${"n".padStart(nW)}$sep${"Avg".padStart(tW)}$sep${"Min".padStart(tW)}$sep${"Max".padStart(tW)}"
        val divider = "${"─".repeat(nodeW)}$sep${"─".repeat(modelW)}$sep${"─".repeat(nW)}$sep${"─".repeat(tW)}$sep${"─".repeat(tW)}$sep${"─".repeat(tW)}"
        val rows    = entries.map { e ->
            "${e.displayName.padEnd(nodeW)}$sep${e.model.padEnd(modelW)}$sep${e.count.toString().padStart(nW)}$sep${fmt(e.avgSec).padStart(tW)}$sep${fmt(e.minSec).padStart(tW)}$sep${fmt(e.maxSec).padStart(tW)}"
        }

        client.postDiscord("**Node Timings (LLM calls this batch)**\n```\n$header\n$divider\n${rows.joinToString("\n")}\n```")
    }

    private fun postScoredJobs(jobs: List<ScoredJob>) {
        if (jobs.isEmpty()) return
        val lines = mutableListOf("**Scored Jobs**")
        for (job in jobs) {
            val score = job.fitScore?.toString() ?: "?"
            lines += "• ${discordJobLabel(job)} — **$score**"
        }
        client.postDiscord(lines.joinToString("\n"))
    }

    /**
     * Discord label for a job: `Company — [Title](artifactUrl)`, linking the title to its
     * report when an artifact URL is present (Discord renders Markdown links). Falls back to
     * a plain title when there is no URL, and to `*(no title)*` when the title is blank.
     */
    private fun discordJobLabel(job: ScoredJob): String {
        val title = job.roleTitle.ifBlank { "*(no title)*" }
        val url   = job.artifactUrl?.takeIf { it.isNotBlank() }
        val titlePart = if (url != null && job.roleTitle.isNotBlank()) "[$title]($url)" else title
        return "${job.company} — $titlePart"
    }

    // ── Per-job (worker) notification ─────────────────────────────────────────

    fun notifyJobResult(job: ScoredJob) {
        if (!client.discordConfigured && !client.telegramConfigured) return
        val score = job.fitScore?.toString() ?: "?"
        val title = job.roleTitle.ifBlank { "*(no title)*" }
        if (job.error != null) {
            client.postDiscord("• ${job.company} — $title — error: ${job.error}")
            return
        }
        val action = job.pipelineAction ?: "?"
        client.postDiscord("• ${discordJobLabel(job)} — **$score** ($action)")
        if ((job.fitScore ?: 0) >= fitThreshold) {
            client.postTelegramHtml("High-fit: ${telegramJobLabel(job)} — ${job.fitScore}")
        }
    }

    /**
     * Telegram (HTML) label for a job: `Company — <a href="…/report.md">Title</a>`, linking the
     * role title to its rendered report instead of dumping the full URL. Falls back to a plain,
     * HTML-escaped title when no artifact is available.
     */
    private fun telegramJobLabel(job: ScoredJob): String {
        val company = htmlEscape(job.company)
        val title   = htmlEscape(job.roleTitle.ifBlank { "(no title)" })
        val reportUrl = reportUrlOf(job)
        val jobUrl    = job.jobUrl?.takeIf { it.isNotBlank() }
        val companyPart = if (jobUrl != null) "<a href=\"${htmlEscape(jobUrl)}\">$company</a>" else company
        val titlePart   = if (reportUrl != null) "<a href=\"${htmlEscape(reportUrl)}\">$title</a>" else title
        return "$companyPart — $titlePart"
    }

    /** Report URL for a job: the artifact directory + `report.md` (matches MetadataUtils). */
    private fun reportUrlOf(job: ScoredJob): String? =
        job.artifactUrl?.takeIf { it.isNotBlank() }?.let { "${it.trimEnd('/')}/report.md" }

    private fun htmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    // ── Telegram ping ─────────────────────────────────────────────────────────

    private fun pingHighFit(jobs: List<ScoredJob>) {
        val highFit = jobs.filter { (it.fitScore ?: 0) >= fitThreshold && it.error == null }
        if (highFit.isEmpty()) return
        val lines = mutableListOf("High-fit jobs (≥$fitThreshold):")
        for (job in highFit) {
            lines += "• ${telegramJobLabel(job)} — ${job.fitScore}"
        }
        client.postTelegramHtml(lines.joinToString("\n"))
    }
}
