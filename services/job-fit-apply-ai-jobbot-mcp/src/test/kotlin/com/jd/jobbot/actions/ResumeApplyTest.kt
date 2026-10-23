package com.jd.jobbot.actions

import com.jd.jobbot.bridge.BridgeReadClient
import com.jd.jobbot.jobs.JobLookup
import com.jd.jobbot.support.FakeBridge
import com.jd.jobbot.support.ResumeFiles
import kotlinx.serialization.json.JsonObject
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Apply to this" with a resume attached: the gates in front of ApplyService, for a card reply and for a pasted link. */
class ResumeApplyTest {
    private val now = Instant.parse("2026-10-01T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val user = "8679792351"

    private class StubLookup(private val events: Map<Long, JsonObject>, private val fail: Boolean = false) :
        JobLookup(BridgeReadClient("http://unused")) {
        override fun event(seq: Long): JsonObject? = if (fail) throw java.io.IOException("down") else events[seq]
    }

    private class Seen(val ctx: TapContext, val upload: ResumeUpload)

    private val seen = mutableListOf<Seen>()
    private val recording = ResumeApplyHandler { ctx, _, upload -> seen += Seen(ctx, upload); TapResponse(Outcomes.DONE, reply = "Filling ${ctx.ref}") }

    private fun service(
        events: Map<Long, JsonObject> = mapOf(7663L to FakeBridge.event(7663)),
        handled: Set<String> = setOf("apply"),
        dryRun: Boolean = false,
        handler: ResumeApplyHandler? = recording,
        lookupFails: Boolean = false,
        store: ActionStore = ActionStore(":memory:", clock),
    ) = TapService(
        StubLookup(events, lookupFails), store, emptyMap(), setOf(user), handled, dryRun, Duration.ofDays(7), clock,
        resumeApply = handler,
    )

    private fun card(
        seq: Long? = 7663, name: String? = "Richard Hatcher.pdf", data: ByteArray = ResumeFiles.pdf(),
        from: String = user, sentAt: Instant? = now.minusSeconds(60), url: String? = null, raw: String? = null,
    ) = ResumeApplyRequest(
        seq = seq, jobUrl = url, userId = from, chatId = user, messageId = 99, messageDate = sentAt?.epochSecond,
        fileName = name, fileData = raw ?: ResumeFiles.b64(data),
    )

    // ── a reply to a card ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a PDF replied to a card starts the application with that file`() {
        val r = service().applyWithResume(card())
        assertEquals(Outcomes.DONE, r.outcome)
        val s = seen.single()
        assertEquals("Richard Hatcher.pdf", s.upload.fileName)
        assertEquals(7663L, s.ctx.request.seq)
        assertEquals("#J7663", s.ctx.ref)
        assertEquals("url:acme.co/jobs/7663", s.ctx.jobKey)
        assertEquals("apply", s.ctx.request.verb)
    }

    @Test
    fun `a docx is accepted too`() {
        service().applyWithResume(card(name = "resume.docx", data = ResumeFiles.docx()))
        assertEquals("resume.docx", seen.single().upload.fileName)
    }

    @Test
    fun `a skipped job qualifies because Richard chose the file himself`() {
        val gated = FakeBridge.event(7708, action = "SKIP", skipReason = "Posted pay is below target")
        val r = service(events = mapOf(7708L to gated)).applyWithResume(card(seq = 7708))
        assertEquals(Outcomes.DONE, r.outcome)
        assertEquals(1, seen.size)
    }

    @Test
    fun `an offered job that was never tailored qualifies`() {
        val offered = FakeBridge.event(7709, action = "OFFER")
        assertEquals(Outcomes.DONE, service(events = mapOf(7709L to offered)).applyWithResume(card(seq = 7709)).outcome)
    }

    @Test
    fun `a job without a posting URL has nothing to apply to`() {
        val r = service(events = mapOf(7663L to FakeBridge.event(7663, jobUrl = null))).applyWithResume(card())
        assertEquals(Outcomes.REFUSED, r.outcome)
        assertEquals("#J7663 has no posting URL, so there's nothing to apply to.", r.reply)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `an unknown job is refused`() {
        assertEquals("Unknown job #J1.", service().applyWithResume(card(seq = 1)).reply)
    }

    @Test
    fun `users off the allowlist are refused before the file is looked at`() {
        val r = service().applyWithResume(card(from = "123", raw = "!!not base64!!"))
        assertEquals("Not authorized.", r.reply)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `cards older than the TTL are refused`() {
        val r = service().applyWithResume(card(sentAt = now.minus(Duration.ofDays(7)).minusSeconds(1)))
        assertEquals(Outcomes.REFUSED, r.outcome)
        assertTrue(r.reply!!.contains("more than 7 days old"))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `a card exactly at the TTL still works`() {
        assertEquals(Outcomes.DONE, service().applyWithResume(card(sentAt = now.minus(Duration.ofDays(7)))).outcome)
    }

    @Test
    fun `a card whose age is unknown is refused`() {
        assertEquals(Outcomes.REFUSED, service().applyWithResume(card(sentAt = null)).outcome)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `the card age check does not apply to a pasted link`() {
        val r = service().applyWithResume(card(seq = null, sentAt = null, url = "https://jobs.lever.co/acme/1"))
        assertEquals(Outcomes.DONE, r.outcome)
    }

    // ── a pasted link ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `a resume with a job link starts an ad hoc application`() {
        val r = service().applyWithResume(card(seq = null, url = "https://jobs.lever.co/acme/123#apply"))
        assertEquals(Outcomes.DONE, r.outcome)
        val s = seen.single()
        assertEquals(AD_HOC_SEQ, s.ctx.request.seq)
        assertEquals("Job at jobs.lever.co", s.ctx.ref)
        assertEquals("https://jobs.lever.co/acme/123", s.ctx.event["job_url"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals("url:jobs.lever.co/acme/123", s.ctx.jobKey)
    }

    @Test
    fun `a pasted link needs no JFAA lookup`() {
        val r = service(events = emptyMap(), lookupFails = true).applyWithResume(card(seq = null, url = "https://jobs.lever.co/acme/123"))
        assertEquals(Outcomes.DONE, r.outcome)
    }

    @Test
    fun `a bad or private link is refused with the reason`() {
        assertEquals("There's no job link.", service().applyWithResume(card(seq = null, url = null)).reply)
        assertEquals("Only http and https job links work.", service().applyWithResume(card(seq = null, url = "ftp://acme.com/x")).reply)
        assertEquals(
            "That link points to a private address, not a job site.",
            service().applyWithResume(card(seq = null, url = "http://jobbot-mcp:8790/health")).reply,
        )
        assertEquals("Links to an IP address aren't allowed.", service().applyWithResume(card(seq = null, url = "http://127.0.0.1/x")).reply)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `the card wins when a request carries both a card and a link`() {
        service().applyWithResume(card(seq = 7663, url = "https://elsewhere.example.com/job"))
        assertEquals(7663L, seen.single().ctx.request.seq)
    }

    @Test
    fun `a seq of zero is not a card`() {
        val r = service().applyWithResume(card(seq = 0, url = null))
        assertEquals("There's no job link.", r.reply)
    }

    // ── the file ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `files that are not a PDF or docx are refused`() {
        assertEquals("Only a PDF or a Word .docx resume can be used.", service().applyWithResume(card(name = "resume.txt")).reply)
        assertEquals("That file isn't a real PDF.", service().applyWithResume(card(data = "MZ not a pdf".toByteArray())).reply)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `undecodable data is refused`() {
        assertEquals("I couldn't read that file.", service().applyWithResume(card(raw = "***")).reply)
    }

    @Test
    fun `an oversize file is refused before it is decoded`() {
        val r = service().applyWithResume(card(raw = "A".repeat(ResumeUpload.MAX_BYTES / 3 * 4 + 100)))
        assertTrue(r.reply!!.contains("over 10 MB"))
        assertTrue(seen.isEmpty())
    }

    // ── switches ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `it follows the apply switch`() {
        assertEquals("Apply isn't available yet.", service(handled = setOf("reply")).applyWithResume(card()).reply)
    }

    @Test
    fun `with Apply off the file is declined, not silently dropped`() {
        val r = service(handler = null).applyWithResume(card())
        assertEquals(Outcomes.REFUSED, r.outcome)
        assertEquals("Apply isn't switched on, so I can't use that resume.", r.reply)
    }

    @Test
    fun `dry run validates and records, but never starts the fill`() {
        val store = ActionStore(":memory:", clock)
        val r = service(dryRun = true, store = store).applyWithResume(card())
        assertEquals(Outcomes.DRY_RUN, r.outcome)
        assertEquals("[dry run] Would apply to #J7663 with Richard Hatcher.pdf.", r.reply)
        assertTrue(seen.isEmpty())
        assertEquals(Outcomes.DRY_RUN, store.recent().single().status)
    }

    @Test
    fun `dry run still refuses what live mode would refuse`() {
        assertEquals(Outcomes.REFUSED, service(dryRun = true).applyWithResume(card(name = "r.exe")).outcome)
    }

    @Test
    fun `a bridge failure is reported and logged, not thrown`() {
        val store = ActionStore(":memory:", clock)
        val r = service(lookupFails = true, store = store).applyWithResume(card())
        assertEquals(Outcomes.ERROR, r.outcome)
        assertTrue(store.recentErrors().single().message.contains("bridge lookup failed"))
    }

    @Test
    fun `a handler exception becomes an error reply and an error record`() {
        val store = ActionStore(":memory:", clock)
        val boom = ResumeApplyHandler { _, _, _ -> error("kaboom") }
        val r = service(handler = boom, store = store).applyWithResume(card())
        assertEquals(Outcomes.ERROR, r.outcome)
        assertEquals("kaboom", store.recentErrors().single().message)
        assertNotNull(r.reply)
        assertNull(r.toast)
    }
}
