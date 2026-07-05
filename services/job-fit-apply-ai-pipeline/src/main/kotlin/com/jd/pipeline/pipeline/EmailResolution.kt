package com.jd.pipeline.pipeline

import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.isDigest
import com.jd.pipeline.state.isInlineDigest

/**
 * What to do with a raw email after ingestion (scan → scrape). Pure decision, no I/O —
 * the caller ([WorkerCommandHandler.resolveEmail]) performs the bridge writes. Extracted
 * so the digest / not-a-job / process branching is testable without a bridge or Gmail.
 */
sealed interface EmailDisposition {
    /** Digest: fan out — re-enqueue each job-posting child as its own JD_SCRAPED item. */
    data class ReEnqueueChildren(val children: List<JDState>) : EmailDisposition

    /** Not a job posting — complete the item with no further processing. */
    object SkipNotJob : EmailDisposition

    /** A single job posting — hand off to the ProcessingPipeline. */
    object Process : EmailDisposition
}

object EmailResolution {
    /** Order matters — digest is checked before the single-posting path. */
    fun classify(ingested: JDState): EmailDisposition = when {
        ingested.isDigest || ingested.isInlineDigest ->
            EmailDisposition.ReEnqueueChildren(ingested.digestJobs.filter { it.isJobPosting })
        !ingested.isJobPosting -> EmailDisposition.SkipNotJob
        else                   -> EmailDisposition.Process
    }
}
