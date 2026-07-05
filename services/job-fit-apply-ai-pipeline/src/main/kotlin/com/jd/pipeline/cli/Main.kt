package com.jd.pipeline.cli

import com.jd.pipeline.cli.commands.*
import com.jd.pipeline.client.AlertService

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        CliOutput.printBanner()
        val command = CommandParser.parse(args)
        dispatch(command)
    }

    private fun dispatch(command: Command) {
        when (command) {
            Command.Test -> TestCommandHandler.run()
            Command.TestResume -> TestResumeCommandHandler.run()
            Command.TestCoverLetter -> TestCoverLetterCommandHandler.run()
            Command.TestSupabase -> TestSupabaseCommandHandler.run()
            is Command.TestChrome -> TestChromeCommandHandler.run(command)
            is Command.ScrapeJdTuner -> ScrapeJdTunerCommandHandler.run(command)
            is Command.ResumeGen -> ResumeGenCommandHandler.run(command)
            is Command.InitProfile -> InitProfileCommandHandler.run(command)
            Command.SignedIn -> SignedInCommandHandler.run()
            Command.JSearch -> JSearchCommandHandler.run()
            Command.Processor -> ProcessorCommandHandler.run()
            is Command.NotifyTimeout -> AlertService().pipelineTimeout(command.minutes)
            Command.Usage -> printUsage()
        }
    }

    private fun printUsage() {
        // Gmail intake/write-back lives in the Poller service (Phase 1). The Processor is
        // Gmail-free: it only claims work items from the bridge and processes them.
        println(
            """
            Usage: pipeline <command>

            Long-running:
              --processor              Claim work items from the bridge and process them (scan/scrape/score/tailor)

            One-shot / dev:
              --jsearch                Ingest JSearch API results
              --resume-gen <path>      Generate a tailored resume from a JD file
              --init-profile <path>    Scaffold a candidate profile
              --scrapetuner [file]     Tune the JD scraper
              --signed-in              Report signed-in scraping status
              --test, --test-resume, --test-coverletter, --test-supabase, --test-chrome [url]
              --notify-timeout <min>   Send a pipeline-timeout alert

            Note: email intake, drafts, and Gmail labeling are handled by the Poller service.
            """.trimIndent()
        )
    }
}
