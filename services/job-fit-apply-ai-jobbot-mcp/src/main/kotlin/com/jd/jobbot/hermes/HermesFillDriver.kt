package com.jd.jobbot.hermes

import com.jd.jobbot.apply.ApplyPage
import com.jd.jobbot.apply.FillExecutor
import com.jd.jobbot.apply.FillInstructions
import com.jd.jobbot.apply.FillResult
import com.jd.jobbot.apply.FillSession
import com.jd.jobbot.apply.FillSessions
import com.jd.jobbot.apply.JobContext
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

/**
 * Plans an application fill by handing it to Hermes. It opens a [FillSession] on the tab, starts a Hermes run whose
 * only way to touch the page is the `fill_*` tools (which route through that session and its hard rules), then waits
 * for the fill to end. It is the replacement for a model loop inside jobbot-mcp: same executor, same guards, but the
 * planning is Hermes' to do, in its own session. Returns once the planner finishes, hands off, runs out of budget, or
 * stops without finishing.
 */
class HermesFillDriver(
    private val runs: HermesRuns,
    private val sessions: FillSessions,
    private val executor: FillExecutor,
    /** Passed to Hermes as the run's model; null/blank leaves it on Hermes' own configured model. */
    private val model: String? = null,
    private val maxWait: Duration = Duration.ofMinutes(25),
    private val maxActCalls: Int = 30,
    private val pollMillis: Long = 1_000,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(HermesFillDriver::class.java)

    fun run(page: ApplyPage, job: JobContext, startFresh: Boolean = true): FillResult {
        if (startFresh) page.goto(job.jobUrl)
        val session = FillSession(sessions.newToken(), page, job, executor, maxActCalls, clock)
        sessions.open(session)
        var runId: String? = null
        try {
            runId = start(session, job, startFresh)
            val deadline = clock.millis() + maxWait.toMillis()
            while (true) {
                session.await(pollMillis)?.let { return it }
                val status = runCatching { runs.status(runId) }.onFailure { log.warn("Hermes run status failed: {}", it.message) }.getOrNull()
                if (status != null && status.terminal) {
                    // The planner's last tool call and the run ending can race; the session's word wins.
                    session.outcome?.let { return it }
                    val shot = executor.shot(page)
                    return if (status.status == "completed") {
                        FillResult.NeedsHuman("The assistant stopped before the form was ready. It's open in the viewer.", shot)
                    } else {
                        FillResult.Failed("The assistant's run ${status.status}: ${status.error ?: "no reason given"}", shot)
                    }
                }
                if (clock.millis() >= deadline) {
                    return FillResult.NeedsHuman("Filling took longer than ${maxWait.toMinutes()} minutes, so I stopped. It's open in the viewer.", executor.shot(page))
                }
            }
        } catch (e: Exception) {
            log.warn("fill via Hermes failed: {}", e.message)
            return FillResult.Failed("Couldn't run the assistant: ${e.message}", executor.shot(page))
        } finally {
            // Whatever ended the fill, the planner has no more business with this tab.
            session.abandon(FillResult.Failed("The fill is over.", null))
            runId?.let { runs.stop(it) }
            sessions.close(session.token)
        }
    }

    /** Starts the run; if Hermes refuses the model name, tries once more on its own configured model. */
    private fun start(session: FillSession, job: JobContext, fresh: Boolean): String {
        val input = FillInstructions.start(session.token, job, fresh)
        val sessionId = "fill-${session.token}"
        return try {
            runs.start(FillInstructions.SYSTEM, input, sessionId, model)
        } catch (e: HermesRuns.HermesException) {
            if (model.isNullOrBlank() || e.status !in 400..422) throw e
            log.warn("Hermes refused model '{}' ({}); starting the run on its configured model", model, e.message)
            runs.start(FillInstructions.SYSTEM, input, sessionId, null)
        }
    }
}
