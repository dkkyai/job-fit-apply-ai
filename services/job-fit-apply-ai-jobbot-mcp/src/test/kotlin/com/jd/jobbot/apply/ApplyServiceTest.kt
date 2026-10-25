package com.jd.jobbot.apply

import com.google.common.util.concurrent.MoreExecutors
import com.jd.jobbot.actions.ActionStore
import com.jd.jobbot.actions.IdVerbHandler
import com.jd.jobbot.actions.Outbox
import com.jd.jobbot.actions.Outcomes
import com.jd.jobbot.actions.TapRequest
import com.jd.jobbot.actions.TapService
import com.jd.jobbot.bridge.BridgeReadClient
import com.jd.jobbot.bridge.TrackWriter
import com.jd.jobbot.jobs.JobLookup
import com.jd.jobbot.support.FakeBridge
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplyServiceTest {
    @TempDir lateinit var dir: Path
    private val user = "8679792351"

    /** A clock the test moves. */
    private class Moving(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId?) = this
        override fun instant() = now
    }

    private class Lookup(val event: JsonObject, val trackStatus: String = "backlog") : JobLookup(BridgeReadClient("http://unused")) {
        override fun event(seq: Long) = event.takeIf { seq == 9L }
        override fun find(seq: Long) = event(seq)?.let { Found(it, FakeBridge.track(41, "Acme", "Staff SDET", status = trackStatus), "track_id") }
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

    /** The inbox as the code step sees it: the code the next lookup finds (null: none has arrived yet), or a failure. */
    private inner class FakeMail(var code: String? = null, var error: Exception? = null) :
        Verifier(com.jd.jobbot.gmail.GmailClient(object : com.jd.jobbot.gmail.GmailAuth(dir, dir) {})) {
        val asked = mutableListOf<Pair<String, Int?>>()
        override fun codeOfLength(site: String, sinceMillis: Long, length: Int?, wait: Duration): String? {
            asked += site to length
            error?.let { throw it }
            return code
        }
    }

    private val legend = "A verification code was sent to dkkytech@gmail.com. To submit your application, enter the 8-character code to confirm you're a human."
    private val codeBoxes = (0..7).map { Element("c$it", "input", "text", if (it == 0) "Security code" else "", inForm = true, maxLength = 1) }
    private fun Rig.typed() = (0..7).joinToString("") { page.values["c$it"].orEmpty() }

    /** Greenhouse's flow: the first Submit shows the code boxes; a second one with [accepts] typed in confirms. */
    private fun Rig.siteWantsCode(accepts: String = "aB3dE5gH") {
        var clicks = 0
        page.onClick = { id ->
            if (id == "e3") {
                clicks++
                when {
                    clicks == 1 -> page.snap = page.snap.copy(elements = page.snap.elements + codeBoxes, text = legend)
                    typed() == accepts -> page.snap = page.snap.copy(elements = readyForm.elements, url = page.snap.url + "/confirmation", text = "Thank you for applying!")
                }
            }
        }
    }

    private fun Rig.fill() = store.recent().first { it.verb == "fill" }

    private inner class Rig(
        var outcome: (ApplyPage) -> FillResult = { p -> FillResult.Ready(FillExecutor.fieldsOf(p.snapshot()), byteArrayOf(9), "") },
        trackStatus: String = "backlog",
        val mail: FakeMail? = null,
    ) {
        val clock = Moving(Instant.parse("2026-10-01T12:00:00Z"))
        val store = ActionStore(":memory:", clock)
        val outbox = Outbox(dir.resolve("o.db").toString(), dir.resolve("shots"), clock)
        val tracks = Tracks()
        val creds = Credentials(dir.resolve("creds.json"), "dkkytech@gmail.com", clock)
        val page = FakePage(readyForm).also { p -> p.onClick = { if (it == "e3") p.snap = p.snap.copy(url = p.snap.url + "/confirmation", text = "Thank you for applying!") } }
        var opened = 0
        val lookup = Lookup(FakeBridge.event(9, jobUrl = "https://boards.greenhouse.io/acme/jobs/9"), trackStatus)
        var service = newService()
        var taps = newTaps()

        /** JobBot restarted: same database and outbox, but no tabs and no workers. */
        fun restart() { service = newService(); taps = newTaps() }

        private fun newService() = ApplyService(
            browser = { _ -> opened++; page },
            fill = { p, _, _ -> outcome(p) },
            lookup = lookup,
            contextFor = { seq -> JobContext("#J$seq", "Acme", "Staff SDET", "https://boards.greenhouse.io/acme/jobs/9", "dkkytech@gmail.com", null, null, null, null) },
            store = store, outbox = outbox, tracks = tracks, viewerUrl = "https://viewer.tailnet/",
            credentials = creds, verifier = mail,
            clock = clock, worker = MoreExecutors.newDirectExecutorService(),
        )
        private fun newTaps() = TapService(lookup, store, mapOf("apply" to service.apply), setOf(user), setOf("apply"), false, Duration.ofDays(7), clock,
            idHandlers = mapOf("submit" to IdVerbHandler(service::submit), "discard" to IdVerbHandler(service::discard), "resume" to IdVerbHandler(service::resume)))

        fun tap(verb: String, n: Long = 9) = taps.tap(TapRequest(verb, n, user, user, 5, clock.now.epochSecond))
        fun review() = outbox.pending().last()
        fun fillId() = review().buttons.first { it.callbackData.startsWith("submit:") || it.callbackData.startsWith("resume:") }.callbackData.substringAfter(':').toLong()
    }

    @Test
    fun `Apply fills in the background and posts a review with Submit and Discard`() {
        val rig = Rig()
        val r = rig.tap("apply")
        assertEquals(Outcomes.DONE, r.outcome)
        assertTrue(r.reply!!.contains("nothing is submitted until you tap ✅ Submit"))
        val review = rig.review()
        assertTrue(review.text.contains("First name: Richard"), review.text)
        assertTrue(review.hasPhoto)
        assertEquals(listOf("✅ Submit", "✖ Discard"), review.buttons.map { it.text })
        assertFalse("click e3" in rig.page.actions, "filling never submits")
    }

    @Test
    fun `Submit clicks the one submit button, records applied, and posts the confirmation`() {
        val rig = Rig()
        rig.tap("apply")
        val r = rig.tap("submit", rig.fillId())
        assertEquals(Outcomes.DONE, r.outcome)
        assertTrue("click e3" in rig.page.actions)
        assertEquals(listOf("status applied", "event application_submitted"), rig.tracks.calls)
        assertTrue(rig.outbox.pending().last().text.startsWith("Submitted:"))
        assertEquals(Outcomes.ALREADY, rig.tap("submit", rig.store.recent().first { it.verb == "fill" }.id).outcome)
    }

    @Test
    fun `a form changed after review is not submitted and gets a fresh review`() {
        val rig = Rig()
        rig.tap("apply")
        val first = rig.fillId()
        rig.page.values["e1"] = "Someone Else"
        val r = rig.tap("submit", first)
        assertEquals(Outcomes.REFUSED, r.outcome)
        assertFalse("click e3" in rig.page.actions)
        assertTrue(rig.review().text.contains("Someone Else"))
        assertEquals(Outcomes.DONE, rig.tap("submit", rig.fillId()).outcome)
    }

    @Test
    fun `the review window expires at 4 h with a reminder at 3 h`() {
        val rig = Rig()
        rig.tap("apply")
        val id = rig.fillId()
        rig.clock.now = rig.clock.now.plus(Duration.ofMinutes(181))
        rig.service.sweep()
        assertTrue(rig.review().text.startsWith("Reminder:"))
        rig.service.sweep()
        assertEquals(1, rig.outbox.pending().count { it.text.startsWith("Reminder:") }, "remind once")
        rig.clock.now = rig.clock.now.plus(Duration.ofMinutes(60))
        rig.service.sweep()
        assertTrue(rig.review().text.contains("expired"))
        assertEquals(Outcomes.REFUSED, rig.tap("submit", id).outcome)
        assertTrue("close" in rig.page.actions)
    }

    @Test
    fun `a hand-off posts Continue, and Continue resumes the same tab`() {
        var calls = 0
        val rig = Rig(outcome = { p -> if (calls++ == 0) FillResult.NeedsHuman("There's a CAPTCHA.", null) else FillResult.Ready(FillExecutor.fieldsOf(p.snapshot()), byteArrayOf(1), "") })
        rig.tap("apply")
        val handoff = rig.review()
        assertTrue(handoff.text.contains("CAPTCHA") && handoff.text.contains("https://viewer.tailnet/"))
        assertEquals("▶ Continue", handoff.buttons.first().text)
        assertEquals(Outcomes.DONE, rig.tap("resume", rig.fillId()).outcome)
        assertEquals(1, rig.opened, "the same tab is reused")
        assertTrue(rig.review().buttons.any { it.text == "✅ Submit" })
    }

    @Test
    fun `a hand-off left by a restarted JobBot is restarted by the next Apply tap`() {
        var calls = 0
        val rig = Rig(outcome = { p -> if (calls++ == 0) FillResult.NeedsHuman("No saved password.", null) else FillResult.Ready(FillExecutor.fieldsOf(p.snapshot()), byteArrayOf(1), "") })
        rig.tap("apply")
        val stale = rig.fillId()
        rig.restart()
        assertEquals(Outcomes.DONE, rig.tap("apply").outcome, "not 'waiting on you' forever")
        assertEquals(ApplyService.EXPIRED, rig.store.byId(stale)?.status)
        assertTrue(rig.review().buttons.any { it.text == "✅ Submit" })
        assertEquals(2, rig.opened)
    }

    @Test
    fun `Continue after a restart expires the hand-off so Apply can start again`() {
        val rig = Rig(outcome = { FillResult.NeedsHuman("CAPTCHA", null) })
        rig.tap("apply")
        val stale = rig.fillId()
        rig.restart()
        val r = rig.tap("resume", stale)
        assertEquals(Outcomes.REFUSED, r.outcome)
        assertTrue(r.toast!!.contains("Tap Apply"))
        assertEquals(ApplyService.EXPIRED, rig.store.byId(stale)?.status)
        assertEquals(Outcomes.DONE, rig.tap("apply").outcome)
    }

    @Test
    fun `a fill still running in this process is not restarted`() {
        val rig = Rig(outcome = { FillResult.NeedsHuman("CAPTCHA", null) })
        rig.tap("apply")
        assertEquals(Outcomes.ALREADY, rig.tap("apply").outcome, "its tab is alive: Continue, don't restart")
        assertEquals(1, rig.opened)
    }

    @Test
    fun `Discard closes the tab and nothing is submitted`() {
        val rig = Rig()
        rig.tap("apply")
        val id = rig.fillId()
        assertEquals(Outcomes.DONE, rig.tap("discard", id).outcome)
        assertTrue("close" in rig.page.actions)
        assertEquals(Outcomes.REFUSED, rig.tap("submit", id).outcome)
    }

    @Test
    fun `a second Apply tap does not start a second fill`() {
        val rig = Rig()
        rig.tap("apply")
        assertEquals(Outcomes.ALREADY, rig.tap("apply").outcome)
        assertEquals(1, rig.opened)
    }

    @Test
    fun `a job the track already marks applied is not filled`() {
        val rig = Rig(trackStatus = "applied")
        assertEquals(Outcomes.ALREADY, rig.tap("apply").outcome)
        assertEquals(0, rig.opened)
    }

    @Test
    fun `a failed fill closes the tab and says why`() {
        val rig = Rig(outcome = { FillResult.Failed("The model call failed", null) })
        rig.tap("apply")
        assertTrue(rig.review().text.contains("Couldn't fill"))
        assertTrue("close" in rig.page.actions)
    }

    @Test
    fun `two submit buttons are ambiguous and nothing is clicked`() {
        val rig = Rig()
        rig.page.snap = readyForm.copy(elements = readyForm.elements + Element("e4", "button", "submit", text = "Submit", inForm = true, formFilled = true))
        rig.tap("apply")
        val r = rig.tap("submit", rig.fillId())
        assertEquals(Outcomes.REFUSED, r.outcome)
        assertFalse(rig.page.actions.any { it.startsWith("click") })
    }

    @Test
    fun `a page change without a confirmation is not recorded as applied`() {
        val rig = Rig()
        rig.page.onClick = { if (it == "e3") rig.page.snap = rig.page.snap.copy(url = "https://boards.greenhouse.io/login", text = "Please sign in") }
        rig.tap("apply")
        val r = rig.tap("submit", rig.fillId())
        assertTrue(r.toast!!.contains("couldn't confirm"), r.toast)
        assertTrue(rig.tracks.calls.isEmpty(), "no applied status without a confirmation")
        assertFalse("close" in rig.page.actions, "the tab stays open for checking")
        assertTrue(rig.outbox.pending().last().text.contains("didn't see a confirmation"))
    }

    @Test
    fun `a confirmed submission activates the password created for that site`() {
        val rig = Rig()
        rig.creds.createPending("greenhouse.io", null, "#J9")
        rig.tap("apply")
        rig.tap("submit", rig.fillId())
        assertEquals(Credentials.ACTIVE, rig.creds.get("greenhouse.io")!!.status)
    }

    @Test
    fun `discarding during a fill means no review is ever posted`() {
        lateinit var rig: Rig
        rig = Rig(outcome = { p ->
            // Richard taps Discard while the fill is still running.
            val id = rig.store.recent().first { it.verb == "fill" }.id
            rig.service.discard(id, TapRequest("discard", id, user, user, 5, rig.clock.now.epochSecond))
            FillResult.Ready(FillExecutor.fieldsOf(p.snapshot()), byteArrayOf(1), "")
        })
        rig.tap("apply")
        assertTrue(rig.outbox.pending().none { it.buttons.any { b -> b.text == "✅ Submit" } }, "no review for a discarded fill")
        assertEquals(ApplyService.CANCELLED, rig.store.recent().first { it.verb == "fill" }.status)
    }

    @Test
    fun `fills an earlier process left open are closed at startup and Richard is told`() {
        val rig = Rig()
        val ids = listOf(ApplyService.QUEUED, ApplyService.FILLING, Outcomes.AWAITING_APPROVAL, ApplyService.NEEDS_HUMAN)
            .mapIndexed { i, status -> status to rig.store.insert(ApplyService.FILL, "k$i", 9, status, user, null).id }
        val finished = rig.store.insert(ApplyService.FILL, "k9", 9, Outcomes.DONE, user, null).id
        assertEquals(4, rig.service.recoverInterrupted())
        ids.forEach { (_, id) -> assertEquals(ApplyService.EXPIRED, rig.store.byId(id)?.status) }
        assertEquals(Outcomes.DONE, rig.store.byId(finished)?.status, "a finished application is never touched")
        val told = rig.outbox.pending()
        assertEquals(4, told.size)
        assertTrue(told.all { it.text.contains("restarted") && it.text.contains("Nothing was submitted") }, told.first().text)
        assertEquals(0, rig.service.recoverInterrupted(), "nothing is left to recover the second time")
    }

    @Test
    fun `an old leftover is closed quietly, not announced again`() {
        val rig = Rig()
        val id = rig.store.insert(ApplyService.FILL, "old", 9, ApplyService.NEEDS_HUMAN, user, null).id
        rig.clock.now = rig.clock.now.plus(Duration.ofHours(5))
        assertEquals(1, rig.service.recoverInterrupted())
        assertEquals(ApplyService.EXPIRED, rig.store.byId(id)?.status)
        assertEquals(0, rig.outbox.pending().size, "older than the review window: no message")
    }

    @Test
    fun `after recovery the job can be applied to again`() {
        val rig = Rig()
        rig.tap("apply")
        val first = rig.fillId()
        rig.restart()
        rig.service.recoverInterrupted()
        assertEquals(ApplyService.EXPIRED, rig.store.byId(first)?.status)
        assertEquals(Outcomes.DONE, rig.tap("apply").outcome)
    }

    @Test
    fun `a code prompt after Submit is read from the inbox, typed in, and submitted once more`() {
        val rig = Rig(mail = FakeMail(code = "aB3dE5gH"))
        rig.siteWantsCode()
        rig.tap("apply")
        val r = rig.tap("submit", rig.fillId())
        assertEquals(Outcomes.DONE, r.outcome)
        assertTrue(r.toast!!.contains("code"), r.toast)
        assertEquals(Outcomes.DONE, rig.fill().status)
        assertEquals("aB3dE5gH", rig.typed())
        assertEquals(listOf("click e3") + (0..7).map { "fill c$it" } + "click e3", rig.page.actions.filter { it.startsWith("click") || it.startsWith("fill c") })
        assertEquals(listOf("status applied", "event application_submitted"), rig.tracks.calls)
        assertTrue(rig.outbox.pending().last().text.startsWith("Submitted:"))
        assertEquals("greenhouse.io" to 8, rig.mail!!.asked.single().let { it.first.removePrefix("boards.") to it.second })
    }

    @Test
    fun `with no inbox the code step goes to Richard with Continue, and nothing is called submitted`() {
        val rig = Rig()
        rig.siteWantsCode()
        rig.tap("apply")
        rig.tap("submit", rig.fillId())
        assertEquals(ApplyService.NEEDS_CODE, rig.fill().status)
        val msg = rig.outbox.pending().last()
        assertTrue(msg.text.contains("wants the code it emailed") && msg.text.contains("Gmail isn't set up"), msg.text)
        assertEquals(listOf("▶ Continue", "✖ Discard"), msg.buttons.map { it.text })
        assertTrue(rig.tracks.calls.isEmpty())
        assertEquals(1, rig.page.actions.count { it == "click e3" }, "no second click without a code")
        assertFalse("close" in rig.page.actions, "the tab stays open")
    }

    @Test
    fun `Continue looks for the email again and finishes once it has arrived`() {
        val mail = FakeMail(code = null)
        val rig = Rig(mail = mail)
        rig.siteWantsCode()
        rig.tap("apply")
        rig.tap("submit", rig.fillId())
        assertEquals(ApplyService.NEEDS_CODE, rig.fill().status)
        assertTrue(rig.outbox.pending().last().text.contains("has arrived yet"))
        mail.code = "aB3dE5gH"
        rig.tap("resume", rig.fill().id)
        assertEquals(Outcomes.DONE, rig.fill().status)
        assertEquals(2, rig.page.actions.count { it == "click e3" })
        assertEquals(2, mail.asked.size)
    }

    @Test
    fun `Continue after Richard typed the code himself sees the confirmation and records it`() {
        val rig = Rig()
        rig.siteWantsCode()
        rig.tap("apply")
        rig.tap("submit", rig.fillId())
        // Richard types the code in the viewer and clicks the site's own submit.
        (0..7).forEach { rig.page.values["c$it"] = "aB3dE5gH"[it].toString() }
        rig.page.onClick("e3")
        rig.tap("resume", rig.fill().id)
        assertEquals(Outcomes.DONE, rig.fill().status)
        assertEquals(1, rig.page.actions.count { it == "click e3" }, "JobBot clicked nothing: the page was already confirmed")
        assertEquals(listOf("status applied", "event application_submitted"), rig.tracks.calls)
    }

    @Test
    fun `a code the site refuses is not retried by JobBot`() {
        val rig = Rig(mail = FakeMail(code = "WRONGONE"))
        rig.siteWantsCode(accepts = "aB3dE5gH")
        rig.tap("apply")
        rig.tap("submit", rig.fillId())
        assertEquals(ApplyService.NEEDS_CODE, rig.fill().status)
        assertEquals(2, rig.page.actions.count { it == "click e3" }, "the first Submit and the one after the code, never a third")
        assertTrue(rig.outbox.pending().last().text.contains("didn't accept the code"), rig.outbox.pending().last().text)
        assertTrue(rig.tracks.calls.isEmpty())
    }

    @Test
    fun `a form that changed after the review gets no code and no second click`() {
        val rig = Rig(mail = FakeMail(code = "aB3dE5gH"))
        rig.siteWantsCode()
        rig.page.onClick.let { first ->
            rig.page.onClick = { id -> first(id); if (id == "e3") rig.page.values["e1"] = "Someone Else" }
        }
        rig.tap("apply")
        rig.tap("submit", rig.fillId())
        assertEquals(ApplyService.NEEDS_CODE, rig.fill().status)
        assertTrue(rig.outbox.pending().last().text.contains("no longer matches"), rig.outbox.pending().last().text)
        assertEquals("", rig.typed())
        assertEquals(1, rig.page.actions.count { it == "click e3" })
    }

    @Test
    fun `an inbox that cannot be read is a hand-off with the reason, not a crash`() {
        val rig = Rig(mail = FakeMail(error = RuntimeException("Gmail token expired")))
        rig.siteWantsCode()
        rig.tap("apply")
        rig.tap("submit", rig.fillId())
        assertEquals(ApplyService.NEEDS_CODE, rig.fill().status)
        assertTrue(rig.outbox.pending().last().text.contains("Gmail token expired"), rig.outbox.pending().last().text)
    }

    @Test
    fun `a page that answers slowly is given a few looks before it is called unknown`() {
        val rig = Rig()
        rig.page.onClick = { if (it == "e3") rig.page.snap = rig.page.snap.copy(text = "Submitting…") }
        rig.page.onPause = { n -> if (n == 2) rig.page.snap = rig.page.snap.copy(text = "Thank you for applying!") }
        rig.tap("apply")
        rig.tap("submit", rig.fillId())
        assertEquals(Outcomes.DONE, rig.fill().status)
        assertEquals(2, rig.page.pauses)
    }

    @Test
    fun `a page that never confirms says what it showed`() {
        val rig = Rig()
        rig.page.onClick = { if (it == "e3") rig.page.snap = rig.page.snap.copy(text = "Apply\nThis field is required\nPlease complete the CAPTCHA") }
        rig.tap("apply")
        rig.tap("submit", rig.fillId())
        assertEquals(ApplyService.UNCONFIRMED, rig.fill().status)
        val text = rig.outbox.pending().last().text
        assertTrue(text.contains("This field is required") && text.contains("Please complete the CAPTCHA"), text)
        assertEquals(3, rig.page.pauses, "it looked again before giving up")
    }

    @Test
    fun `a restart after Submit was clicked is not reported as nothing submitted`() {
        val rig = Rig()
        listOf(ApplyService.SUBMITTING, ApplyService.VERIFYING, ApplyService.NEEDS_CODE).forEachIndexed { i, status -> rig.store.insert(ApplyService.FILL, "k$i", 9, status, user, null) }
        assertEquals(3, rig.service.recoverInterrupted())
        assertTrue(rig.store.open(ApplyService.FILL).isEmpty())
        assertTrue(rig.store.recent().filter { it.verb == "fill" }.all { it.status == ApplyService.UNCONFIRMED })
        val told = rig.outbox.pending()
        assertTrue(told.all { it.text.contains("Submit was clicked") && !it.text.contains("Nothing was submitted") }, told.first().text)
    }

    @Test
    fun `a second Apply tap while the site waits for its code does not start another fill`() {
        val rig = Rig()
        rig.siteWantsCode()
        rig.tap("apply")
        rig.tap("submit", rig.fillId())
        assertEquals(Outcomes.ALREADY, rig.tap("apply").outcome)
        assertEquals(Outcomes.ALREADY, rig.tap("submit", rig.fill().id).outcome)
    }

    @Test
    fun `discarding while the site waits for its code does not claim nothing was clicked`() {
        val rig = Rig()
        rig.siteWantsCode()
        rig.tap("apply")
        rig.tap("submit", rig.fillId())
        val r = rig.tap("discard", rig.fill().id)
        assertEquals(ApplyService.CANCELLED, rig.fill().status)
        assertTrue(r.toast!!.contains("still waiting for its code"), r.toast)
        assertTrue("close" in rig.page.actions)
    }
}
