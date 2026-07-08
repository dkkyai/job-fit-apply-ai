package com.jd.pipeline.utils

import com.jd.pipeline.config.Config
import com.jd.pipeline.models.CandidateProfile
import com.jd.pipeline.models.CareerEntry
import com.jd.pipeline.models.EducationEntry
import com.jd.pipeline.nodes.tailor.TailoredProfile
import java.nio.file.Files
import java.nio.file.Path

/**
 * Deterministic résumé HTML renderer — assembles the final HTML directly from structured
 * data, with no LLM call.
 *
 * Reads the committed head+CSS skeleton at [Config.BASE_RESUME_TEMPLATE_PATH] (which carries
 * a `<!-- RESUME_BODY -->` sentinel) and replaces the sentinel with a body that matches the
 * reference `generated_resume.html` structure exactly:
 *
 *  - a centered header (name + `email | phone | location`)
 *  - `<h2>` section headings with a per-role `full-page-table` + `<ul>` bullet list
 *    (`<li><strong>Category:</strong> text</li>`)
 *  - an optional "Independent Projects" section, rendered only when non-empty
 *  - an education table
 *  - a page-broken Skills section, one `<p><strong>Label:</strong> a · b · c</p>` per group
 *
 * Consumes a [TailoredProfile] so the JD-aligned fields (summary, career history, projects,
 * skill groups, jd-matched skills) take precedence; identity + education come from the base.
 */
object ResumeHtmlRenderer {

    private const val BODY_SENTINEL = "<!-- RESUME_BODY -->"

    /** Render a tailored profile to a full HTML document. */
    fun render(tailored: TailoredProfile, templatePath: Path = Config.BASE_RESUME_TEMPLATE_PATH): String =
        readSkeleton(templatePath).replace(BODY_SENTINEL, buildBody(tailored))

    /** Convenience: render an untailored profile (the base résumé) verbatim. */
    fun render(profile: CandidateProfile, templatePath: Path = Config.BASE_RESUME_TEMPLATE_PATH): String =
        render(TailoredProfile.untailored(profile), templatePath)

    // ── body assembly ──────────────────────────────────────────────────────────

    private fun buildBody(t: TailoredProfile): String {
        val sections = mutableListOf<String>()
        sections += header(t.base)
        sections += "<h2>Summary</h2>"
        sections += "<p>${esc(t.summary)}</p>"
        sections += "<h2>Experience</h2>"
        t.careerHistory.forEach { sections += roleBlock(it) }
        if (t.projects.isNotEmpty()) {
            sections += "<h2>Independent Projects</h2>"
            t.projects.forEach { sections += roleBlock(it) }
        }
        sections += "<h2>Education</h2>"
        t.base.background.education.forEach { sections += educationBlock(it) }
        sections += skillsSection(t.skillGroups, t.jdMatchedSkills)
        return sections.joinToString("\n\n")
    }

    private fun header(profile: CandidateProfile): String {
        val id = profile.identity
        return """
            |<div class="resume-header">
            |  <span class="resume-name">${esc(id.fullName)}</span>
            |  <span class="resume-contact">
            |    <a href="mailto:${esc(id.email)}">${esc(id.email)}</a>
            |    <span class="spacer">|</span>
            |    ${esc(id.phone)}
            |    <span class="spacer">|</span>
            |    ${esc(id.location)}
            |  </span>
            |</div>
        """.trimMargin()
    }

    private fun roleBlock(e: CareerEntry): String {
        val table = """
            |<table class="full-page-table">
            |  <tbody>
            |    <tr>
            |      <td class="left-align bold-text">${esc(e.role)}</td>
            |      <td class="right-align">${dateRange(e.startDate, e.endDate)}</td>
            |    </tr>
            |    <tr>
            |      <td class="left-align bold-text">${esc(e.company)}</td>
            |      <td class="right-align">${esc(e.location)}</td>
            |    </tr>
            |  </tbody>
            |</table>
        """.trimMargin()
        val items = e.bullets.joinToString("\n") { b ->
            val cat = b.category.trim()
            if (cat.isNotEmpty()) "  <li><strong>${esc(cat)}:</strong> ${esc(b.text)}</li>"
            else "  <li>${esc(b.text)}</li>"
        }
        return "$table\n\n<ul>\n$items\n</ul>"
    }

    private fun educationBlock(e: EducationEntry): String = """
        |<table class="full-page-table">
        |  <tbody>
        |    <tr>
        |      <td class="left-align bold-text">${esc(e.degreeLine)}</td>
        |      <td class="right-align">${esc(e.year)}</td>
        |    </tr>
        |    <tr>
        |      <td class="left-align bold-text">${esc(e.school)}</td>
        |      <td class="right-align">${esc(e.location ?: "")}</td>
        |    </tr>
        |  </tbody>
        |</table>
    """.trimMargin()

    private fun skillsSection(groups: Map<String, List<String>>, jdMatched: List<String>): String {
        val paras = groups.entries.joinToString("\n\n") { (label, items) ->
            val ordered = orderJdFirst(items, jdMatched)
            "<p><strong>${esc(label)}:</strong> ${ordered.joinToString(" · ") { esc(it) }}</p>"
        }
        return "<div class=\"page-break\">\n\n<h2>Skills</h2>\n\n$paras\n\n</div>"
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Within a group, lead with JD-matched skills (original spelling preserved), then the rest. */
    private fun orderJdFirst(items: List<String>, jdMatched: List<String>): List<String> {
        if (jdMatched.isEmpty()) return items
        val jd = jdMatched.map { it.lowercase() }.toSet()
        val (matched, rest) = items.partition { it.lowercase() in jd }
        return matched + rest
    }

    /** "9/2024 – 1/2026"; a blank/null end renders "Present". */
    private fun dateRange(start: String, end: String?): String = "${fmt(start)} – ${fmt(end)}"

    private val YEAR_MONTH = Regex("""^(\d{4})-(\d{1,2})$""")

    private fun fmt(date: String?): String {
        val d = date?.trim().orEmpty()
        if (d.isEmpty()) return "Present"
        val m = YEAR_MONTH.find(d) ?: return d
        val (year, month) = m.destructured
        return "${month.toInt()}/$year"
    }

    private fun esc(s: String): String = s.trim()
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun readSkeleton(templatePath: Path): String =
        if (Files.exists(templatePath)) Files.readString(templatePath) else FALLBACK_SKELETON

    private val FALLBACK_SKELETON = """
        <!DOCTYPE html>
        <html lang="en"><head><meta charset="UTF-8"><title>Resume</title></head>
        <body>
        $BODY_SENTINEL
        </body></html>
    """.trimIndent()
}
