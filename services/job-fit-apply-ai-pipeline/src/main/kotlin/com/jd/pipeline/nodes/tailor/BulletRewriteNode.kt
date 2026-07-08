package com.jd.pipeline.nodes.tailor

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.fasterxml.jackson.databind.JsonNode
import com.jd.pipeline.client.LlmCaller
import com.jd.pipeline.client.LlmClient
import com.jd.pipeline.config.Config
import com.jd.pipeline.models.Bullet
import com.jd.pipeline.models.CareerEntry
import java.nio.file.Files

/**
 * Tailor subgraph node D (4/7): per-role bullet rewrite.
 *
 * Rewrites every bullet as `action verb → what → quantified result → scope`, mirroring
 * JD phrasing from the gap analysis's `supported` set and enforcing the staff-vs-senior
 * test (generic-execution bullets are rewritten toward ownership / cross-team impact).
 * Each rewritten bullet also carries the reorder inputs ([BulletMeta]): which must-have
 * terms it covers, whether it is quantified, and whether it shows Staff-level scope.
 *
 * The LLM output folds back into the profile's career entries by the
 * `(role, company, start_date)` join key; roles or bullets the LLM drops keep their
 * original content (non-fatal degradation).
 */
class BulletRewriteNode(
    // 480s (default 300): bullet_rewrite emits the largest output (all roles' bullets) and on the
    // dense local 27B was measured at 3082 tok / 297s — only ~18s under the old 315s hard wall.
    // Raised so resumes with more roles/bullets don't time out. (hard wall = 480 + 15s grace.)
    private val llm: LlmCaller = LlmClient.reasoningClient(nodeKey = "bullet_rewrite", timeoutSeconds = 480)
) {
    private val mapper = ObjectMapper().registerKotlinModule()

    fun process(state: TailorState): TailorState {
        val jd = state.jdRequirements ?: return state.copy(error = "bullet_rewrite: jdRequirements is null")
        val gap = state.gapAnalysis ?: return state.copy(error = "bullet_rewrite: gapAnalysis is null")
        val profile = state.candidateProfile
            ?: return state.copy(error = "bullet_rewrite: candidateProfile is null")

        println("[bullet_rewrite] Rewriting bullets for: ${state.roleTitle} @ ${state.company}")

        return try {
            val prompt = buildPrompt(jd, gap, state, profile)
            val response = llm.call(prompt)
            val rewrites = parseRoleRewrites(stripJsonFences(response))
            println("[bullet_rewrite] LLM returned rewrites for ${rewrites.size} role(s)")

            val career = applyRewrites(profile.background.careerHistory, rewrites)
            val projects = applyRewrites(profile.projects, rewrites)

            val flatBullets = career.diagnostics + projects.diagnostics
            println("[bullet_rewrite] Rewrote ${flatBullets.size} bullet(s) across ${career.entries.size + projects.entries.size} role(s)")

            state.copy(
                tailoredCareerHistory = career.entries,
                tailoredProjects = projects.entries,
                bulletMeta = career.meta + projects.meta,
                tailoredBullets = flatBullets
            )
        } catch (e: Exception) {
            state.copy(error = "bullet_rewrite: ${e.message}")
        }
    }

    /** Result of folding LLM rewrites back into one entry list. */
    internal data class FoldResult(
        val entries: List<CareerEntry>,
        val meta: Map<String, List<BulletMeta>>,
        val diagnostics: List<TailoredBullet>
    )

    /**
     * Fold per-role LLM rewrites back into the original `CareerEntry` list, matching by
     * [roleKey]. Roles missing from the response keep their original bullets; within a
     * matched role, bullets pair by index and a missing/blank rewrite keeps the original
     * (its meta falls back to a digit-scan for `quantified`). `internal` for test visibility.
     */
    internal fun applyRewrites(originals: List<CareerEntry>, rewrites: List<RoleRewrite>): FoldResult {
        val byKey = rewrites.associateBy { roleKey(it.role, it.company, it.startDate) }
        val metaMap = mutableMapOf<String, List<BulletMeta>>()
        val diagnostics = mutableListOf<TailoredBullet>()

        val entries = originals.map { entry ->
            val key = roleKey(entry.role, entry.company, entry.startDate)
            val match = byKey[key] ?: return@map entry.also {
                metaMap[key] = entry.bullets.map { b -> fallbackMeta(b.text) }
            }

            val metas = mutableListOf<BulletMeta>()
            val mergedBullets = entry.bullets.mapIndexed { idx, original ->
                val rb = match.bullets.getOrNull(idx)
                if (rb != null && rb.rewritten.isNotBlank()) {
                    metas += BulletMeta(
                        mustHaveHits = rb.mustHaveHits.size,
                        quantified = rb.quantified,
                        senioritySignal = rb.senioritySignal
                    )
                    diagnostics += TailoredBullet(
                        original = original.text,
                        rewritten = rb.rewritten,
                        mustHaveHits = rb.mustHaveHits,
                        quantified = rb.quantified,
                        senioritySignal = rb.senioritySignal
                    )
                    // Category is JD-alignable signal (renders as the bold lead-in) — take the
                    // LLM's re-labelling, fall back to the original label when omitted.
                    Bullet(category = rb.category.ifBlank { original.category }, text = rb.rewritten)
                } else {
                    metas += fallbackMeta(original.text)
                    original
                }
            }
            metaMap[key] = metas
            entry.copy(bullets = mergedBullets)
        }
        return FoldResult(entries, metaMap, diagnostics)
    }

    /**
     * Parse the LLM response into a list of [RoleRewrite], tolerating the common local-model
     * habit of wrapping the array in an object (`{"roles": [...]}`) or returning a single
     * role object instead of a one-element array. `internal` for test visibility.
     */
    internal fun parseRoleRewrites(cleaned: String): List<RoleRewrite> {
        val root = mapper.readTree(cleaned)
        val array: JsonNode = when {
            root.isArray -> root
            // A single role object (has a "role" field) is wrapped; otherwise the object is a
            // wrapper like {"roles": [...]} — take its first array-valued field.
            root.isObject && root.has("role") -> mapper.createArrayNode().add(root)
            root.isObject -> root.elements().asSequence().firstOrNull { it.isArray }
                ?: if (root.has("bullets")) mapper.createArrayNode().add(root) else null
            else -> null
        } ?: throw IllegalStateException("expected a JSON array of role rewrites, got ${root.nodeType}")
        return mapper.convertValue(array, object : TypeReference<List<RoleRewrite>>() {})
    }

    /** Meta for a bullet the LLM did not rewrite: unranked, quantified detected by digit scan. */
    private fun fallbackMeta(text: String): BulletMeta =
        BulletMeta(mustHaveHits = 0, quantified = text.any { it.isDigit() }, senioritySignal = false)

    private fun buildPrompt(
        jd: JdRequirements,
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
                "bullets" to entry.bullets.map { linkedMapOf("category" to it.category, "text" to it.text) }
            )
        }
        val rolesJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rolesPayload)

        val supported = gap.supported.take(15).joinToString("\n") { "- ${it.term} — evidence: ${it.evidence}" }
            .ifBlank { "(none)" }
        val pullForward = gap.missingButSupported.take(6).joinToString("\n") { "- ${it.term} — evidence: ${it.evidence}" }
            .ifBlank { "(none)" }

        return buildString {
            appendLine(TailorRubric.prepend(skillPrompt))
            appendLine()
            appendLine("TARGET TITLE: ${jd.targetTitle} at ${state.company}")
            appendLine("MUST-HAVE TERMS (count hits against these, JD exact phrasing): ${jd.mustHave.joinToString(", ")}")
            appendLine("SENIORITY SIGNALS TO EVIDENCE: ${jd.senioritySignals.joinToString(", ").ifBlank { "(none)" }}")
            appendLine("DOMAIN KEYWORDS (weave in where truthful): ${jd.domainKeywords.take(10).joinToString(", ")}")
            appendLine()
            appendLine("SUPPORTED TERMS (the ONLY terms you may add or mirror):")
            appendLine(supported)
            appendLine()
            appendLine("PULL FORWARD FIRST (under-surfaced must-haves — work these into the strongest truthful bullet):")
            appendLine(pullForward)
            appendLine()
            appendLine("DO NOT CLAIM (no resume evidence): ${gap.unsupported.joinToString(", ").ifBlank { "(none)" }}")
            state.atsReport?.let { report ->
                appendLine()
                appendLine("PREVIOUS VALIDATION FEEDBACK (revision pass — fix these concretely):")
                appendLine("- Supported terms still MISSING from the output: ${report.missingTerms.joinToString(", ").ifBlank { "(none)" }}")
                appendLine("- Unsupported terms that LEAKED (remove): ${report.leakedUnsupportedTerms.joinToString(", ").ifBlank { "(none)" }}")
                appendLine("- Improvements: ${report.topImprovements.joinToString("; ").ifBlank { "(none)" }}")
            }
            appendLine()
            appendLine("CANDIDATE ROLES (career history + projects). Each bullet is { category, text }.")
            appendLine("Rewrite BOTH per bullet: re-label the category to the JD's terminology where truthful,")
            appendLine("and rewrite the text. Preserve role/company/start_date verbatim — they are join keys.")
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
        /** Which JD must-have terms this bullet now covers (exact terms from the input list). */
        @JsonProperty("must_have_hits") val mustHaveHits: List<String> = emptyList(),
        @JsonProperty("quantified") val quantified: Boolean = false,
        @JsonProperty("seniority_signal") val senioritySignal: Boolean = false
    )

    companion object {
        private val DEFAULT_PROMPT = """
            |Rewrite each role's bullets for the target JD. Formula per bullet: strong action verb →
            |what you did → quantified result → scope. Fragments (no "I"), past tense for past roles.
            |Mirror JD phrasing only from the SUPPORTED TERMS list. Preserve every number from the
            |original verbatim; never invent metrics or tools. Apply the staff-vs-senior test: if a
            |bullet reads as generic execution, rewrite it to show ownership, cross-team influence,
            |or downstream impact.
            |
            |Return ONLY a valid JSON array — one element per role, same order, no fences:
            |[
            |  {
            |    "role": "exact role from input", "company": "exact company", "start_date": "exact start_date",
            |    "bullets": [
            |      { "original": "verbatim original text",
            |        "category": "short bold label, JD-aligned where truthful",
            |        "rewritten": "rewritten bullet",
            |        "must_have_hits": ["must-have terms this bullet covers", ...],
            |        "quantified": true/false,
            |        "seniority_signal": true/false }
            |    ]
            |  }
            |]
            |One rewritten bullet per original bullet, in the same order (index alignment).
        """.trimMargin()
    }
}
