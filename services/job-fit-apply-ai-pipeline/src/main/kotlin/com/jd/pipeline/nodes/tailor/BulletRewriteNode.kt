package com.jd.pipeline.nodes.tailor

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.client.LlmClient
import com.jd.pipeline.config.Config
import com.jd.pipeline.models.Bullet
import com.jd.pipeline.models.CareerEntry
import java.nio.file.Files

/**
 * Tailor subgraph node 4/6: Bullet Rewrite (per-role structured)
 */
class BulletRewriteNode(
    // 480s (default 300): bullet_rewrite emits the largest output (all roles' bullets) and on the
    // dense local 27B was measured at 3082 tok / 297s — only ~18s under the old 315s hard wall.
    // Raised so resumes with more roles/bullets don't time out. (hard wall = 480 + 15s grace.)
    private val llm: LlmCaller = LlmClient.reasoningClient(nodeKey = "bullet_rewrite", timeoutSeconds = 480)
) {
    private val mapper = ObjectMapper().registerKotlinModule()

    fun process(state: TailorState): TailorState {
        val jd = state.jdStructured ?: return state.copy(error = "bullet_rewrite: jdStructured is null")
        val gap = state.gapAnalysis ?: return state.copy(error = "bullet_rewrite: gapAnalysis is null")
        val profile = state.candidateProfile
            ?: return state.copy(error = "bullet_rewrite: candidateProfile is null")

        println("[bullet_rewrite] Rewriting bullets for: ${state.roleTitle} @ ${state.company}")

        return try {
            val prompt = buildPrompt(jd, gap, state, profile)
            val response = llm.call(prompt)
            val cleaned = stripJsonFences(response)
            val rewrites: List<RoleRewrite> = mapper.readValue(
                cleaned,
                object : TypeReference<List<RoleRewrite>>() {}
            )
            println("[bullet_rewrite] LLM returned rewrites for ${rewrites.size} role(s)")

            val (tailoredCareer, careerBullets) = applyRewrites(profile.background.careerHistory, rewrites)
            val (tailoredProjects, projectBullets) = applyRewrites(profile.projects, rewrites)

            val flatBullets = careerBullets + projectBullets
            println("[bullet_rewrite] Rewrote ${flatBullets.size} bullet(s) across ${tailoredCareer.size + tailoredProjects.size} role(s)")

            state.copy(
                tailoredCareerHistory = tailoredCareer,
                tailoredProjects = tailoredProjects,
                tailoredBullets = flatBullets
            )
        } catch (e: Exception) {
            state.copy(error = "bullet_rewrite: ${e.message}")
        }
    }

    /**
     * Fold per-role LLM rewrites back into the original `CareerEntry` list, matching
     * by `(role, company, start_date)`. Roles missing from the response keep their
     * original bullets. Returns the rewritten list plus a flat `TailoredBullet`
     * list (only entries that were actually rewritten) for diagnostic output.
     *
     * `internal` for test visibility.
     */
    internal fun applyRewrites(
        originals: List<CareerEntry>,
        rewrites: List<RoleRewrite>
    ): Pair<List<CareerEntry>, List<TailoredBullet>> {
        val byKey = rewrites.associateBy { it.matchKey() }
        val flat = mutableListOf<TailoredBullet>()
        val tailored = originals.map { entry ->
            val match = byKey[matchKey(entry)] ?: return@map entry
            val originalBullets = entry.bullets
            val rewritten = match.bullets

            // Pair by index. Keep the original verbatim when the LLM dropped a bullet.
            val mergedBullets = originalBullets.mapIndexed { idx, original ->
                val rb = rewritten.getOrNull(idx)
                if (rb != null && rb.rewritten.isNotBlank()) {
                    flat += TailoredBullet(
                        original = original.text,
                        rewritten = rb.rewritten,
                        jdAlignmentScore = rb.jdAlignmentScore
                    )
                    // Re-label the category to the JD's wording when supplied, else keep the original.
                    Bullet(category = rb.category.ifBlank { original.category }, text = rb.rewritten)
                } else {
                    original
                }
            }
            entry.copy(bullets = mergedBullets)
        }
        return tailored to flat
    }

    private fun matchKey(entry: CareerEntry) =
        "${entry.role.trim().lowercase()}|${entry.company.trim().lowercase()}|${entry.startDate.trim()}"

    private fun RoleRewrite.matchKey() =
        "${role.trim().lowercase()}|${company.trim().lowercase()}|${startDate.trim()}"

    private fun buildPrompt(
        jd: JdStructured,
        gap: GapAnalysis,
        state: TailorState,
        profile: com.jd.pipeline.models.CandidateProfile
    ): String {
        val skillPrompt = try {
            if (Files.exists(Config.BULLET_REWRITE_SKILL)) Files.readString(Config.BULLET_REWRITE_SKILL)
            else DEFAULT_PROMPT
        } catch (_: Exception) { DEFAULT_PROMPT }

        val rolesPayload = (profile.background.careerHistory + profile.projects).map { entry ->
            linkedMapOf(
                "role" to entry.role,
                "company" to entry.company,
                "start_date" to entry.startDate,
                "end_date" to entry.endDate,
                "location" to entry.location,
                "bullets" to entry.bullets
            )
        }
        val rolesJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rolesPayload)

        return buildString {
            appendLine(skillPrompt.trim())
            appendLine()
            appendLine("TARGET ROLE: ${jd.roleTitle} (${jd.seniority}) at ${state.company}")
            appendLine("REQUIRED SKILLS: ${jd.requiredSkills.take(12).joinToString(", ")}")
            appendLine("PREFERRED SKILLS: ${jd.preferredSkills.take(8).joinToString(", ")}")
            appendLine("DOMAIN KEYWORDS: ${jd.domainKeywords.take(10).joinToString(", ")}")
            appendLine("ATS PHRASES (distribute across bullets where truthful): ${jd.atsExactPhrases.take(10).joinToString(", ")}")
            appendLine("TOP GAPS (de-emphasise or omit): ${gap.topGaps.take(5).joinToString(", ")}")
            state.atsScore?.let { score ->
                appendLine()
                appendLine("PREVIOUS ATS FEEDBACK (revision pass — address these where truthful):")
                appendLine("- Previous overall score: ${score.overallScore}/100")
                appendLine("- Remaining gaps: ${score.remainingGaps.joinToString("; ").ifBlank { "(none)" }}")
                appendLine("- Improvements: ${score.top3Improvements.joinToString("; ").ifBlank { "(none)" }}")
            }
            appendLine()
            appendLine("CANDIDATE ROLES (career history + projects). Rewrite the bullets array for each role.")
            appendLine("Each bullet is { category, text }. Rewrite BOTH: sharpen the text AND re-label the category to the")
            appendLine("JD's terminology where truthful (else keep the original category).")
            appendLine("Preserve role/company/start_date verbatim — those are the join keys.")
            appendLine("Return one rewritten bullet per original bullet, in the same order.")
            appendLine()
            append(rolesJson)
        }
    }

    private fun stripJsonFences(text: String): String =
        text.replace(Regex("```(?:json)?"), "").trim()
            .let { if (it.endsWith("`")) it.dropLast(1).trim() else it }

    // ── per-role rewrite JSON shape ──────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RoleRewrite(
        @JsonProperty("role") val role: String = "",
        @JsonProperty("company") val company: String = "",
        @JsonProperty("start_date") val startDate: String = "",
        @JsonProperty("bullets") val bullets: List<RewrittenBullet> = emptyList()
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RewrittenBullet(
        @JsonProperty("original") val original: String = "",
        @JsonProperty("category") val category: String = "",
        @JsonProperty("rewritten") val rewritten: String = "",
        @JsonProperty("jd_alignment_score") val jdAlignmentScore: Int = 0
    )

    companion object {
        private val DEFAULT_PROMPT = """
            |You are an ATS-optimised resume writer. Rewrite the bullets within each role.
            |
            |Return ONLY a valid JSON array — one element per role — with no markdown fences
            |or preamble:
            |[
            |  {
            |    "role": "exact role title from input",
            |    "company": "exact company from input",
            |    "start_date": "exact start_date from input",
            |    "bullets": [
            |      { "original": "verbatim original bullet text",
            |        "category": "short bold label (a few words), re-aligned to the JD where truthful",
            |        "rewritten": "rewritten ATS-aligned version",
            |        "jd_alignment_score": integer (0-100) }
            |    ]
            |  }
            |]
            |
            |Rules:
            |- One rewritten bullet per original bullet, IN THE SAME ORDER (index alignment).
            |- Each bullet keeps a short "category" label. Re-align it to the JD's wording where
            |  truthful; otherwise repeat the original category.
            |- Preserve ALL numbers, percentages, and quantification from the original.
            |- Do not add skills, tools, or metrics not present in the original bullet.
            |- Lead with a strong action verb.
            |- Naturally weave in JD domain keywords where truthful.
            |- Plain text only — no markdown, no symbols, no smart quotes.
            |- DO NOT reorder roles, rename companies, change start_date, or merge bullets.
            |  Those fields are the join keys back into the candidate profile.
        """.trimMargin()
    }
}
