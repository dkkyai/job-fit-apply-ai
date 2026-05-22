package com.jd.pipeline.pipeline

import com.jd.pipeline.nodes.*
import com.jd.pipeline.nodes.tailor.ResumeTailoringSubgraph
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.PipelineAction
import com.jd.pipeline.state.emailIntake
import com.jd.pipeline.state.isDigest
import com.jd.pipeline.state.isFromEmail
import com.jd.pipeline.state.isRecruiterEmail
import com.jd.pipeline.utils.MetadataUtils

/**
 * Pipeline graph with routing logic.
 *
 * Mermaid flowchart:
 * ```mermaid
 * flowchart TD
 *     Start(["Input Email"]) --> Scan["ScanEmailNode"]
 *     JSearch(["JSearch API"]) --> JSearchState["JSearchClient.toJDState\n(full JD text, salary, location)"]
 *     JSearchState --> ScoreRoute
 *
 *     Scan --> ScanData["Visible + hidden email data may provide partial JD fields"]
 *     Scan --> JobBoard{"Job board digest?"}
 *     JobBoard -->|Yes| ExtractLinks["Extract digest jobs from email\n(company/title/location/url/partial JD)"]
 *     JobBoard -->|No| RecruiterPosting{"Specific recruiter job posting?"}
 *
 *     RecruiterPosting -->|No| SaveNonJob["SaveJobDescriptionNode"]
 *     SaveNonJob --> EndNonJob(["End"])
 *     RecruiterPosting -->|Yes| ScrapeSingle["ScrapeJdNode"]
 *     ScrapeSingle --> ScrapeOutcome{"Scrape returned page content?"}
 *
 *     ExtractLinks --> ChildLoop["Process each digest child"]
 *     ChildLoop --> ChildScrape["ScrapeJdNode\nHTTP for most boards\nPlaywright for LinkedIn"]
 *     ChildScrape --> ChildOutcome{"Scrape succeeded?"}
 *     ChildOutcome -->|Yes| ChildSave["Use scraped JD data\noverwrite scan JD text"]
 *     ChildOutcome -->|No| ChildSaveFallback["Keep partial email-scan JD data"]
 *     ChildSave --> ScoreRoute
 *     ChildSaveFallback --> ScoreRoute
 *
 *     ScrapeOutcome -->|Yes| SaveSingle["Use scraped JD data\noverwrite scan JD text"]
 *     ScrapeOutcome -->|No| SaveSingleFallback["Keep partial email-scan JD data"]
 *     SaveSingleFallback --> ScoreRoute
 *     SaveSingle --> ScoreRoute
 *
 *     subgraph ScoreRoute ["Score and Route"]
 *         direction TB
 *         Duplicate["CheckDuplicateNode"] --> DupDecision{"duplicate?"}
 *         DupDecision -->|Yes| TrackOnly["SupabaseTrackNode"]
 *         DupDecision -->|No| Score["ScoreFitNode"]
 *         Score --> ActionDecision{"action != tailor?"}
 *         ActionDecision -->|Yes| TrackOnly
 *         ActionDecision -->|No| Tailor["ResumeTailoringSubgraph"]
 *
 *         subgraph Tailor ["ResumeTailoringSubgraph (6-node internal pipeline)"]
 *             direction TB
 *             T1["JdExtractionNode"] --> T2["GapAnalysisNode"]
 *             T2 --> T3["SummaryRewriteNode"]
 *             T3 --> T4["BulletRewriteNode"]
 *             T4 --> T5["SkillsRestructureNode"]
 *             T5 --> T6["AtsScoringNode"]
 *         end
 *
 *         Tailor --> Cover["GenerateCoverLetterNode"]
 *         Cover --> Render["RenderResumePdfNode"]
 *         Render --> Artifact["AddArtifactUrlNode"]
 *         Artifact --> TrackTailor["SupabaseTrackNode"]
 *         TrackOnly --> ReplyDecision{"Recruiter email\nand action == tailor?"}
 *         TrackTailor --> ReplyDecision
 *         ReplyDecision -->|Yes| Draft["CreateDraftReplyNode"]
 *         ReplyDecision -->|No| RouteEnd(["End"])
 *         Draft --> RouteEnd
 *     end
 * ```
 */
class JDPipeline {

    private val nodes = mapOf(
        "scan_jd" to ScanEmailNode(),
        "scrape_jd" to ScrapeJdNode(),
        "save_jd" to SaveJobDescriptionNode(),
        "score_fit" to ScoreFitNode(),
        "check_duplicate" to CheckDuplicateNode(),
        "tailor_resume" to ResumeTailoringSubgraph(),
        "generate_cover_letter" to GenerateCoverLetterNode(),
        "render_resume_pdf" to RenderResumePdfNode(),
        "add_artifact_url" to AddArtifactUrlNode(),
        "supabase_track" to SupabaseTrackNode(),
        "create_draft_reply" to CreateDraftReplyNode()
    )

    /**
     * Run the pipeline on an input state.
     */
    fun invoke(input: JDState): JDState {
        return try {
            invokeInternal(input)
        } catch (e: Exception) {
            System.err.println("[pipeline] ERROR: ${e.message}")
            input.copy(error = e.message ?: "Pipeline node failed")
        }
    }

    /**
     * Internal pipeline execution without top-level error handling.
     */
    private fun invokeInternal(input: JDState): JDState {
        // JSearch-sourced states already have full JD text — skip scan, scrape, and save.
        if (input.intake !is IntakeContext.Email) {
            return scoreAndRoute(input)
        }

        // Start with scan_jd
        var current = nodes["scan_jd"]?.process(input) ?: input

        // Digest emails (job board): each child carries email-scan fields and gets scraped later here.
        if (current.isDigest) {
            val digestJobs = current.digestJobs
            if (digestJobs.isNotEmpty()) {
                var processed = 0
                val processedDigestJobs = mutableListOf<JDState>()
                for (digestJob in digestJobs) {
                    if (!digestJob.isJobPosting) continue
                    val result = processDigestJob(digestJob)
                    processedDigestJobs.add(result)
                    processed++
                }
                println("[pipeline] Digest complete — $processed processed")
                return current.copy(digestJobs = processedDigestJobs)
            }
            return current
        }

        // Non-digest path
        if (!current.isJobPosting) {
            current = nodes["save_jd"]?.process(current) ?: current
            return current
        }

        // Single job: use email-scan fields as fallback, but let a successful scrape replace JD text and enrich fields.
        current = nodes["scrape_jd"]?.process(current) ?: current
        current = nodes["save_jd"]?.process(current) ?: current
        return scoreAndRoute(current)
    }

    /**
     * Run a digest child through scrape → save → score → check_duplicate → tailor/track.
     * A successful scrape overwrites email-derived JD text. If scrape fails, the email-scan data survives.
     */
    private fun processDigestJob(job: JDState): JDState {
        var current = nodes["scrape_jd"]?.process(job) ?: job
        current = nodes["save_jd"]?.process(current) ?: current
        return scoreAndRoute(current)
    }

    /**
     * check_duplicate → (skip score_fit if duplicate) → score_fit → tailor or track,
     * then optionally draft reply.
     */
    private fun scoreAndRoute(input: JDState): JDState {
        var current = nodes["check_duplicate"]?.process(input) ?: input

        if (current.isDuplicate && !current.isRecruiterEmail) {
            current = nodes["supabase_track"]?.process(current) ?: current
            return routeAfterSupabase(current)
        }

        current = nodes["score_fit"]?.process(current) ?: current

        // Recruiter emails with a JD always get the full tailor treatment regardless of fit score.
        if (current.isRecruiterEmail && current.pipelineAction != PipelineAction.TAILOR) {
            current = current.copy(pipelineAction = PipelineAction.TAILOR, skippedReason = "")
        }

        if (current.pipelineAction != PipelineAction.TAILOR) {
            current = nodes["supabase_track"]?.process(current) ?: current
            return routeAfterSupabase(current)
        }

        current = nodes["tailor_resume"]?.process(current) ?: current
        if (current.error.isNotEmpty()) {
            System.err.println("[pipeline] tailor failed: ${current.error}")
            // Skip PDF render and artifact URL when tailoring failed, but still track and draft reply
            current = nodes["supabase_track"]?.process(current) ?: current
            return routeAfterSupabase(current)
        }

        current = nodes["generate_cover_letter"]?.process(current) ?: current
        current = nodes["render_resume_pdf"]?.process(current) ?: current
        current = nodes["add_artifact_url"]?.process(current) ?: current
        current = nodes["supabase_track"]?.process(current) ?: current
        return routeAfterSupabase(current)
    }

    /**
     * Route after supabase_track:
     * - if is_recruiter_email (any fit score) → create_draft_reply → END
     * - else → END
     */
    private fun routeAfterSupabase(current: JDState): JDState {
        val result = if (current.isRecruiterEmail && current.emailIntake?.emailId?.isNotBlank() == true) {
            nodes["create_draft_reply"]?.process(current) ?: current
        } else {
            current
        }

        return if (result.isJobPosting || !result.isFromEmail) {
            val metadataUrl = MetadataUtils.writeMetadata(result)
            if (metadataUrl.isNotEmpty()) result.copy(metadataUrl = metadataUrl) else result
        } else {
            result
        }
    }

    /**
     * Route after scan node.
     */
    fun routeAfterScan(state: JDState): String? {
        return if (state.isJobPosting) "scrape_jd" else null
    }

    /**
     * Resets batch-level scrape state (blocked domains, LinkedIn session flag).
     * Called automatically on construction since nodes are fresh per pipeline instance,
     * but exposed explicitly for clarity and testability.
     */
    fun resetBatch() {
        (nodes["scrape_jd"] as? ScrapeJdNode)?.resetBatch()
    }

    /** Domains that were blocked (403/CAPTCHA/auth-gated) during this batch. */
    fun batchBlockedDomains(): Set<String> =
        (nodes["scrape_jd"] as? ScrapeJdNode)?.batchBlockedDomains?.toSet() ?: emptySet()

    /** Whether a LinkedIn auth failure was detected during this batch. */
    fun batchLinkedInSessionExpired(): Boolean =
        (nodes["scrape_jd"] as? ScrapeJdNode)?.batchLinkedInSessionExpired ?: false

}
