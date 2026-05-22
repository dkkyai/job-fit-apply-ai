package com.jd.pipeline.nodes.scan.digest

import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import org.jsoup.Jsoup

object WelcomeToTheJungleDigestStrategy : BoardDigestStrategy {
    override fun expand(parent: JDState, email: IntakeContext.Email): List<JDState> {
        val emailHtml = email.htmlBody
        if (emailHtml.isBlank()) return emptyList()
        val document = Jsoup.parse(emailHtml)
        val anchors = document.select("a[href*='sendgrid.net/ls/click']")
        val jobs = mutableListOf<JDState>()
        val seenTitles = mutableSetOf<String>()

        for (anchor in anchors) {
            if (jobs.size >= MAX_JOBS_PER_EMAIL) break
            val strongs = anchor.select("strong").map { it.text().replace(Regex("\\s+"), " ").trim() }.filter { it.isNotBlank() }
            if (strongs.size < 2) continue
            val company = strongs[0]
            val roleTitle = strongs[1]
            if (!seenTitles.add(roleTitle)) continue
            val fullText = anchor.text().replace(Regex("\\s+"), " ").trim()
            val salaryMatch = Regex("""Salary:\s*\$[0-9Kk\-]+""").find(fullText)
            val locationMatch = Regex("""Remote \(within the US\)|[A-Z][A-Za-z .'-]+,\s*[A-Z]{2}(?:\s*\([^)]+\))?""").find(fullText)
            jobs.add(createParsedDigestJob(parent, company, roleTitle, locationMatch?.value?.trim().orEmpty(), salaryMatch?.value?.trim().orEmpty(), cleanUrl(anchor.attr("href"))))
        }
        return jobs
    }
}
