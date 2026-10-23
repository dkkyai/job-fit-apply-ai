package com.jd.jobbot.apply

import com.jd.jobbot.actions.ActionStore
import com.jd.jobbot.actions.AD_HOC_SEQ
import com.jd.jobbot.actions.InlineButton
import com.jd.jobbot.actions.JobUrls
import com.jd.jobbot.actions.Outbox
import com.jd.jobbot.actions.Outcomes
import com.jd.jobbot.actions.ResumeApplyHandler
import com.jd.jobbot.actions.ResumeUpload
import com.jd.jobbot.actions.TapContext
import com.jd.jobbot.actions.TapRequest
import com.jd.jobbot.actions.TapResponse
import com.jd.jobbot.actions.VerbHandler
import com.jd.jobbot.bridge.TrackWriter
import com.jd.jobbot.bridge.str
import com.jd.jobbot.jobs.JobIdentity
import com.jd.jobbot.jobs.JobLookup
import com.jd.jobbot.jobs.JobRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The Apply workflow (Phase 4). A tap on Apply queues a fill in the apply browser; the result is
 * posted (screenshot + every filled field) under ✅ Submit / ✖ Discard. Only a ✅ Submit tap
 * clicks the site's submit button, and only if the form still matches what the card showed.
 * A filled form waits at most [reviewTtl] (4 h), with a reminder at [reminderAfter] (3 h).
 * Fills run one at a time on a single worker.
 */
class ApplyService(
    private val browser: ApplyBrowser,
    /** FillAgent.run: fill the page for the job; `false` continues a page already in progress. */
    private val fill: (ApplyPage, JobContext, Boolean) -> FillResult,
    private val lookup: JobLookup,
    private val contextFor: (Long) -> JobContext?,
    private val store: ActionStore,
    private val outbox: Outbox,
    private val tracks: TrackWriter?,
    private val viewerUrl: String?,
    /** To activate a password created for a site once an application there is confirmed. */
    private val credentials: Credentials? = null,
    /** Hears about every jobbot-cargo application's progress, to record it in the packet's STATUS.md. */
    private val cargoStatus: CargoStatusListener? = null,
    private val reviewTtl: Duration = Duration.ofHours(4),
    private val reminderAfter: Duration = Duration.ofHours(3),
    private val clock: Clock = Clock.systemUTC(),
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "apply-fill").apply { isDaemon = true } },
) {
    private val log = LoggerFactory.getLogger(ApplyService::class.java)
    private val pages = ConcurrentHashMap<Long, ApplyPage>()
    /** Fills queued or running in this process. One left "queued"/"filling" by an earlier process is dead. */
    private val running = ConcurrentHashMap.newKeySet<Long>()
    private val fmt = DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.of("America/Los_Angeles"))

    /** Resumes Richard sent for a fill, by fill id. Held in memory only: a restart orphans the fill anyway. */
    private val uploads = ConcurrentHashMap<Long, ResumeUpload>()
    /** A packet's application-answers.md, by fill id. */
    private val answers = ConcurrentHashMap<Long, String>()

    /** The card's Apply tap: the tailored resume. */
    val apply = VerbHandler { ctx, store -> startFill(ctx, store, null) }

    /**
     * Apply from a jobbot-cargo packet: the packet's own résumé file and answers, at the packet's apply URL. It is the
     * same fill as a pasted link, so it ends at ✅ Submit, and each step is recorded in the packet's STATUS.md.
     */
    fun applyPacket(p: PacketApply, chatId: String): TapResponse {
        // The packet comes from a repo other agents write to: its URL gets the same checks as a pasted link.
        val link = when (val c = JobUrls.check(p.applyUrl)) {
            is JobUrls.Check.Rejected -> return TapResponse(Outcomes.REFUSED, reply = "The apply URL isn't usable: ${c.reason}")
            is JobUrls.Check.Ok -> c
        }
        val event = buildJsonObject { put("job_url", link.url); put("company", p.company); put("role_title", p.title) }
        val ctx = TapContext(
            TapRequest("apply", AD_HOC_SEQ, "cargo", chatId, null, null), event, JobIdentity.of(event), p.label,
        )
        val extra = mapOf("cargo_folder" to p.folder, "company" to p.company, "title" to p.title) + listOfNotNull(p.approval?.let { "approval" to it })
        return startFill(ctx, store, p.upload, extra, p.answers)
    }

    /** "Apply to this" with a PDF or .docx replied to the card: that file replaces the tailored resume. */
    val applyWithResume = ResumeApplyHandler { ctx, store, upload -> startFill(ctx, store, upload) }

    /**
     * Synchronized: the "is one already open?" check and the insert must be one step. Card taps are serialized by
     * TapService, but packet applications and pasted links come in on other threads (parallel tool calls), so
     * the lock lives here.
     */
    @Synchronized
    private fun startFill(
        ctx: TapContext, store: ActionStore, upload: ResumeUpload?, extra: Map<String, String> = emptyMap(), packetAnswers: String? = null,
    ): TapResponse {
        alreadyApplied(ctx)?.let { return it.forUpload(upload) }
        val latest = store.latest(FILL, ctx.jobKey)
        if (latest != null && isOrphan(latest)) {
            // Its tab or worker died with an earlier JobBot process: nothing can continue it, so start over.
            store.update(latest.id, EXPIRED)
        } else when (latest?.status) {
            QUEUED, FILLING -> return TapResponse(Outcomes.ALREADY, toast = "Already filling this one.").forUpload(upload)
            Outcomes.AWAITING_APPROVAL -> return TapResponse(Outcomes.ALREADY, toast = "It's filled and waiting for your review above.").forUpload(upload, discardFirst = true)
            NEEDS_HUMAN -> return TapResponse(Outcomes.ALREADY, toast = "It's waiting on you — see the hand-off message above.").forUpload(upload, discardFirst = true)
            Outcomes.DONE -> return TapResponse(Outcomes.ALREADY, toast = "Already submitted ${fmt.format(Instant.ofEpochMilli(latest.updatedAt))}.").forUpload(upload)
        }
        val details = upload?.let {
            buildJsonObject {
                put("resume_upload", it.fileName); put("resume_bytes", it.bytes.size)
                if (ctx.request.seq == AD_HOC_SEQ) { put("job_url", ctx.event.str("job_url")); put("label", ctx.ref) }
                extra.forEach { (k, v) -> put(k, v) }
            }.toString()
        }
        val fill = store.insert(FILL, ctx.jobKey, ctx.request.seq, QUEUED, ctx.request.chatId, ctx.request.messageId?.toString(), details)
        upload?.let { uploads[fill.id] = it }
        packetAnswers?.let { answers[fill.id] = it }
        cargo(fill.details, "in_progress", (extra["approval"]?.let { "draft approved: \"$it\" — " } ?: "") + "opened the form via JobBot${upload?.let { " with ${it.fileName}" } ?: ""}")
        start(fill.id, fresh = true)
        val company = ctx.event.str("company") ?: ctx.ref
        return TapResponse(
            Outcomes.DONE,
            toast = "Filling ${ctx.ref}…",
            reply = "Filling the application for $company in the apply browser" +
                (upload?.let { " with your resume (${it.fileName})" } ?: "") + ". " +
                "I'll post it here for review — nothing is submitted until you tap ✅ Submit.",
        )
    }

    /**
     * An upload arrives as a message, so a refusal has to be said in chat. When the fill that blocked it is
     * still open, the file was not used: say so, and what to do about it.
     */
    private fun TapResponse.forUpload(upload: ResumeUpload?, discardFirst: Boolean = false): TapResponse {
        upload ?: return this
        val why = toast ?: reply ?: "That job is already in progress."
        val next = if (discardFirst) " Your file wasn't used — ✖ Discard that one, then send the resume again." else " Your file wasn't used."
        return TapResponse(outcome, reply = why + next)
    }

    /** ✅ Submit under a filled form. */
    fun submit(id: Long, req: TapRequest): TapResponse {
        val fill = store.byId(id)?.takeIf { it.verb == FILL } ?: return gone("Unknown application.")
        when (fill.status) {
            Outcomes.DONE -> return TapResponse(Outcomes.ALREADY, toast = "Already submitted.", actionRow = emptyList())
            UNCONFIRMED -> return TapResponse(Outcomes.ALREADY, toast = "Submit was already clicked — check the viewer.", actionRow = emptyList())
            EXPIRED, CANCELLED, FAILED -> return gone("This application is no longer open. Tap Apply on the card to start again.")
        }
        if (fill.status != Outcomes.AWAITING_APPROVAL) return TapResponse(Outcomes.REFUSED, toast = "Not ready to submit yet.")
        if (Duration.ofMillis(clock.millis() - readyAt(fill)) > reviewTtl) return expire(fill.id, "The review window (${reviewTtl.toHours()} h) has passed.")
        val page = pages[id] ?: return gone("The browser tab for this application is gone (JobBot restarted?). Tap Apply on the card to fill it again.")

        val snap = page.snapshot()
        val approved = JSON.parseToJsonElement(fill.details ?: "{}").jsonObject["fingerprint"]?.jsonPrimitive?.content
        if (approved != fingerprint(FillAgent.fieldsOf(snap))) {
            // Someone (Richard in the viewer, or the site) changed the form since the review card.
            val ready = FillResult.Ready(FillAgent.fieldsOf(snap), page.screenshot(), "The form changed since the last review.")
            store.update(id, SUPERSEDED)
            postReview(store.insert(FILL, fill.jobKey, fill.seq, Outcomes.AWAITING_APPROVAL, fill.chatId, null, reviewDetails(ready, fill.details)).also { pages[it.id] = page; pages.remove(id) }.id, label(fill), ready)
            return TapResponse(Outcomes.REFUSED, toast = "The form changed — nothing submitted. A fresh review is below.", actionRow = emptyList())
        }
        val submits = snap.elements.filter { (it.kind == "button" || it.kind == "input") && SitePolicy.isSubmitControl(it.text.ifBlank { it.label }, it.type, it.formFilled) }
        if (submits.size != 1) {
            return TapResponse(
                Outcomes.REFUSED,
                toast = if (submits.isEmpty()) "I can't find the submit button — finish it in the viewer." else "More than one submit button — finish it in the viewer.",
            )
        }
        store.update(id, SUBMITTING)
        page.click(submits.single().id)
        page.settle()
        val after = page.snapshot()
        val shot = runCatching { page.screenshot() }.getOrNull()
        // Only a confirmation message counts. A URL change alone could be a login redirect or an
        // error page, and marking the track "applied" on that would be a false record.
        if (!CONFIRMATION.containsMatchIn(after.text)) {
            store.update(id, UNCONFIRMED, buildJsonObject { put("submitted_url", after.url) }.toString())
            cargo(fill.details, "blocked", "Submit clicked via JobBot, outcome UNCONFIRMED (no confirmation text seen) — check the site before applying again")
            outbox.post(
                "I clicked Submit for ${label(fill)} but didn't see a confirmation. The tab is still open — " +
                    "check it${viewerUrl?.let { " in the viewer: $it" } ?: " in the viewer"}. The track was not changed.",
                photo = shot,
            )
            return TapResponse(Outcomes.DONE, toast = "Clicked Submit — couldn't confirm. Check the viewer.", actionRow = emptyList())
        }
        store.update(id, Outcomes.DONE, buildJsonObject { put("submitted_url", after.url); put("confirmed", true) }.toString())
        pages.remove(id)?.close()
        // A confirmed submission proves the account works: a password created for this site is now active.
        listOfNotNull(Credentials.siteKey(snap.url), Credentials.siteKey(after.url)).distinct().forEach { site ->
            if (credentials?.get(site)?.status == Credentials.PENDING) credentials.activate(site)
        }
        recordApplied(fill.seq)
        cargo(fill.details, "completed", "submitted via JobBot on Richard's ✅ Submit tap — site confirmation: \"${CONFIRMATION.find(after.text)?.value ?: "seen"}\"")
        outbox.post("Submitted: ${label(fill)}.", photo = shot)
        return TapResponse(Outcomes.DONE, toast = "Submitted.", actionRow = emptyList())
    }

    /** ✖ Discard: close the tab; nothing was submitted. */
    fun discard(id: Long, req: TapRequest): TapResponse {
        val fill = store.byId(id)?.takeIf { it.verb == FILL } ?: return gone("Unknown application.")
        if (fill.status == Outcomes.DONE) return TapResponse(Outcomes.ALREADY, toast = "Already submitted.", actionRow = emptyList())
        store.update(id, CANCELLED)
        pages.remove(id)?.close()
        releaseFiles(id)
        cargo(fill.details, "blocked", "discarded by Richard in Telegram before submitting; nothing was submitted")
        return TapResponse(Outcomes.DONE, toast = "Discarded. Nothing was submitted.", actionRow = emptyList())
    }

    /** ▶ Continue after Richard finished a hand-off step in the viewer. */
    fun resume(id: Long, req: TapRequest): TapResponse {
        val fill = store.byId(id)?.takeIf { it.verb == FILL } ?: return gone("Unknown application.")
        if (fill.status != NEEDS_HUMAN) return TapResponse(Outcomes.REFUSED, toast = "Nothing to continue.")
        if (pages[id] == null) return expire(id, "The browser tab for this application is gone (JobBot restarted?).")
        store.update(id, QUEUED)
        start(id, fresh = false)
        return TapResponse(Outcomes.DONE, toast = "Continuing…", actionRow = emptyList())
    }

    /** Remind at 3 h, expire at 4 h. Called every minute. */
    fun sweep() {
        store.recent(200).filter { it.verb == FILL && it.status == Outcomes.AWAITING_APPROVAL }.forEach { fill ->
            val age = Duration.ofMillis(clock.millis() - readyAt(fill))
            val details = runCatching { JSON.parseToJsonElement(fill.details ?: "{}").jsonObject }.getOrNull()
            when {
                age > reviewTtl -> {
                    expire(fill.id, "")
                    outbox.post("The filled application for ${label(fill)} expired after ${reviewTtl.toHours()} h without a decision. Nothing was submitted.")
                }
                age > reminderAfter && details?.get("reminded") == null -> {
                    store.update(fill.id, fill.status, buildJsonObject { details?.forEach { (k, v) -> put(k, v) }; put("reminded", true) }.toString())
                    outbox.post(
                        "Reminder: the application for ${label(fill)} is filled and waiting. It expires in ${(reviewTtl - age).toMinutes()} min.",
                        listOf(InlineButton("✅ Submit", "submit:${fill.id}"), InlineButton("✖ Discard", "discard:${fill.id}")),
                    )
                }
            }
        }
    }

    private fun start(id: Long, fresh: Boolean) {
        running += id
        worker.submit { try { runFill(id, fresh) } finally { running -= id } }
    }

    /** An open fill this process can no longer act on: no live worker, or no tab to continue or submit. */
    private fun isOrphan(fill: ActionStore.Action): Boolean = when (fill.status) {
        QUEUED, FILLING -> fill.id !in running
        NEEDS_HUMAN, Outcomes.AWAITING_APPROVAL -> fill.id !in running && pages[fill.id] == null
        else -> false
    }

    internal fun runFill(id: Long, fresh: Boolean) {
        val fill = store.byId(id) ?: return
        val upload = uploads[id]
        val job = if (fill.seq == AD_HOC_SEQ) adHocJob(fill, upload) else contextFor(fill.seq)?.let { base -> upload?.let { base.withUploadedResume(it) } ?: base }
        if (job == null) {
            store.update(id, FAILED); outbox.post("Couldn't start ${label(fill)}: no posting URL or job data.")
            cargo(fill.details, "blocked", "JobBot couldn't start: no posting URL or job data")
            return
        }
        store.update(id, FILLING)
        val page = pages[id] ?: runCatching { browser.open { url -> SitePolicy.navigationAllowed(url, job.jobUrl, job.company) } }
            .getOrElse {
                store.update(id, FAILED)
                outbox.post("Couldn't open the apply browser for ${job.ref}: ${it.message}")
                cargo(fill.details, "blocked", "JobBot couldn't open its browser: ${it.message?.take(120)}")
                return
            }.also { pages[id] = it }
        val result = runCatching { fill(page, job, fresh) }
            .getOrElse { FillResult.Failed("${it.javaClass.simpleName}: ${it.message}", null) }
        // Discarded (or expired) while the fill ran: never resurrect it with a review.
        if (store.byId(id)?.status in setOf(CANCELLED, EXPIRED, SUPERSEDED)) {
            pages.remove(id)?.close()
            releaseFiles(id)
            return
        }
        when (result) {
            is FillResult.Ready -> {
                releaseFiles(id)
                cargo(fill.details, "parked_at_review", "filled end to end by JobBot; parked at the final review, NOT submitted — awaiting Richard's ✅ Submit. Resume: ${upload?.fileName ?: "tailored"}")
                val shown = upload?.let { result.withNote("Resume: ${it.fileName} (your file, not the tailored one). No cover letter.") } ?: result
                store.update(id, Outcomes.AWAITING_APPROVAL, reviewDetails(shown, fill.details))
                postReview(id, label(fill), shown)
            }
            is FillResult.NeedsHuman -> {
                store.update(id, NEEDS_HUMAN)
                cargo(fill.details, "blocked", "needs Richard: ${result.reason.take(300)}")
                outbox.post(
                    "${job.ref} needs you: ${result.reason}" + (viewerUrl?.let { "\nOpen the apply browser: $it" } ?: "") +
                        "\nTap ▶ Continue when you're done there.",
                    listOf(InlineButton("▶ Continue", "resume:$id"), InlineButton("✖ Discard", "discard:$id")),
                    result.screenshot,
                )
            }
            is FillResult.Failed -> {
                releaseFiles(id)
                store.update(id, FAILED)
                store.recordError("fill ${job.ref}", result.reason)
                cargo(fill.details, "blocked", "JobBot's fill failed: ${result.reason.take(300)}")
                pages.remove(id)?.close()
                outbox.post("Couldn't fill ${job.ref}: ${result.reason}", photo = result.screenshot)
            }
        }
    }

    private fun releaseFiles(id: Long) { uploads.remove(id); answers.remove(id) }

    /** Tell the listener about a jobbot-cargo application's progress; a failure there never disturbs the application. */
    private fun cargo(details: String?, state: String, note: String) {
        val listener = cargoStatus ?: return
        val folder = runCatching { JSON.parseToJsonElement(details ?: "{}").jsonObject["cargo_folder"]?.jsonPrimitive?.content }.getOrNull() ?: return
        runCatching { listener.record(folder, state, note) }.onFailure { store.recordError("cargo status $folder", it.message ?: "") }
    }

    /** A pasted link: the candidate profile and Richard's file, and a posting URL from the link instead of a card. */
    private fun adHocJob(fill: ActionStore.Action, upload: ResumeUpload?): JobContext? {
        upload ?: return null
        val details = runCatching { JSON.parseToJsonElement(fill.details ?: "{}").jsonObject }.getOrNull() ?: return null
        val url = details["job_url"]?.jsonPrimitive?.content ?: return null
        return contextFor(AD_HOC_SEQ)?.copy(
            jobUrl = url, ref = label(fill), company = details["company"]?.jsonPrimitive?.content, title = details["title"]?.jsonPrimitive?.content,
            answers = answers[fill.id],
        )?.withUploadedResume(upload)
    }

    /** What to call this application in chat: the card's `#J` reference, or the site for a pasted link. */
    private fun label(fill: ActionStore.Action): String {
        if (fill.seq != AD_HOC_SEQ) return JobRef.format(fill.seq)
        return runCatching { JSON.parseToJsonElement(fill.details ?: "{}").jsonObject["label"]?.jsonPrimitive?.content }.getOrNull() ?: "that job"
    }

    private fun postReview(id: Long, label: String, ready: FillResult.Ready) {
        val fields = ready.fields.joinToString("\n") { (k, v) -> "• $k: $v" }.ifBlank { "(no fields read)" }
        outbox.post(
            "Ready to submit $label — review every field. Tap ✅ Submit to submit exactly this " +
                "(within ${reviewTtl.toHours()} h).${ready.note.takeIf { it.isNotBlank() }?.let { "\nNote: $it" } ?: ""}\n\n$fields" +
                (viewerUrl?.let { "\n\nViewer: $it" } ?: ""),
            listOf(InlineButton("✅ Submit", "submit:$id"), InlineButton("✖ Discard", "discard:$id")),
            ready.screenshot,
        )
    }

    /** [prior] is the fill's earlier details: what identifies an ad hoc job (its link, its label) must outlive the review. */
    private fun reviewDetails(ready: FillResult.Ready, prior: String? = null) = buildJsonObject {
        runCatching { JSON.parseToJsonElement(prior ?: "{}").jsonObject }.getOrNull()
            ?.forEach { (k, v) -> if (k in CARRIED) put(k, v) }
        put("fingerprint", fingerprint(ready.fields))
        put("fields", ready.fields.size)
        put("ready_at", clock.millis())
    }.toString()

    /** When the form became ready for review — the review window's start (updates must not reset it). */
    private fun readyAt(fill: ActionStore.Action): Long =
        runCatching { JSON.parseToJsonElement(fill.details ?: "{}").jsonObject["ready_at"]?.jsonPrimitive?.content?.toLong() }
            .getOrNull() ?: fill.updatedAt

    private fun alreadyApplied(ctx: TapContext): TapResponse? {
        if (ctx.request.seq == AD_HOC_SEQ) return null
        val status = runCatching { lookup.find(ctx.request.seq)?.track?.str("status") }.getOrNull()
        return if (status == "applied") TapResponse(Outcomes.ALREADY, toast = "Already applied (the track says so).") else null
    }

    private fun recordApplied(seq: Long) {
        if (seq == AD_HOC_SEQ) return // a pasted link has no JFAA track to update
        val writer = tracks ?: return
        runCatching {
            val found = lookup.find(seq) ?: return
            if (found.trackMatch !in setOf("track_id", "artifact_url")) return
            val trackId = found.track?.get("id")?.jsonPrimitive?.content?.toLongOrNull() ?: return
            writer.setStatus(trackId, "applied")
            writer.addEvent(trackId, "application_submitted", "Submitted from Telegram", buildJsonObject { put("ref", JobRef.format(seq)) })
        }.onFailure { store.recordError("track applied ${JobRef.format(seq)}", it.message ?: "") }
    }

    private fun expire(id: Long, why: String): TapResponse {
        store.byId(id)?.let { cargo(it.details, "blocked", "review window expired or the browser tab was lost; nothing was submitted") }
        store.update(id, EXPIRED)
        pages.remove(id)?.close()
        releaseFiles(id)
        return gone("$why Nothing was submitted. Tap Apply on the card to fill it again.".trim())
    }

    private fun gone(toast: String) = TapResponse(Outcomes.REFUSED, toast = toast, actionRow = emptyList())

    companion object {
        const val FILL = "fill"
        const val QUEUED = "queued"
        const val FILLING = "filling"
        const val NEEDS_HUMAN = "needs_human"
        const val SUBMITTING = "submitting"
        /** Submit was clicked but no confirmation appeared: Richard checks the tab. */
        const val UNCONFIRMED = "submitted_unconfirmed"
        const val EXPIRED = "expired"
        const val CANCELLED = "cancelled"
        const val SUPERSEDED = "superseded"
        const val FAILED = "failed"
        private val JSON = Json { ignoreUnknownKeys = true }
        /** Details keys that survive the move from "filling" to "awaiting review". */
        private val CARRIED = setOf("resume_upload", "resume_bytes", "job_url", "label", "cargo_folder", "company", "title")
        private val CONFIRMATION = Regex("""(?i)thank you|application (has been )?(received|submitted)|we('ve| have) received|successfully submitted""")

        fun fingerprint(fields: List<Pair<String, String>>): String =
            MessageDigest.getInstance("SHA-256").digest(fields.joinToString("\u0000") { "${it.first}\u0001${it.second}" }.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}

/** Records a jobbot-cargo application's progress in the packet's STATUS.md. */
fun interface CargoStatusListener {
    fun record(folder: String, state: String, note: String)
}

/** Everything to apply from one jobbot-cargo packet. [upload] is the packet's own résumé; [answers] its application-answers.md. */
class PacketApply(
    val folder: String,
    val label: String,
    val company: String,
    val title: String,
    val applyUrl: String,
    val upload: ResumeUpload,
    val answers: String?,
    /** Richard's exact words approving a packet that needed it (a DRAFT, a ⚠, a reopened one). */
    val approval: String? = null,
)
