package com.jd.pipeline.nodes
import com.jd.pipeline.state.PipelineAction

import com.jd.pipeline.fixtures.TestJDStateFactory
import com.jd.pipeline.nodes.tailor.ResumeTailoringSubgraph
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.emailIntake
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Unit tests for ResumeTailoringSubgraph.
 *
 * These tests cover guard conditions and pass-through behaviour only.
 * Full integration tests (requiring Ollama and a resume file) are excluded from CI.
 */
class ResumeTailoringSubgraphTest {

    private val subgraph = ResumeTailoringSubgraph()

    @Test
    fun shouldPassThroughWhenPipelineActionIsSkip() {
        val input = TestJDStateFactory.createLowScoredState() // pipelineAction = PipelineAction.SKIP

        val result = subgraph.process(input)

        // No tailoring should occur — same object returned
        assertSame(input, result)
    }

    @Test
    fun shouldPassThroughWhenPipelineActionIsNotTailor() {
        val input = JDState(
            isJobPosting = true,
            company = "Acme",
            roleTitle = "Staff SDET",
            jdText = "Some JD text",
            pipelineAction = PipelineAction.SKIP
        )

        val result = subgraph.process(input)

        assertSame(input, result)
    }

    @Test
    fun shouldHandleSubgraphInvocationWithoutCrashing() {
        // The subgraph reads candidateProfile from JDState and renders tailored_resume.html
        // via GenerateResumeHtmlNode.renderFromProfile. In a bare CI environment without an
        // LLM backend, the call may surface an error state — either outcome is acceptable
        // here; the test exists only to confirm the entry point preserves the JDState shape.
        val input = TestJDStateFactory.createHighScoredState().copy(
            jdText = "Staff SDET role requiring XCUITest, Espresso, and Bitrise CI/CD experience."
        )

        val result = subgraph.process(input)

        // Result is always a valid JDState
        assertEquals(input.company, result.company)
        assertEquals(input.roleTitle, result.roleTitle)
        assertEquals(input.fitScore, result.fitScore)
        assertEquals(input.pipelineAction, result.pipelineAction)
    }

    @Test
    fun shouldPreserveAllInputFieldsOnPassThrough() {
        val input = TestJDStateFactory.createHighScoredState().copy(
            pipelineAction = PipelineAction.SKIP
        )

        val result = subgraph.process(input)

        assertEquals(input.emailIntake?.emailId, result.emailIntake?.emailId)
        assertEquals(input.company, result.company)
        assertEquals(input.roleTitle, result.roleTitle)
        assertEquals(input.fitScore, result.fitScore)
        assertEquals(input.strengths, result.strengths)
        assertEquals(input.gaps, result.gaps)
    }
}
