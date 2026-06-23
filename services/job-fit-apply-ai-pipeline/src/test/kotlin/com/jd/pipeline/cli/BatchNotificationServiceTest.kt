package com.jd.pipeline.cli

import com.jd.pipeline.client.NotificationClient
import com.jd.pipeline.utils.NodeTimer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("BatchNotificationService")
class BatchNotificationServiceTest {

    private lateinit var client: NotificationClient
    private lateinit var service: BatchNotificationService

    private fun summary(
        emailsProcessed: Int = 1,
        jobs: Int = 1,
        tailored: Int = 0,
        skipped: Int = 0,
        duplicate: Int = 0,
        scoredJobs: List<ScoredJob> = emptyList(),
    ) = BatchNotificationService.BatchSummary(
        emailsProcessed = emailsProcessed,
        jobs            = jobs,
        tailored        = tailored,
        skipped         = skipped,
        duplicate       = duplicate,
        startTime       = Instant.now().minusSeconds(60),
        scoredJobs      = scoredJobs,
    )

    @BeforeEach
    fun setUp() {
        NodeTimer.reset()
        client = mock<NotificationClient>()
        service = BatchNotificationService(client = client, fitThreshold = 75)
    }

    @Nested
    @DisplayName("early-exit conditions")
    inner class EarlyExit {

        @Test
        @DisplayName("sends nothing when neither Discord nor Telegram is configured")
        fun skipsWhenNoChannelsConfigured() {
            whenever(client.discordConfigured).thenReturn(false)
            whenever(client.telegramConfigured).thenReturn(false)
            service.notify(summary())
            verify(client, never()).postDiscord(any())
            verify(client, never()).postTelegram(any())
        }

        @Test
        @DisplayName("sends nothing when emailsProcessed is 0")
        fun skipsWhenNoEmailsProcessed() {
            whenever(client.discordConfigured).thenReturn(true)
            whenever(client.telegramConfigured).thenReturn(false)
            service.notify(summary(emailsProcessed = 0))
            verify(client, never()).postDiscord(any())
            verify(client, never()).postTelegram(any())
        }
    }

    @Nested
    @DisplayName("Discord messages")
    inner class DiscordMessages {

        @BeforeEach
        fun enableDiscord() {
            whenever(client.discordConfigured).thenReturn(true)
            whenever(client.telegramConfigured).thenReturn(false)
        }

        @Test
        @DisplayName("posts batch summary to Discord")
        fun postsBatchSummary() {
            service.notify(summary(emailsProcessed = 3, jobs = 2, tailored = 1))
            verify(client).postDiscord(any())
        }

        @Test
        @DisplayName("posts at least two Discord messages when scoredJobs is non-empty")
        fun postsScoredJobsWhenPresent() {
            val jobs = listOf(ScoredJob("Acme", "Engineer", 80, "TAILOR", null, null))
            service.notify(summary(scoredJobs = jobs))
            // batch summary + scored-jobs list = at least 2 Discord calls
            verify(client, org.mockito.kotlin.atLeast(2)).postDiscord(any())
        }

        @Test
        @DisplayName("posts at least one Discord message even when scoredJobs is empty")
        fun postsAtLeastOneWhenNoScoredJobs() {
            service.notify(summary(scoredJobs = emptyList()))
            verify(client, org.mockito.kotlin.atLeast(1)).postDiscord(any())
        }
    }

    @Nested
    @DisplayName("Telegram high-fit ping")
    inner class TelegramPing {

        @BeforeEach
        fun enableBoth() {
            whenever(client.discordConfigured).thenReturn(true)
            whenever(client.telegramConfigured).thenReturn(true)
        }

        @Test
        @DisplayName("sends Telegram ping (HTML) when at least one job meets threshold")
        fun pingsSentForHighFitJob() {
            val jobs = listOf(ScoredJob("Acme", "Staff SDET", 90, "TAILOR", null, null))
            service.notify(summary(scoredJobs = jobs))
            verify(client).postTelegramHtml(any())
        }

        @Test
        @DisplayName("does not send Telegram ping when all jobs are below threshold")
        fun noPingBelowThreshold() {
            val jobs = listOf(ScoredJob("Acme", "Engineer", 50, "SKIP", null, null))
            service.notify(summary(scoredJobs = jobs))
            verify(client, never()).postTelegramHtml(any())
        }

        @Test
        @DisplayName("does not send Telegram ping for high-score jobs that have errors")
        fun noPingForErrorJobs() {
            val jobs = listOf(ScoredJob("Acme", "Engineer", 90, "TAILOR", "LLM timeout", null))
            service.notify(summary(scoredJobs = jobs))
            verify(client, never()).postTelegramHtml(any())
        }

        @Test
        @DisplayName("links the role title to report.md in the Telegram high-fit ping")
        fun linksTitleToReportInPing() {
            val jobs = listOf(ScoredJob("Acme", "Staff SDET", 90, "TAILOR", null, "https://artifacts.example.com/acme/"))
            service.notify(summary(scoredJobs = jobs))
            verify(client).postTelegramHtml(org.mockito.kotlin.check {
                assertTrue(
                    it.contains("<a href=\"https://artifacts.example.com/acme/report.md\">Staff SDET</a>"),
                    "title should be an HTML link to report.md"
                )
                assertTrue(!it.contains("Report:"), "should not dump the raw URL with a 'Report:' label")
            })
        }

        @Test
        @DisplayName("uses a plain title (no link) when artifactUrl is null")
        fun plainTitleWhenNoArtifactUrl() {
            val jobs = listOf(ScoredJob("Acme", "Staff SDET", 90, "TAILOR", null, null))
            service.notify(summary(scoredJobs = jobs))
            verify(client).postTelegramHtml(org.mockito.kotlin.check {
                assertTrue(!it.contains("<a href"), "no link when there is no artifact URL")
                assertTrue(it.contains("Staff SDET"), "plain title should still be present")
            })
        }

        @Test
        @DisplayName("uses a plain title (no link) when artifactUrl is blank")
        fun plainTitleWhenArtifactUrlBlank() {
            val jobs = listOf(ScoredJob("Acme", "Staff SDET", 90, "TAILOR", null, ""))
            service.notify(summary(scoredJobs = jobs))
            verify(client).postTelegramHtml(org.mockito.kotlin.check {
                assertTrue(!it.contains("<a href"), "no link when artifact URL is blank")
            })
        }
    }

    @Nested
    @DisplayName("logConfigStatus")
    inner class LogConfigStatus {

        @Test
        @DisplayName("returns false and warns when no channel is configured")
        fun falseWhenUnconfigured() {
            whenever(client.discordConfigured).thenReturn(false)
            whenever(client.telegramConfigured).thenReturn(false)
            assertEquals(false, service.logConfigStatus())
        }

        @Test
        @DisplayName("returns true when Discord is configured")
        fun trueWhenDiscordConfigured() {
            whenever(client.discordConfigured).thenReturn(true)
            whenever(client.telegramConfigured).thenReturn(false)
            assertEquals(true, service.logConfigStatus())
        }

        @Test
        @DisplayName("returns true when only Telegram is configured")
        fun trueWhenTelegramConfigured() {
            whenever(client.discordConfigured).thenReturn(false)
            whenever(client.telegramConfigured).thenReturn(true)
            assertEquals(true, service.logConfigStatus())
        }
    }

    @Nested
    @DisplayName("notifyJobResult (per-job worker notification)")
    inner class NotifyJobResult {

        @Test
        @DisplayName("sends nothing when neither channel is configured")
        fun skipsWhenUnconfigured() {
            whenever(client.discordConfigured).thenReturn(false)
            whenever(client.telegramConfigured).thenReturn(false)
            service.notifyJobResult(ScoredJob("Acme", "Staff SDET", 90, "TAILOR", null, null))
            verify(client, never()).postDiscord(any())
            verify(client, never()).postTelegram(any())
        }

        @Test
        @DisplayName("posts a Discord line for every successful job")
        fun postsDiscordPerJob() {
            whenever(client.discordConfigured).thenReturn(true)
            whenever(client.telegramConfigured).thenReturn(false)
            service.notifyJobResult(ScoredJob("Acme", "Engineer", 30, "SKIP", null, null))
            verify(client).postDiscord(any())
        }

        @Test
        @DisplayName("pings Telegram for a high-fit job at/above threshold")
        fun pingsTelegramForHighFit() {
            whenever(client.discordConfigured).thenReturn(true)
            whenever(client.telegramConfigured).thenReturn(true)
            // fitThreshold is 75 (set in @BeforeEach)
            service.notifyJobResult(ScoredJob("Acme", "Staff SDET", 75, "TAILOR", null, null))
            verify(client).postDiscord(any())
            verify(client).postTelegramHtml(any())
        }

        @Test
        @DisplayName("does not ping Telegram below threshold")
        fun noTelegramBelowThreshold() {
            whenever(client.discordConfigured).thenReturn(true)
            whenever(client.telegramConfigured).thenReturn(true)
            service.notifyJobResult(ScoredJob("Acme", "Engineer", 74, "TAILOR", null, null))
            verify(client).postDiscord(any())
            verify(client, never()).postTelegramHtml(any())
        }

        @Test
        @DisplayName("posts an error line and never pings Telegram for failed jobs")
        fun errorJobPostsErrorOnly() {
            whenever(client.discordConfigured).thenReturn(true)
            whenever(client.telegramConfigured).thenReturn(true)
            service.notifyJobResult(ScoredJob("Acme", "Engineer", 90, "TAILOR", "LLM timeout", null))
            verify(client).postDiscord(any())
            verify(client, never()).postTelegramHtml(any())
        }

        @Test
        @DisplayName("links the role title to report.md in the per-job Telegram ping")
        fun linksTitleToReportInPerJobPing() {
            whenever(client.discordConfigured).thenReturn(true)
            whenever(client.telegramConfigured).thenReturn(true)
            service.notifyJobResult(ScoredJob("Acme", "Staff SDET", 90, "TAILOR", null, "https://artifacts.example.com/acme/"))
            verify(client).postTelegramHtml(org.mockito.kotlin.check {
                assertTrue(
                    it.contains("<a href=\"https://artifacts.example.com/acme/report.md\">Staff SDET</a>"),
                    "title should be an HTML link to report.md"
                )
                assertTrue(!it.contains("Report:"), "should not dump the raw URL with a 'Report:' label")
            })
        }

        @Test
        @DisplayName("uses a plain title (no link) in per-job ping when artifactUrl is null")
        fun plainTitleInPerJobPingWhenNoUrl() {
            whenever(client.discordConfigured).thenReturn(true)
            whenever(client.telegramConfigured).thenReturn(true)
            service.notifyJobResult(ScoredJob("Acme", "Staff SDET", 90, "TAILOR", null, null))
            verify(client).postTelegramHtml(org.mockito.kotlin.check {
                assertTrue(!it.contains("<a href"), "no link when there is no artifact URL")
            })
        }

        @Test
        @DisplayName("anchors the company to the job posting URL when present")
        fun anchorsCompanyToJobUrl() {
            whenever(client.discordConfigured).thenReturn(true)
            whenever(client.telegramConfigured).thenReturn(true)
            service.notifyJobResult(
                ScoredJob(
                    "Acme", "Staff SDET", 90, "TAILOR", null,
                    artifactUrl = "https://artifacts.example.com/acme/",
                    jobUrl = "https://boards.example.com/acme/job/123",
                )
            )
            verify(client).postTelegramHtml(org.mockito.kotlin.check {
                assertTrue(
                    it.contains("<a href=\"https://boards.example.com/acme/job/123\">Acme</a>"),
                    "company should link to the job posting URL"
                )
                assertTrue(
                    it.contains("<a href=\"https://artifacts.example.com/acme/report.md\">Staff SDET</a>"),
                    "title should still link to report.md"
                )
            })
        }

        @Test
        @DisplayName("uses a plain company (no link) when there is no job URL")
        fun plainCompanyWhenNoJobUrl() {
            whenever(client.discordConfigured).thenReturn(true)
            whenever(client.telegramConfigured).thenReturn(true)
            service.notifyJobResult(
                ScoredJob("Acme", "Staff SDET", 90, "TAILOR", null, artifactUrl = "https://artifacts.example.com/acme/", jobUrl = null)
            )
            verify(client).postTelegramHtml(org.mockito.kotlin.check {
                assertTrue(it.contains("High-fit: Acme — <a href="), "company should be plain text when no job URL")
            })
        }

        @Test
        @DisplayName("HTML-escapes the company/title in the per-job ping")
        fun htmlEscapesPerJobPing() {
            whenever(client.discordConfigured).thenReturn(true)
            whenever(client.telegramConfigured).thenReturn(true)
            service.notifyJobResult(ScoredJob("A&B <Corp>", "Dev & Ops", 90, "TAILOR", null, null))
            verify(client).postTelegramHtml(org.mockito.kotlin.check {
                assertTrue(it.contains("A&amp;B &lt;Corp&gt;"), "company should be HTML-escaped")
                assertTrue(it.contains("Dev &amp; Ops"), "title should be HTML-escaped")
            })
        }

        @Test
        @DisplayName("links the title to the artifact URL in the Discord per-job line")
        fun discordLineLinksTitle() {
            whenever(client.discordConfigured).thenReturn(true)
            whenever(client.telegramConfigured).thenReturn(false)
            service.notifyJobResult(ScoredJob("Acme", "Staff SDET", 30, "SKIP", null, "https://artifacts.example.com/acme"))
            verify(client).postDiscord(org.mockito.kotlin.check {
                assertTrue(
                    it.contains("[Staff SDET](https://artifacts.example.com/acme)"),
                    "Discord line should link the title to the artifact URL"
                )
            })
        }

        @Test
        @DisplayName("Discord per-job line uses a plain (unlinked) title when no artifact URL")
        fun discordLinePlainTitleWhenNoUrl() {
            whenever(client.discordConfigured).thenReturn(true)
            whenever(client.telegramConfigured).thenReturn(false)
            service.notifyJobResult(ScoredJob("Acme", "Staff SDET", 30, "SKIP", null, null))
            verify(client).postDiscord(org.mockito.kotlin.check {
                assertTrue(!it.contains("]("), "Discord line should have no Markdown link when there is no URL")
                assertTrue(it.contains("Staff SDET"), "Discord line should still contain the plain title")
            })
        }
    }

    @Nested
    @DisplayName("notifyTimeout (pipeline-timeout alert)")
    inner class NotifyTimeoutAlert {

        @Test
        @DisplayName("posts the timeout alert to both Discord and Telegram")
        fun postsToBothChannels() {
            whenever(client.discordConfigured).thenReturn(true)
            whenever(client.telegramConfigured).thenReturn(true)
            service.notifyTimeout(120)
            verify(client).postDiscord(org.mockito.kotlin.check {
                assertTrue(it.contains("120 min"), "alert should state the timeout duration")
                assertTrue(it.contains("timed out"), "alert should say it timed out")
            })
            verify(client).postTelegram(org.mockito.kotlin.check {
                assertTrue(it.contains("120 min"), "alert should state the timeout duration")
            })
        }

        @Test
        @DisplayName("sends nothing when no channel is configured")
        fun skipsWhenUnconfigured() {
            whenever(client.discordConfigured).thenReturn(false)
            whenever(client.telegramConfigured).thenReturn(false)
            service.notifyTimeout(120)
            verify(client, never()).postDiscord(any())
            verify(client, never()).postTelegram(any())
        }
    }

    @Nested
    @DisplayName("ScoredJob data class")
    inner class ScoredJobModel {

        @Test
        @DisplayName("holds all fields correctly including artifactUrl")
        fun holdsFields() {
            val job = ScoredJob("Acme", "Staff SDET", 88, "TAILOR", null, "https://artifacts.example.com/acme")
            assertEquals("Acme", job.company)
            assertEquals("Staff SDET", job.roleTitle)
            assertEquals(88, job.fitScore)
            assertEquals("TAILOR", job.pipelineAction)
            assertEquals(null, job.error)
            assertEquals("https://artifacts.example.com/acme", job.artifactUrl)
        }

        @Test
        @DisplayName("defaults artifactUrl to null")
        fun defaultsArtifactUrlToNull() {
            val job = ScoredJob("Acme", "Staff SDET", 88, "TAILOR", null)
            assertEquals(null, job.artifactUrl)
        }

        @Test
        @DisplayName("supports null fitScore and error")
        fun supportsNulls() {
            val job = ScoredJob("Corp", "Engineer", null, null, "oops")
            assertEquals(null, job.fitScore)
            assertEquals("oops", job.error)
        }
    }
}
