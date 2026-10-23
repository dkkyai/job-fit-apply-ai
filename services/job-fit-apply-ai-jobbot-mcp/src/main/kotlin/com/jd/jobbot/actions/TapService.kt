package com.jd.jobbot.actions

import com.jd.jobbot.bridge.str
import com.jd.jobbot.jobs.Eligibility
import com.jd.jobbot.jobs.JobIdentity
import com.jd.jobbot.jobs.JobLookup
import com.jd.jobbot.jobs.JobRef
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

/**
 * Turns one button tap into one decision. Every tap passes the same checks, in this order, before
 * any verb runs:
 *  1. the verb is one JobBot knows;
 *  2. the user is on the allowlist (empty allowlist = nobody);
 *  3. the card is younger than the TTL;
 *  4. this deployment handles the verb;
 *  5. the seq resolves to a completed event on the bridge;
 *  6. the event qualifies for the verb (the shared eligibility contract).
 * Dry-run stops there and only logs. Taps are serialized, so two simultaneous taps on the same
 * card cannot both pass a handler's "already done?" check.
 */
class TapService(
    private val lookup: JobLookup,
    private val store: ActionStore,
    private val handlers: Map<String, VerbHandler>,
    private val allowedUsers: Set<String>,
    private val handledVerbs: Set<String>,
    private val dryRun: Boolean,
    private val cardTtl: Duration,
    private val clock: Clock = Clock.systemUTC(),
    approvals: ApprovalHandler? = null,
    idHandlers: Map<String, IdVerbHandler> = emptyMap(),
    /** Apply with an uploaded resume; null while Apply is off. */
    private val resumeApply: ResumeApplyHandler? = null,
) {
    private val idHandlers: Map<String, IdVerbHandler> = (approvals?.idHandlers() ?: emptyMap()) + idHandlers
    private val log = LoggerFactory.getLogger(TapService::class.java)

    @Synchronized
    fun tap(req: TapRequest): TapResponse {
        val verb = req.verb.lowercase()
        val ref = JobRef.format(req.seq)
        if (verb !in KNOWN_VERBS) return refused("Unknown action.")
        if (req.userId !in allowedUsers) {
            log.warn("refused tap {} {} from non-allowlisted user {}", verb, ref, req.userId)
            return refused("Not authorized.")
        }
        // Fail closed: a tap whose card age is unknown cannot be shown to be inside the TTL.
        val sentAt = req.messageDate ?: return refused("Can't tell how old this card is, so its buttons are disabled.")
        if (Duration.ofSeconds(clock.instant().epochSecond - sentAt) > cardTtl) {
            return TapResponse(
                Outcomes.EXPIRED,
                toast = "This card is more than ${cardTtl.toDays()} days old, so its buttons have expired.",
                actionRow = emptyList(),
            )
        }
        val baseVerb = BASE_VERB[verb] ?: verb
        if (baseVerb !in handledVerbs) return refused("${verb.replaceFirstChar { it.uppercase() }} isn't available yet.")

        if (verb in ID_VERBS) return byId(verb, req)

        val event = try {
            lookup.event(req.seq)
        } catch (e: Exception) {
            store.recordError("tap $verb $ref", "bridge lookup failed: ${e.message}")
            return TapResponse(Outcomes.ERROR, toast = "Couldn't reach JFAA. Try again in a minute.")
        } ?: return refused("Unknown job $ref.")

        if (!Eligibility.eligible(baseVerb, event)) {
            // An Apply on an older card for a skipped (e.g. pay-gated) job: there is no tailored resume.
            if (baseVerb == "apply" && event.str("job_url") != null) {
                val why = event.str("skip_reason")?.let { " ($it)" } ?: ""
                return refused("Not applying: this job was skipped$why, so there's no tailored resume.".take(TOAST_MAX))
            }
            return refused("${baseVerb.replaceFirstChar { it.uppercase() }} isn't available for this job.")
        }

        val jobKey = JobIdentity.of(event)
        if (dryRun) {
            store.insert(verb, jobKey, req.seq, Outcomes.DRY_RUN, req.chatId, req.messageId?.toString())
            log.info("[dry run] would {} {} ({})", verb, ref, jobKey)
            return TapResponse(Outcomes.DRY_RUN, toast = "[dry run] Would $verb $ref.")
        }

        val handler = handlers[verb] ?: return refused("${verb.replaceFirstChar { it.uppercase() }} isn't available yet.")
        return try {
            handler.handle(TapContext(req, event, jobKey, ref), store)
        } catch (e: Exception) {
            log.error("tap {} {} failed", verb, ref, e)
            store.recordError("tap $verb $ref", e.message ?: e.javaClass.simpleName)
            TapResponse(Outcomes.ERROR, toast = "Something went wrong with $verb. It's logged; try again.")
        }
    }

    /**
     * "Apply to this" with a resume attached, as a reply to a card. It passes the same gates as an Apply tap
     * (allowlist, card age, Apply switched on, the job resolves) and then checks the file itself. It does not
     * need a tailored resume: Richard picked the file, so a skipped job qualifies if it has a posting URL.
     */
    @Synchronized
    fun applyWithResume(req: ResumeApplyRequest): TapResponse {
        val card = req.seq?.takeIf { it > 0 }
        val ref = card?.let { JobRef.format(it) } ?: "that link"
        if (req.userId !in allowedUsers) {
            log.warn("refused resume upload for {} from non-allowlisted user {}", ref, req.userId)
            return declined("Not authorized.")
        }
        if (card != null) {
            val sentAt = req.messageDate ?: return declined("Can't tell how old that card is, so I can't use it.")
            if (Duration.ofSeconds(clock.instant().epochSecond - sentAt) > cardTtl) {
                return declined("That card is more than ${cardTtl.toDays()} days old. Reply to a newer card.")
            }
        }
        if ("apply" !in handledVerbs) return declined("Apply isn't available yet.")
        // Bound the work before decoding: base64 inflates by a third.
        if (req.fileData.length > ResumeUpload.MAX_BYTES / 3 * 4 + 16) {
            return declined("That file is over ${ResumeUpload.MAX_BYTES / (1024 * 1024)} MB.")
        }
        val bytes = try {
            java.util.Base64.getDecoder().decode(req.fileData)
        } catch (e: IllegalArgumentException) {
            return declined("I couldn't read that file.")
        }
        val upload = when (val check = ResumeUpload.validate(req.fileName, bytes)) {
            is ResumeUpload.Check.Rejected -> return declined(check.reason)
            is ResumeUpload.Check.Ok -> check.upload
        }
        val ctxEvent: JsonObject
        val ctxRef: String
        val ctxSeq: Long
        if (card != null) {
            ctxEvent = try {
                lookup.event(card)
            } catch (e: Exception) {
                store.recordError("resume upload $ref", "bridge lookup failed: ${e.message}")
                return TapResponse(Outcomes.ERROR, reply = "Couldn't reach JFAA. Try again in a minute.")
            } ?: return declined("Unknown job $ref.")
            if (!Eligibility.eligibleForResumeUpload(ctxEvent)) return declined("$ref has no posting URL, so there's nothing to apply to.")
            ctxRef = ref
            ctxSeq = card
        } else {
            val link = when (val c = JobUrls.check(req.jobUrl)) {
                is JobUrls.Check.Rejected -> return declined(c.reason)
                is JobUrls.Check.Ok -> c
            }
            ctxEvent = buildJsonObject { put("job_url", link.url) }
            ctxRef = "Job at ${link.host}"
            ctxSeq = AD_HOC_SEQ
        }

        val jobKey = JobIdentity.of(ctxEvent)
        if (dryRun) {
            store.insert("apply", jobKey, ctxSeq, Outcomes.DRY_RUN, req.chatId, req.messageId?.toString())
            log.info("[dry run] would apply to {} with uploaded resume {}", ctxRef, upload.fileName)
            return TapResponse(Outcomes.DRY_RUN, reply = "[dry run] Would apply to $ctxRef with ${upload.fileName}.")
        }
        val handler = resumeApply ?: return declined("Apply isn't switched on, so I can't use that resume.")
        val ctx = TapContext(
            TapRequest("apply", ctxSeq, req.userId, req.chatId, req.messageId, req.messageDate), ctxEvent, jobKey, ctxRef,
        )
        return try {
            handler.handle(ctx, store, upload)
        } catch (e: Exception) {
            log.error("resume apply {} failed", ctxRef, e)
            store.recordError("resume apply $ctxRef", e.message ?: e.javaClass.simpleName)
            TapResponse(Outcomes.ERROR, reply = "Something went wrong with that application. It's logged; try again.")
        }
    }

    /**
     * Buttons addressed by an action id (Send/Cancel under a draft, Submit/Discard/Continue under a
     * filled application): no seq or eligibility — the action record is the authority.
     */
    private fun byId(verb: String, req: TapRequest): TapResponse {
        val handler = idHandlers[verb] ?: return refused("${verb.replaceFirstChar { it.uppercase() }} isn't available yet.")
        if (dryRun) {
            log.info("[dry run] would {} {}", verb, req.seq)
            return TapResponse(Outcomes.DRY_RUN, toast = "[dry run] Would $verb.")
        }
        return try {
            handler.handle(req.seq, req)
        } catch (e: Exception) {
            log.error("{} {} failed", verb, req.seq, e)
            store.recordError("$verb ${req.seq}", e.message ?: e.javaClass.simpleName)
            TapResponse(Outcomes.ERROR, toast = "Something went wrong — nothing was sent or submitted. It's logged; try again.")
        }
    }

    private fun refused(toast: String) = TapResponse(Outcomes.REFUSED, toast = toast)

    /** A refusal to say in chat: an upload is a message, not a button tap, so there is no toast to show. */
    private fun declined(reply: String) = TapResponse(Outcomes.REFUSED, reply = reply)

    companion object {
        /** Telegram's answerCallbackQuery text limit. */
        private const val TOAST_MAX = 200
        val ID_VERBS = setOf("send", "cancel", "submit", "discard", "resume")
        val KNOWN_VERBS = setOf("apply", "reply", "archive", "undo", "tailor") + ID_VERBS

        /** Verbs that ride on another verb's switch in JOBBOT_ACTIONS. */
        val BASE_VERB = mapOf(
            "undo" to "archive", "send" to "reply", "cancel" to "reply",
            "submit" to "apply", "discard" to "apply", "resume" to "apply",
        )
    }
}

/** Apply until the fill workflow ships (Phase 4): the user asked for exactly this reply. */
object ApplyNotImplemented : VerbHandler {
    const val REPLY = "Not implemented yet"

    override fun handle(ctx: TapContext, store: ActionStore): TapResponse {
        store.insert("apply", ctx.jobKey, ctx.request.seq, Outcomes.NOT_IMPLEMENTED, ctx.request.chatId, ctx.request.messageId?.toString())
        return TapResponse(Outcomes.NOT_IMPLEMENTED, reply = REPLY)
    }
}
