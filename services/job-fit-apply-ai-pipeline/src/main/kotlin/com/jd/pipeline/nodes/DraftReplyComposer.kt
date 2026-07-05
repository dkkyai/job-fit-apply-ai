package com.jd.pipeline.nodes

import com.jd.pipeline.client.LlmClient
import com.jd.pipeline.config.Config
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import java.nio.file.Files

/**
 * Generates the recruiter reply body — the LLM/templating half of the old CreateDraftReplyNode,
 * with NO Gmail. The Processor calls this to fill [JDState.draftText]; the Poller (which owns
 * Gmail) creates the actual draft from that text. Extracted so reply composition is testable
 * and container-safe (no OAuth on the Processor).
 *
 * [generate] is injectable so tests can stub the LLM.
 */
class DraftReplyComposer(
    private val generate: (String) -> String = { prompt ->
        LlmClient.fromModelString(Config.DRAFT_REPLY_MODEL, jsonMode = false, temperature = 0.3, nodeKey = "draft_reply")
            .call(prompt)
    },
) {

    /** Returns the reply body, or null when the state is not a recruiter email we can reply to. */
    fun compose(state: JDState): String? {
        val email = state.intake as? IntakeContext.Email ?: return null
        if (!email.isRecruiter || email.emailId.isBlank()) return null
        val prompt = fillTemplate(loadSkillTemplate(), state, email)
        return generate(prompt)
    }

    private fun loadSkillTemplate(): String {
        val skillPath = Config.SKILLS_DIR.resolve("DRAFT_REPLY_SKILL.md")
        return if (Files.exists(skillPath)) Files.readString(skillPath) else DEFAULT_SKILL_TEMPLATE
    }

    private fun fillTemplate(template: String, input: JDState, email: IntakeContext.Email): String {
        val prefs = input.candidateProfile?.preferences
        val strengths = input.strengths.joinToString(", ").ifBlank { "strong automation and CI/CD background" }
        val scoreStr = input.fitScore?.let { "%.0f".format(it) } ?: "N/A"
        val safeEmailBody = sanitizeEmailBody(email.rawBody)
        val preferencesBlock = buildPreferencesBlock(prefs)
        return template
            .replace("{{role_title}}", input.roleTitle.ifBlank { "the role" })
            .replace("{{company}}", input.company.ifBlank { "your company" })
            .replace("{{location}}", input.location.ifBlank { "unspecified" })
            .replace("{{fit_score}}", scoreStr)
            .replace("{{strengths}}", strengths)
            .replace("{{email_body}}", safeEmailBody)
            .replace("{{preferences}}", preferencesBlock)
            .replace("{{author_name}}", input.candidateProfile?.identity?.fullName?.ifBlank { "Applicant" } ?: "Applicant")
    }

    private fun buildPreferencesBlock(prefs: com.jd.pipeline.models.CandidatePreferences?): String {
        if (prefs == null) return ""
        return buildString {
            appendLine("## Candidate Preferences")
            appendLine()
            appendLine("- **Visa status:** ${prefs.visaStatus}")
            if (!prefs.visaSponsorshipRequired) {
                appendLine("  → Does not require visa sponsorship")
            }
            appendLine("- **Work arrangement:** ${prefs.preferredWorkArrangement}")
            append("- **Available to start:** ")
            appendLine(
                when {
                    prefs.availableToStartDate != null -> prefs.availableToStartDate
                    prefs.noticePeriodDays != null -> "${prefs.noticePeriodDays}-day notice period"
                    else -> "Immediately"
                }
            )
            if (prefs.willingToRelocate) {
                appendLine("- **Relocation:** Willing to relocate${prefs.relocationNotes?.let { " — $it" } ?: ""}")
            } else {
                appendLine("- **Relocation:** Not willing to relocate")
                prefs.relocationNotes?.let { appendLine("  → $it") }
            }
            if (prefs.travelPercentage != null) {
                appendLine("- **Travel:** Willing to travel ${prefs.travelPercentage}")
            }
            if (prefs.openToContractRoles) {
                append("- **Contract:** Open to contract")
                prefs.minimumContractRateHourly?.let { append(" at \$$it/hr") }
                appendLine()
            }
            prefs.compensationNotes?.let { appendLine("- **Compensation:** $it") }
            prefs.additionalNotes?.let { appendLine("- **Notes:** $it") }
        }
    }

    /** Strip content that looks like prompt injection from the recruiter email body. */
    private fun sanitizeEmailBody(raw: String): String {
        val injectionPatterns = listOf(
            Regex("""(?i)(ignore|disregard|forget)\s+(all\s+)?(previous|prior|above|earlier)\s+(instructions?|prompt|context|rules?)"""),
            Regex("""(?i)you\s+are\s+(now\s+)?(a|an)\s+\w+"""),
            Regex("""(?i)(system\s+)?prompt\s*[:=]"""),
            Regex("""(?i)output\s+your\s+(system\s+)?prompt"""),
            Regex("""(?i)act\s+as\s+(if\s+)?(you\s+are\s+)?"""),
            Regex("""(?i)<\s*(system|instruction|prompt)\s*>""")
        )
        return raw.lines()
            .filterNot { line -> injectionPatterns.any { it.containsMatchIn(line) } }
            .joinToString("\n")
            .trim()
    }

    companion object {
        val DEFAULT_SKILL_TEMPLATE = """
            You are drafting a professional reply to a recruiter.
            Role: {{role_title}} at {{company}} ({{location}}).
            My strengths: {{strengths}}.
            {{preferences}}

            Reply to the email below. Answer any questions directly. Mention my attached resume and cover letter.
            Sign as: Best regards,\n{{author_name}}

            RECRUITER EMAIL:
            {{email_body}}
        """.trimIndent()
    }
}
