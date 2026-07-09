package com.jd.pipeline.nodes.tailor

import com.fasterxml.jackson.databind.ObjectMapper
import com.jd.pipeline.config.Config
import com.jd.pipeline.nodes.Node
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.PipelineAction
import com.jd.pipeline.utils.OutputUtils
import com.jd.pipeline.utils.ResumeHtmlRenderer
import java.nio.file.Files
import java.nio.file.Path

/**
 * ResumeTailoringSubgraph — the Node<JDState> entry point.
 *
 * Runs seven sequential tailoring stages on an internal [TailorState], assembles a
 * [TailoredProfile] from the outputs, and renders it to
 * `output/<timestamp>/tailored_resume.html` via [ResumeHtmlRenderer].
 *
 * Pipeline (A–G):
 *   JdExtractionNode → GapAnalysisNode → SummaryRewriteNode → BulletRewriteNode
 *   → BulletReorderNode (deterministic) → SkillsRestructureNode → AtsValidationNode
 *
 * The subgraph optimises for three ordered targets — ATS pass, recruiter skim,
 * hiring-manager depth — under a hard integrity guardrail: gap analysis partitions the
 * JD's terms into supported/unsupported, rewrites may only emphasise supported terms,
 * and validation deterministically detects leaks. When the validation report is
 * actionable (missing supported must-haves, leaked terms, doubled words, or a low
 * score), one refinement pass re-runs the rewrite nodes with the concrete gap list.
 */
class ResumeTailoringSubgraph(
    private val jdExtraction: JdExtractionNode        = JdExtractionNode(),
    private val gapAnalysis: GapAnalysisNode          = GapAnalysisNode(),
    private val summaryRewrite: SummaryRewriteNode    = SummaryRewriteNode(),
    private val bulletRewrite: BulletRewriteNode      = BulletRewriteNode(),
    private val bulletReorder: BulletReorderNode      = BulletReorderNode(),
    private val skillsRestructure: SkillsRestructureNode = SkillsRestructureNode(),
    private val atsValidation: AtsValidationNode      = AtsValidationNode(),
) : Node<JDState> {

    private val mapper = ObjectMapper()

    override fun process(input: JDState): JDState {
        if (input.pipelineAction != PipelineAction.TAILOR) return input

        println("[tailor_subgraph] Starting tailoring pipeline for: ${input.roleTitle} @ ${input.company}")

        // ── Resolve / create output directory ─────────────────────────────────
        val outputDir = OutputUtils.getOutputDirectory(input).also { Files.createDirectories(it) }
        val outputPath = outputDir.toString()

        // ── Guard: candidateProfile must be loaded ─────────────────────────────
        val profile = input.candidateProfile
        if (profile == null) {
            System.err.println("[tailor_subgraph] ERROR: candidateProfile is null — run --init-profile first")
            return input.copy(outputPath = outputPath, error = "tailor_subgraph: candidateProfile is null")
        }

        // ── Guard: jdText must have enough content to tailor meaningfully ──────
        // If the JD is just metadata (e.g. LinkedIn scrape failed), render the
        // untailored profile so the PDF render step still produces an artifact.
        val jdWordCount = input.jdText.split(Regex("\\s+"))
            .count { it.isNotBlank() && !it.startsWith("http") }
        if (jdWordCount < 50) {
            System.err.println("[tailor_subgraph] WARN: jdText too sparse ($jdWordCount non-URL words) — rendering untailored profile")
            writeUntailoredHtml(outputDir, profile)
            return input.copy(outputPath = outputPath, tailoringDegradedNodes = listOf("all (untailored — JD too sparse)"))
        }

        // ── Initialise TailorState ─────────────────────────────────────────────
        // The subgraph always runs its own JD extraction (rich JdRequirements schema);
        // score_fit's leaner JdStructured serves scoring only.
        var state = TailorState(
            jdText = input.jdText,
            candidateProfile = profile,
            fitScore = input.fitScore ?: 0f,
            strengths = input.strengths,
            gaps = input.gaps,
            company = input.company,
            roleTitle = input.roleTitle,
            trackId = input.trackId ?: 0
        )

        // ── Sequential execution — a content-node failure is NON-FATAL ─────────
        // A flaky local-LLM step must NOT abandon the whole report. We keep the base
        // content for the failed section and still render the resume → PDF → report, so
        // artifact_url is always populated. buildTailoredProfile falls back to the base
        // profile for any tailored field a failed node left null. [degraded] records
        // which nodes fell back → surfaced in report.md.
        val degraded = mutableListOf<String>()
        state = runNode("jd_extraction", state, degraded) { jdExtraction.process(it) }
        state = runNode("gap_analysis", state, degraded) { gapAnalysis.process(it) }
        state = runNode("summary_rewrite", state, degraded) { summaryRewrite.process(it) }
        state = runNode("bullet_rewrite", state, degraded) { bulletRewrite.process(it) }
        if (state.tailoredCareerHistory != null) {
            state = runNode("bullet_reorder", state, degraded) { bulletReorder.process(it) }
        }
        state = runNode("skills_restructure", state, degraded) { skillsRestructure.process(it) }

        // Validate whatever will render: needs the tailored bullets + skills. A degraded summary
        // falls back to the base one, which the validator scores against (that is what renders).
        if (state.tailoredCareerHistory != null && state.restructuredSkills != null) {
            state = runNode("ats_validation", state, degraded) { atsValidation.process(it) }
        }

        // ── One refinement pass when the validation report is actionable ───────
        state = maybeRefine(state)

        // ── Save output files ──────────────────────────────────────────────────
        saveOutputFiles(outputDir, state)

        // ── Render tailored HTML from structured profile ──────────────────────
        return try {
            val tailored = buildTailoredProfile(profile, state)
            val html = ResumeHtmlRenderer.render(tailored)
            Files.writeString(outputDir.resolve("tailored_resume.html"), html)
            println("[tailor_subgraph] tailored_resume.html written to $outputDir")

            if (degraded.isNotEmpty()) {
                println("[tailor_subgraph] Complete (short-circuited — fell back on: ${degraded.joinToString(", ")}) → $outputPath")
            } else {
                println("[tailor_subgraph] Complete — coverage: ${state.atsReport?.mustHaveCoveragePct}%, overall: ${state.atsReport?.overallScore} → $outputPath")
            }

            input.copy(outputPath = outputPath, tailoringDegradedNodes = degraded)
        } catch (e: Exception) {
            val msg = "tailor_subgraph: render_resume_html failed: ${e.message}"
            System.err.println("[tailor_subgraph] ERROR: $msg")
            input.copy(outputPath = outputPath, error = msg, tailoringDegradedNodes = degraded)
        }
    }

    /**
     * Run one tailoring node non-fatally: a thrown exception or a node-reported error is
     * logged and swallowed (error cleared) so the subgraph continues and still produces a
     * partially-tailored resume + report instead of abandoning it. On failure the base
     * content for that section is kept (buildTailoredProfile falls back to the profile).
     */
    private fun runNode(
        name: String,
        state: TailorState,
        degraded: MutableList<String>,
        block: (TailorState) -> TailorState,
    ): TailorState {
        val next = try {
            block(state)
        } catch (e: Exception) {
            System.err.println("[tailor_subgraph] WARN ($name): threw ${e.message} — keeping base content, continuing")
            degraded.add(name)
            return state
        }
        if (next.error.isNotEmpty()) {
            System.err.println("[tailor_subgraph] WARN ($name): ${next.error} — keeping base content, continuing")
            degraded.add(name)
            return next.copy(error = "")
        }
        return next
    }

    /**
     * One validation-driven refinement pass. Triggers when the first report is actionable
     * (missing supported must-haves, leaked unsupported terms, doubled words) or scores
     * below [Config.ATS_REFINE_THRESHOLD]. The rewrite nodes re-run with the concrete gap
     * list in their prompts (they read `state.atsReport`), then re-validate. Keeps
     * whichever pass scored higher; any refinement error is non-fatal and falls back to
     * the first-pass outputs.
     */
    private fun maybeRefine(initial: TailorState): TailorState {
        val report = initial.atsReport ?: return initial
        if (!Config.ATS_REFINE_ENABLED) return initial
        if (!report.needsRefinement && report.overallScore >= Config.ATS_REFINE_THRESHOLD) return initial

        println("[tailor_subgraph] Validation actionable (coverage=${report.mustHaveCoveragePct}%, " +
            "missing=${report.missingTerms.size}, leaked=${report.leakedUnsupportedTerms.size}, " +
            "overall=${report.overallScore}) — running refinement pass")

        // Non-fatal per node (mirrors the main pass): a failed rewrite keeps the first-pass
        // content for that section but does NOT abort the pass — so a summary that fails does
        // not stop skills_restructure from re-running to remove a leaked term.
        val stages: List<Pair<String, (TailorState) -> TailorState>> = listOf(
            "summary_rewrite" to summaryRewrite::process,
            "bullet_rewrite" to bulletRewrite::process,
            "bullet_reorder" to bulletReorder::process,
            "skills_restructure" to skillsRestructure::process,
            "ats_validation" to atsValidation::process,
        )
        var state = initial
        for ((name, run) in stages) {
            state = try {
                run(state).let { next ->
                    if (next.error.isEmpty()) next
                    else { System.err.println("[tailor_subgraph] WARN (refine/$name): ${next.error} — keeping prior content"); state }
                }
            } catch (e: Exception) {
                System.err.println("[tailor_subgraph] WARN (refine/$name): threw ${e.message} — keeping prior content")
                state
            }
        }

        val refined = state.atsReport ?: return initial
        return if (refined.overallScore >= report.overallScore) {
            println("[tailor_subgraph] Refinement improved: overall ${report.overallScore} → ${refined.overallScore}, " +
                "coverage ${report.mustHaveCoveragePct}% → ${refined.mustHaveCoveragePct}%")
            state
        } else {
            println("[tailor_subgraph] Refinement scored lower (${refined.overallScore} < ${report.overallScore}) — keeping first pass")
            initial
        }
    }

    /**
     * Build a [TailoredProfile] from the subgraph's outputs. Falls back to the
     * corresponding field on the base profile when a tailored field is null
     * (e.g. when skills_restructure failed non-fatally).
     */
    private fun buildTailoredProfile(profile: com.jd.pipeline.models.CandidateProfile, state: TailorState): TailoredProfile {
        val skillGroups = state.restructuredSkills?.groupedByCategory
            ?.takeIf { it.isNotEmpty() }
            ?: TailoredProfile.untailored(profile).skillGroups
        val jdMatched = state.restructuredSkills?.jdMatchedSkills ?: emptyList()
        return TailoredProfile(
            base = profile,
            summary = state.tailoredSummary?.takeIf { it.isNotBlank() } ?: profile.background.summary,
            careerHistory = state.tailoredCareerHistory ?: profile.background.careerHistory,
            projects = state.tailoredProjects ?: profile.projects,
            skillGroups = skillGroups,
            jdMatchedSkills = jdMatched
        )
    }

    private fun writeUntailoredHtml(outputDir: Path, profile: com.jd.pipeline.models.CandidateProfile) {
        try {
            val html = ResumeHtmlRenderer.render(profile)
            Files.writeString(outputDir.resolve("tailored_resume.html"), html)
            println("[tailor_subgraph] Wrote untailored render of candidate profile (no JD content to tailor against)")
        } catch (e: Exception) {
            System.err.println("[tailor_subgraph] WARN: failed to write untailored tailored_resume.html: ${e.message}")
        }
    }

    private fun saveOutputFiles(outputDir: Path, state: TailorState) {
        // tailored_summary.txt
        state.tailoredSummary?.let { summary ->
            writeQuietly(outputDir.resolve("tailored_summary.txt"), summary)
        }

        // tailored_bullets.txt — human-readable rewrite diagnostics
        state.tailoredBullets?.let { bullets ->
            val content = buildString {
                bullets.forEach { b ->
                    appendLine("ORIGINAL:   ${b.original}")
                    appendLine("REWRITTEN:  ${b.rewritten}")
                    appendLine("MUST-HAVES: ${b.mustHaveHits.joinToString(", ").ifBlank { "(none)" }}")
                    appendLine("QUANTIFIED: ${b.quantified}   SENIORITY SIGNAL: ${b.senioritySignal}")
                    appendLine()
                }
            }
            writeQuietly(outputDir.resolve("tailored_bullets.txt"), content)
        }

        // restructured_skills.txt — human-readable render of the groups
        state.restructuredSkills?.let { skills ->
            val content = buildString {
                skills.groupedByCategory.forEach { (label, items) ->
                    appendLine("$label: ${items.joinToString(", ")}")
                }
                if (skills.removedForThisRole.isNotEmpty()) {
                    appendLine()
                    appendLine("Removed for this role: ${skills.removedForThisRole.joinToString(", ")}")
                }
            }
            writeQuietly(outputDir.resolve("restructured_skills.txt"), content)
        }

        // ats_report.txt — the actionable validation scorecard
        state.atsReport?.let { report ->
            val content = buildString {
                appendLine("ATS Validation Report")
                appendLine("=====================")
                appendLine("Overall Score:        ${report.overallScore}/100")
                appendLine("Must-Have Coverage:   ${report.mustHaveCoveragePct}%")
                appendLine("Seniority Alignment:  ${report.seniorityAlignment}/100")
                appendLine("Quantification:       ${report.quantification}/100")
                appendLine("Format Safety:        ${report.formatSafety}/100")
                if (report.missingTerms.isNotEmpty()) {
                    appendLine()
                    appendLine("Supported must-haves NOT yet placed (actionable):")
                    report.missingTerms.forEach { appendLine("  - $it") }
                }
                if (report.unreachableTerms.isNotEmpty()) {
                    appendLine()
                    appendLine("Must-haves the candidate cannot claim (no resume evidence):")
                    report.unreachableTerms.forEach { appendLine("  - $it") }
                }
                if (report.leakedUnsupportedTerms.isNotEmpty()) {
                    appendLine()
                    appendLine("INTEGRITY: unsupported terms leaked into the output:")
                    report.leakedUnsupportedTerms.forEach { appendLine("  - $it") }
                }
                if (report.doubledWords.isNotEmpty()) {
                    appendLine()
                    appendLine("Doubled words found: ${report.doubledWords.joinToString(", ")}")
                }
                if (report.styleWarnings.isNotEmpty()) {
                    appendLine()
                    appendLine("Style warnings (skim readability):")
                    report.styleWarnings.forEach { appendLine("  - $it") }
                }
                if (report.topImprovements.isNotEmpty()) {
                    appendLine()
                    appendLine("Top improvements:")
                    report.topImprovements.forEach { appendLine("  - $it") }
                }
            }
            writeQuietly(outputDir.resolve("ats_report.txt"), content)
        }

        // gap_analysis.json — machine-readable for downstream tooling
        state.gapAnalysis?.let { gap ->
            try {
                writeQuietly(
                    outputDir.resolve("gap_analysis.json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(gap)
                )
            } catch (e: Exception) {
                System.err.println("[tailor_subgraph] WARN: failed to serialise gap_analysis.json: ${e.message}")
            }
        }
    }

    private fun writeQuietly(path: Path, content: String) {
        try {
            Files.writeString(path, content)
        } catch (e: Exception) {
            System.err.println("[tailor_subgraph] WARN: failed to save ${path.fileName}: ${e.message}")
        }
    }
}
