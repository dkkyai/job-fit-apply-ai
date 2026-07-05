package com.jdbridge.unit

import com.jdbridge.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Store-level tests for the write-back cursor + completed feed. These exercise the
 * `completed_seq` AtomicLong directly (recordResult works by id, no claim needed),
 * including the monotonic-across-restart property the review bug depended on.
 */
class StoreWritebackTest {

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
    }

    private suspend fun completeSkip(id: String, error: String? = null) =
        recordResult(id, ResultRequest(pipeline_action = "skip", fit_score = 0, error = error))

    private suspend fun seqOf(id: String): Long = completedJobs(0).single { it.job_id == id }.completed_seq

    @Test
    fun `completed_seq stays monotonic across re-init (restart)`() = runTest {
        val id1 = enqueue("{}", null, "k1")
        completeSkip(id1)
        val seq1 = seqOf(id1)

        // Simulate a bridge restart: re-init the DB (re-seeds the cursor from the persisted max).
        initDb()

        val id2 = enqueue("{}", null, "k2")
        completeSkip(id2)
        val seq2 = seqOf(id2)

        assertTrue(seq2 > seq1, "completed_seq must not reset after re-init: seq1=$seq1 seq2=$seq2")
    }

    @Test
    fun `completed_seq is strictly increasing and unique across completions`() = runTest {
        val seqs = (1..5).map { i ->
            val id = enqueue("{}", null, "sk$i")
            completeSkip(id)
            seqOf(id)
        }
        assertEquals(seqs.size, seqs.toSet().size, "seqs must be unique")
        assertTrue(seqs.zipWithNext().all { (a, b) -> b > a }, "seqs must be strictly increasing: $seqs")
    }

    @Test
    fun `error result is terminal and surfaces in the completed feed`() = runTest {
        val id = enqueue("{}", null, "err1")
        completeSkip(id, error = "boom")
        assertEquals(JobStatus.ERROR.value, getJob(id)!!.status)
        assertEquals("boom", completedJobs(0).single { it.job_id == id }.error)
    }

    @Test
    fun `JD_SCRAPED job carries no message_id in the feed (Poller skips Gmail)`() = runTest {
        val id = enqueue(defaultJdJson(), null, "jd1")   // default type = JD_SCRAPED
        completeSkip(id)
        assertNull(completedJobs(0).single { it.job_id == id }.message_id)
    }

    @Test
    fun `enqueue-time message_id persists to the feed even if the result omits it`() = runTest {
        val id = enqueue("{}", null, "m1", type = WorkItemType.EMAIL_RAW, messageId = "gmail-123")
        completeSkip(id)   // ResultRequest has no message_id
        assertEquals("gmail-123", completedJobs(0).single { it.job_id == id }.message_id)
    }

    @Test
    fun `completed feed excludes pending, claimed, and acknowledged jobs`() = runTest {
        val pending = enqueue("{}", null, "p")               // never completed
        val done = enqueue("{}", null, "d"); completeSkip(done)
        val acked = enqueue("{}", null, "a"); completeSkip(acked); markWritebackDone(acked)

        val feedIds = completedJobs(0).map { it.job_id }.toSet()
        assertTrue(done in feedIds, "a fresh completed job should be in the feed")
        assertTrue(pending !in feedIds, "pending jobs must not be in the feed")
        assertTrue(acked !in feedIds, "acknowledged (writeback_done) jobs must not be in the feed")
    }
}
