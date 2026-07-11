package com.jd.pipeline.cli.commands

import com.jd.pipeline.cli.Command
import com.jd.pipeline.client.SteelBrowser
import com.jd.pipeline.config.Config
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.WaitUntilState
import java.net.URI

/**
 * Smoke-test for the self-hosted Steel Browser backend. Creates a Steel session, connects over CDP,
 * opens a tab to a probe URL (default: LinkedIn jobs), and reports whether the session looks
 * authenticated — plus the interactive debug URL to open on a phone for re-auth. Does not run the
 * pipeline. Run after bringing up the `steel` service.
 *
 *   ./gradlew run --args="--test-steel"
 *   ./gradlew run --args="--test-steel https://www.linkedin.com/jobs/view/123456789"
 */
object TestSteelCommandHandler {
    private const val DEFAULT_PROBE_URL = "https://www.linkedin.com/jobs/"

    fun run(cmd: Command.TestSteel) {
        val base = Config.STEEL_BASE_URL
        println("[test-steel] STEEL_BASE_URL=${base.ifBlank { "(empty)" }}  STEEL_UI_URL=${Config.STEEL_UI_URL.ifBlank { "(empty)" }}")

        if (base.isBlank()) {
            println("[test-steel] Steel is disabled — set STEEL_BASE_URL (e.g. http://localhost:3000) and bring up the steel service.")
            return
        }

        val browser = SteelBrowser()
        try {
            if (!browser.isAvailable()) {
                println("[test-steel] ❌ Could not create/connect a Steel session at $base.")
                println("[test-steel]    Check the steel service is up and reachable, then re-run.")
                return
            }
            println("[test-steel] ✅ Connected to a Steel session (CDP) at $base")
            browser.debugUrl()?.let { println("[test-steel] 🔗 Debug URL (open on your phone to sign in): $it") }

            val url = cmd.url?.takeIf { it.isNotBlank() } ?: DEFAULT_PROBE_URL
            val host = runCatching { URI.create(url).host?.lowercase() }.getOrNull().orEmpty()
            println("[test-steel] Opening tab for $host → $url")

            val page = browser.pageForDomain(host)
            page.navigate(url, Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(30000.0))
            runCatching { page.waitForLoadState(LoadState.LOAD, Page.WaitForLoadStateOptions().setTimeout(15000.0)) }

            val finalUrl = page.url()
            val title = runCatching { page.title() }.getOrDefault("")
            println("[test-steel] final URL: $finalUrl")
            println("[test-steel] title:     $title")

            // Heuristic only — good enough to confirm the session, not a strict auth check.
            val signal = "$finalUrl $title".lowercase()
            val loggedOut = listOf("/login", "/authwall", "/checkpoint", "sign in", "log in", "join now")
                .any { signal.contains(it) }
            if (loggedOut) {
                println("[test-steel] ⚠️  Looks SIGNED OUT — open the debug URL above on your phone, sign in, then re-run.")
            } else {
                println("[test-steel] ✅ Looks authenticated — scraping should reuse this session.")
            }
        } catch (e: Exception) {
            println("[test-steel] ❌ Error: ${e.message}")
        } finally {
            // Exports the refreshed storageState and releases the Steel session.
            browser.close()
        }
    }
}
