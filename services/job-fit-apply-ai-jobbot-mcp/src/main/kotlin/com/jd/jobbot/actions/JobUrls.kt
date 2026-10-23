package com.jd.jobbot.actions

import java.net.URI

/**
 * A job link Richard pasted with his resume. The link is his own, so it is trusted to be a job posting, but
 * it still has to be somewhere the apply browser can safely go: a public http(s) site, never an address on
 * JobBot's own networks or the host.
 */
object JobUrls {
    sealed interface Check {
        data class Ok(val url: String, val host: String) : Check
        data class Rejected(val reason: String) : Check
    }

    private const val MAX_LENGTH = 2048
    private val PRIVATE_SUFFIXES = listOf(".local", ".localhost", ".internal", ".lan", ".localdomain", ".home.arpa", ".intranet")
    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    /** A real top-level domain starts with a letter ("com", "co", "xn--p1ai"). Every numeric or 0x-hex form of an IP address does not. */
    private val TLD = Regex("""^[a-z][a-z0-9-]*$""")

    fun check(raw: String?): Check {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return Check.Rejected("There's no job link.")
        if (text.length > MAX_LENGTH) return Check.Rejected("That link is too long.")
        val uri = runCatching { URI(text) }.getOrNull() ?: return Check.Rejected("That doesn't look like a link.")
        val scheme = uri.scheme?.lowercase() ?: return Check.Rejected("That doesn't look like a link.")
        if (scheme != "http" && scheme != "https") return Check.Rejected("Only http and https job links work.")
        if (uri.rawUserInfo != null) return Check.Rejected("Links with a username or password in them aren't allowed.")
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return Check.Rejected("That doesn't look like a link.")
        // IPv6 literals keep their brackets in URI.host; none of them is a public job site.
        if (host.startsWith("[") || IPV4.matches(host)) return Check.Rejected("Links to an IP address aren't allowed.")
        // 127.1, 0x7f.0.0.1 and 2130706433 all reach loopback through a resolver without being a dotted quad.
        if ('.' !in host || host == "localhost" || PRIVATE_SUFFIXES.any { host.endsWith(it) }) {
            return Check.Rejected("That link points to a private address, not a job site.")
        }
        if (!TLD.matches(host.substringAfterLast('.'))) return Check.Rejected("Links to an IP address aren't allowed.")
        // Drop the fragment: it never reaches the server and only makes two links to one posting differ. The path and
        // query stay exactly as given (raw): decoding and re-encoding them would turn %26 into a bare '&'.
        val clean = buildString {
            append(scheme).append("://").append(host)
            if (uri.port != -1) append(':').append(uri.port)
            append(uri.rawPath.orEmpty())
            uri.rawQuery?.let { append('?').append(it) }
        }
        return Check.Ok(clean, host)
    }
}
