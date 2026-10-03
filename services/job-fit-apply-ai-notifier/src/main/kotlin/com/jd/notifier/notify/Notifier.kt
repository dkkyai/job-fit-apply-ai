package com.jd.notifier.notify

import com.jd.notifier.bridge.CompletedEvent
import com.jd.notifier.config.Config

/**
 * What one event's delivery achieved, per channel.
 *
 * [acked] is the loop's cursor gate. The policy is explicit because partial success is the
 * interesting case: an event is acked when nothing is left that a retry could fix. A PERMANENT
 * failure counts as acked — retrying a bad token forever would block every later event behind it,
 * turning one misconfiguration into a total outage.
 */
data class NotifyOutcome(
    val discord: DeliveryResult = DeliveryResult.SKIPPED,
    val telegram: DeliveryResult = DeliveryResult.SKIPPED,
) {
    val acked: Boolean
        get() = discord != DeliveryResult.RETRYABLE && telegram != DeliveryResult.RETRYABLE

    val sentAnything: Boolean
        get() = discord == DeliveryResult.DELIVERED || telegram == DeliveryResult.DELIVERED

    /** Channels already landed, so a retry of this event does not re-send them. */
    fun deliveredChannels(): Set<String> = buildSet {
        if (discord == DeliveryResult.DELIVERED) add("discord")
        if (telegram == DeliveryResult.DELIVERED) add("telegram")
    }

    /** Fold a retry's result into what previously landed. */
    fun merge(previous: NotifyOutcome): NotifyOutcome = NotifyOutcome(
        discord = if (previous.discord == DeliveryResult.DELIVERED) previous.discord else discord,
        telegram = if (previous.telegram == DeliveryResult.DELIVERED) previous.telegram else telegram,
    )
}

/**
 * Formats and sends the per-job messages for a completed event (moved from the pipeline's
 * BatchNotificationService.notifyJobResult): a Discord line per job, and a Telegram high-fit ping
 * when `fit_score >= fitThreshold`. Pure orchestration over [NotificationClient].
 */
class Notifier(
    private val client: NotificationClient = NotificationClient(),
    private val fitThreshold: Int = Config.FIT_THRESHOLD,
    private val buttonsEnabled: Boolean = Config.TELEGRAM_BUTTONS_ENABLED,
    private val linkButtonsEnabled: Boolean = Config.TELEGRAM_LINK_BUTTONS_ENABLED,
    private val actions: Set<TelegramButtons.Action> = TelegramButtons.parseActions(Config.TELEGRAM_ACTIONS),
    private val links: ArtifactLinks = ArtifactLinks(),
    private val template: AlertTemplate = AlertTemplate(Config.TELEGRAM_TEMPLATE_FILE.takeIf { it.isNotBlank() }?.let { java.nio.file.Path.of(it) }),
) {
    /**
     * Deliver [event]. [alreadyDelivered] names channels that landed on a previous attempt of this
     * same event; they are not re-sent, so a retry provoked by one channel failing does not
     * duplicate the channel that succeeded.
     *
     * A nothing-to-do event (no channels configured, or a non-job terminal like a digest parent)
     * comes back fully SKIPPED, which is [NotifyOutcome.acked] — the cursor must move past it.
     */
    fun notify(event: CompletedEvent, alreadyDelivered: Set<String> = emptySet()): NotifyOutcome {
        if (!client.discordConfigured && !client.telegramConfigured) return NotifyOutcome()
        // Non-job terminal events (digest parents, not-a-job) carry no company and no error — the
        // old Processor never notified these; skip them. Error events (company may be null) still send.
        if (event.company.isNullOrBlank() && event.error == null) return NotifyOutcome()

        fun discord(text: String) =
            if ("discord" in alreadyDelivered) DeliveryResult.DELIVERED else client.postDiscord(text)

        val company = event.company ?: ""
        val title = (event.roleTitle ?: "").ifBlank { "*(no title)*" }
        if (event.error != null) {
            return NotifyOutcome(discord = discord("• $company — $title — error: ${event.error}"))
        }
        val score = event.fitScore?.toString() ?: "?"
        val action = event.pipelineAction ?: "?"
        val discordResult = discord("• ${discordJobLabel(event)} — **$score** ($action)")
        val builtIn = "High-fit: ${telegramJobLabel(event)} — ${event.fitScore}" + detailsBlock(event) + skipLine(event) + jobRefLine(event)
        // The skip reason rides under any template too: a card without a resume must say why.
        val templated = templated(event)?.let { it + skipLine(event) }
        fun send(text: String) = if (buttonsEnabled) client.postTelegramHtmlWithButtons(text, buttonsFor(event)) else client.postTelegramHtml(text)
        val telegramResult = when {
            (event.fitScore ?: 0) < fitThreshold -> DeliveryResult.SKIPPED
            "telegram" in alreadyDelivered -> DeliveryResult.DELIVERED
            templated != null -> send(templated).let { r ->
                // Telegram refused the templated HTML: one re-send in the built-in format, so a bad
                // template can never cost the alert.
                if (r == DeliveryResult.PERMANENT) send(builtIn) else r
            }
            else -> send(builtIn)
        }
        return NotifyOutcome(discord = discordResult, telegram = telegramResult)
    }

    /**
     * View Report / View Resume plus the eligible action buttons for one event.
     *
     * Links and actions are independently gated: links are inert URLs, while an action hands
     * work to the JobBot agent. An action is sent only when it is enabled *and* [eligible] for
     * this event — the agent's plugin re-checks the same rules on every tap.
     */
    private fun buttonsFor(event: CompletedEvent): List<List<TelegramButtons.Button>> {
        val resolved = when {
            !linkButtonsEnabled -> ArtifactLinks.Links(null, null)
            // Skipped: the report is the scoring notes, and there is no resume to look up.
            event.skipped() -> ArtifactLinks.Links(reportUrlOf(event), null)
            else -> links.resolve(event.artifactUrl, event.artifacts?.resumePdf)
        }
        return TelegramButtons.forHighFit(
            reportUrl = resolved.reportUrl,
            resumeUrl = resolved.resumeUrl,
            actions = actionsFor(event),
            completedSeq = event.completedSeq,
        )
    }

    /** The ping from the editable template, or null for the built-in format (no template, or any problem). */
    private fun templated(e: CompletedEvent): String? = runCatching {
        if (e.completedSeq <= 0) return null
        template.render(values(e))
    }.getOrNull()

    /** Enabled actions this event qualifies for, in display order. */
    internal fun actionsFor(event: CompletedEvent): List<TelegramButtons.Action> =
        if (event.completedSeq <= 0) {
            emptyList()
        } else {
            TelegramButtons.Action.entries.filter { it in actions && eligible(it, event) }
        }

    private fun values(e: CompletedEvent) = AlertTemplate.Values(
        company = e.company ?: "",
        title = (e.roleTitle ?: "").ifBlank { "(no title)" },
        score = e.fitScore?.toString() ?: "?",
        action = e.pipelineAction ?: "",
        ref = "#J${e.completedSeq}",
        jobUrl = e.jobUrl,
        reportUrl = reportUrlOf(e),
        location = e.location,
        remotePolicy = e.remotePolicy,
        salary = e.salaryRange,
        source = e.source,
        strengths = e.strengths.orEmpty(),
        gaps = e.gaps.orEmpty(),
    )

    /**
     * Location · salary · source, the top strengths and the main gap, each on its own lines and
     * left out when unknown — the Muse card's facts as Telegram text.
     */
    private fun detailsBlock(e: CompletedEvent): String {
        val v = values(e)
        return buildString {
            AlertTemplate.details(v).takeIf { it.isNotEmpty() }?.let { append("\n").append(htmlEscape(it)) }
            AlertTemplate.strengths(v).takeIf { it.isNotEmpty() }?.let { append("\n<b>Why it fits</b>\n").append(htmlEscape(it)) }
            AlertTemplate.gap(v).takeIf { it.isNotEmpty() }?.let { append("\n<b>Gap:</b> ").append(htmlEscape(it)) }
        }
    }

    /** "Skipped: <reason>" on its own line for a scored job that was not tailored, else nothing. */
    private fun skipLine(e: CompletedEvent): String =
        if (!e.skipped()) "" else "\nSkipped: " + htmlEscape(e.skipReason?.trim()?.ifBlank { null } ?: "not tailored, so there's no resume")

    private fun reportUrlOf(e: CompletedEvent): String? =
        e.artifactUrl?.takeIf { it.isNotBlank() }?.let { "${it.trimEnd('/')}/report.md" }

    /**
     * `#J<completed_seq>` on its own line: the job reference the agent resolves when the user
     * replies to the ping, and a Telegram hashtag that gathers every message about the job.
     */
    private fun jobRefLine(e: CompletedEvent): String =
        if (e.completedSeq > 0) "\n#J${e.completedSeq}" else ""

    /** `Company — [Title](artifactUrl)` — the title links to its report when present. */
    private fun discordJobLabel(e: CompletedEvent): String {
        val role = e.roleTitle ?: ""
        val title = role.ifBlank { "*(no title)*" }
        val url = e.artifactUrl?.takeIf { it.isNotBlank() }
        val titlePart = if (url != null && role.isNotBlank()) "[$title]($url)" else title
        return "${e.company ?: ""} — $titlePart"
    }

    /** `<a href=jobUrl>Company</a> — <a href=report>Title</a>` (HTML for Telegram). */
    private fun telegramJobLabel(e: CompletedEvent): String {
        val company = htmlEscape(e.company ?: "")
        val title = htmlEscape((e.roleTitle ?: "").ifBlank { "(no title)" })
        val reportUrl = e.artifactUrl?.takeIf { it.isNotBlank() }?.let { "${it.trimEnd('/')}/report.md" }
        val jobUrl = e.jobUrl?.takeIf { it.isNotBlank() }
        val companyPart = if (jobUrl != null) "<a href=\"${htmlEscape(jobUrl)}\">$company</a>" else company
        val titlePart = if (reportUrl != null) "<a href=\"${htmlEscape(reportUrl)}\">$title</a>" else title
        return "$companyPart — $titlePart"
    }

    private fun htmlEscape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    companion object {
        const val TAILOR = "TAILOR"

        /**
         * Terminal labels whose email the poller leaves in the inbox (LabelApplier). Everything
         * else — JD_Processed, JD_Processed_Digest — is archived by the poller already, so an
         * Archive button there would do nothing.
         */
        val INBOX_TERMINAL_LABELS = setOf(
            "Recruiter_Response_Required",
            "JD_Not_Found",
            "JD_Application_Update",
            "JD_Error",
            "JD_Scrape_Failed",
        )

        /**
         * Whether [action] makes sense for [e]. Mirrored by the agent's `jobbot_actions` plugin;
         * both sides are pinned by `docker/jobbot/contract/button_eligibility.json`.
         */
        fun eligible(action: TelegramButtons.Action, e: CompletedEvent): Boolean = when (action) {
            // Apply uploads the tailored resume: a skipped (e.g. pay-gated) job has none.
            TelegramButtons.Action.APPLY -> !e.jobUrl.isNullOrBlank() && e.pipelineAction.equals(TAILOR, ignoreCase = true)
            TelegramButtons.Action.REPLY -> e.isRecruiter && !e.messageId.isNullOrBlank()
            TelegramButtons.Action.ARCHIVE ->
                !e.messageId.isNullOrBlank() && e.terminalLabel in INBOX_TERMINAL_LABELS
        }
    }
}
