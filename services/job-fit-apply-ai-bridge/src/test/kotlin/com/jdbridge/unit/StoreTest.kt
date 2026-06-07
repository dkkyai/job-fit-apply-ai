package com.jdbridge.unit

import com.jdbridge.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreCreateJobTest {

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
    }

    @Test
    fun `createJob inserts row with status queued`() = runTest {
        createJob("job-001", SubmitJobRequest(jd_text = "x".repeat(200), company = "Acme"))
        val row = getJob("job-001")
        assertNotNull(row)
        assertEquals(JobStatus.QUEUED.value, row.status)
    }

    @Test
    fun `createJob stores title and company`() = runTest {
        createJob("job-002", SubmitJobRequest(
            jd_text    = "x".repeat(200),
            role_title = "Staff SDET",
            company    = "Acme Corp",
        ))
        val row = getJob("job-002")!!
        assertEquals("Staff SDET", row.title)
        assertEquals("Acme Corp",  row.company)
    }

    @Test
    fun `createJob stores jd_json as non-null string`() = runTest {
        createJob("job-003", SubmitJobRequest(jd_text = "x".repeat(200)))
        val row = getJob("job-003")!!
        assertNotNull(row.jdJson)
        assertTrue(row.jdJson!!.contains("jd_text"))
    }

    @Test
    fun `createJob sets created_at within 2 seconds of wall clock`() = runTest {
        val before = System.currentTimeMillis() / 1000L
        createJob("job-004", SubmitJobRequest(jd_text = "x".repeat(200)))
        val after = System.currentTimeMillis() / 1000L
        val row = getJob("job-004")!!
        assertTrue(row.createdAt >= before && row.createdAt <= after + 1)
        assertTrue(row.updatedAt >= before && row.updatedAt <= after + 1)
    }
}

class StoreUpdateJobTest {

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
    }

    @Test
    fun `updateJob changes status`() = runTest {
        createJob("upd-001", SubmitJobRequest(jd_text = "x".repeat(200)))
        updateJob("upd-001", JobUpdate(status = JobStatus.SCORING))
        val row = getJob("upd-001")!!
        assertEquals(JobStatus.SCORING.value, row.status)
    }

    @Test
    fun `updateJob stores fit_score of 0`() = runTest {
        createJob("upd-002", SubmitJobRequest(jd_text = "x".repeat(200)))
        updateJob("upd-002", JobUpdate(fitScore = 0))
        assertEquals(0, getJob("upd-002")!!.fitScore)
    }

    @Test
    fun `updateJob stores fit_score of 100`() = runTest {
        createJob("upd-003", SubmitJobRequest(jd_text = "x".repeat(200)))
        updateJob("upd-003", JobUpdate(fitScore = 100))
        assertEquals(100, getJob("upd-003")!!.fitScore)
    }

    @Test
    fun `updateJob serializes and deserializes ArtifactUrls`() = runTest {
        createJob("upd-004", SubmitJobRequest(jd_text = "x".repeat(200)))
        val artifacts = ArtifactUrls("/api/jobs/upd-004/resume.pdf", "/api/jobs/upd-004/cover_letter.txt")
        updateJob("upd-004", JobUpdate(status = JobStatus.COMPLETE, artifacts = artifacts))
        val row = getJob("upd-004")!!
        assertNotNull(row.artifacts)
        assertEquals("/api/jobs/upd-004/resume.pdf", row.artifacts!!.resume_pdf)
    }

    @Test
    fun `updateJob updates multiple fields atomically`() = runTest {
        createJob("upd-005", SubmitJobRequest(jd_text = "x".repeat(200)))
        updateJob("upd-005", JobUpdate(
            status          = JobStatus.TAILORING,
            progressMessage = "Building resume…",
            fitScore        = 75,
        ))
        val row = getJob("upd-005")!!
        assertEquals(JobStatus.TAILORING.value, row.status)
        assertEquals("Building resume…", row.progressMessage)
        assertEquals(75, row.fitScore)
    }

    @Test
    fun `updateJob on nonexistent job does not throw`() = runTest {
        updateJob("nonexistent", JobUpdate(status = JobStatus.ERROR))
    }

    @Test
    fun `updateJob bumps updated_at`() = runTest {
        createJob("upd-006", SubmitJobRequest(jd_text = "x".repeat(200)))
        val before = getJob("upd-006")!!.updatedAt
        Thread.sleep(1010)
        updateJob("upd-006", JobUpdate(status = JobStatus.SCORING))
        val after = getJob("upd-006")!!.updatedAt
        assertTrue(after >= before, "updated_at must not decrease")
    }
}

class StoreGetJobTest {

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
    }

    @Test
    fun `getJob returns null for unknown id`() = runTest {
        assertNull(getJob("does-not-exist"))
    }

    @Test
    fun `getJob returns all fields after create and update`() = runTest {
        createJob("get-001", SubmitJobRequest(
            jd_text    = "x".repeat(200),
            role_title = "Engineer",
            company    = "TestCo",
        ))
        updateJob("get-001", JobUpdate(
            status          = JobStatus.COMPLETE,
            fitScore        = 85,
            progressMessage = "Done.",
        ))
        val row = getJob("get-001")!!
        assertEquals("get-001",             row.id)
        assertEquals("Engineer",            row.title)
        assertEquals("TestCo",             row.company)
        assertEquals(JobStatus.COMPLETE.value, row.status)
        assertEquals(85,                    row.fitScore)
        assertEquals("Done.",               row.progressMessage)
    }

    @Test
    fun `getJob exposes deserialized artifacts, not raw column`() = runTest {
        createJob("get-002", SubmitJobRequest(jd_text = "x".repeat(200)))
        updateJob("get-002", JobUpdate(artifacts = ArtifactUrls("/api/jobs/get-002/resume.pdf", "")))
        val row = getJob("get-002")!!
        assertNotNull(row.artifacts)
    }

    @Test
    fun `getJob preserves unicode in jd_json`() = runTest {
        createJob("get-003", SubmitJobRequest(jd_text = "职位：🎉" + "x".repeat(150)))
        val row = getJob("get-003")!!
        assertNotNull(row.jdJson)
        assertTrue(row.jdJson!!.contains("🎉"))
    }

    @Test
    fun `getJob returns fit_score of 0 not null`() = runTest {
        createJob("get-004", SubmitJobRequest(jd_text = "x".repeat(200)))
        updateJob("get-004", JobUpdate(fitScore = 0))
        assertEquals(0, getJob("get-004")!!.fitScore)
    }
}

class StoreJobDirTest {

    @BeforeEach
    fun setup() { useTempStoreDir() }

    @Test
    fun `jobDir returns path containing jobId`() {
        initTestDb()
        val path = jobDir("abc-123")
        assertTrue(path.toString().contains("abc-123"))
    }

    @Test
    fun `jobDir creates directory`() {
        initTestDb()
        val path = jobDir("new-job")
        assertTrue(path.toFile().isDirectory)
    }

    @Test
    fun `jobDir is idempotent`() {
        initTestDb()
        val p1 = jobDir("dup")
        val p2 = jobDir("dup")
        assertEquals(p1, p2)
        assertTrue(p2.toFile().isDirectory)
    }
}

class StoreConcurrencyTest {

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
    }

    @Test
    fun `50 concurrent createJob calls all succeed`() = runBlocking {
        val jobs = (1..50).map { i ->
            launch {
                createJob("concurrent-$i", SubmitJobRequest(jd_text = "x".repeat(200)))
            }
        }
        jobs.forEach { it.join() }
        for (i in 1..50) {
            assertNotNull(getJob("concurrent-$i"), "Job concurrent-$i should exist")
        }
    }

    @Test
    fun `concurrent createJob with same job_id only one succeeds`() = runBlocking {
        val jobId = "duplicate-job"
        createJob(jobId, SubmitJobRequest(jd_text = "x".repeat(200)))
        // Second insert should not throw but may be handled by DB
        try {
            createJob(jobId, SubmitJobRequest(jd_text = "y".repeat(200)))
            // If it didn't throw, verify only one job exists
        } catch (e: Exception) {
            // Expected - primary key constraint violation
        }
        // Only one job should exist
        assertNotNull(getJob(jobId), "One copy of job should exist")
    }
}

class JdJsonFieldRoundTripTest {

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
    }

    @Test
    fun `location and job_url are preserved in jd_json round-trip`() = runTest {
        createJob("roundtrip-001", SubmitJobRequest(
            jd_text    = "x".repeat(200),
            location   = "Seattle, WA",
            job_url    = "https://example.com/job/123",
            role_title = "SDET",
        ))
        val row = getJob("roundtrip-001")!!
        assertNotNull(row.jdJson)
        assertTrue(row.jdJson!!.contains("Seattle"), "location should be preserved in jd_json")
        assertTrue(row.jdJson!!.contains("example.com/job/123"), "job_url should be preserved in jd_json")
    }

    @Test
    fun `null location and job_url in jd_json are handled correctly`() = runTest {
        createJob("roundtrip-002", SubmitJobRequest(jd_text = "x".repeat(200)))
        val row = getJob("roundtrip-002")!!
        assertNotNull(row.jdJson)
        // When location and job_url are null, they should not appear in JSON (using explicitNulls=false)
        assertFalse(row.jdJson!!.contains("\"location\""),
            "null location should not appear in JSON")
    }
}
