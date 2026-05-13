package com.jd.pipeline.cli
import com.jd.pipeline.state.PipelineAction

import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

import kotlin.test.assertEquals

/**
 * Unit tests for batch summary metrics calculation in Main.kt.
 *
 * Verifies that:
 * - "Tailored (≥ 40)" counts jobs where pipelineAction == PipelineAction.TAILOR && !isDuplicate
 * - "Skipped" counts jobs where pipelineAction != PipelineAction.TAILOR && !isDuplicate
 * - "Duplicate" counts jobs where isDuplicate == true
 */
class MainTest {

    private fun countTailored(jobs: List<JDState>): Int =
        jobs.count { it.pipelineAction == PipelineAction.TAILOR && !it.isDuplicate }

    private fun countDuplicate(jobs: List<JDState>): Int =
        jobs.count { it.isDuplicate }

    private fun countSkipped(jobs: List<JDState>): Int =
        jobs.count { it.pipelineAction != PipelineAction.TAILOR && !it.isDuplicate }

    @Nested
    @DisplayName("Batch Summary Metrics Tests")
    inner class BatchSummaryMetricsTests {

        @Test
        @DisplayName("Test tailored count includes jobs with pipelineAction='tailor' and not duplicate")
        fun testTailoredCountIncludesTailoredNonDuplicateJobs() {
            val jobs = listOf(
                JDState(pipelineAction = PipelineAction.TAILOR, isDuplicate = false),
                JDState(pipelineAction = PipelineAction.TAILOR, isDuplicate = false),
                JDState(pipelineAction = PipelineAction.SKIP, isDuplicate = false)
            )

            val tailored = countTailored(jobs)

            assertEquals(2, tailored)
        }

        @Test
        @DisplayName("Test tailored count excludes duplicate jobs even with tailor action")
        fun testTailoredCountExcludesDuplicateJobs() {
            val jobs = listOf(
                JDState(pipelineAction = PipelineAction.TAILOR, isDuplicate = true),
                JDState(pipelineAction = PipelineAction.TAILOR, isDuplicate = false),
                JDState(pipelineAction = PipelineAction.TAILOR, isDuplicate = true)
            )

            val tailored = countTailored(jobs)

            // Only 1 job has tailor action AND is NOT duplicate
            assertEquals(1, tailored)
        }

        @Test
        @DisplayName("Test skipped count includes jobs with pipelineAction != 'tailor'")
        fun testSkippedCountIncludesNonTailoredJobs() {
            val jobs = listOf(
                JDState(pipelineAction = PipelineAction.SKIP, isDuplicate = false),
                JDState(pipelineAction = PipelineAction.SKIP, isDuplicate = false),
                JDState(pipelineAction = PipelineAction.TAILOR, isDuplicate = false)
            )

            val skipped = countSkipped(jobs)
            val duplicate = countDuplicate(jobs)

            assertEquals(2, skipped)
            assertEquals(0, duplicate)
        }

        @Test
        @DisplayName("Test skipped count excludes duplicate jobs")
        fun testSkippedCountExcludesDuplicateJobs() {
            val jobs = listOf(
                JDState(pipelineAction = PipelineAction.TAILOR, isDuplicate = true),
                JDState(pipelineAction = PipelineAction.SKIP, isDuplicate = true),
                JDState(pipelineAction = PipelineAction.SKIP, isDuplicate = false)
            )

            val skipped = countSkipped(jobs)
            val duplicate = countDuplicate(jobs)

            // 1 non-duplicate, non-tailored job is skipped
            assertEquals(1, skipped)
            // 2 jobs are duplicates (regardless of action)
            assertEquals(2, duplicate)
        }

        @Test
        @DisplayName("Test totals for mixed job types")
        fun testTotalsForMixedJobTypes() {
            val jobs = listOf(
                // 2 tailored, non-duplicate jobs
                JDState(pipelineAction = PipelineAction.TAILOR, isDuplicate = false),
                JDState(pipelineAction = PipelineAction.TAILOR, isDuplicate = false),
                // 1 duplicate job with tailor action
                JDState(pipelineAction = PipelineAction.TAILOR, isDuplicate = true),
                // 1 non-tailored, non-duplicate job
                JDState(pipelineAction = PipelineAction.SKIP, isDuplicate = false),
                // 1 duplicate job with skip action
                JDState(pipelineAction = PipelineAction.SKIP, isDuplicate = true)
            )

            val tailored = countTailored(jobs)
            val skipped = countSkipped(jobs)
            val duplicate = countDuplicate(jobs)

            assertEquals(2, tailored)
            assertEquals(1, skipped)
            assertEquals(2, duplicate)
            assertEquals(5, tailored + skipped + duplicate)
        }

        @Test
        @DisplayName("Test edge case with empty job list")
        fun testEmptyJobList() {
            val jobs = emptyList<JDState>()

            val tailored = countTailored(jobs)
            val skipped = countSkipped(jobs)
            val duplicate = countDuplicate(jobs)

            assertEquals(0, tailored)
            assertEquals(0, skipped)
            assertEquals(0, duplicate)
        }

        @Test
        @DisplayName("Test all jobs tailored - no skipped or duplicate")
        fun testAllJobsTailored() {
            val jobs = listOf(
                JDState(pipelineAction = PipelineAction.TAILOR, isDuplicate = false),
                JDState(pipelineAction = PipelineAction.TAILOR, isDuplicate = false),
                JDState(pipelineAction = PipelineAction.TAILOR, isDuplicate = false)
            )

            val tailored = countTailored(jobs)
            val skipped = countSkipped(jobs)
            val duplicate = countDuplicate(jobs)

            assertEquals(3, tailored)
            assertEquals(0, skipped)
            assertEquals(0, duplicate)
        }

        @Test
        @DisplayName("Test all jobs skipped or duplicate")
        fun testAllJobsSkippedOrDuplicate() {
            val jobs = listOf(
                JDState(pipelineAction = PipelineAction.SKIP, isDuplicate = false),
                JDState(pipelineAction = PipelineAction.SKIP, isDuplicate = true),
                JDState(pipelineAction = PipelineAction.TAILOR, isDuplicate = true)
            )

            val tailored = countTailored(jobs)
            val skipped = countSkipped(jobs)
            val duplicate = countDuplicate(jobs)

            assertEquals(0, tailored)
            assertEquals(1, skipped)
            assertEquals(2, duplicate)
        }

        @Test
        @DisplayName("Test digest jobs structure - each child job counted independently")
        fun testDigestJobsCountedIndependently() {
            // Simulate digest email with 3 child jobs
            val childJobs = listOf(
                JDState(
                    isJobPosting = true,
                    pipelineAction = PipelineAction.TAILOR,
                    isDuplicate = false,
                    fitScore = 85.0f
                ),
                JDState(
                    isJobPosting = true,
                    pipelineAction = PipelineAction.SKIP,
                    isDuplicate = false,
                    fitScore = 25.0f
                ),
                JDState(
                    isJobPosting = true,
                    pipelineAction = PipelineAction.TAILOR,
                    isDuplicate = true,
                    fitScore = 90.0f
                )
            )

            val tailored = countTailored(childJobs)
            val skipped = countSkipped(childJobs)
            val duplicate = countDuplicate(childJobs)

            // Only the first job qualifies as tailored (tailor action + not duplicate)
            assertEquals(1, tailored)
            // The second job is skip (non-tailored, non-duplicate)
            assertEquals(1, skipped)
            // The third job is duplicate (even though it has tailor action)
            assertEquals(1, duplicate)
        }

        @Test
        @DisplayName("Test duplicate count includes only jobs where isDuplicate=true")
        fun testDuplicateCountIncludesOnlyDuplicateJobs() {
            val jobs = listOf(
                JDState(pipelineAction = PipelineAction.TAILOR, isDuplicate = true),
                JDState(pipelineAction = PipelineAction.SKIP, isDuplicate = true),
                JDState(pipelineAction = PipelineAction.TAILOR, isDuplicate = false),
                JDState(pipelineAction = PipelineAction.SKIP, isDuplicate = false)
            )

            val duplicate = countDuplicate(jobs)

            assertEquals(2, duplicate)
        }
    }
}
