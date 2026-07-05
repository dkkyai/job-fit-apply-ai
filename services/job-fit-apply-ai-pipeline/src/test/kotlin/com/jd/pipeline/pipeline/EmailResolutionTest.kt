package com.jd.pipeline.pipeline

import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The post-ingestion routing decision for a raw email: digest fans out, non-job is skipped,
 * a single posting is processed. Pure — no bridge / Gmail. Mirrors [TerminalLabelTest] in style.
 */
class EmailResolutionTest {

    private fun email(isDigest: Boolean = false, isInlineDigest: Boolean = false) =
        IntakeContext.Email(
            emailId = "e1", subject = "s", from = "f@x.com", rawBody = "b", htmlBody = "",
            isRecruiter = false, isDigest = isDigest, isInlineDigest = isInlineDigest,
        )

    @Test
    fun `single job posting is processed`() {
        val s = JDState(isJobPosting = true, intake = email())
        assertEquals(EmailDisposition.Process, EmailResolution.classify(s))
    }

    @Test
    fun `non-job email is skipped`() {
        val s = JDState(isJobPosting = false, intake = email())
        assertEquals(EmailDisposition.SkipNotJob, EmailResolution.classify(s))
    }

    @Test
    fun `digest re-enqueues only its job-posting children`() {
        val children = listOf(
            JDState(isJobPosting = true, company = "A"),
            JDState(isJobPosting = false, company = "B"),   // filtered out
            JDState(isJobPosting = true, company = "C"),
        )
        val s = JDState(isJobPosting = false, intake = email(isDigest = true), digestJobs = children)

        val disposition = EmailResolution.classify(s)
        assertTrue(disposition is EmailDisposition.ReEnqueueChildren)
        val names = (disposition as EmailDisposition.ReEnqueueChildren).children.map { it.company }
        assertEquals(listOf("A", "C"), names)
    }

    @Test
    fun `inline digest is treated as a digest fan-out`() {
        val s = JDState(
            isJobPosting = true,   // inline digests can look like a posting; digest check wins
            intake = email(isInlineDigest = true),
            digestJobs = listOf(JDState(isJobPosting = true, company = "A")),
        )
        assertTrue(EmailResolution.classify(s) is EmailDisposition.ReEnqueueChildren)
    }

    @Test
    fun `digest with no job-posting children yields an empty re-enqueue`() {
        val s = JDState(isJobPosting = false, intake = email(isDigest = true), digestJobs = emptyList())
        val disposition = EmailResolution.classify(s)
        assertTrue(disposition is EmailDisposition.ReEnqueueChildren)
        assertTrue((disposition as EmailDisposition.ReEnqueueChildren).children.isEmpty())
    }
}
