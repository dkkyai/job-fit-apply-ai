package com.jd.jobbot.actions

import com.jd.jobbot.jobs.Eligibility
import com.jd.jobbot.jobs.JobIdentity
import com.jd.jobbot.jobs.JobLookup
import com.jd.jobbot.jobs.JobRef
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
) {
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
        req.messageDate?.let { sentAt ->
            val age = Duration.ofSeconds(clock.instant().epochSecond - sentAt)
            if (age > cardTtl) {
                return TapResponse(
                    Outcomes.EXPIRED,
                    toast = "This card is more than ${cardTtl.toDays()} days old, so its buttons have expired.",
                    actionRow = emptyList(),
                )
            }
        }
        val baseVerb = if (verb == "undo") "archive" else verb
        if (baseVerb !in handledVerbs) return refused("${verb.replaceFirstChar { it.uppercase() }} isn't available yet.")

        val event = try {
            lookup.event(req.seq)
        } catch (e: Exception) {
            store.recordError("tap $verb $ref", "bridge lookup failed: ${e.message}")
            return TapResponse(Outcomes.ERROR, toast = "Couldn't reach JFAA. Try again in a minute.")
        } ?: return refused("Unknown job $ref.")

        if (!Eligibility.eligible(baseVerb, event)) return refused("${baseVerb.replaceFirstChar { it.uppercase() }} isn't available for this job.")

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

    private fun refused(toast: String) = TapResponse(Outcomes.REFUSED, toast = toast)

    companion object {
        val KNOWN_VERBS = setOf("apply", "reply", "archive", "undo")
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
