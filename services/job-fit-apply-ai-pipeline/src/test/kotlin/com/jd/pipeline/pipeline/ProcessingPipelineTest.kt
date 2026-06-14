package com.jd.pipeline.pipeline

import com.jd.pipeline.nodes.CheckDuplicateNode
import com.jd.pipeline.nodes.Node
import com.jd.pipeline.source.IngestionSource
import com.jd.pipeline.source.JdRecord
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DisplayName("ProcessingPipelineTest")
class ProcessingPipelineTest {

    @BeforeEach
    fun setup() {
        CheckDuplicateNode.resetFallback()
    }

    private fun minimalRecord(jdText: String = "x".repeat(200)) = JdRecord(
        jdText     = jdText,
        company    = "Acme Corp",
        roleTitle  = "Staff SDET",
        location   = "Seattle, WA",
        jobUrl     = null,
        source     = IngestionSource.EMAIL,
    )

    private fun recruiterRecord() = minimalRecord().copy(
        intakeMeta = com.jd.pipeline.source.IntakeContext.Email(
            emailId = "e1", from = "rec@firm.com", subject = "Great role for you",
            rawBody = "body", htmlBody = "",
            isRecruiter = true, isDigest = false, isInlineDigest = false,
        ),
    )

    private fun injectNode(pipeline: ProcessingPipeline, fieldName: String, node: Node<JDState>) {
        val field = ProcessingPipeline::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(pipeline, node)
    }

    @Test
    @DisplayName("invoke returns SKIP with error when checkDuplicate throws")
    fun invokeCatchesCheckDuplicateException() {
        val pipeline = ProcessingPipeline()
        injectNode(pipeline, "checkDuplicate", Node { _ ->
            throw RuntimeException("simulated checkDuplicate failure")
        })

        val result = pipeline.invoke(minimalRecord())

        assertEquals("SKIP", result.pipelineAction)
        assertEquals(0, result.fitScore)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("simulated checkDuplicate failure"))
    }

    @Test
    @DisplayName("invoke returns SKIP with error when scoreFit throws")
    fun invokeCatchesScoreFitException() {
        val pipeline = ProcessingPipeline()
        // checkDuplicate must pass first (return non-duplicate state)
        injectNode(pipeline, "checkDuplicate", Node { state -> state.copy(isDuplicate = false) })
        injectNode(pipeline, "scoreFit", Node { _ ->
            throw RuntimeException("simulated scoreFit failure")
        })

        val result = pipeline.invoke(minimalRecord())

        assertEquals("SKIP", result.pipelineAction)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("simulated scoreFit failure"))
    }

    @Test
    @DisplayName("invoke returns SKIP when job is duplicate and not a recruiter email")
    fun invokeSkipsDuplicateNonRecruiter() {
        val pipeline = ProcessingPipeline()
        injectNode(pipeline, "checkDuplicate", Node { state ->
            state.copy(isDuplicate = true)
        })
        injectNode(pipeline, "supabaseTrack", Node { state -> state })

        val result = pipeline.invoke(minimalRecord())

        assertTrue(result.isDuplicate)
    }

    @Test
    @DisplayName("invoke returns error result when tailor subgraph fails")
    fun invokeHandlesTailorSubgraphError() {
        val pipeline = ProcessingPipeline()
        injectNode(pipeline, "checkDuplicate", Node { state -> state.copy(isDuplicate = false) })
        injectNode(pipeline, "scoreFit", Node { state ->
            state.copy(
                pipelineAction = com.jd.pipeline.state.PipelineAction.TAILOR,
                fitScore = 90f,
            )
        })
        injectNode(pipeline, "tailorSubgraph", Node { state ->
            state.copy(error = "tailor subgraph failed")
        })
        injectNode(pipeline, "supabaseTrack", Node { state -> state })

        val result = pipeline.invoke(minimalRecord())

        // tailor error surfaces in the result
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("tailor subgraph failed"))
    }

    @Test
    @DisplayName("recruiter email is forced to TAILOR even when scoreFit returns SKIP")
    fun invokeForcesTailorForRecruiterLowScore() {
        val pipeline = ProcessingPipeline()
        var tailorCalled = false
        injectNode(pipeline, "checkDuplicate", Node { state -> state.copy(isDuplicate = false) })
        injectNode(pipeline, "scoreFit", Node { state ->
            state.copy(pipelineAction = com.jd.pipeline.state.PipelineAction.SKIP, fitScore = 20f)
        })
        injectNode(pipeline, "tailorSubgraph", Node { state ->
            tailorCalled = true
            state.copy(error = "stop after tailor") // short-circuit before LLM cover-letter/PDF nodes
        })
        injectNode(pipeline, "supabaseTrack", Node { state -> state })

        pipeline.invoke(recruiterRecord())

        // The recruiter override (ProcessingPipeline lines 81-83) must route a low-score
        // recruiter email through the tailor node anyway.
        assertTrue(tailorCalled, "recruiter email must be tailored even on a low fit score")
    }

    @Test
    @DisplayName("recruiter email is scored + tailored even when it is a duplicate")
    fun invokeDoesNotSkipDuplicateRecruiter() {
        val pipeline = ProcessingPipeline()
        var tailorCalled = false
        injectNode(pipeline, "checkDuplicate", Node { state -> state.copy(isDuplicate = true) })
        injectNode(pipeline, "scoreFit", Node { state ->
            state.copy(pipelineAction = com.jd.pipeline.state.PipelineAction.SKIP, fitScore = 20f)
        })
        injectNode(pipeline, "tailorSubgraph", Node { state ->
            tailorCalled = true
            state.copy(error = "stop after tailor")
        })
        injectNode(pipeline, "supabaseTrack", Node { state -> state })

        pipeline.invoke(recruiterRecord())

        // The duplicate guard (line 72) excludes recruiters, so a duplicate recruiter
        // email still proceeds through score + tailor.
        assertTrue(tailorCalled, "duplicate recruiter email must still be scored and tailored")
    }

    @Test
    @DisplayName("invoke maps blank outputPath to null in result")
    fun invokeOutputPathNullWhenBlank() {
        val pipeline = ProcessingPipeline()
        injectNode(pipeline, "checkDuplicate", Node { state -> state.copy(isDuplicate = false) })
        injectNode(pipeline, "scoreFit", Node { state ->
            state.copy(
                pipelineAction = com.jd.pipeline.state.PipelineAction.SKIP,
                fitScore = 30f,
            )
        })
        injectNode(pipeline, "supabaseTrack", Node { state -> state })

        val result = pipeline.invoke(minimalRecord())

        assertEquals(null, result.outputPath)
    }
}
