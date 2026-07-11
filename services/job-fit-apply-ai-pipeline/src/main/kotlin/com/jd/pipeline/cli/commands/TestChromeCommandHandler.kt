package com.jd.pipeline.cli.commands

import com.jd.pipeline.cli.Command
import com.jd.pipeline.client.ChromeCdpBrowser
import com.jd.pipeline.config.Config
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.WaitUntilState
import java.net.URI

/**
 * Smoke test for the persistent Chrome (CDP) scraping setup. Connects to the configured debug
 * Chrome, opens a tab to a probe URL (default: LinkedIn jobs), and reports whether the session
 * looks authenticated — without running the pipeline. Run after `scripts/launch-chrome-cdp.sh`.
 *
 *   ./gradlew run --args="--test-chrome"
 *   ./gradlew run --args="--test-chrome https://www.linkedin.com/jobs/view/123456789"
 */
object TestChromeCommandHandler {
    private const val DEFAULT_PROBE_URL = "https://www.linkedin.com/jobs/"

    fun run(cmd: Command.TestChrome) {
        val endpoint = Config.CHROME_CDP_ENDPOINT
        println("[test-chrome] CHROME_CDP_ENDPOINT=${endpoint.ifBlank { "(empty)" }}  CHROME_DEBUG_PORT=${Config.CHROME_DEBUG_PORT}")

        if (endpoint.isBlank()) {
            println("[test-chrome] CDP is disabled — set CHROME_CDP_ENDPOINT (e.g. http://localhost:${Config.CHROME_DEBUG_PORT}) and run scripts/launch-chrome-cdp.sh.")
            println("[test-chrome] With it empty, browser scraping is unavailable (plain-HTTP scraping still works).")
            return
        }

        val browser = ChromeCdpBrowser()
        try {
            if (!browser.isAvailable()) {
                println("[test-chrome] ❌ Could not connect to debug Chrome at $endpoint.")
                println("[test-chrome]    Start it with scripts/launch-chrome-cdp.sh, then re-run.")
                return
            }
            println("[test-chrome] ✅ Connected to Chrome over CDP at $endpoint")

            val url = cmd.url?.takeIf { it.isNotBlank() } ?: DEFAULT_PROBE_URL
            val host = runCatching { URI.create(url).host?.lowercase() }.getOrNull().orEmpty()
            println("[test-chrome] Opening tab for $host → $url")

            val page = browser.pageForDomain(host)
            page.navigate(url, Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(30000.0))
            runCatching { page.waitForLoadState(LoadState.LOAD, Page.WaitForLoadStateOptions().setTimeout(15000.0)) }

            val finalUrl = page.url()
            val title = runCatching { page.title() }.getOrDefault("")
            println("[test-chrome] final URL: $finalUrl")
            println("[test-chrome] title:     $title")

            // Heuristic only — good enough to confirm the session, not a strict auth check.
            val signal = "$finalUrl $title".lowercase()
            val loggedOut = listOf("/login", "/authwall", "/checkpoint", "sign in", "log in", "join now")
                .any { signal.contains(it) }
            if (loggedOut) {
                println("[test-chrome] ⚠️  Looks SIGNED OUT — sign in to this Chrome window, then re-run.")
            } else {
                println("[test-chrome] ✅ Looks authenticated — scraping should reuse this session.")
            }
        } catch (e: Exception) {
            println("[test-chrome] ❌ Error: ${e.message}")
        } finally {
            browser.close() // disconnects + closes the probe tab; does NOT close the user's Chrome
        }
    }
}
