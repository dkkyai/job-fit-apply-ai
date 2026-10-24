package com.jd.jobbot.apply

import com.google.common.util.concurrent.MoreExecutors
import com.jd.jobbot.actions.ActionStore
import com.jd.jobbot.actions.IdVerbHandler
import com.jd.jobbot.actions.Outbox
import com.jd.jobbot.actions.Outcomes
import com.jd.jobbot.actions.ResumeUpload
import com.jd.jobbot.actions.TapRequest
import com.jd.jobbot.actions.TapService
import com.jd.jobbot.bridge.BridgeReadClient
import com.jd.jobbot.jobs.JobLookup
import com.jd.jobbot.support.ResumeFiles
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A jobbot-cargo application's life inside ApplyService: what it tells the STATUS.md listener, and what survives each step. */
class ApplyServiceCargoTest {
    @TempDir lateinit var dir: Path
    private val user = "8679792351"
    private val folder = "applications/acme/sdet/v00"

    private class Moving(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId?) = this
        override fun instant() = now
    }

    private val form = Snapshot(
        "https://jobs.example.com/apply",
        elements = listOf(
            Element("e1", "input", "text", "First name", value = "Richard", inForm = true, formFilled = true),
            Element("e3", "button", "submit", text = "Submit application", inForm = true, formFilled = true),
        ),
    )
    private val upload = (ResumeUpload.validate("Richard-Hatcher-Resume.pdf", ResumeFiles.pdf("acme")) as ResumeUpload.Check.Ok).upload
    private fun packet(url: String = "https://jobs.example.com/acme/1", answers: String? = "Sponsorship: No", approval: String? = null) =
        PacketApply(folder, "Acme — SDET", "Acme", "SDET", url, upload, answers, approval)

    private inner class Rig(
        listener: ((String, String, String) -> Unit)? = null,
        private val openFails: Boolean = false,
    ) {
        val clock = Moving(Instant.parse("2026-10-22T20:00:00Z"))
        val store = ActionStore(":memory:", clock)
        val outbox = Outbox(dir.resolve("o.db").toString(), dir.resolve("shots"), clock)
        val events = mutableListOf<Triple<String, String, String>>()
        val jobs = mutableListOf<JobContext>()
        val page = FakePage(form).also { p -> p.onClick = { if (it == "e3") p.snap = p.snap.copy(url = p.snap.url + "/done", text = "Thank you for applying!") } }
        var outcome: (ApplyPage) -> FillResult = { p -> FillResult.Ready(FillExecutor.fieldsOf(p.snapshot()), byteArrayOf(9), "") }
        val lookup = JobLookup(BridgeReadClient("http://unused"))
        val service = ApplyService(
            browser = { _ -> if (openFails) error("chromium is down") else page },
            fill = { p, job, _ -> jobs += job; outcome(p) }, lookup = lookup,
            contextFor = { JobContext("", null, null, "", "dkkytech@gmail.com", "years: 15+", null, null, null) },
            store = store, outbox = outbox, tracks = null, viewerUrl = "https://viewer/",
            cargoStatus = CargoStatusListener { f, s, n -> events += Triple(f, s, n); listener?.invoke(f, s, n) },
            clock = clock, worker = MoreExecutors.newDirectExecutorService(),
        )
        val taps = TapService(
            lookup, store, emptyMap(), setOf(user), setOf("apply"), false, Duration.ofDays(7), clock,
            idHandlers = mapOf("submit" to IdVerbHandler(service::submit), "discard" to IdVerbHandler(service::discard), "resume" to IdVerbHandler(service::resume)),
        )
        fun tap(verb: String, n: Long) = taps.tap(TapRequest(verb, n, user, user, 5, clock.now.epochSecond))
        fun review() = outbox.pending().last()
        fun fillId() = review().buttons.first { it.callbackData.startsWith("submit:") || it.callbackData.startsWith("resume:") }.callbackData.substringAfter(':').toLong()
        fun states() = events.map { it.second }
    }

    @Test
    fun `a packet application reports each step with its folder`() {
        val rig = Rig()
        rig.service.applyPacket(packet(), "mcp")
        assertEquals(listOf("in_progress", "parked_at_review"), rig.states())
        assertTrue(rig.events.all { it.first == folder })
        rig.tap("submit", rig.fillId())
        assertEquals(listOf("in_progress", "parked_at_review", "completed"), rig.states())
        assertTrue(rig.events.last().third.contains("Thank you"), rig.events.last().third)
    }

    @Test
    fun `the starting line names the file, and the approval when there was one`() {
        val rig = Rig()
        rig.service.applyPacket(packet(approval = "do the acme draft"), "mcp")
        val first = rig.events.first().third
        assertTrue(first.startsWith("draft approved: \"do the acme draft\" — opened the form via JobBot with Richard-Hatcher-Resume.pdf"), first)
        val plain = Rig()
        plain.service.applyPacket(packet(), "mcp")
        assertEquals("opened the form via JobBot with Richard-Hatcher-Resume.pdf", plain.events.first().third)
    }

    @Test
    fun `a card Apply and a pasted link never write to STATUS`() {
        val rig = Rig()
        val link = rig.taps.applyWithResume(com.jd.jobbot.actions.ResumeApplyRequest(
            jobUrl = "https://jobs.example.com/x", userId = user, chatId = user, fileName = "r.pdf", fileData = ResumeFiles.b64(ResumeFiles.pdf()),
        ))
        // No resumeApply handler on this TapService, so the link is declined; wire one the way Main does.
        assertEquals(Outcomes.REFUSED, link.outcome)
        val withHandler = TapService(rig.lookup, rig.store, emptyMap(), setOf(user), setOf("apply"), false, Duration.ofDays(7), rig.clock, resumeApply = rig.service.applyWithResume)
        val r = withHandler.applyWithResume(com.jd.jobbot.actions.ResumeApplyRequest(
            jobUrl = "https://jobs.example.com/x", userId = user, chatId = user, fileName = "r.pdf", fileData = ResumeFiles.b64(ResumeFiles.pdf()),
        ))
        assertEquals(Outcomes.DONE, r.outcome)
        assertEquals(emptyList(), rig.events, "a pasted link has no packet, so nothing is recorded")
    }

    @Test
    fun `a listener that throws never disturbs the application, and the failure is recorded`() {
        val rig = Rig(listener = { _, _, _ -> error("git is down") })
        val r = rig.service.applyPacket(packet(), "mcp")
        assertEquals(Outcomes.DONE, r.outcome)
        assertEquals(Outcomes.DONE, rig.tap("submit", rig.fillId()).outcome, "Submit still works")
        assertTrue(rig.store.recentErrors(10).any { it.context == "cargo status $folder" && it.message.contains("git is down") })
        assertEquals(3, rig.events.size)
    }

    @Test
    fun `the fill gets the packet's company, title, url, answers and file`() {
        val rig = Rig()
        rig.service.applyPacket(packet(url = "https://jobs.example.com/acme/1?gh_jid=7#apply"), "mcp")
        val job = rig.jobs.single()
        assertEquals("Acme", job.company)
        assertEquals("SDET", job.title)
        assertEquals("https://jobs.example.com/acme/1?gh_jid=7", job.jobUrl)
        assertEquals("Sponsorship: No", job.answers)
        assertEquals("Richard-Hatcher-Resume.pdf", job.resumeFileName)
        assertEquals("Acme — SDET", job.ref)
        assertEquals("years: 15+", job.profileYaml)
    }

    @Test
    fun `a bad URL is refused before anything is created`() {
        val rig = Rig()
        for (url in listOf("http://127.1/x", "file:///etc/passwd", "http://jobbot-mcp:8790/", "https://u:p@x.com/j", "")) {
            val r = rig.service.applyPacket(packet(url = url), "mcp")
            assertEquals(Outcomes.REFUSED, r.outcome, url)
            assertTrue(r.reply!!.startsWith("The apply URL isn't usable"), r.reply)
        }
        assertEquals(emptyList(), rig.store.recent(10))
        assertEquals(emptyList(), rig.events)
        assertEquals(emptyList(), rig.outbox.pending())
    }

    @Test
    fun `a hand-off is recorded, Continue keeps the packet's data, and the next review is recorded`() {
        val rig = Rig()
        var first = true
        rig.outcome = { p -> if (first) { first = false; FillResult.NeedsHuman("CAPTCHA on jobs.example.com", null) } else FillResult.Ready(FillExecutor.fieldsOf(p.snapshot()), byteArrayOf(9), "") }
        rig.service.applyPacket(packet(), "mcp")
        assertEquals(listOf("in_progress", "blocked"), rig.states())
        assertEquals("needs Richard: CAPTCHA on jobs.example.com", rig.events.last().third)
        rig.tap("resume", rig.fillId())
        assertEquals(listOf("in_progress", "blocked", "parked_at_review"), rig.states())
        assertEquals(2, rig.jobs.size)
        assertEquals("Sponsorship: No", rig.jobs.last().answers, "the answers survive a hand-off")
        assertEquals("Acme", rig.jobs.last().company)
        assertEquals("Richard-Hatcher-Resume.pdf", rig.jobs.last().resumeFileName)
    }

    @Test
    fun `a form changed after review gets a fresh review that still completes the right packet`() {
        val rig = Rig()
        rig.service.applyPacket(packet(), "mcp")
        val first = rig.fillId()
        rig.page.values["e1"] = "Someone Else"
        assertEquals(Outcomes.REFUSED, rig.tap("submit", first).outcome)
        val second = rig.fillId()
        assertTrue(second != first)
        assertEquals(Outcomes.DONE, rig.tap("submit", second).outcome)
        val completed = rig.events.filter { it.second == "completed" }
        assertEquals(1, completed.size)
        assertEquals(folder, completed.single().first, "the superseding review carries the packet folder")
        assertTrue(rig.review().text.startsWith("Submitted: Acme — SDET"), rig.review().text)
    }

    @Test
    fun `a submit with no confirmation is recorded as blocked, never completed`() {
        val rig = Rig()
        rig.page.onClick = { if (it == "e3") rig.page.snap = rig.page.snap.copy(text = "Something went wrong, please try again") }
        rig.service.applyPacket(packet(), "mcp")
        rig.tap("submit", rig.fillId())
        assertFalse("completed" in rig.states())
        assertEquals("blocked", rig.states().last())
        assertTrue(rig.events.last().third.contains("UNCONFIRMED"), rig.events.last().third)
        assertTrue(rig.review().text.contains("Acme — SDET"), rig.review().text)
    }

    @Test
    fun `Discard is recorded as blocked`() {
        val rig = Rig()
        rig.service.applyPacket(packet(), "mcp")
        rig.tap("discard", rig.fillId())
        assertEquals("blocked", rig.states().last())
        assertEquals("discarded by Richard in Telegram before submitting; nothing was submitted", rig.events.last().third)
    }

    @Test
    fun `an expired review is recorded once, however many sweeps run`() {
        val rig = Rig()
        rig.service.applyPacket(packet(), "mcp")
        rig.clock.now = rig.clock.now.plus(Duration.ofHours(5))
        rig.service.sweep(); rig.service.sweep(); rig.service.sweep()
        assertEquals(1, rig.events.count { it.third.startsWith("review window expired") })
    }

    @Test
    fun `the 3 hour reminder does not write to STATUS`() {
        val rig = Rig()
        rig.service.applyPacket(packet(), "mcp")
        rig.clock.now = rig.clock.now.plus(Duration.ofMinutes(190))
        rig.service.sweep()
        assertEquals(listOf("in_progress", "parked_at_review"), rig.states())
    }

    @Test
    fun `a failed fill and a browser that will not open are both recorded as blocked`() {
        val failed = Rig()
        failed.outcome = { FillResult.Failed("the model call failed", null) }
        failed.service.applyPacket(packet(), "mcp")
        assertEquals("blocked", failed.states().last())
        assertEquals("JobBot's fill failed: the model call failed", failed.events.last().third)

        val noBrowser = Rig(openFails = true)
        noBrowser.service.applyPacket(packet(), "mcp")
        assertEquals("blocked", noBrowser.states().last())
        assertTrue(noBrowser.events.last().third.contains("couldn't open its browser"), noBrowser.events.last().third)
        assertTrue(noBrowser.outbox.pending().last().text.contains("Acme — SDET"))
    }

    @Test
    fun `after a JobBot restart the old review cannot be submitted and nothing false is recorded`() {
        val rig = Rig()
        rig.service.applyPacket(packet(), "mcp")
        val id = rig.fillId()
        val restarted = ApplyService(
            browser = { _ -> rig.page }, fill = { p, _, _ -> rig.outcome(p) }, lookup = rig.lookup, contextFor = { null },
            store = rig.store, outbox = rig.outbox, tracks = null, viewerUrl = null, clock = rig.clock,
            cargoStatus = CargoStatusListener { f, s, n -> rig.events += Triple(f, s, n) },
            worker = MoreExecutors.newDirectExecutorService(),
        )
        val r = restarted.submit(id, TapRequest("submit", id, user, user, 5, rig.clock.now.epochSecond))
        assertEquals(Outcomes.REFUSED, r.outcome)
        assertFalse("completed" in rig.states())
        assertFalse("click e3" in rig.page.actions)
    }

    @Test
    fun `with no listener everything still works`() {
        val rig = Rig()
        val bare = ApplyService(
            browser = { _ -> rig.page }, fill = { p, _, _ -> rig.outcome(p) }, lookup = rig.lookup,
            contextFor = { JobContext("", null, null, "", "dkkytech@gmail.com", "p", null, null, null) },
            store = rig.store, outbox = rig.outbox, tracks = null, viewerUrl = null, clock = rig.clock, worker = MoreExecutors.newDirectExecutorService(),
        )
        assertEquals(Outcomes.DONE, bare.applyPacket(packet(), "mcp").outcome)
        assertNull(rig.events.firstOrNull())
    }

    @Test
    fun `a packet dry run records an action and touches nothing else`() {
        val rig = Rig()
        val r = rig.service.dryRunPacket(packet(), "mcp")
        assertEquals(Outcomes.DRY_RUN, r.outcome)
        assertEquals("[dry run] Would apply to Acme — SDET with Richard-Hatcher-Resume.pdf.", r.reply)
        assertEquals(emptyList(), rig.events, "no STATUS line")
        assertEquals(emptyList(), rig.jobs, "no fill")
        assertEquals(emptyList(), rig.page.actions, "no browser")
        assertEquals(emptyList(), rig.outbox.pending(), "no review")
        val row = rig.store.recent(5).single()
        assertEquals("dry_run", row.status)
        assertEquals("apply", row.verb, "not a fill, so it never counts as one")
        assertTrue(row.details!!.contains(folder))
    }

    @Test
    fun `a packet dry run checks the URL like a real one`() {
        val rig = Rig()
        for (url in listOf("http://127.1/x", "file:///etc/passwd", "")) {
            val r = rig.service.dryRunPacket(packet(url = url), "mcp")
            assertEquals(Outcomes.REFUSED, r.outcome, url)
        }
        assertEquals(emptyList(), rig.store.recent(5))
    }

    @Test
    fun `dry runs leave the way clear for the real application`() {
        val rig = Rig()
        repeat(3) { rig.service.dryRunPacket(packet(), "mcp") }
        assertEquals(Outcomes.DONE, rig.service.applyPacket(packet(), "mcp").outcome)
        assertEquals(listOf("in_progress", "parked_at_review"), rig.states())
        assertEquals(1, rig.jobs.size)
    }
}
