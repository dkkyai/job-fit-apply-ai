package com.jd.jobbot.cli

import com.jd.jobbot.bridge.BridgeReadClient
import com.jd.jobbot.files.OutputFiles
import com.jd.jobbot.jobs.JobLookup
import com.jd.jobbot.profile.ProfileReader
import com.jd.jobbot.support.FakeBridge
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The context-building helpers Main wires into ApplyService: what a fill is told about the job and the candidate. */
class MainWiringTest {
    @TempDir lateinit var dir: Path
    private var bridge: FakeBridge? = null

    @AfterTest fun stop() { bridge?.close() }

    private fun profile(resume: String? = "name: Richard", candidate: String? = "years: 15+"): ProfileReader {
        val r = dir.resolve("resume.yaml"); val c = dir.resolve("candidate_profile.yaml")
        if (resume != null) Files.writeString(r, resume) else Files.deleteIfExists(r)
        if (candidate != null) Files.writeString(c, candidate) else Files.deleteIfExists(c)
        return ProfileReader(r.toString(), c.toString())
    }

    // ── a pasted link or a packet: no JFAA job behind it ─────────────────────────────────────

    @Test
    fun `an ad hoc job carries the profile and nothing about a JFAA job`() {
        val job = adHocJobContext(profile())
        assertEquals("years: 15+\n---\nname: Richard", job.profileYaml, "the candidate profile comes first")
        assertEquals("", job.ref); assertEquals("", job.jobUrl)
        assertNull(job.company); assertNull(job.title); assertNull(job.resumeYaml); assertNull(job.coverLetter); assertNull(job.resumePdf)
        assertNull(job.answers)
        assertEquals(false, job.resumeIsUpload)
    }

    @Test
    fun `an ad hoc job still works when only one profile file exists, or neither`() {
        assertEquals("years: 15+", adHocJobContext(profile(resume = null)).profileYaml)
        assertEquals("name: Richard", adHocJobContext(profile(candidate = null)).profileYaml)
        assertNull(adHocJobContext(ProfileReader("/nonexistent/a", "/nonexistent/b")).profileYaml)
    }

    @Test
    fun `the profile is read fresh for every job`() {
        val reader = profile()
        assertEquals("years: 15+\n---\nname: Richard", adHocJobContext(reader).profileYaml)
        Files.writeString(dir.resolve("candidate_profile.yaml"), "years: 16")
        assertEquals("years: 16\n---\nname: Richard", adHocJobContext(reader).profileYaml, "an edit is picked up with no restart")
    }

    // ── a JFAA card ──────────────────────────────────────────────────────────────────────────

    private fun lookup(vararg events: kotlinx.serialization.json.JsonObject): Pair<JobLookup, BridgeReadClient> {
        bridge = FakeBridge(events = events.toList())
        val client = object : BridgeReadClient(bridge!!.url) {
            override fun resumePdf(jobId: String) = if (jobId == "job-9") "%PDF tailored".toByteArray() else error("no pdf for $jobId")
        }
        return JobLookup(client) to client
    }

    private fun outputFolder(): OutputFiles {
        val d = Files.createDirectories(dir.resolve("out/20261001_000000_acme_9"))
        Files.writeString(d.resolve("tailored_resume.yaml"), "name: tailored")
        Files.writeString(d.resolve("cover_letter.txt"), "Dear Acme")
        return OutputFiles(dir.resolve("out").toString())
    }

    @Test
    fun `a card job is built from its event, its output folder and the bridge`() {
        val (lookup, client) = lookup(FakeBridge.event(9, jobUrl = "https://acme.co/jobs/9", artifactUrl = "http://markserv:8081/20261001_000000_acme_9/"))
        val job = jobContext(9, lookup, client, outputFolder(), profile())!!
        assertEquals("#J9", job.ref)
        assertEquals("Acme", job.company)
        assertEquals("Staff SDET", job.title)
        assertEquals("https://acme.co/jobs/9", job.jobUrl)
        assertEquals("name: tailored", job.resumeYaml)
        assertEquals("Dear Acme", job.coverLetter)
        assertEquals("%PDF tailored", String(job.resumePdf!!))
        assertEquals("RichardHatcherResume.pdf", job.resumeFileName)
        assertEquals("years: 15+\n---\nname: Richard", job.profileYaml)
        assertEquals(false, job.resumeIsUpload)
    }

    @Test
    fun `a card job with no posting URL cannot be filled`() {
        val (lookup, client) = lookup(FakeBridge.event(9, jobUrl = null))
        assertNull(jobContext(9, lookup, client, outputFolder(), profile()))
    }

    @Test
    fun `an unknown card cannot be filled`() {
        val (lookup, client) = lookup(FakeBridge.event(9))
        assertNull(jobContext(404, lookup, client, outputFolder(), profile()))
    }

    @Test
    fun `missing output files and a missing PDF leave those fields empty, not the whole job`() {
        val (lookup, client) = lookup(FakeBridge.event(9, jobUrl = "https://acme.co/jobs/9", artifactUrl = "http://markserv:8081/does_not_exist/"))
        val job = jobContext(9, lookup, client, OutputFiles(dir.resolve("out").toString()), profile())!!
        assertNull(job.resumeYaml); assertNull(job.coverLetter)
        assertEquals("https://acme.co/jobs/9", job.jobUrl)

        val (l2, c2) = lookup(FakeBridge.event(8, jobUrl = "https://acme.co/jobs/8"))
        assertNull(jobContext(8, l2, c2, outputFolder(), profile())!!.resumePdf, "a bridge failure fetching the PDF is not fatal")
    }

    @Test
    fun `a card job's context can take an uploaded resume without losing the job`() {
        val (lookup, client) = lookup(FakeBridge.event(9, jobUrl = "https://acme.co/jobs/9", artifactUrl = "http://markserv:8081/20261001_000000_acme_9/"))
        val upload = (com.jd.jobbot.actions.ResumeUpload.validate("mine.pdf", com.jd.jobbot.support.ResumeFiles.pdf()) as com.jd.jobbot.actions.ResumeUpload.Check.Ok).upload
        val job = jobContext(9, lookup, client, outputFolder(), profile())!!.withUploadedResume(upload)
        assertEquals("Acme", job.company)
        assertNull(job.resumeYaml); assertNull(job.coverLetter)
        assertEquals("mine.pdf", job.resumeFileName)
        assertTrue(job.resumeIsUpload)
    }

    @Test
    fun `accounts text never shows a password`() {
        val creds = com.jd.jobbot.apply.Credentials(dir.resolve("creds.json"), "dkkytech@gmail.com")
        assertEquals("JobBot hasn't created any site accounts yet.", accountsText(creds))
        val password = creds.createPending("example.com", "https://example.com/login", "Acme — SDET")
        creds.recordGoogle("jobs.google-signin.example", null, null)
        val text = accountsText(creds)
        assertTrue(text.contains("example.com") && text.contains("password (pending)"), text)
        assertTrue(text.contains("Sign in with Google"), text)
        assertTrue(!text.contains(password), "no password in the listing")
    }
}
