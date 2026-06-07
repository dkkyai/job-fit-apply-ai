package com.jd.pipeline.utils

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("NodeTimer")
class NodeTimerTest {

    @BeforeEach
    fun reset() = NodeTimer.reset()

    @Nested
    @DisplayName("record and summary")
    inner class RecordAndSummary {

        @Test
        @DisplayName("single recording appears in summary")
        fun singleRecording() {
            NodeTimer.record("score_fit", 1500)
            val entries = NodeTimer.summary()
            val entry = entries.firstOrNull { it.displayName == "ScoreFit" }
            assertTrue(entry != null, "Expected ScoreFit in summary")
            assertEquals(1, entry.count)
            assertEquals(1.5, entry.avgSec, 0.001)
            assertEquals(1.5, entry.minSec, 0.001)
            assertEquals(1.5, entry.maxSec, 0.001)
        }

        @Test
        @DisplayName("multiple recordings accumulate correctly")
        fun multipleRecordings() {
            NodeTimer.record("score_fit", 1000)
            NodeTimer.record("score_fit", 3000)
            val entry = NodeTimer.summary().first { it.displayName == "ScoreFit" }
            assertEquals(2, entry.count)
            assertEquals(2.0, entry.avgSec, 0.001)
            assertEquals(1.0, entry.minSec, 0.001)
            assertEquals(3.0, entry.maxSec, 0.001)
        }

        @Test
        @DisplayName("nodes not recorded are absent from summary")
        fun absentNodesOmitted() {
            NodeTimer.record("score_fit", 1000)
            val keys = NodeTimer.summary().map { it.displayName }
            assertTrue("ScrapeJd" !in keys)
            assertTrue("CoverLetter" !in keys)
        }

        @Test
        @DisplayName("summary returns entries in pipeline display order")
        fun displayOrder() {
            NodeTimer.record("cover_letter", 1000)
            NodeTimer.record("score_fit", 1000)
            NodeTimer.record("scrape_jd", 1000)
            val names = NodeTimer.summary().map { it.displayName }
            val scrapeIdx = names.indexOf("ScrapeJd")
            val scoreIdx  = names.indexOf("ScoreFit")
            val coverIdx  = names.indexOf("CoverLetter")
            assertTrue(scrapeIdx < scoreIdx, "ScrapeJd should come before ScoreFit")
            assertTrue(scoreIdx  < coverIdx, "ScoreFit should come before CoverLetter")
        }

        @Test
        @DisplayName("unknown node keys are excluded from summary output")
        fun unknownNodeIgnored() {
            NodeTimer.record("not_a_real_node", 500)
            val names = NodeTimer.summary().map { it.displayName }
            assertTrue("not_a_real_node" !in names)
        }
    }

    @Nested
    @DisplayName("reset")
    inner class Reset {

        @Test
        @DisplayName("clears all recorded samples")
        fun clearsAllSamples() {
            NodeTimer.record("score_fit", 1000)
            NodeTimer.record("cover_letter", 2000)
            NodeTimer.reset()
            assertTrue(NodeTimer.summary().isEmpty())
        }

        @Test
        @DisplayName("records after reset are independent")
        fun recordsAfterResetAreIndependent() {
            NodeTimer.record("score_fit", 9999)
            NodeTimer.reset()
            NodeTimer.record("score_fit", 500)
            val entry = NodeTimer.summary().first { it.displayName == "ScoreFit" }
            assertEquals(1, entry.count)
            assertEquals(0.5, entry.avgSec, 0.001)
        }
    }
}
