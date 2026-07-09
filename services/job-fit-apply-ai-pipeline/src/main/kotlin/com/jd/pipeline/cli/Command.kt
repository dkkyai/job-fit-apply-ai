package com.jd.pipeline.cli

sealed class Command {
    object Test : Command()
    object TestResume : Command()
    object TestCoverLetter : Command()
    object TestSupabase : Command()
    /** Smoke-test the persistent Chrome (CDP) scraping setup. Optional probe URL. */
    data class TestChrome(val url: String?) : Command()
    data class ScrapeJdTuner(val file: String?, val maxIterations: Int) : Command()
    data class ResumeGen(val path: String) : Command()
    data class InitProfile(val path: String) : Command()
    object SignedIn : Command()
    /** Long-running Processor loop: claim work items from the bridge and process them (no Gmail). */
    object Processor : Command()
    /** Exit 0 iff the processor loop's heartbeat is fresh — container healthcheck. */
    object Health : Command()
    /** Send a pipeline-timeout alert to Discord/Telegram. Invoked by run_jd_pipeline.sh. */
    data class NotifyTimeout(val minutes: Int) : Command()
    /** No recognized command — print usage. */
    object Usage : Command()
}
