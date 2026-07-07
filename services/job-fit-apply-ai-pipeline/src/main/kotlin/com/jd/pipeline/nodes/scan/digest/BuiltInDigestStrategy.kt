package com.jd.pipeline.nodes.scan.digest

import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * Built In (builtin.com) "New … Job Matches" daily digest.
 *
 * Each job is a card-style `<a>` whose visible body holds the company
 * (`font-size:16px`), role title (`font-weight:700`), a remote-policy span,
 * a location span and a salary span. The anchor href is an AWS SES tracking
 * redirect (`…awstrack.me/L0/<percent-encoded builtin url>/1/…`) that wraps the
 * real `builtin.com/job/<slug>/<id>` URL — we unwrap and de-track it so the
 * scrape stage fetches the canonical job page.
 */
object BuiltInDigestStrategy : BoardDigestStrategy {

    // Real job pages: builtin.com/job/<slug>/<numeric-id>. Excludes /jobs, /profile, /company, /newsletter.
    private val JOB_URL_PATTERN: Pattern = Pattern.compile("""https?://(?:www\.)?builtin\.com/job/[^/?#\s]+/\d+""", Pattern.CASE_INSENSITIVE)
    private val SALARY_PATTERN: Pattern = Pattern.compile("""\$[\d,]+(?:\s*[-–]\s*\$?[\d,]+)?""")

    override fun expand(parent: JDState, email: IntakeContext.Email): List<JDState> {
        val htmlJobs = parseFromHtml(parent, email.htmlBody)
        if (htmlJobs.isNotEmpty()) return htmlJobs
        return parseFromPlainText(parent, email.rawBody)
    }

    private fun parseFromHtml(input: JDState, emailHtml: String): List<JDState> {
        if (emailHtml.isBlank()) return emptyList()
        val doc = Jsoup.parse(emailHtml)
        val jobs = mutableListOf<JDState>()
        val seen = mutableSetOf<String>()

        for (anchor in doc.select("a[href]")) {
            if (jobs.size >= MAX_JOBS_PER_EMAIL) break
            val url = unwrapTrackingUrl(anchor.attr("href"))
            if (!isBuiltInJobUrl(url) || !seen.add(url)) continue

            val company = anchor.selectFirst("div[style*=font-size:16px]")?.text()?.trim().orEmpty()
            val roleTitle = anchor.selectFirst("div[style*=font-weight:700]")?.text()?.trim().orEmpty()
            if (roleTitle.isBlank()) continue

            val spans = anchor.select("span[style*=vertical-align:middle]").map { it.text().trim() }.filter { it.isNotBlank() }
            val remoteText = spans.getOrNull(0).orEmpty()
            val location = spans.getOrNull(1)?.takeUnless { it.contains('$') }.orEmpty()
            val salary = extractSalary(anchor.text())

            val job = createParsedDigestJob(input, company, roleTitle, location, salary, url)
            val remotePolicy = normalizeRemotePolicy(remoteText)
            jobs.add(if (remotePolicy.isNotBlank()) job.copy(remotePolicy = remotePolicy) else job)
        }
        return jobs
    }

    /** Fallback: pull bare builtin job URLs out of the raw body when no HTML cards are present. */
    private fun parseFromPlainText(input: JDState, emailBody: String): List<JDState> {
        if (emailBody.isBlank()) return emptyList()
        val jobs = mutableListOf<JDState>()
        val seen = mutableSetOf<String>()
        // Unwrap any awstrack-wrapped links first, then scan for canonical job URLs.
        val unwrapped = Pattern.compile("""https?://\S+""").matcher(emailBody).let { m ->
            buildString { while (m.find()) { append(unwrapTrackingUrl(m.group())); append('\n') } }
        }
        val matcher = JOB_URL_PATTERN.matcher(unwrapped)
        while (matcher.find() && jobs.size < MAX_JOBS_PER_EMAIL) {
            val url = matcher.group()
            if (!seen.add(url)) continue
            jobs.add(createParsedDigestJob(input, "", "", "", "", url))
        }
        return jobs
    }

    /**
     * Unwraps an AWS SES (`awstrack.me/L0/<encoded>/1/…`) redirect to the original URL and strips
     * tracking query params. Non-wrapped URLs are returned de-tracked. Public for unit testing.
     */
    fun unwrapTrackingUrl(href: String): String {
        val cleaned = cleanUrl(href)
        val marker = "/L0/"
        val raw = cleaned.indexOf(marker).let { i ->
            if (i < 0) cleaned
            else {
                val rest = cleaned.substring(i + marker.length)
                val end = rest.indexOf('/')               // boundary before the trailing "/1/<id>/<sig>"
                val encoded = if (end >= 0) rest.substring(0, end) else rest
                try { URLDecoder.decode(encoded, StandardCharsets.UTF_8) } catch (_: Exception) { encoded }
            }
        }
        return raw.substringBefore('?').substringBefore('#')
    }

    fun isBuiltInJobUrl(url: String): Boolean = JOB_URL_PATTERN.matcher(url).find()

    private fun extractSalary(text: String): String {
        val m = SALARY_PATTERN.matcher(text)
        return if (m.find()) m.group().replace('–', '-').trim() else ""
    }

    private fun normalizeRemotePolicy(text: String): String = when {
        text.isBlank() -> ""
        text.contains("hybrid", ignoreCase = true) -> "hybrid"
        text.contains("remote", ignoreCase = true) -> "remote"
        text.contains("in office", ignoreCase = true) || text.contains("in-office", ignoreCase = true) ||
            text.contains("on-site", ignoreCase = true) || text.contains("onsite", ignoreCase = true) -> "onsite"
        else -> ""
    }
}
