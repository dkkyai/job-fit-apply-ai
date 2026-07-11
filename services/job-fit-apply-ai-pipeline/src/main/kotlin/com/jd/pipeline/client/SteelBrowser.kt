package com.jd.pipeline.client

import com.jd.pipeline.config.Config
import com.microsoft.playwright.Browser
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Drives a self-hosted Steel Browser as the CDP scraping backend, replacing a raw host Chrome.
 *
 * On first use it creates a Steel session (injecting the persisted [SteelStorageStore] so it
 * resumes logged in), connects Playwright over the session's CDP websocket, and hands out one
 * warm reusable tab per domain — the same warm-session strategy as [ChromeCdpBrowser]. On [close]
 * it exports the session's refreshed cookies back to the store and releases the Steel session.
 *
 * [debugUrl] returns the session's interactive live-view URL (over the Tailscale UI base) so a
 * re-auth alert can link straight to a phone sign-in.
 *
 * Single-threaded by contract but guarded with `@Synchronized`, mirroring [ChromeCdpBrowser].
 */
class SteelBrowser(
    private val baseUrl: String = Config.STEEL_BASE_URL,
    private val uiBaseUrl: String = Config.STEEL_UI_URL,
    private val sessionTimeoutMs: Long = Config.STEEL_SESSION_TIMEOUT_MS,
    private val client: SteelClient = SteelClient(baseUrl),
    private val store: SteelStorageStore = SteelStorageStore(Paths.get(Config.STEEL_STORAGE_STATE_PATH)),
) : CdpBrowser {

    private val log = LoggerFactory.getLogger(SteelBrowser::class.java)

    private var playwright: Playwright? = null
    private var browser: Browser? = null
    private var session: SteelClient.SteelSession? = null
    private var connectAttempted = false
    private val pagesByHost = mutableMapOf<String, Page>()

    /** URL fragments that mean a tab is stuck on an auth/challenge page and should be recreated. */
    private val stuckMarkers = listOf("/login", "/checkpoint", "/challenge", "captcha", "security-verification")

    @Synchronized
    override fun isAvailable(): Boolean {
        if (baseUrl.isBlank()) return false
        ensureConnected()
        return browser?.isConnected == true
    }

    private fun ensureConnected() {
        if (connectAttempted) return
        connectAttempted = true
        try {
            val s = client.createSession(store.load(), sessionTimeoutMs)
            val pw = Playwright.create()
            browser = pw.chromium().connectOverCDP(hostToIp(resolveWsEndpoint(baseUrl, s.websocketUrl)))
            playwright = pw
            session = s
            log.info("Connected to Steel session {} at {}", s.id, baseUrl)
        } catch (e: Exception) {
            log.warn("Could not connect to Steel at {}: {}", baseUrl, e.message)
            runCatching { playwright?.close() }
            playwright = null
            browser = null
            session = null
        }
    }

    /**
     * Return a healthy, foregrounded tab dedicated to [host] from the Steel session's context
     * (which holds the injected/logged-in cookies), reused across jobs. Recreated if closed or
     * parked on a login/checkpoint/captcha page. Call only when [isAvailable] is true.
     */
    @Synchronized
    override fun pageForDomain(host: String): Page {
        val live = browser?.takeIf { it.isConnected }
            ?: error("Steel browser not available — call isAvailable() first")

        pagesByHost[host]?.let { cached ->
            if (isHealthy(cached)) {
                runCatching { cached.bringToFront() }
                return cached
            }
            log.info("Recreating unhealthy tab for {}", host)
            runCatching { cached.close() }
            pagesByHost.remove(host)
        }

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

    /** Interactive live-view URL for phone re-auth (over the Tailscale UI base), or null if no session. */
    @Synchronized
    override fun debugUrl(): String? {
        val s = session ?: return null
        val debugPath = s.debugUrl ?: return null
        return interactiveDebugUrl(uiBaseUrl.ifBlank { baseUrl }, debugPath)
    }

    /**
     * Export the session's refreshed cookies to the store, release the Steel session, then dispose
     * the local Playwright connection. Ordering matters: persist before release so a natural cookie
     * refresh during the batch is captured.
     */
    @Synchronized
    override fun close() {
        session?.let { s ->
            runCatching { client.exportContext(s.id)?.let { store.save(it) } }
            runCatching { client.releaseSession(s.id) }
        }
        pagesByHost.values.forEach { runCatching { it.close() } }
        pagesByHost.clear()
        runCatching { browser?.close() }
        runCatching { playwright?.close() }
        browser = null
        playwright = null
        session = null
        connectAttempted = false
    }

    companion object {
        /**
         * Rewrite the host:port of Steel's returned websocketUrl (always `ws://localhost:3000/`,
         * relative to the Steel process) to the reachable [baseUrl] host:port, keeping the ws path
         * and query. Needed because the processor reaches Steel over the compose network, not localhost.
         */
        fun resolveWsEndpoint(baseUrl: String, websocketUrl: String): String {
            val base = URI(baseUrl)
            val ws = URI(websocketUrl)
            val scheme = if (base.scheme.equals("https", ignoreCase = true)) "wss" else "ws"
            val port = if (base.port != -1) ":${base.port}" else ""
            val path = ws.rawPath?.ifBlank { "/" } ?: "/"
            val query = ws.rawQuery?.let { "?$it" } ?: ""
            return "$scheme://${base.host}$port$path$query"
        }

        /**
         * Resolve a ws endpoint's hostname to its IPv4 so the underlying Chrome accepts the CDP
         * connection. Chrome DevTools rejects any `Host` header that isn't an IP or `localhost`
         * (DNS-rebinding guard) — connecting via the `steel` service name resets the socket
         * (`ECONNRESET`). Same rewrite `docker-entrypoint.sh` applies for the host-Chrome path.
         * `localhost` and literal IPs are returned unchanged; on resolution failure the input is
         * returned as-is (the connect then surfaces its own error).
         */
        fun hostToIp(endpoint: String): String {
            val uri = URI(endpoint)
            val host = uri.host ?: return endpoint
            if (host == "localhost" || host.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))) return endpoint
            val ip = runCatching { java.net.InetAddress.getByName(host).hostAddress }.getOrNull() ?: return endpoint
            val port = if (uri.port != -1) ":${uri.port}" else ""
            val path = uri.rawPath?.ifBlank { "/" } ?: "/"
            val query = uri.rawQuery?.let { "?$it" } ?: ""
            return "${uri.scheme}://$ip$port$path$query"
        }

        /**
         * Build the interactive debug URL a human opens (e.g. from a phone): the [debugPath]'s path
         * rehosted onto [uiBase] (the Tailscale-reachable Steel base), with the interactive params.
         */
        fun interactiveDebugUrl(uiBase: String, debugPath: String): String {
            val ub = URI(uiBase)
            val d = URI(debugPath)
            val port = if (ub.port != -1) ":${ub.port}" else ""
            val path = d.rawPath?.ifBlank { "/" } ?: "/"
            return "${ub.scheme}://${ub.host}$port$path?interactive=true&showControls=true"
        }
    }
}
