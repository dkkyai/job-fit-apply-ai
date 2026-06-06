package com.jd.pipeline.pipeline

import com.jd.pipeline.nodes.Node
import com.jd.pipeline.nodes.ScrapeJdNode
import com.jd.pipeline.source.IngestionSource
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("IngestionPipelineTest")
class IngestionPipelineTest {

    // ── reflection helpers ────────────────────────────────────────────────────

    private fun injectScanNode(pipeline: IngestionPipeline, node: Node<JDState>) {
        val f = IngestionPipeline::class.java.getDeclaredField("scanNode")
        f.isAccessible = true
        f.set(pipeline, node)
    }

    private fun injectSaveNode(pipeline: IngestionPipeline, node: Node<JDState>) {
        val f = IngestionPipeline::class.java.getDeclaredField("saveNode")
        f.isAccessible = true
        f.set(pipeline, node)
    }

    // ── toJdRecord ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("toJdRecord maps state fields correctly")
    fun toJdRecordMapsFields() {
        val pipeline = IngestionPipeline()
        val state = JDState(
            jdText    = "full jd text",
            company   = "Acme Corp",
            roleTitle = "Staff SDET",
            location  = "Seattle, WA",
            jobUrl    = "https://example.com/job/1",
        )

        val record = pipeline.toJdRecord(state, idempotencyKey = "email-abc")

        assertEquals("full jd text",              record.jdText)
        assertEquals("Acme Corp",                 record.company)
        assertEquals("Staff SDET",                record.roleTitle)
        assertEquals("Seattle, WA",               record.location)
        assertEquals("https://example.com/job/1", record.jobUrl)
        assertEquals("email-abc",                 record.idempotencyKey)
        assertEquals(IngestionSource.EMAIL,        record.source)
    }

    @Test
    @DisplayName("toJdRecord maps blank string fields to null")
    fun toJdRecordBlanksToNull() {
        val pipeline = IngestionPipeline()
        val state = JDState(
            jdText    = "some jd",
            company   = "",
            roleTitle = "   ",
            location  = "",
            jobUrl    = "",
        )

        val record = pipeline.toJdRecord(state)

        assertNull(record.company)
        assertNull(record.roleTitle)
        assertNull(record.location)
        assertNull(record.jobUrl)
        assertNull(record.idempotencyKey)
    }

    @Test
    @DisplayName("toJdRecord with null idempotencyKey leaves it null")
    fun toJdRecordNullKeyIsNull() {
        val pipeline = IngestionPipeline()
        val state = JDState(jdText = "jd", company = "Acme")
        val record = pipeline.toJdRecord(state, idempotencyKey = null)
        assertNull(record.idempotencyKey)
    }

    // ── invoke routing ────────────────────────────────────────────────────────

    @Test
    @DisplayName("invoke routes non-job-posting through saveNode and never calls scrape")
    fun invokeNonJobPostingSkipsScrape() {
        val pipeline = IngestionPipeline()

        injectScanNode(pipeline, Node { state -> state.copy(isJobPosting = false) })

        val mockScrape = mock<ScrapeJdNode>()
        pipeline.scrapeNode = mockScrape

        var saveCalled = false
        injectSaveNode(pipeline, Node { state -> saveCalled = true; state })

        val result = pipeline.invoke(JDState(isJobPosting = true))

        assertFalse(result.isJobPosting)
        assertTrue(saveCalled,  "saveNode should be called for non-job-postings")
        verify(mockScrape, never()).process(any())
    }

    @Test
    @DisplayName("invoke routes job posting through scrape and save")
    fun invokeSingleJobPostingRunsScrapeAndSave() {
        val pipeline = IngestionPipeline()

        injectScanNode(pipeline, Node { state ->
            state.copy(isJobPosting = true, jdText = "original jd")
        })

        val mockScrape = mock<ScrapeJdNode>()
        whenever(mockScrape.process(any())).doAnswer { inv ->
            (inv.arguments[0] as JDState).copy(jdText = "scraped content")
        }
        whenever(mockScrape.batchBlockedDomains).thenReturn(mutableSetOf())
        whenever(mockScrape.batchLinkedInSessionExpired).thenReturn(false)
        pipeline.scrapeNode = mockScrape

        var saveCalled = false
        injectSaveNode(pipeline, Node { state -> saveCalled = true; state })

        val result = pipeline.invoke(JDState(isJobPosting = false))

        assertTrue(result.isJobPosting)
        assertTrue(saveCalled, "saveNode should be called for job postings")
        verify(mockScrape).process(any())
        assertEquals("scraped content", result.jdText)
    }

    @Test
    @DisplayName("invoke processes only isJobPosting children of a digest email")
    fun invokeDigestProcessesJobPostingChildrenOnly() {
        val pipeline = IngestionPipeline()

        val jobChild    = JDState(isJobPosting = true,  jdText = "child jd")
        val nonJobChild = JDState(isJobPosting = false, jdText = "not a job")

        injectScanNode(pipeline, Node { state ->
            state.copy(isJobPosting = true, digestJobs = listOf(jobChild, nonJobChild))
        })

        val mockScrape = mock<ScrapeJdNode>()
        whenever(mockScrape.process(any())).doAnswer { inv -> inv.arguments[0] as JDState }
        whenever(mockScrape.batchBlockedDomains).thenReturn(mutableSetOf())
        whenever(mockScrape.batchLinkedInSessionExpired).thenReturn(false)
        pipeline.scrapeNode = mockScrape
        injectSaveNode(pipeline, Node { state -> state })

        val input = JDState(
            intake = IntakeContext.Email(
                emailId        = "digest-001",
                subject        = "Daily Digest",
                from           = "noreply@jobright.ai",
                rawBody        = "",
                htmlBody       = "",
                isRecruiter    = false,
                isDigest       = true,
                isInlineDigest = false,
            ),
        )
        val result = pipeline.invoke(input)

        assertEquals(1, result.digestJobs.size, "only the isJobPosting child should be processed")
        assertTrue(result.digestJobs.first().isJobPosting)
    }
}
