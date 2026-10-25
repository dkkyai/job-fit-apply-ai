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
    /** FillExecutor.run: fill the page for the job; `false` continues a page already in progress. */
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
    /** Fetches the code a site emails after Submit (Greenhouse); without it a code prompt is handed to Richard. */
    private val verifier: Verifier? = null,
    private val reviewTtl: Duration = Duration.ofHours(4),
    private val reminderAfter: Duration = Duration.ofHours(3),
    private val clock: Clock = Clock.systemUTC(),
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "apply-fill").apply { isDaemon = true } },
    /** Where the emailed-code step runs after a Submit click: not behind a fill that may be using the worker for 25 minutes. */
    private val verifyWorker: ExecutorService = worker,
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

    /**
     * JOBBOT_DRY_RUN for a packet application: every check a real one passes, then a DRY_RUN action record and
     * nothing else. No browser is opened, no fill is queued, no review is posted and STATUS.md is not touched, so a
     * dry run never blocks (or counts as) the real application that follows it.
     */
    fun dryRunPacket(p: PacketApply, chatId: String): TapResponse {
        val link = when (val c = JobUrls.check(p.applyUrl)) {
            is JobUrls.Check.Rejected -> return TapResponse(Outcomes.REFUSED, reply = "The apply URL isn't usable: ${c.reason}")
            is JobUrls.Check.Ok -> c
        }
        val event = buildJsonObject { put("job_url", link.url); put("company", p.company); put("role_title", p.title) }
        store.insert(
            "apply", JobIdentity.of(event), AD_HOC_SEQ, Outcomes.DRY_RUN, chatId, null,
            buildJsonObject { put("cargo_folder", p.folder); put("resume_upload", p.upload.fileName) }.toString(),
        )
        return TapResponse(Outcomes.DRY_RUN, reply = "[dry run] Would apply to ${p.label} with ${p.upload.fileName}.")
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
            VERIFYING, NEEDS_CODE -> return TapResponse(Outcomes.ALREADY, toast = "Submit was clicked — it's waiting for the site's emailed code (see above).").forUpload(upload)
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
            VERIFYING, NEEDS_CODE -> return TapResponse(Outcomes.ALREADY, toast = "Submit was already clicked — the site is waiting for its emailed code.", actionRow = emptyList())
            EXPIRED, CANCELLED, FAILED -> return gone("This application is no longer open. Tap Apply on the card to start again.")
        }
        if (fill.status != Outcomes.AWAITING_APPROVAL) return TapResponse(Outcomes.REFUSED, toast = "Not ready to submit yet.")
        if (Duration.ofMillis(clock.millis() - readyAt(fill)) > reviewTtl) return expire(fill.id, "The review window (${reviewTtl.toHours()} h) has passed.")
        val page = pages[id] ?: return gone("The browser tab for this application is gone (JobBot restarted?). Tap Apply on the card to fill it again.")

        val snap = page.snapshot()
        val approved = JSON.parseToJsonElement(fill.details ?: "{}").jsonObject["fingerprint"]?.jsonPrimitive?.content
        if (approved != fingerprint(FillExecutor.fieldsOf(snap))) {
            // Someone (Richard in the viewer, or the site) changed the form since the review card.
            val ready = FillResult.Ready(FillExecutor.fieldsOf(snap), page.screenshot(), "The form changed since the last review.")
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
        val clickedAt = clock.millis()
        page.click(submits.single().id)
        page.settle()
        return when (val step = readAfterSubmit(page)) {
            is SubmitStep.Confirmed -> confirmed(fill, page, snap.url, step.words)
            is SubmitStep.CodePrompt -> {
                // The click was only the start: the site emailed a code and wants it before it takes the application.
                store.update(id, VERIFYING, withDetails(fill.details, "clicked_at" to JsonPrimitive(clickedAt)))
                cargo(fill.details, "in_progress", "Submit clicked; the site wants an emailed security code — JobBot is fetching it")
                runVerification(id, FIRST_CODE_WAIT)
                TapResponse(Outcomes.DONE, toast = "Clicked Submit — the site wants the code it emailed. Fetching it…", actionRow = emptyList())
            }
            is SubmitStep.Unknown -> unconfirmed(fill, page, step)
        }
    }

    /**
     * What the page shows once the click has had time to act. A site that confirms slowly gets a few seconds before the
     * page is called unknown; a code prompt or a confirmation is taken as soon as it is there.
     */
    private fun readAfterSubmit(page: ApplyPage): SubmitStep {
        var step = PostSubmit.classify(page.snapshot())
        repeat(SETTLE_LOOKS) {
            if (step !is SubmitStep.Unknown) return step
            page.pause(SETTLE_PAUSE_MS)
            step = PostSubmit.classify(page.snapshot())
        }
        return step
    }

    /** Submitted for sure: the site said so. */
    private fun confirmed(fill: ActionStore.Action, page: ApplyPage, urlBefore: String, words: String): TapResponse {
        val after = page.snapshot()
        val shot = runCatching { page.screenshot() }.getOrNull()
        store.update(fill.id, Outcomes.DONE, buildJsonObject { put("submitted_url", after.url); put("confirmed", true) }.toString())
        pages.remove(fill.id)?.close()
        // A confirmed submission proves the account works: a password created for this site is now active.
        listOfNotNull(Credentials.siteKey(urlBefore), Credentials.siteKey(after.url)).distinct().forEach { site ->
            if (credentials?.get(site)?.status == Credentials.PENDING) credentials.activate(site)
        }
        recordApplied(fill.seq)
        cargo(fill.details, "completed", "submitted via JobBot on Richard's ✅ Submit tap — site confirmation: \"${words.ifBlank { "seen" }}\"")
        outbox.post("Submitted: ${label(fill)}.", photo = shot)
        return TapResponse(Outcomes.DONE, toast = "Submitted.", actionRow = emptyList())
    }

    /** Submit was clicked and the page neither confirmed nor asked for something JobBot knows: Richard looks. */
    private fun unconfirmed(fill: ActionStore.Action, page: ApplyPage, step: SubmitStep.Unknown): TapResponse {
        val shot = runCatching { page.screenshot() }.getOrNull()
        store.update(fill.id, UNCONFIRMED, buildJsonObject { put("submitted_url", page.url) }.toString())
        cargo(
            fill.details, "blocked",
            "Submit clicked via JobBot, outcome UNCONFIRMED (no confirmation text seen${step.excerpt.takeIf { it.isNotBlank() }?.let { "; the page says: ${it.take(200)}" } ?: ""}) — check the site before applying again",
        )
        outbox.post(
            "I clicked Submit for ${label(fill)} but didn't see a confirmation." +
                (if (step.captcha) " A CAPTCHA is on the page." else "") +
                (step.excerpt.takeIf { it.isNotBlank() }?.let { " The page says: \"$it\"." } ?: "") +
                " The tab is still open — check it${viewerUrl?.let { " in the viewer: $it" } ?: " in the viewer"}. The track was not changed.",
            photo = shot,
        )
        return TapResponse(Outcomes.DONE, toast = "Clicked Submit — couldn't confirm. Check the viewer.", actionRow = emptyList())
    }

    /** Runs the code step on the worker, so a mail poll never holds up a tap. */
    private fun runVerification(id: Long, wait: Duration) {
        running += id
        verifyWorker.submit {
            try { finishVerification(id, wait) }
            catch (e: Exception) { store.recordError("verify $id", e.message ?: e.javaClass.simpleName) }
            finally { running -= id }
        }
    }

    /**
     * The site asked for an emailed code after Submit. Fetch it from the inbox (only mail from the site or its ATS
     * mailer), type it in, and click the site's submit control once more — the same application Richard approved, so the
     * form is checked against the review card first. A code that never arrives, is refused, or a page that is not a code
     * prompt (any more) goes back to Richard with the tab still open; nothing here loops.
     */
    private fun finishVerification(id: Long, wait: Duration) {
        val fill = store.byId(id) ?: return
        val page = pages[id]
        if (page == null) {
            store.update(id, UNCONFIRMED)
            cargo(fill.details, "blocked", "Submit clicked via JobBot; the tab is gone, outcome UNCONFIRMED — check the site before applying again")
            outbox.post("The browser tab for ${label(fill)} is gone after Submit was clicked, so I can't tell whether it went through. Check your email and the site before applying again.")
            return
        }
        val snap = page.snapshot()
        val step = when (val s = PostSubmit.classify(snap)) {
            is SubmitStep.Confirmed -> { confirmed(fill, page, snap.url, s.words); return }
            is SubmitStep.Unknown -> { unconfirmed(fill, page, s); return }
            is SubmitStep.CodePrompt -> s
        }
        val details = runCatching { JSON.parseToJsonElement(fill.details ?: "{}").jsonObject }.getOrDefault(buildJsonObject {})
        val clickedAt = details["clicked_at"]?.jsonPrimitive?.content?.toLongOrNull() ?: fill.updatedAt
        val attempts = details["code_attempts"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        if (attempts >= MAX_CODE_ATTEMPTS) return handOffCode(fill, page, step, "The site didn't take the code I entered.")

        // Only the site's own ATS mailer may supply it; a failure to read mail is a hand-off, not a crash.
        val site = Credentials.siteKey(page.url)
        val mail = verifier
        val code = when {
            mail == null -> return handOffCode(fill, page, step, "I can't read the inbox (Gmail isn't set up for JobBot).")
            site == null -> return handOffCode(fill, page, step, "I can't tell which site is asking.")
            else -> runCatching { mail.codeOfLength(site, clickedAt, step.length, wait) }
                .getOrElse { return handOffCode(fill, page, step, "I couldn't read the inbox (${it.message?.take(100) ?: it.javaClass.simpleName}).") }
        } ?: return handOffCode(fill, page, step, "No email with a code from $site has arrived yet.")

        val approved = details["fingerprint"]?.jsonPrimitive?.content
        val withoutCode = snap.copy(elements = snap.elements.filter { e -> step.boxes.none { it.id == e.id } })
        if (approved != null && approved != fingerprint(FillExecutor.fieldsOf(withoutCode))) {
            return handOffCode(fill, page, step, "The form no longer matches what you approved, so I did not enter the code.")
        }
        store.update(id, VERIFYING, withDetails(fill.details, "code_attempts" to JsonPrimitive(attempts + 1)))
        if (step.boxes.size > 1) step.boxes.zip(code.toList()).forEach { (box, ch) -> page.fill(box.id, ch.toString()) } else page.fill(step.boxes.single().id, code)

        val entered = page.snapshot()
        val submits = entered.elements.filter { (it.kind == "button" || it.kind == "input") && SitePolicy.isSubmitControl(it.text.ifBlank { it.label }, it.type, it.formFilled) }
        if (submits.size != 1) return handOffCode(fill, page, step, "I entered the code but can't find the one submit button.")
        runCatching { page.click(submits.single().id); page.settle() }
            .onFailure { return handOffCode(fill, page, step, "I entered the code but the submit button wouldn't click.") }
        when (val next = readAfterSubmit(page)) {
            is SubmitStep.Confirmed -> confirmed(fill, page, snap.url, next.words)
            is SubmitStep.CodePrompt -> handOffCode(fill, page, next, "The site didn't accept the code I entered.")
            is SubmitStep.Unknown -> unconfirmed(fill, page, next)
        }
    }

    /** The code step needs Richard: the code is not in the inbox, was refused, or the page isn't as expected. */
    private fun handOffCode(fill: ActionStore.Action, page: ApplyPage, step: SubmitStep.CodePrompt, why: String) {
        store.update(fill.id, NEEDS_CODE, withDetails(fill.details, "code_attempts" to JsonPrimitive(0)))
        cargo(fill.details, "in_progress", "Submit clicked; waiting for the site's emailed security code — ${why.take(160)}")
        outbox.post(
            "I clicked Submit for ${label(fill)} and the site wants the code it emailed" +
                (step.prompt.takeIf { it.isNotBlank() }?.let { " (\"$it\")" } ?: "") + ". $why Nothing is confirmed yet." +
                "\nType the code into the page in the viewer and tap ▶ Continue, or tap ▶ Continue and I'll look for the email again." +
                (viewerUrl?.let { "\nViewer: $it" } ?: ""),
            listOf(InlineButton("▶ Continue", "resume:${fill.id}"), InlineButton("✖ Discard", "discard:${fill.id}")),
            runCatching { page.screenshot() }.getOrNull(),
        )
    }

    /** [details] with [extra] merged in: the review's fingerprint and the application's identity must survive each step. */
    private fun withDetails(details: String?, vararg extra: Pair<String, JsonPrimitive>): String = buildJsonObject {
        runCatching { JSON.parseToJsonElement(details ?: "{}").jsonObject }.getOrNull()?.forEach { (k, v) -> if (k !in extra.map { it.first }) put(k, v) }
        extra.forEach { (k, v) -> put(k, v) }
    }.toString()

    /** ✖ Discard: close the tab; nothing was submitted. */
    fun discard(id: Long, req: TapRequest): TapResponse {
        val fill = store.byId(id)?.takeIf { it.verb == FILL } ?: return gone("Unknown application.")
        if (fill.status == Outcomes.DONE) return TapResponse(Outcomes.ALREADY, toast = "Already submitted.", actionRow = emptyList())
        val clicked = fill.status in CLICKED
        store.update(id, CANCELLED)
        pages.remove(id)?.close()
        releaseFiles(id)
        // After the click the site was waiting for its emailed code: it took nothing until it had one, but say only what is known.
        cargo(
            fill.details, "blocked",
            if (clicked) "discarded by Richard after Submit was clicked; the site was still waiting for its emailed code, so it should not have gone through — check the site if unsure"
            else "discarded by Richard in Telegram before submitting; nothing was submitted",
        )
        return TapResponse(
            Outcomes.DONE,
            toast = if (clicked) "Discarded. The site was still waiting for its code, so it shouldn't have gone through." else "Discarded. Nothing was submitted.",
            actionRow = emptyList(),
        )
    }

    /** ▶ Continue after Richard finished a hand-off step in the viewer. */
    fun resume(id: Long, req: TapRequest): TapResponse {
        val fill = store.byId(id)?.takeIf { it.verb == FILL } ?: return gone("Unknown application.")
        if (fill.status == NEEDS_CODE) {
            if (pages[id] == null) {
                store.update(id, UNCONFIRMED)
                return gone("The browser tab is gone, so I can't tell whether it went through. Check your email and the site before applying again.")
            }
            store.update(id, VERIFYING, withDetails(fill.details, "code_attempts" to JsonPrimitive(0)))
            runVerification(id, RECHECK_CODE_WAIT)
            return TapResponse(Outcomes.DONE, toast = "Checking…", actionRow = emptyList())
        }
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

    /**
     * Run once at startup, before any new fill. A fill is a tab and a worker in this process, so every one still open
     * in the database belongs to an earlier process that died (a restart, a crash): it can never finish or be
     * submitted. Close each, hand a packet's application back to its STATUS.md, and tell Richard about the ones he
     * may be waiting on. Returns how many were closed.
     */
    @Synchronized
    fun recoverInterrupted(): Int {
        val stale = store.open(FILL).filter { it.id !in running && pages[it.id] == null }
        for (fill in stale) {
            if (fill.status in CLICKED) {
                // Submit had already been clicked, so "nothing was submitted" would be a guess: the outcome is unknown.
                store.update(fill.id, UNCONFIRMED)
                cargo(fill.details, "blocked", "JobBot restarted after Submit was clicked; the outcome is unconfirmed — check the site (it may be waiting for an emailed code) before applying again")
                if (Duration.ofMillis(clock.millis() - fill.updatedAt) <= reviewTtl) {
                    outbox.post("JobBot restarted after Submit was clicked for ${label(fill)}, so I can't tell whether it went through (the site may have been waiting for an emailed code). Check your email and the site before applying again.")
                }
                continue
            }
            store.update(fill.id, EXPIRED)
            releaseFiles(fill.id)
            val folder = cargoFolder(fill.details)
            if (folder != null) {
                runCatching { cargoStatus?.restore(folder, "JobBot restarted during this application; nothing was submitted") }
                    .onFailure { store.recordError("cargo restore $folder", it.message ?: it.javaClass.simpleName) }
            }
            // Anything older than the review window is a leftover nobody is waiting on any more.
            if (Duration.ofMillis(clock.millis() - fill.updatedAt) <= reviewTtl) {
                val was = when (fill.status) {
                    QUEUED, FILLING -> "being filled"
                    Outcomes.AWAITING_APPROVAL -> "waiting for your review (the filled form is gone)"
                    else -> "waiting on you"
                }
                outbox.post(
                    "JobBot restarted while the application for ${label(fill)} was $was. Nothing was submitted. " +
                        "Tap Apply on the card, or ask me again, to start it over.",
                )
            }
        }
        if (stale.isNotEmpty()) log.warn("closed {} fill(s) left open by an earlier process", stale.size)
        return stale.size
    }

    private fun cargoFolder(details: String?): String? =
        runCatching { JSON.parseToJsonElement(details ?: "{}").jsonObject["cargo_folder"]?.jsonPrimitive?.content }.getOrNull()

    private fun start(id: Long, fresh: Boolean) {
        running += id
        worker.submit { try { runFill(id, fresh) } finally { running -= id } }
    }

    /** An open fill this process can no longer act on: no live worker, or no tab to continue or submit. */
    private fun isOrphan(fill: ActionStore.Action): Boolean = when (fill.status) {
        QUEUED, FILLING -> fill.id !in running
        NEEDS_HUMAN, Outcomes.AWAITING_APPROVAL, VERIFYING, NEEDS_CODE -> fill.id !in running && pages[fill.id] == null
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
        val folder = cargoFolder(details) ?: return
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
        /** Submit was clicked and the site asked for an emailed code; JobBot is fetching and entering it. */
        const val VERIFYING = "verifying"
        /** The emailed code could not be entered by JobBot: Richard types it in the viewer (or taps Continue to try again). */
        const val NEEDS_CODE = "needs_code"
        /** Submit was clicked but no confirmation appeared: Richard checks the tab. */
        const val UNCONFIRMED = "submitted_unconfirmed"
        const val EXPIRED = "expired"
        const val CANCELLED = "cancelled"
        const val SUPERSEDED = "superseded"
        const val FAILED = "failed"
        private val JSON = Json { ignoreUnknownKeys = true }
        /** Statuses after the site's submit control was clicked: an application that may already be in. */
        private val CLICKED = setOf(SUBMITTING, VERIFYING, NEEDS_CODE)
        /** Details keys that survive the move from "filling" to "awaiting review". */
        private val CARRIED = setOf("resume_upload", "resume_bytes", "job_url", "label", "cargo_folder", "company", "title")
        /** How long the first look for the emailed code waits, and each look after a Continue tap. */
        private val FIRST_CODE_WAIT = Duration.ofMinutes(2)
        private val RECHECK_CODE_WAIT = Duration.ofSeconds(45)
        /** A code is entered automatically at most this many times per hand-off, so a wrong one cannot loop. */
        private const val MAX_CODE_ATTEMPTS = 1
        /** After a click an unrecognised page gets this many more looks, this far apart, before it is called unknown. */
        private const val SETTLE_LOOKS = 3
        private const val SETTLE_PAUSE_MS = 2_000L

        fun fingerprint(fields: List<Pair<String, String>>): String =
            MessageDigest.getInstance("SHA-256").digest(fields.joinToString("\u0000") { "${it.first}\u0001${it.second}" }.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}

/** Records a jobbot-cargo application's progress in the packet's STATUS.md. */
interface CargoStatusListener {
    fun record(folder: String, state: String, note: String)

    /**
     * An attempt was cut off by a restart, with nothing submitted: put the packet back in the state it had before the
     * attempt (so it can be retried, or stays blocked if Richard had blocked it). A packet no longer mid-attempt is left alone.
     */
    fun restore(folder: String, note: String) {}

    companion object {
        /** A listener that only records, as the tests build them. */
        operator fun invoke(record: (folder: String, state: String, note: String) -> Unit): CargoStatusListener =
            object : CargoStatusListener { override fun record(folder: String, state: String, note: String) = record(folder, state, note) }
    }
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
