package com.jd.pipeline.cli

import com.jd.pipeline.config.Config
import java.nio.file.Files
import java.nio.file.Path

object CommandParser {
    fun parse(args: Array<String>): Command {
        var test = false
        var testResume = false
        var testCoverLetter = false
        var testSupabase = false
        var testGmail = false
        var reauth = false
        var checkToken = false
        var scanTuner = false
        var scanTunerFile: String? = null
        var scrapeJdTuner = false
        var scrapeJdTunerFile: String? = null
        var maxIterations = 5
        var email: String? = null
        var expectedData: String? = null
        var expectedDataFile: String? = null
        var maxEmails = Config.GMAIL_MAX_EMAILS
        var signedIn = false
        var debug = false
        var jdJson: String? = null
        var resumeGenPath: String? = null
        var initProfilePath: String? = null
        var jsearch = false

        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--test" -> test = true
                "--test-resume" -> testResume = true
                "--test-coverletter" -> testCoverLetter = true
                "--test-supabase" -> testSupabase = true
                "--test-gmail" -> testGmail = true
                "--reauth" -> reauth = true
                "--check-token" -> checkToken = true
                "--scantuner" -> {
                    scanTuner = true
                    if (i + 1 < args.size && !args[i + 1].startsWith("--")) {
                        scanTunerFile = args[i + 1]
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
                "--debug" -> debug = true
                "--signed-in" -> signedIn = true
                "--email" -> {
                    if (i + 1 < args.size) {
                        email = args[i + 1]
                        i++
                    }
                }
                "--expected-data" -> {
                    if (i + 1 < args.size) {
                        expectedData = args[i + 1]
                        i++
                    }
                }
                "--expected-data-file" -> {
                    if (i + 1 < args.size) {
                        expectedDataFile = args[i + 1]
                        i++
                    }
                }
                "--max-emails", "--max-email" -> {
                    if (i + 1 < args.size) {
                        maxEmails = args[i + 1].toIntOrNull() ?: Config.GMAIL_MAX_EMAILS
                        i++
                    }
                }
                "--max-iterations" -> {
                    if (i + 1 < args.size) {
                        maxIterations = (args[i + 1].toIntOrNull() ?: 5).coerceAtLeast(1)
                        i++
                    }
                }
                "--jd-json" -> {
                    if (i + 1 < args.size) {
                        jdJson = args[i + 1]
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
                "--jd-json-file" -> {
                    if (i + 1 < args.size) {
                        val rawPath = args[i + 1]
                        val normalized = Path.of(rawPath).normalize()
                        val projectDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
                        val resolved = normalized.toAbsolutePath().normalize()
                        if (!resolved.startsWith(projectDir) && !resolved.startsWith(Path.of(System.getProperty("java.io.tmpdir")))) {
                            println("[ERROR] --jd-json-file: path escapes allowed directories: $rawPath")
                        } else if (!Files.exists(normalized)) {
                            println("[ERROR] --jd-json-file: file not found: $rawPath")
                        } else {
                            jdJson = Files.readString(normalized)
                        }
                        i++
                    }
                }
                "--jsearch" -> jsearch = true
            }
            i++
        }

        return when {
            test -> Command.Test
            testResume -> Command.TestResume
            testCoverLetter -> Command.TestCoverLetter
            testSupabase -> Command.TestSupabase
            testGmail -> Command.TestGmail
            reauth -> Command.Reauth
            checkToken -> Command.CheckToken
            scanTuner -> Command.ScanTuner(scanTunerFile, maxIterations, debug)
            scrapeJdTuner -> Command.ScrapeJdTuner(scrapeJdTunerFile, maxIterations)
            resumeGenPath != null -> Command.ResumeGen(resumeGenPath)
            initProfilePath != null -> Command.InitProfile(initProfilePath)
            jdJson != null -> Command.JdJson(jdJson)
            email != null -> Command.SingleEmail(email, expectedData, expectedDataFile, maxIterations, debug)
            signedIn -> Command.SignedIn
            jsearch -> Command.JSearch
            else -> Command.Batch(maxEmails, debug)
        }
    }
}
