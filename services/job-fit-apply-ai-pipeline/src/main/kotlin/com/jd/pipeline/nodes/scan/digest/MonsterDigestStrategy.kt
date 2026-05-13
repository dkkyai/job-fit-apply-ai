package com.jd.pipeline.nodes.scan.digest

import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import org.jsoup.Jsoup

object MonsterDigestStrategy : BoardDigestStrategy {
    override fun expand(parent: JDState, email: IntakeContext.Email): List<JDState> {
        val emailBody = email.rawBody
        val emailHtml = email.htmlBody
        val normalized = emailBody.replace("&nbsp;", " ").replace(Regex("\\s+"), " ").trim()
        val urlsByTitle = findUrlsByAnchorText(emailHtml)
        val titles = extractMonsterTitles(emailHtml)
        val jobs = mutableListOf<JDState>()
        val seenKeys = mutableSetOf<String>()

        for (roleTitle in titles) {
            if (jobs.size >= MAX_JOBS_PER_EMAIL) break
            val pattern = Regex(
                """${Regex.escape(roleTitle)}\s+(.+?)\s+-\s+(.+?)\s+-\s+([A-Z]{2}|NULL)\s+(?:VIEW JOB|QUICK APPLY)""",
                setOf(RegexOption.IGNORE_CASE)
            )
            val match = pattern.find(normalized) ?: continue
            val company = match.groupValues[1].trim()
            val cityOrRegion = match.groupValues[2].trim()
            val state = match.groupValues[3].trim()
            val location = when {
                state.equals("NULL", ignoreCase = true) -> cityOrRegion
                cityOrRegion.equals("NULL", ignoreCase = true) -> state
                else -> "$cityOrRegion, $state"
            }
            if (company.contains("today's jobs", ignoreCase = true) ||
                company.contains("Job Alerts", ignoreCase = true) ||
                company.contains(roleTitle, ignoreCase = true) ||
                location.equals("here", ignoreCase = true)) continue
            val dedupeKey = "${company.lowercase()}|${roleTitle.lowercase()}"
            if (!seenKeys.add(dedupeKey)) continue
            jobs.add(createParsedDigestJob(parent, company, roleTitle, location, "", urlsByTitle[roleTitle].orEmpty()))
        }
        return jobs
    }

    private fun extractMonsterTitles(emailHtml: String): List<String> {
        if (emailHtml.isBlank()) return emptyList()
        return Jsoup.parse(emailHtml).select("a strong")
            .map { it.text().replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() && !it.equals("VIEW JOB", ignoreCase = true) && !it.equals("QUICK APPLY", ignoreCase = true) }
            .distinct()
    }
}
