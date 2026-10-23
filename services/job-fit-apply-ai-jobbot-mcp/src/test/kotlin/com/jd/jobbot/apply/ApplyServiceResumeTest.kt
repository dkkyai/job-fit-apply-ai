package com.jd.jobbot.apply

import com.google.common.util.concurrent.MoreExecutors
import com.jd.jobbot.actions.AD_HOC_SEQ
import com.jd.jobbot.actions.ActionStore
import com.jd.jobbot.actions.IdVerbHandler
import com.jd.jobbot.actions.Outbox
import com.jd.jobbot.actions.Outcomes
import com.jd.jobbot.actions.ResumeApplyRequest
import com.jd.jobbot.actions.TapRequest
import com.jd.jobbot.actions.TapResponse
import com.jd.jobbot.actions.TapService
import com.jd.jobbot.bridge.BridgeReadClient
import com.jd.jobbot.bridge.TrackWriter
import com.jd.jobbot.jobs.JobLookup
import com.jd.jobbot.support.FakeBridge
import com.jd.jobbot.support.ResumeFiles
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Apply with a resume Richard sent: through TapService and ApplyService, with a fake browser and a recording fill. */
class ApplyServiceResumeTest {
    @TempDir lateinit var dir: Path
    private val user = "8679792351"
    private val pdf = ResumeFiles.pdf("richard")
    private val linkUrl = "https://jobs.lever.co/acme/123"

    private class Moving(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId?) = this
        override fun instant() = now
    }

    private class Lookup(val event: JsonObject) : JobLookup(BridgeReadClient("http://unused")) {
        override fun event(seq: Long) = event.takeIf { seq == 9L }
        override fun find(seq: Long) = event(seq)?.let { Found(it, FakeBridge.track(41, "Acme", "Staff SDET"), "track_id") }
    }

    private class Tracks : TrackWriter("http://unused") {
        val calls = mutableListOf<String>()
        override fun setStatus(trackId: Long, status: String) { calls += "status $status" }
        override fun addEvent(trackId: Long, kind: String, summary: String?, details: JsonElement?) { calls += "event $kind" }
    }

    private val readyForm = Snapshot(
        "https://boards.greenhouse.io/acme/jobs/9",
        elements = listOf(
            Element("e1", "input", "text", "First name", value = "Richard", inForm = true, formFilled = true),
            Element("e3", "button", "submit", text = "Submit application", inForm = true, formFilled = true),
        ),
    )

    private inner class Rig(event: JsonObject = FakeBridge.event(9, jobUrl = "https://boards.greenhouse.io/acme/jobs/9")) {
        val clock = Moving(Instant.parse("2026-10-01T12:00:00Z"))
        val store = ActionStore(":memory:", clock)
        val outbox = Outbox(dir.resolve("o.db").toString(), dir.resolve("shots"), clock)
        val tracks = Tracks()
        val creds = Credentials(dir.resolve("creds.json"), "dkkytech@gmail.com", clock)
        val page = FakePage(readyForm).also { p -> p.onClick = { if (it == "e3") p.snap = p.snap.copy(url = p.snap.url + "/confirmation", text = "Thank you for applying!") } }
        val lookup = Lookup(event)
        /** Every JobContext the fill loop was handed, in order. */
        val jobs = mutableListOf<JobContext>()
        var outcome: (ApplyPage) -> FillResult = { p -> FillResult.Ready(FillAgent.fieldsOf(p.snapshot()), byteArrayOf(9), "") }

        val service = ApplyService(
            browser = { _ -> page },
            fill = { p, job, _ -> jobs += job; outcome(p) },
            lookup = lookup,
            contextFor = { seq ->
                if (seq == AD_HOC_SEQ) JobContext("", null, null, "", "dkkytech@gmail.com", "years: 15+", null, null, null)
                else JobContext("#J$seq", "Acme", "Staff SDET", "https://boards.greenhouse.io/acme/jobs/9", "dkkytech@gmail.com", "years: 15+", "name: tailored", "Dear Acme", "TAILORED".toByteArray())
            },
            store = store, outbox = outbox, tracks = tracks, viewerUrl = "https://viewer.tailnet/", credentials = creds,
            clock = clock, worker = MoreExecutors.newDirectExecutorService(),
        )
        val taps = TapService(
            lookup, store, mapOf("apply" to service.apply), setOf(user), setOf("apply"), false, Duration.ofDays(7), clock,
            idHandlers = mapOf("submit" to IdVerbHandler(service::submit), "discard" to IdVerbHandler(service::discard), "resume" to IdVerbHandler(service::resume)),
            resumeApply = service.applyWithResume,
        )

        fun tap(verb: String, n: Long = 9) = taps.tap(TapRequest(verb, n, user, user, 5, clock.now.epochSecond))
        fun upload(name: String = "Richard Hatcher.pdf", data: ByteArray = pdf, seq: Long? = 9, url: String? = null): TapResponse =
            taps.applyWithResume(ResumeApplyRequest(seq, url, user, user, 5, clock.now.epochSecond, name, ResumeFiles.b64(data)))
        fun review() = outbox.pending().last()
        fun fillId() = review().buttons.first { it.callbackData.startsWith("submit:") || it.callbackData.startsWith("resume:") }.callbackData.substringAfter(':').toLong()
    }

    // ── a reply to a card ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the fill uploads Richard's file under its own name, not the tailored resume`() {
        val rig = Rig()
        val r = rig.upload(name = "Richard Hatcher - Senior SDET.pdf")
        assertEquals(Outcomes.DONE, r.outcome)
        assertTrue(r.reply!!.contains("with your resume (Richard Hatcher - Senior SDET.pdf)"), r.reply)
        val job = rig.jobs.single()
        assertEquals("Richard Hatcher - Senior SDET.pdf", job.resumeFileName)
        assertEquals("application/pdf", job.resumeMime)
        assertContentEquals(pdf, job.resumePdf)
        assertTrue(job.resumeIsUpload)
    }

    @Test
    fun `a docx is uploaded with the Word mime type`() {
        val rig = Rig()
        val docx = ResumeFiles.docx()
        rig.upload(name = "resume.docx", data = docx)
        val job = rig.jobs.single()
        assertEquals("resume.docx", job.resumeFileName)
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", job.resumeMime)
        assertContentEquals(docx, job.resumePdf)
    }

    @Test
    fun `answers still come from the candidate profile, with no tailored resume or cover letter`() {
        val rig = Rig()
        rig.upload()
        val job = rig.jobs.single()
        assertEquals("years: 15+", job.profileYaml)
        assertNull(job.resumeYaml, "the tailored YAML describes a different resume")
        assertNull(job.coverLetter, "no cover letter with an uploaded resume")
        assertEquals("Acme", job.company)
        assertEquals("https://boards.greenhouse.io/acme/jobs/9", job.jobUrl)
    }

    @Test
    fun `the Apply button is unchanged and still uses the tailored resume`() {
        val rig = Rig()
        rig.tap("apply")
        val job = rig.jobs.single()
        assertFalse(job.resumeIsUpload)
        assertEquals("RichardHatcherResume.pdf", job.resumeFileName)
        assertEquals("name: tailored", job.resumeYaml)
        assertEquals("Dear Acme", job.coverLetter)
        assertContentEquals("TAILORED".toByteArray(), job.resumePdf)
    }

    @Test
    fun `the review card names the file and says there is no cover letter`() {
        val rig = Rig()
        rig.upload(name = "Richard.pdf")
        val text = rig.review().text
        assertTrue(text.startsWith("Ready to submit #J9"), text)
        assertTrue(text.contains("Resume: Richard.pdf (your file, not the tailored one). No cover letter."), text)
        assertEquals(listOf("✅ Submit", "✖ Discard"), rig.review().buttons.map { it.text })
    }

    @Test
    fun `an uploaded-resume application still waits for the Submit tap`() {
        val rig = Rig()
        rig.upload()
        assertFalse("click e3" in rig.page.actions, "filling never submits")
        assertEquals(Outcomes.DONE, rig.tap("submit", rig.fillId()).outcome)
        assertTrue("click e3" in rig.page.actions)
        assertEquals(listOf("status applied", "event application_submitted"), rig.tracks.calls)
        assertEquals("Submitted: #J9.", rig.outbox.pending().last().text)
    }

    @Test
    fun `a skipped job can be applied to with a file`() {
        val rig = Rig(FakeBridge.event(9, action = "SKIP", skipReason = "Posted pay is below target", jobUrl = "https://boards.greenhouse.io/acme/jobs/9"))
        assertEquals(Outcomes.REFUSED, rig.tap("apply").outcome, "the button stays refused")
        assertEquals(Outcomes.DONE, rig.upload().outcome)
        assertEquals(1, rig.jobs.size)
    }

    @Test
    fun `the file is recorded on the action for the audit trail`() {
        val rig = Rig()
        rig.upload(name = "Richard.pdf")
        val fill = rig.store.recent().first { it.verb == "fill" }
        assertTrue(fill.details!!.contains("Richard.pdf"), fill.details)
    }

    @Test
    fun `a file sent while a hand-off is pending is not used and asks to discard first`() {
        val rig = Rig()
        rig.outcome = { FillResult.NeedsHuman("CAPTCHA", null) }
        rig.upload(name = "first.pdf")
        val r = rig.upload(name = "other.pdf")
        assertEquals(Outcomes.ALREADY, r.outcome)
        assertTrue(r.reply!!.contains("waiting on you"), r.reply)
        assertTrue(r.reply!!.contains("Your file wasn't used"), r.reply)
        assertEquals(1, rig.jobs.size)
    }

    @Test
    fun `a file sent while a form awaits review asks to discard first`() {
        val rig = Rig()
        rig.upload(name = "first.pdf")
        val r = rig.upload(name = "second.pdf")
        assertEquals(Outcomes.ALREADY, r.outcome)
        assertTrue(r.reply!!.contains("waiting for your review"), r.reply)
        assertTrue(r.reply!!.contains("Discard that one, then send the resume again"), r.reply)
        assertEquals(1, rig.jobs.size)
    }

    @Test
    fun `after Discard a different file starts a fresh application`() {
        val rig = Rig()
        rig.upload(name = "first.pdf")
        rig.tap("discard", rig.fillId())
        val r = rig.upload(name = "second.pdf")
        assertEquals(Outcomes.DONE, r.outcome)
        assertEquals(listOf("first.pdf", "second.pdf"), rig.jobs.map { it.resumeFileName })
    }

    @Test
    fun `a Continue after a hand-off keeps using the same file`() {
        val rig = Rig()
        var first = true
        rig.outcome = { p -> if (first) { first = false; FillResult.NeedsHuman("CAPTCHA", null) } else FillResult.Ready(FillAgent.fieldsOf(p.snapshot()), byteArrayOf(9), "") }
        rig.upload(name = "mine.pdf")
        assertEquals(Outcomes.DONE, rig.tap("resume", rig.fillId()).outcome)
        assertEquals(listOf("mine.pdf", "mine.pdf"), rig.jobs.map { it.resumeFileName })
        assertTrue(rig.review().text.contains("Resume: mine.pdf"), rig.review().text)
    }

    @Test
    fun `a job the track already marks applied is not filled`() {
        val rig = Rig()
        val applied = object : JobLookup(BridgeReadClient("http://unused")) {
            override fun event(seq: Long) = rig.lookup.event.takeIf { seq == 9L }
            override fun find(seq: Long) = event(seq)?.let { Found(it, FakeBridge.track(41, "Acme", "Staff SDET", status = "applied"), "track_id") }
        }
        val svc = ApplyService(
            browser = { _ -> rig.page }, fill = { p, _, _ -> rig.outcome(p) }, lookup = applied, contextFor = { null },
            store = rig.store, outbox = rig.outbox, tracks = rig.tracks, viewerUrl = null, clock = rig.clock, worker = MoreExecutors.newDirectExecutorService(),
        )
        val taps = TapService(applied, rig.store, emptyMap(), setOf(user), setOf("apply"), false, Duration.ofDays(7), rig.clock, resumeApply = svc.applyWithResume)
        val r = taps.applyWithResume(ResumeApplyRequest(9, null, user, user, 5, rig.clock.now.epochSecond, "r.pdf", ResumeFiles.b64(pdf)))
        assertEquals(Outcomes.ALREADY, r.outcome)
        assertTrue(r.reply!!.contains("Already applied") && r.reply!!.contains("Your file wasn't used"), r.reply)
    }

    @Test
    fun `a discarded or failed fill releases its file`() {
        val rig = Rig()
        rig.outcome = { FillResult.Failed("boom", null) }
        rig.upload()
        assertTrue(rig.outbox.pending().last().text.contains("boom"))
        // A new file can start straight away: the failed fill does not block the job.
        rig.outcome = { p -> FillResult.Ready(FillAgent.fieldsOf(p.snapshot()), byteArrayOf(9), "") }
        assertEquals(Outcomes.DONE, rig.upload(name = "again.pdf").outcome)
        assertEquals("again.pdf", rig.jobs.last().resumeFileName)
    }

    // ── a pasted link ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `a resume with a link applies at that link with the profile and the file`() {
        val rig = Rig()
        val r = rig.upload(seq = null, url = "$linkUrl#apply")
        assertEquals(Outcomes.DONE, r.outcome)
        assertTrue(r.reply!!.contains("Filling the application for Job at jobs.lever.co"), r.reply)
        assertTrue(r.reply!!.contains("with your resume (Richard Hatcher.pdf)"), r.reply)
        val job = rig.jobs.single()
        assertEquals(linkUrl, job.jobUrl)
        assertEquals("Job at jobs.lever.co", job.ref)
        assertNull(job.company)
        assertEquals("years: 15+", job.profileYaml)
        assertNull(job.resumeYaml)
        assertNull(job.coverLetter)
        assertContentEquals(pdf, job.resumePdf)
        assertEquals("Richard Hatcher.pdf", job.resumeFileName)
    }

    @Test
    fun `the review card for a link names the site, never a #J reference`() {
        val rig = Rig()
        rig.upload(seq = null, url = linkUrl)
        val text = rig.review().text
        assertTrue(text.startsWith("Ready to submit Job at jobs.lever.co"), text)
        assertTrue(text.contains("Resume: Richard Hatcher.pdf"), text)
        assertFalse(text.contains("#J"), text)
    }

    @Test
    fun `submitting a link application confirms it without touching any track`() {
        val rig = Rig()
        rig.upload(seq = null, url = linkUrl)
        assertEquals(Outcomes.DONE, rig.tap("submit", rig.fillId()).outcome)
        assertTrue("click e3" in rig.page.actions)
        assertEquals(emptyList(), rig.tracks.calls, "a pasted link has no JFAA track")
        assertEquals("Submitted: Job at jobs.lever.co.", rig.outbox.pending().last().text)
    }

    @Test
    fun `a link application survives the hand-off and the review with its label`() {
        val rig = Rig()
        var first = true
        rig.outcome = { p -> if (first) { first = false; FillResult.NeedsHuman("CAPTCHA", null) } else FillResult.Ready(FillAgent.fieldsOf(p.snapshot()), byteArrayOf(9), "") }
        rig.upload(seq = null, url = linkUrl)
        assertTrue(rig.outbox.pending().last().text.startsWith("Job at jobs.lever.co needs you"), rig.outbox.pending().last().text)
        rig.tap("resume", rig.fillId())
        assertEquals(listOf(linkUrl, linkUrl), rig.jobs.map { it.jobUrl })
        assertTrue(rig.review().text.startsWith("Ready to submit Job at jobs.lever.co"), rig.review().text)
    }

    @Test
    fun `a link application is recorded as a fill with no job number`() {
        val rig = Rig()
        rig.upload(seq = null, url = linkUrl)
        val fill = rig.store.recent().first { it.verb == "fill" }
        assertEquals(AD_HOC_SEQ, fill.seq)
        assertEquals("url:jobs.lever.co/acme/123", fill.jobKey)
        assertTrue(fill.details!!.contains(linkUrl))
    }

    @Test
    fun `the same link twice does not start a second fill`() {
        val rig = Rig()
        rig.upload(seq = null, url = linkUrl)
        val r = rig.upload(seq = null, url = "$linkUrl?utm_source=x")
        assertEquals(Outcomes.ALREADY, r.outcome)
        assertEquals(1, rig.jobs.size)
    }

    @Test
    fun `a link whose posting is already being filled from a card is not filled twice`() {
        val rig = Rig(FakeBridge.event(9, jobUrl = linkUrl))
        rig.tap("apply")
        val r = rig.upload(seq = null, url = linkUrl)
        assertEquals(Outcomes.ALREADY, r.outcome)
        assertEquals(1, rig.jobs.size)
    }

    @Test
    fun `an expired link application tells Richard in chat with its label`() {
        val rig = Rig()
        rig.upload(seq = null, url = linkUrl)
        rig.clock.now = rig.clock.now.plus(Duration.ofHours(5))
        rig.service.sweep()
        assertEquals(
            "The filled application for Job at jobs.lever.co expired after 4 h without a decision. Nothing was submitted.",
            rig.outbox.pending().last().text,
        )
    }

    @Test
    fun `a fill that lost its link or file after a restart fails cleanly`() {
        val rig = Rig()
        // A queued ad hoc fill with no upload in memory, as after a restart that the orphan check missed.
        val fill = rig.store.insert(ApplyService.FILL, "url:x", AD_HOC_SEQ, ApplyService.QUEUED, user, null, """{"job_url":"$linkUrl","label":"Job at jobs.lever.co"}""")
        rig.service.runFill(fill.id, fresh = true)
        assertEquals(ApplyService.FAILED, rig.store.byId(fill.id)!!.status)
        assertTrue(rig.outbox.pending().last().text.startsWith("Couldn't start Job at jobs.lever.co"), rig.outbox.pending().last().text)
        assertNotNull(rig.outbox.pending().last())
    }
}
