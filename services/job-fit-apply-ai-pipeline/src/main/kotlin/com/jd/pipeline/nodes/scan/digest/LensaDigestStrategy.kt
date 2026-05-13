package com.jd.pipeline.nodes.scan.digest

import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import org.jsoup.Jsoup

object LensaDigestStrategy : BoardDigestStrategy {
    override fun expand(parent: JDState, email: IntakeContext.Email): List<JDState> {
        val emailHtml = email.htmlBody
        if (emailHtml.isBlank()) return emptyList()
        val document = Jsoup.parse(emailHtml)
        val cards = document.select("table[style*=border-radius]")
        val jobs = mutableListOf<JDState>()

        for (card in cards) {
            if (jobs.size >= MAX_JOBS_PER_EMAIL) break
            val titleAnchor = card.selectFirst("a[href*='email.mg3.lensa.com/c/']") ?: continue
            val company = card.select("td")
                .map { it.text().replace(Regex("\\s+"), " ").trim() }
                .firstOrNull { it.isNotBlank() && !it.contains("★") && !it.matches(Regex("""\d+(?:\.\d+)?""")) }
                ?.takeIf { it != titleAnchor.text().replace(Regex("\\s+"), " ").trim() } ?: continue
            val rawTitle = titleAnchor.text().replace(Regex("\\s+"), " ").trim()
            if (rawTitle.isBlank()) continue
            val locationText = card.select("div").map { it.text().replace(Regex("\\s+"), " ").trim() }.firstOrNull { looksLikeLocation(it) }.orEmpty()
            val badges = card.select("span").map { it.text().replace(Regex("\\s+"), " ").trim() }.filter { it.isNotBlank() }
            val salaryRange = card.text().replace(Regex("\\s+"), " ")
                .let { Regex("""\$[0-9Kk\-]+(?:-\$?[0-9Kk]+)? / yr\.(?: \(est\.\))?""").find(it)?.value.orEmpty() }
            val (roleTitle, inferredLocation) = cleanLensaTitleAndLocation(rawTitle, locationText, badges)
            val jobUrl = cleanUrl(titleAnchor.attr("href"))
            if (salaryRange.isBlank()) continue
            jobs.add(createParsedDigestJob(parent, company, roleTitle, inferredLocation, salaryRange, jobUrl))
        }
        return jobs
    }

    private fun cleanLensaTitleAndLocation(rawTitle: String, explicitLocation: String, badges: List<String>): Pair<String, String> {
        var title = rawTitle.trim()
        var location = explicitLocation.trim()

        if (location.isBlank()) {
            val roleAwareCityRemote = Regex("""^(.*?(?:Engineer|Developer|Programmer|Architect|Manager|Designer|Analyst|Scientist|Specialist|Lead))\s+([A-Z][A-Za-z.'-]+(?:\s+[A-Z][A-Za-z.'-]+)*,\s*[A-Z]{2}\s+or\s+Remote)$""").find(title)
            if (roleAwareCityRemote != null) {
                title = roleAwareCityRemote.groupValues[1].trim()
                location = roleAwareCityRemote.groupValues[2].trim()
            } else {
                val cityRemoteLocation = Regex("""[A-Z][A-Za-z.'-]+(?:\s+[A-Z][A-Za-z.'-]+)*,\s*[A-Z]{2}\s+or\s+Remote$""").find(title)
                if (cityRemoteLocation != null) {
                    val stripped = title.removeSuffix(cityRemoteLocation.value).trim()
                    if (stripped.isNotBlank()) { title = stripped; location = cityRemoteLocation.value.trim() }
                } else {
                    val m = Regex("""^(.*)\s+([A-Z][A-Za-z .'-]+,\s*[A-Z]{2}|Remote(?:\s+[A-Z]{2,})?)$""").find(title)
                    if (m != null) { title = m.groupValues[1].trim(); location = m.groupValues[2].trim() }
                }
            }
            if (location.isBlank() && title.endsWith("(Remote)", ignoreCase = true)) { title = title.removeSuffix("(Remote)").trim(); location = "Remote" }
            else if (location.isBlank() && title.endsWith(" Remote", ignoreCase = true)) { title = title.removeSuffix("Remote").trim().removeSuffix(",").trim(); location = "Remote" }
        }
        if (title.endsWith("(Hybrid)", ignoreCase = true)) title = title.removeSuffix("(Hybrid)").trim()
        if (location.isBlank() && badges.any { it.equals("Remote", ignoreCase = true) }) location = "Remote"
        if (location.startsWith("Remote ", ignoreCase = true)) location = "Remote"
        title = title.trim().trimEnd(',', '-', ' ')
        return title to location
    }
}
