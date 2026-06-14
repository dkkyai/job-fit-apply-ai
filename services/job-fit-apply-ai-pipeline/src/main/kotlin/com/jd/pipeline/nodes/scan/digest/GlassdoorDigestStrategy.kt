package com.jd.pipeline.nodes.scan.digest

import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import org.jsoup.Jsoup
import java.util.regex.Pattern

object GlassdoorDigestStrategy : BoardDigestStrategy {
    override fun expand(parent: JDState, email: IntakeContext.Email): List<JDState> {
        val emailBody = email.rawBody
        val emailHtml = email.htmlBody

        val htmlJobs = parseGlassdoorDigestJobsFromHtml(parent, emailHtml)
        if (htmlJobs.isNotEmpty()) return htmlJobs

        val normalized = cleanUrl(emailBody).replace(Regex("\\s+"), " ").trim()
        val anyUrlPattern = Pattern.compile("https://www\\.glassdoor\\.com/(?:partner/jobListing\\.htm\\?[^\\s]+?rdforyou=true|Job/jobs\\.htm\\?[^\\s]+?utm_term=[A-Za-z0-9_-]+)")
        val allMatches = mutableListOf<Pair<String, IntRange>>()
        val anyMatcher = anyUrlPattern.matcher(normalized)
        while (anyMatcher.find()) allMatches.add(anyMatcher.group() to (anyMatcher.start() until anyMatcher.end()))

        if (allMatches.isEmpty()) return emptyList()

        val jobs = mutableListOf<JDState>()
        for ((index, match) in allMatches.withIndex()) {
            val url = match.first
            if (!url.contains("/partner/jobListing.htm")) continue
            val previousEnd = if (index == 0) 0 else allMatches[index - 1].second.last + 1
            val segment = normalized.substring(previousEnd, match.second.first).trim()
            val parsed = parseGlassdoorCardSegment(segment) ?: continue
            val jdText = buildGlassdoorSummary(parsed, url)
            jobs.add(parent.withEmailFlags(isDigest = true).copy(
                isJobPosting = true, jobUrl = url,
                company = parsed.company, roleTitle = parsed.roleTitle,
                location = parsed.location, salaryRange = parsed.salaryRange,
                remotePolicy = "unknown", jdText = jdText, scrapedContent = jdText
            ))
        }
        return jobs
    }

    private fun parseGlassdoorDigestJobsFromHtml(input: JDState, emailHtml: String): List<JDState> {
        if (emailHtml.isBlank()) return emptyList()
        val doc = Jsoup.parse(emailHtml)
        val anchors = doc.select("a[href*=/partner/jobListing.htm]")
        if (anchors.isEmpty()) return emptyList()

        val jobs = mutableListOf<JDState>()
        val seen = mutableSetOf<String>()

        for (anchor in anchors) {
            if (jobs.size >= MAX_JOBS_PER_EMAIL) break
            val href = cleanUrl(anchor.attr("abs:href").ifBlank { anchor.attr("href") })
            if (href.isBlank() || href in seen || !href.contains("/partner/jobListing.htm")) continue
            val parsed = parseGlassdoorHtmlCard(anchor) ?: continue
            val jdText = buildGlassdoorSummary(parsed, href)
            jobs.add(input.withEmailFlags(isDigest = true).copy(
                isJobPosting = true, jobUrl = href,
                company = parsed.company, roleTitle = parsed.roleTitle,
                location = parsed.location, salaryRange = parsed.salaryRange,
                remotePolicy = "unknown", jdText = jdText, scrapedContent = jdText
            ))
            seen.add(href)
        }
        return jobs
    }

    private data class ParsedGlassdoorCard(val company: String, val roleTitle: String, val location: String, val salaryRange: String)

    private fun parseGlassdoorHtmlCard(anchorHtml: org.jsoup.nodes.Element): ParsedGlassdoorCard? {
        fun textsOf(tag: String) = anchorHtml.select(tag)
            .map { it.text().replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
        val pTexts = textsOf("p")
        // Current Glassdoor job-alert emails render the company name in a <span>
        // (role/location/salary stay in <p> tags). Older formats put the company
        // in the first <p>, so fall back to that when no usable <span> exists.
        val spanTexts = textsOf("span")

        fun isCardMeta(t: String) = isGlassdoorRating(t) || isGlassdoorLocation(t) ||
            isGlassdoorSalary(t) || isGlassdoorBadge(t) || isGlassdoorAge(t)

        // The company <span> often flattens an inline rating into its text, e.g.
        // "Blue Origin 3.3 ★" — strip the trailing "<n.n> ★" to get a clean name.
        val rawCompany = spanTexts.firstOrNull { !isCardMeta(it) }
            ?: pTexts.firstOrNull { !isCardMeta(it) }
            ?: return null
        val company = rawCompany.replace(Regex("\\s*\\d+(?:\\.\\d+)?\\s*★\\s*$"), "").trim()
            .ifBlank { return null }
        val roleTitle = pTexts.firstOrNull { it != rawCompany && it != company && !isCardMeta(it) } ?: return null
        val location = pTexts.firstOrNull(::isGlassdoorLocation) ?: return null
        val salary = pTexts.firstOrNull(::isGlassdoorSalary).orEmpty()
        return ParsedGlassdoorCard(company, roleTitle, location, salary)
    }

    private fun parseGlassdoorCardSegment(segment: String): ParsedGlassdoorCard? {
        if (segment.isBlank()) return null
        val salaryMatcher = Pattern.compile("\\$[^()]+\\((?:Employer|Glassdoor) est\\.\\)").matcher(segment)
        if (!salaryMatcher.find()) return null
        val salary = salaryMatcher.group().trim()
        val beforeSalary = segment.substring(0, salaryMatcher.start()).trim()
        val locationMatcher = Pattern.compile("([A-Z][A-Za-z .'-]+,\\s*[A-Z]{2})$").matcher(beforeSalary)
        if (!locationMatcher.find()) return null
        val location = locationMatcher.group(1).trim()
        val beforeLocation = beforeSalary.substring(0, locationMatcher.start()).trim()
        val starIndex = beforeLocation.indexOf('★')
        if (starIndex < 0) return null
        val company = beforeLocation.substring(0, starIndex).trim().replace(Regex("\\s*\\d+(?:\\.\\d+)?\\s*$"), "").trim()
        val roleTitle = beforeLocation.substring(starIndex + 1).trim()
        if (company.isBlank() || roleTitle.isBlank()) return null
        return ParsedGlassdoorCard(company, roleTitle, location, salary)
    }

    private fun isGlassdoorRating(text: String) = text.matches(Regex("\\d+(?:\\.\\d+)?\\s*★"))
    private fun isGlassdoorLocation(text: String) = text.matches(Regex("[A-Z][A-Za-z .'-]+,\\s*[A-Z]{2}"))
    // Salary text looks like "$129K - $175K ( Employer est. )" — note the space
    // before the closing paren in current emails, so match "est." not "est.)".
    private fun isGlassdoorSalary(text: String) = text.contains('$') && text.contains("est.")
    private fun isGlassdoorBadge(text: String) = text.equals("Best Place to Work", ignoreCase = true)
    private fun isGlassdoorAge(text: String) =
        text.matches(Regex("\\d+[dhwm]")) || text.equals("Just posted", ignoreCase = true)

    private fun buildGlassdoorSummary(card: ParsedGlassdoorCard, url: String): String = buildString {
        append(card.roleTitle).append(" at ").append(card.company).append(". ")
        append("Location: ").append(card.location).append(". ")
        append("Salary: ").append(card.salaryRange).append(". ")
        append("Apply: ").append(url)
    }

    fun extractGlassdoorJobUrls(emailBody: String): List<String> {
        val normalized = cleanUrl(emailBody)
        val seen = mutableSetOf<String>()
        val results = mutableListOf<String>()
        val partnerPattern = Pattern.compile("https://www\\.glassdoor\\.com/partner/jobListing\\.htm\\?[^\\s]+?rdforyou=true")
        val matcher = partnerPattern.matcher(normalized)
        while (matcher.find() && seen.size < MAX_JOBS_PER_EMAIL) {
            val url = matcher.group()
            if (url !in seen) { seen.add(url); results.add(url) }
        }
        return results
    }
}
