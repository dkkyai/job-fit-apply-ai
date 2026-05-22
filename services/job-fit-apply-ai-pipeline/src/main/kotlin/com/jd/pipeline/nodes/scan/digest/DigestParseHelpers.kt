package com.jd.pipeline.nodes.scan.digest

import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.emailIntake
import org.jsoup.Jsoup

internal const val MAX_JOBS_PER_EMAIL = 25

internal fun JDState.withEmailFlags(
    isDigest: Boolean? = null,
    isInlineDigest: Boolean? = null,
    isRecruiter: Boolean? = null
): JDState {
    val email = this.emailIntake ?: return this
    return this.copy(
        intake = email.copy(
            isDigest = isDigest ?: email.isDigest,
            isInlineDigest = isInlineDigest ?: email.isInlineDigest,
            isRecruiter = isRecruiter ?: email.isRecruiter
        )
    )
}

internal fun createParsedDigestJob(input: JDState, company: String, roleTitle: String, location: String, salaryRange: String, jobUrl: String): JDState {
    val base = input.withEmailFlags(isDigest = true).copy(
        isJobPosting = true,
        company = company.trim(), roleTitle = roleTitle.trim(), location = location.trim(),
        salaryRange = salaryRange.trim(), jobUrl = jobUrl.trim(),
        remotePolicy = inferRemotePolicy(location, roleTitle, salaryRange)
    )
    val jdText = buildDigestSummaryText(base)
    return base.copy(jdText = jdText, scrapedContent = jdText)
}

internal fun buildDigestSummaryText(job: JDState): String = buildString {
    append(job.roleTitle).append(" @ ").append(job.company)
    if (job.location.isNotBlank()) append(" | ").append(job.location)
    if (job.salaryRange.isNotBlank()) append(" | ").append(job.salaryRange)
    if (job.jobUrl.isNotBlank()) append(" | ").append(job.jobUrl)
}

internal fun inferRemotePolicy(vararg values: String): String {
    val joined = values.joinToString(" ").lowercase()
    return when { "hybrid" in joined -> "hybrid"; "on-site" in joined || "onsite" in joined -> "onsite"; "remote" in joined -> "remote"; else -> "unknown" }
}

internal fun looksLikeLocation(text: String): Boolean =
    text.contains("Remote", ignoreCase = true) ||
    text.matches(Regex(""".+\,\s*[A-Z]{2}(?:\s*\([^)]+\))?""")) ||
    text.contains("within the US", ignoreCase = true) ||
    text.contains(" | ")

internal fun findUrlsByAnchorText(emailHtml: String): Map<String, String> {
    if (emailHtml.isBlank()) return emptyMap()
    val document = Jsoup.parse(emailHtml)
    val urls = linkedMapOf<String, String>()
    for (anchor in document.select("a[href]")) {
        val strongText = anchor.select("strong").text().replace(Regex("\\s+"), " ").trim()
        val text = if (strongText.isNotBlank()) strongText else anchor.text().replace(Regex("\\s+"), " ").trim()
        if (text.isBlank() || text.equals("VIEW JOB", ignoreCase = true) || text.equals("QUICK APPLY", ignoreCase = true)) continue
        urls.putIfAbsent(text, cleanUrl(anchor.attr("href")))
    }
    return urls
}

internal fun applyDigestSummary(result: JDState, digestJobs: List<JDState>): JDState {
    if (digestJobs.isEmpty()) return result
    val companies = digestJobs.map { it.company.trim() }.filter { it.isNotBlank() }.distinct()
    val locations = digestJobs.map { it.location.trim() }.filter { it.isNotBlank() }.distinct()
    val titles = digestJobs.map { it.roleTitle.trim() }.filter { it.isNotBlank() }
    val first = digestJobs.first()

    val company = when { companies.isEmpty() -> first.company; companies.size == 1 -> companies.first(); else -> "Multiple companies" }
    val roleTitle = when { titles.isEmpty() -> first.roleTitle; digestJobs.size == 1 -> titles.first(); else -> "Job digest (${digestJobs.size} jobs)" }
    val location = when { locations.isEmpty() -> first.location; locations.size == 1 -> locations.first(); else -> locations.take(3).joinToString(" | ") }

    val digestSummary = digestJobs.mapIndexed { i, job ->
        buildString {
            append(i + 1).append(". ")
            append(if (job.roleTitle.isNotBlank()) job.roleTitle else "Unknown role").append(" @ ")
            append(if (job.company.isNotBlank()) job.company else "Unknown company")
            if (job.location.isNotBlank()) append(" | ").append(job.location)
            if (job.salaryRange.isNotBlank()) append(" | ").append(job.salaryRange)
            if (job.jobUrl.isNotBlank()) append(" | ").append(job.jobUrl)
        }
    }.joinToString("\n")

    return if (digestJobs.size == 1) {
        result.copy(company = company, roleTitle = roleTitle, location = location,
            jobUrl = first.jobUrl, salaryRange = first.salaryRange, remotePolicy = first.remotePolicy,
            jdText = digestSummary, scrapedContent = digestSummary)
    } else {
        result.copy(company = company, roleTitle = roleTitle, location = location,
            jobUrl = "", salaryRange = "", remotePolicy = "unknown",
            jdText = digestSummary, scrapedContent = digestSummary)
    }
}
