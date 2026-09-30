package com.jd.notifier.notify

/**
 * Inline keyboard buttons for a high-fit Telegram notification.
 *
 * Two button kinds ride in one `reply_markup`:
 *  - **URL buttons** (View Report / View Resume) open the artifact over the tailnet.
 *  - **A callback button** (Apply) is delivered back as a plain message whose text *is* the
 *    label, which is exactly how the JobBot agent resolves actions: it looks the label up
 *    verbatim in its pending-actions state (see the repo's REQUIREMENTS.md button protocol).
 *
 * Telegram caps `callback_data` at 64 bytes. Nanobot truncates an over-long label at the
 * platform boundary, and a truncated label never matches its own registration — so the label
 * is fitted *here*, before sending, and the same fitted string is what gets registered.
 */
object TelegramButtons {

    /** Telegram's hard cap on `callback_data`, in bytes. */
    const val CALLBACK_DATA_LIMIT = 64

    const val APPLY_PREFIX = "Apply: "
    const val REPORT_TEXT = "View Report"
    const val RESUME_TEXT = "View Resume"
    const val APPLY_TEXT = "Apply"

    /** One inline button: exactly one of [url] (opens) or [callbackData] (reports a tap). */
    data class Button(
        val text: String,
        val url: String? = null,
        val callbackData: String? = null,
    )

    fun urlButton(text: String, url: String?): Button? =
        url?.takeIf { it.isNotBlank() }?.trim()
            // A button whose target is not an absolute http(s) URL is unreachable in a chat
            // client — Telegram rejects it and the sender silently loses a button. Dropping it
            // here keeps a bad base URL (or an unresolved bridge-relative path) from turning
            // into a broken button on the user's screen.
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?.let { Button(text = text, url = it) }

    /**
     * A row is only real if it has buttons. Building rows from absent links would otherwise
     * leave an empty inner list, and Telegram rejects `{"inline_keyboard":[[]]}`.
     */
    private fun rowOf(vararg buttons: Button?): List<Button>? =
        buttons.filterNotNull().takeIf { it.isNotEmpty() }

    /**
     * The Apply label. Must stay within [CALLBACK_DATA_LIMIT] UTF-8 *bytes* once the prefix is
     * added, because the agent matches this exact string.
     *
     * `dirName` is deliberately not part of the label: pipeline dirnames run to 254 chars, so
     * they cannot fit. The dirname travels in the registration record instead.
     */
    fun applyLabel(company: String?, title: String?, maxBytes: Int = CALLBACK_DATA_LIMIT): String {
        val who = (company ?: "").trim().ifBlank { "Unknown" }
        val what = (title ?: "").trim().ifBlank { "role" }
        return fit(APPLY_PREFIX + "$who / $what", maxBytes)
    }

    /**
     * The Apply label, made unique by [discriminator].
     *
     * The plain `company / title` form **collides** with the sweep's reply labels and, when two
     * postings share a company and title, with itself. The agent resolves a tap by exact label
     * match, so a collision routes one job's tap to another job's action. The discriminator (a
     * short digest of the job's immutable dirname) keeps the label unique while the readable
     * prefix stays intact for the human reading the chat.
     */
    fun applyLabelUnique(
        company: String?,
        title: String?,
        discriminator: String,
        maxBytes: Int = CALLBACK_DATA_LIMIT,
    ): String {
        val suffix = " #$discriminator"
        val budget = maxBytes - suffix.toByteArray(Charsets.UTF_8).size
        val head = applyLabel(company, title, budget.coerceAtLeast(0))
        return head + suffix
    }

    /** Short, stable discriminator for a job dirname. */
    fun discriminator(dirName: String): String =
        dirName.hashCode().toUInt().toString(16).padStart(8, '0').take(8)

    /** Truncate to [maxBytes] UTF-8 bytes without splitting a codepoint. */
    internal fun fit(s: String, maxBytes: Int): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return s
        var end = maxBytes
        // A UTF-8 continuation byte is 10xxxxxx; back off until we sit on a char boundary.
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8).trimEnd()
    }

    /**
     * Report + Resume + Apply, dropping any button whose target is unknown and the Apply button
     * when the event carries no pipeline action to apply to.
     *
     * Returns rows-of-buttons, matching Telegram's `inline_keyboard` shape.
     */
    fun forHighFit(
        reportUrl: String?,
        resumeUrl: String?,
        applyLabel: String?,
    ): List<List<Button>> {
        val rows = mutableListOf<List<Button>>()
        rowOf(urlButton(REPORT_TEXT, reportUrl), urlButton(RESUME_TEXT, resumeUrl))?.let { rows += it }
        rowOf(applyLabel?.takeIf { it.isNotBlank() }?.let { Button(APPLY_TEXT, callbackData = it) })
            ?.let { rows += it }
        return rows
    }

    /**
     * The Telegram `reply_markup` value for [rows], or null when there is nothing to render.
     * Serialised by hand to keep the wire shape obvious in tests.
     */
    fun replyMarkupJson(rows: List<List<Button>>): String? {
        if (rows.isEmpty()) return null
        val keyboard = rows.joinToString(",") { row ->
            row.joinToString(",", "[", "]") { b ->
                val fields = mutableListOf("\"text\":${json(b.text)}")
                b.url?.let { fields += "\"url\":${json(it)}" }
                b.callbackData?.let { fields += "\"callback_data\":${json(it)}" }
                fields.joinToString(",", "{", "}")
            }
        }
        return "{\"inline_keyboard\":[$keyboard]}"
    }

    private fun json(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }
}
