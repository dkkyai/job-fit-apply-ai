package com.jd.jobbot.apply

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One application fill, as the planner (the model behind the `fill_*` tools) sees it. It owns what the old model loop
 * kept in local variables: the page, the job, the passwords typed so far, the step budget, and the outcome. Every
 * method returns the JSON text the planner reads, already scrubbed of any password this fill typed, and every action
 * goes through [FillExecutor] and its hard rules. The planner decides what to do next; it can do nothing else.
 *
 * Calls are serialized: a browser tab is driven by one thing at a time.
 */
class FillSession(
    val token: String,
    private val page: ApplyPage,
    val job: JobContext,
    private val executor: FillExecutor,
    /** How many `act` calls a fill may make before it hands off to Richard. */
    private val maxActCalls: Int = 30,
    clock: Clock = Clock.systemUTC(),
) {
    private val started = clock.millis()
    /** Every password this fill typed. Whatever the page reflects, none of them may reach the planner. */
    private val secrets = mutableSetOf<String>()
    private var actCalls = 0
    /** Every call of any kind. Hermes does not cap a run's iterations, so a planner that only ever looks (snapshot after snapshot) must still run out. */
    private var calls = 0
    private val maxCalls = maxActCalls * CALLS_PER_ACT
    private var lastSnapshot: Snapshot? = null

    private val done = CountDownLatch(1)

    @Volatile
    var outcome: FillResult? = null
        private set

    /** Waits up to [millis] for the fill to end; the outcome, or null if it is still going. */
    fun await(millis: Long): FillResult? {
        done.await(millis, TimeUnit.MILLISECONDS)
        return outcome
    }

    /** What the job is and everything the planner may answer from: the same material the old model loop was shown. */
    @Synchronized
    fun context(): String = outcome?.let { stopped(it) } ?: overBudget() ?: json(buildJsonObject {
        put("job", buildJsonObject {
            put("ref", job.ref); put("company", job.company); put("title", job.title); put("posting_url", job.jobUrl)
            put("account_email", job.accountEmail)
        })
        put("candidate_profile_yaml", job.profileYaml?.take(PROFILE_CHARS))
        put("tailored_resume_yaml", job.resumeYaml?.take(PROFILE_CHARS))
        put("cover_letter", job.coverLetter?.take(COVER_LETTER_CHARS))
        job.answers?.let { put("application_answers", it.take(PROFILE_CHARS)) }
        if (job.resumeIsUpload) {
            put("resume_file", "Richard sent his own resume (${job.resumeFileName}) for this application. Upload it with upload_resume. " +
                "You cannot read it: answer from candidate_profile_yaml only. There is no cover letter.")
        }
        put("act_calls_left", maxActCalls - actCalls)
    })

    /**
     * The page now: its interactive elements by id, and its visible text (untrusted). Google's own sign-in screens
     * are handled here by rule and never shown to the planner; a CAPTCHA or a hand-off ends the fill.
     */
    @Synchronized
    fun snapshot(): String {
        outcome?.let { return stopped(it) }
        overBudget()?.let { return it }
        val notes = mutableListOf<String>()
        var snap = page.snapshot()
        var handled = 0
        while (true) {
            if (snap.captcha) return stop(FillResult.NeedsHuman("There's a CAPTCHA on ${executor.host(snap.url)}.", executor.shot(page)))
            val google = executor.handleGoogle(page, snap, job) ?: break
            when (google) {
                is FillExecutor.GoogleOutcome.Handoff -> return stop(FillResult.NeedsHuman(google.reason, executor.shot(page)))
                FillExecutor.GoogleOutcome.Continued -> {
                    notes += "Google's sign-in page was handled by code (continued)."
                    if (++handled >= MAX_GOOGLE_SCREENS) break
                    page.settle()
                    snap = page.snapshot()
                }
            }
        }
        lastSnapshot = snap
        return json(buildJsonObject {
            put("status", "continue")
            if (notes.isNotEmpty()) put("notes", JsonArray(notes.map { JsonPrimitive(it) }))
            put("page", buildJsonObject {
                put("url", snap.url); put("title", snap.title)
                put("elements", JSON.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(Element.serializer()), snap.elements.take(MAX_ELEMENTS)))
                put("visible_text_UNTRUSTED", snap.text.take(VISIBLE_TEXT_CHARS))
            })
            put("act_calls_left", maxActCalls - actCalls)
        })
    }

    /** Runs up to [MAX_ACTIONS] actions, in order, on the elements of the latest snapshot. Stops at the first refusal or error. */
    @Synchronized
    fun act(actions: List<JsonObject>): String {
        outcome?.let { return stopped(it) }
        overBudget()?.let { return it }
        if (++actCalls > maxActCalls) {
            return stop(FillResult.NeedsHuman("I ran out of steps before the form was ready. It's open in the viewer.", executor.shot(page)))
        }
        val snap = lastSnapshot ?: return json(buildJsonObject {
            put("status", "continue"); put("error", "Call fill_snapshot first: element ids come from the latest snapshot.")
        })
        val results = mutableListOf<JsonElement>()
        for (action in actions.take(MAX_ACTIONS)) {
            val result = runCatching { executor.execute(action, page, snap, job, started, secrets) { _, _ -> } }
                .getOrElse { "error: ${it.javaClass.simpleName}: ${it.message?.take(160)}" }
            results += buildJsonObject { put("do", action.text("do")); put("target", action.text("id") ?: action.text("url")); put("result", result) }
            if (result.startsWith("handoff:")) {
                return stop(FillResult.NeedsHuman(result.removePrefix("handoff:").trim(), executor.shot(page)))
            }
            if (result.startsWith("refused") || result.startsWith("error")) break
        }
        page.settle()
        return json(buildJsonObject {
            put("status", "continue")
            put("results", JsonArray(results))
            if (actions.size > MAX_ACTIONS) put("not_run", actions.size - MAX_ACTIONS)
            put("act_calls_left", maxActCalls - actCalls)
        })
    }

    /** The planner's last word: the form is [ready] for Richard's review, or a person is needed. */
    @Synchronized
    fun finish(status: String, note: String): String {
        outcome?.let { return stopped(it) }
        return when (status) {
            // A new password stays pending here: "ready" is not proof the site accepted the signup. It is activated
            // only when an application on that site is confirmed submitted.
            "ready" -> stop(
                runCatching { FillResult.Ready(FillExecutor.fieldsOf(page.snapshot()), page.screenshot(), note) }
                    .getOrElse { FillResult.Failed("Couldn't read the finished form: ${it.message}", null) },
            )
            "need_human" -> stop(FillResult.NeedsHuman(note.ifBlank { "This step needs you." }, executor.shot(page)))
            else -> json(buildJsonObject { put("status", "continue"); put("error", "status must be \"ready\" or \"need_human\".") })
        }
    }

    /** The planner is gone or too slow: end the fill with [result] unless it already ended. */
    @Synchronized
    fun abandon(result: FillResult) {
        if (outcome == null) { outcome = result; done.countDown() }
    }

    /** Counts a call; once the budget is spent the fill is handed to Richard and the planner is told it is over. */
    private fun overBudget(): String? =
        if (++calls > maxCalls) stop(FillResult.NeedsHuman("I ran out of steps before the form was ready. It's open in the viewer.", executor.shot(page))) else null

    private fun stop(result: FillResult): String {
        outcome = result
        done.countDown()
        return stopped(result)
    }

    private fun stopped(result: FillResult): String = json(buildJsonObject {
        put("status", "stopped")
        put("message", when (result) {
            is FillResult.Ready -> "The form is ready for Richard's review. The fill is over: do not call any more fill tools."
            is FillResult.NeedsHuman -> "The fill is over: Richard has been asked to step in (${result.reason.take(200)}). Do not call any more fill tools."
            is FillResult.Failed -> "The fill failed (${result.reason.take(200)}). Do not call any more fill tools."
        })
    })

    private fun json(obj: JsonObject): String = secrets.fold(obj.toString()) { acc, s -> if (s.length >= MIN_SECRET) acc.replace(s, "[redacted]") else acc }

    companion object {
        const val MAX_ACTIONS = 15
        /** Looking is cheaper than acting, so a fill may make this many calls of any kind per act call it is allowed. */
        const val CALLS_PER_ACT = 4
        private const val MAX_ELEMENTS = 150
        private const val VISIBLE_TEXT_CHARS = 3000
        private const val PROFILE_CHARS = 12_000
        private const val COVER_LETTER_CHARS = 4_000
        private const val MAX_GOOGLE_SCREENS = 5
        private const val MIN_SECRET = 8
        private val JSON = Json { encodeDefaults = false; explicitNulls = false }
    }
}

/** The fills in progress, by the session token the planner was handed. A token is the only way to reach a fill's browser tab. */
class FillSessions {
    private val sessions = ConcurrentHashMap<String, FillSession>()

    fun newToken(): String = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    fun open(session: FillSession) { sessions[session.token] = session }
    fun get(token: String?): FillSession? = token?.let { sessions[it] }
    fun close(token: String) { sessions.remove(token) }
    fun active(): Int = sessions.size

    private companion object { val random = SecureRandom() }
}
