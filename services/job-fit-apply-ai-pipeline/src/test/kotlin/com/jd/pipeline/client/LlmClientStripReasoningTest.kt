package com.jd.pipeline.client

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Guards [LlmClient.stripReasoning], which removes chain-of-thought from LLM output before it
 * reaches prose consumers such as the recruiter draft reply. Regression: Qwen3.5 emits
 * `<thinking>…</thinking>` (not `<think>`), which the old strip regex missed, so the entire
 * reasoning block leaked into the Gmail draft.
 */
@DisplayName("LlmClient.stripReasoning")
class LlmClientStripReasoningTest {

    @Test
    @DisplayName("strips <thinking> blocks (Qwen3.5 — the reported bug)")
    fun stripsThinkingTag() {
        val raw = "<thinking>Let me consider the recruiter's questions…</thinking>\n\nHi Jordan, thanks for reaching out."
        assertEquals("Hi Jordan, thanks for reaching out.", LlmClient.stripReasoning(raw))
    }

    @Test
    @DisplayName("strips <think> blocks (DeepSeek-R1 / MiniMax)")
    fun stripsThinkTag() {
        val raw = "<think>reasoning here</think>Best regards,\nAlex"
        assertEquals("Best regards,\nAlex", LlmClient.stripReasoning(raw))
    }

    @Test
    @DisplayName("strips <reasoning> blocks")
    fun stripsReasoningTag() {
        assertEquals("Answer.", LlmClient.stripReasoning("<reasoning>x</reasoning>\nAnswer."))
    }

    @Test
    @DisplayName("is case-insensitive and tolerant of attributes/whitespace in the tag")
    fun caseAndAttributeInsensitive() {
        val raw = "< Thinking foo=\"bar\" >secret plan</ THINKING >\nVisible reply"
        assertEquals("Visible reply", LlmClient.stripReasoning(raw))
    }

    @Test
    @DisplayName("strips a multiline thinking block")
    fun stripsMultiline() {
        val raw = """
            <thinking>
            step 1
            step 2
            </thinking>
            Dear recruiter, I'm interested.
        """.trimIndent()
        assertEquals("Dear recruiter, I'm interested.", LlmClient.stripReasoning(raw))
    }

    @Test
    @DisplayName("handles an orphan closing tag (reasoning, close tag, then the answer)")
    fun stripsOrphanCloseTag() {
        val raw = "Okay, the candidate is a strong fit because…</think>\nHello, thank you for the opportunity."
        assertEquals("Hello, thank you for the opportunity.", LlmClient.stripReasoning(raw))
    }

    @Test
    @DisplayName("leaves clean prose untouched (only trimmed)")
    fun leavesCleanTextUntouched() {
        assertEquals("Hi, I'd love to chat.", LlmClient.stripReasoning("  Hi, I'd love to chat.  "))
    }

    @Test
    @DisplayName("does not strip an angle-bracket word that is not a reasoning tag")
    fun doesNotStripUnrelatedTags() {
        val raw = "I work with <thinktank> clients and React <div> components."
        assertEquals(raw, LlmClient.stripReasoning(raw))
    }

    @Test
    @DisplayName("strips multiple thinking blocks")
    fun stripsMultipleBlocks() {
        val raw = "<think>a</think>First.<think>b</think> Second."
        assertEquals("First. Second.", LlmClient.stripReasoning(raw))
    }

    @Test
    @DisplayName("empty input returns empty")
    fun emptyInput() {
        assertEquals("", LlmClient.stripReasoning(""))
    }
}
