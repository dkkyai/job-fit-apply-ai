package com.jd.pipeline.nodes.scan.digest

import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import org.jsoup.Jsoup

object JobLeadsDigestStrategy : BoardDigestStrategy {
    override fun expand(parent: JDState, email: IntakeContext.Email): List<JDState> {
        val emailBody = email.rawBody
        val emailHtml = email.htmlBody
        if (emailHtml.isBlank()) return emptyList()
        val urls = Regex("""View job:\s*(https://www\.jobleads\.com/[^\s]+)""")
            .findAll(emailBody).map { it.groupValues[1].trim() }.toList()
        val document = Jsoup.parse(emailHtml)
        val cards = document.select("td[style*=padding: 16px]")
        val jobs = mutableListOf<JDState>()

        for (card in cards) {
            if (jobs.size >= MAX_JOBS_PER_EMAIL) break
            val divTexts = card.select("div").map { it.text().replace(Regex("\\s+"), " ").trim() }.filter { it.isNotBlank() }
            val title = divTexts.firstOrNull() ?: continue
            val company = divTexts.drop(1).firstOrNull() ?: continue
            val location = divTexts.drop(2).firstOrNull { looksLikeLocation(it) } ?: continue
            val salary = divTexts.firstOrNull { it.startsWith("USD ") }.orEmpty()
            val url = urls.getOrNull(jobs.size).orEmpty()
            jobs.add(createParsedDigestJob(parent, company, title, location, salary, url))
        }
        return jobs
    }
}
