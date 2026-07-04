package com.jd.pipeline.client

import com.jd.pipeline.config.Config
import com.microsoft.playwright.Browser
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import org.slf4j.LoggerFactory

/**
 * Connects to a single long-lived, user-launched Chrome over the DevTools Protocol
 * (`connectOverCDP`) and hands out one reusable tab per domain.
 *
 * Why: the legacy scrape path copied the whole Chrome profile into a temp dir and launched a
 * throwaway browser per job, so session-cookie refreshes were discarded (steady sign-outs) and
 * every cold launch looked like a bot. Connecting to a persistent Chrome keeps the logged-in
 * session warm and lets it refresh naturally, and reusing a per-domain tab (warm referrer /
 * history / JS context) is far less CAPTCHA-prone than spawning a fresh tab per job.
 *
 * Lifecycle: lazily connects on first use; the connection and tabs are reused across every job
 * in a batch. [close] disposes the tabs and disconnects Playwright — it does NOT close the
 * user's Chrome (`connectOverCDP` only detaches).
 *
 * Single-threaded by contract (ingestion runs one job at a time) but guarded with `@Synchronized`
 * so a future concurrent caller can't corrupt the per-host tab map.
 */
class ChromeCdpBrowser(private val endpoint: String = Config.CHROME_CDP_ENDPOINT) {

    private val log = LoggerFactory.getLogger(ChromeCdpBrowser::class.java)

    private var playwright: Playwright? = null
    private var browser: Browser? = null
    private var connectAttempted = false
    private val pagesByHost = mutableMapOf<String, Page>()

    /** URL fragments that mean a tab is stuck on an auth/challenge page and should be recreated. */
    private val stuckMarkers = listOf("/login", "/checkpoint", "/challenge", "captcha", "security-verification")

    /** True when an endpoint is configured and a live CDP connection is established. */
    @Synchronized
    fun isAvailable(): Boolean {
        if (endpoint.isBlank()) return false
        ensureConnected()
        return browser?.isConnected == true
    }

    private fun ensureConnected() {
        if (connectAttempted) return
        connectAttempted = true
        try {
            val pw = Playwright.create()
            browser = pw.chromium().connectOverCDP(endpoint)
            playwright = pw
            log.info("Connected to Chrome over CDP at {}", endpoint)
        } catch (e: Exception) {
            log.warn("Could not connect to Chrome over CDP at {}: {}", endpoint, e.message)
            runCatching { playwright?.close() }
            playwright = null
            browser = null
        }
    }

    /**
     * Return a healthy, foregrounded tab dedicated to [host], creating one in the existing profile
     * context if needed. The tab is reused across jobs for the same domain and is NOT closed by
     * callers. A cached tab is discarded and recreated if it has closed/crashed or is parked on a
     * login/checkpoint/captcha page, so a bad state on one job can't poison the next.
     * Call only when [isAvailable] is true.
     */
    @Synchronized
    fun pageForDomain(host: String): Page {
        val live = browser?.takeIf { it.isConnected }
            ?: error("CDP browser not available — call isAvailable() first")

        pagesByHost[host]?.let { cached ->
            if (isHealthy(cached)) {
                runCatching { cached.bringToFront() }
                return cached
            }
            log.info("Recreating unhealthy tab for {}", host)
            runCatching { cached.close() }
            pagesByHost.remove(host)
        }

        // Reuse the existing profile context (holds the logged-in cookies); never create a fresh one.
        val context = live.contexts().firstOrNull() ?: live.newContext()
        val page = context.newPage().apply {
            setDefaultTimeout(Config.PLAYWRIGHT_TIMEOUT_MS)
            setDefaultNavigationTimeout(Config.PLAYWRIGHT_TIMEOUT_MS)
        }
        pagesByHost[host] = page
        runCatching { page.bringToFront() }
        return page
    }

    private fun isHealthy(page: Page): Boolean {
        if (page.isClosed) return false
        val url = runCatching { page.url().lowercase() }.getOrDefault("")
        return stuckMarkers.none { url.contains(it) }
    }

    /** Dispose tracked tabs and disconnect Playwright. Does NOT close the user's Chrome. */
    @Synchronized
    fun close() {
        pagesByHost.values.forEach { runCatching { it.close() } }
        pagesByHost.clear()
        runCatching { browser?.close() }   // detaches the CDP connection; the user's Chrome stays up
        runCatching { playwright?.close() }
        browser = null
        playwright = null
        connectAttempted = false
    }
}
