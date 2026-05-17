package com.jd.pipeline.pipeline

import com.jd.pipeline.fixtures.TestJDStateFactory
import com.jd.pipeline.nodes.Node
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for JDPipeline error handling behavior.
 */
@DisplayName("JDPipelineErrorTest")
class JDPipelineErrorTest {

    @Test
    @DisplayName("invoke catches unhandled node exceptions and returns error state")
    fun invokeCatchesNodeExceptions() {
        val pipeline = JDPipeline()

        // Use reflection to replace the private nodes map with a mutable one containing a throwing node
        val nodesField = JDPipeline::class.java.getDeclaredField("nodes")
        nodesField.isAccessible = true

        val throwingNode = Node<JDState> { _ ->
            throw RuntimeException("Simulated node failure")
        }

        @Suppress("UNCHECKED_CAST")
        val mutableNodes = (nodesField.get(pipeline) as Map<String, Node<JDState>>).toMutableMap()
        mutableNodes["scan_jd"] = throwingNode
        nodesField.set(pipeline, mutableNodes)

        val input = TestJDStateFactory.createFullJobPostingState()

        val result = pipeline.invoke(input)

        assertTrue(result.error.isNotEmpty(), "Expected error to be set")
        assertEquals("Simulated node failure", result.error)
    }

    @Test
    @DisplayName("invoke returns input copy with error for JSearch-sourced failures")
    fun invokeReturnsErrorForJSearchFailures() {
        val pipeline = JDPipeline()

        val nodesField = JDPipeline::class.java.getDeclaredField("nodes")
        nodesField.isAccessible = true

        val throwingNode = Node<JDState> { _ ->
            throw RuntimeException("Score fit failure")
        }

        @Suppress("UNCHECKED_CAST")
        val mutableNodes = (nodesField.get(pipeline) as Map<String, Node<JDState>>).toMutableMap()
        mutableNodes["score_fit"] = throwingNode
        nodesField.set(pipeline, mutableNodes)

        val input = TestJDStateFactory.createJSearchSourcedState()

        val result = pipeline.invoke(input)

        assertTrue(result.error.isNotEmpty(), "Expected error to be set")
        assertEquals("Score fit failure", result.error)
    }
}
