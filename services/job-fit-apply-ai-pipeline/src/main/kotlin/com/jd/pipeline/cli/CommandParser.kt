package com.jd.pipeline.cli

object CommandParser {
    fun parse(args: Array<String>): Command {
        var test = false
        var testResume = false
        var testCoverLetter = false
        var testSupabase = false
        var testChrome = false
        var testChromeUrl: String? = null
        var scrapeJdTuner = false
        var scrapeJdTunerFile: String? = null
        var maxIterations = 5
        var signedIn = false
        var resumeGenPath: String? = null
        var initProfilePath: String? = null
        var jsearch = false
        var processor = false
        var notifyTimeoutMinutes: Int? = null

        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--test" -> test = true
                "--test-resume" -> testResume = true
                "--test-coverletter" -> testCoverLetter = true
                "--test-supabase" -> testSupabase = true
                "--test-chrome" -> {
                    testChrome = true
                    if (i + 1 < args.size && !args[i + 1].startsWith("--")) {
                        testChromeUrl = args[i + 1]
                        i++
                    }
                }
                "--scrapetuner" -> {
                    scrapeJdTuner = true
                    if (i + 1 < args.size && !args[i + 1].startsWith("--")) {
                        scrapeJdTunerFile = args[i + 1]
                        i++
                    }
                }
                "--signed-in" -> signedIn = true
                "--max-iterations" -> {
                    if (i + 1 < args.size) {
                        maxIterations = (args[i + 1].toIntOrNull() ?: 5).coerceAtLeast(1)
                        i++
                    }
                }
                "--resume-gen" -> {
                    if (i + 1 < args.size) {
                        resumeGenPath = args[i + 1]
                        i++
                    }
                }
                "--init-profile" -> {
                    if (i + 1 < args.size) {
                        initProfilePath = args[i + 1]
                        i++
                    }
                }
                "--jsearch" -> jsearch = true
                "--processor" -> processor = true
                // Deprecated alias for --processor (Phase 1 rename). Removed at cutover.
                "--worker" -> {
                    System.err.println("[cli] --worker is deprecated; use --processor")
                    processor = true
                }
                "--notify-timeout" -> {
                    if (i + 1 < args.size) {
                        notifyTimeoutMinutes = args[i + 1].toIntOrNull()
                        i++
                    }
                }
            }
            i++
        }

        return when {
            notifyTimeoutMinutes != null -> Command.NotifyTimeout(notifyTimeoutMinutes)
            test -> Command.Test
            testResume -> Command.TestResume
            testCoverLetter -> Command.TestCoverLetter
            testSupabase -> Command.TestSupabase
            testChrome -> Command.TestChrome(testChromeUrl)
            scrapeJdTuner -> Command.ScrapeJdTuner(scrapeJdTunerFile, maxIterations)
            resumeGenPath != null -> Command.ResumeGen(resumeGenPath)
            initProfilePath != null -> Command.InitProfile(initProfilePath)
            signedIn -> Command.SignedIn
            jsearch -> Command.JSearch
            processor -> Command.Processor
            else -> Command.Usage
        }
    }
}
