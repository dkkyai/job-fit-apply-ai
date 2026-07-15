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
    private val reconnectCooldownMs: Long = Config.STEEL_RECONNECT_COOLDOWN_MS,
    private val connectMaxAttempts: Int = Config.STEEL_CONNECT_MAX_ATTEMPTS,
    private val connectBackoffBaseMs: Long = Config.STEEL_CONNECT_BACKOFF_BASE_MS,
    private val circuitBreakerThreshold: Int = Config.STEEL_CIRCUIT_BREAKER_THRESHOLD,
    private val circuitOpenCooldownMs: Long = Config.STEEL_CIRCUIT_OPEN_COOLDOWN_MS,
    private val client: SteelClient = SteelClient(baseUrl),
    private val store: SteelStorageStore = SteelStorageStore(Paths.get(Config.STEEL_STORAGE_STATE_PATH)),
    // Monotonic clock seam (nanos); overridable so the cooldown is unit-testable without sleeping.
    private val nanoTime: () -> Long = System::nanoTime,
    // Backoff-sleep seam; overridable so retry/backoff is unit-testable without actually sleeping.
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : CdpBrowser {

    private val log = LoggerFactory.getLogger(SteelBrowser::class.java)

    private var playwright: Playwright? = null
    private var browser: Browser? = null
    private var session: SteelClient.SteelSession? = null
    // Monotonic deadline (nanoTime) before which a fresh connect attempt is skipped, armed after a
    // failed connect so a down/wedged backend isn't re-probed on every scrape. Null = attempt now.
    private var reconnectDeadlineNanos: Long? = null
    // Circuit-breaker counter: consecutive failed connect cycles (each already exhausted its in-scrape
    // retries). Reset to 0 on a good connect; at [circuitBreakerThreshold] the breaker opens and the
    // cooldown widens to [circuitOpenCooldownMs] so a wedged backend isn't re-probed every job.
    private var consecutiveConnectFailures = 0
    private val pagesByHost = mutableMapOf<String, Page>()

    /** URL fragments that mean a tab is stuck on an auth/challenge page and should be recreated. */
    private val stuckMarkers = listOf("/login", "/checkpoint", "/challenge", "captcha", "security-verification")

    @Synchronized
    override fun isAvailable(): Boolean {
        if (baseUrl.isBlank()) return false
        ensureLive()
        return browser?.isConnected == true
    }

    /**
     * Ensure a live connection, transparently reconnecting if a previously-established session
     * dropped mid-batch (Steel idle-releases sessions after [sessionTimeoutMs], so a long/idle batch
     * can lose one between jobs). A failed connect backs off for [reconnectCooldownMs] rather than
     * latching off for the whole batch, so a down/wedged backend that the watchdog restarts is picked
     * up on the next scrape past the cooldown — no pipeline restart — without hammering a dead one.
     */
    private fun ensureLive() {
        if (browser?.isConnected == true) return
        // Only tear down + reset when we still hold a stale live handle — a *genuine* drop of a
        // previously-connected session. resetConnection() clears the cooldown so a genuine drop
        // reconnects immediately; but after a *failed* reconnect the handle is already null, so we
        // fall straight through to ensureConnected() and honour the cooldown instead of resetting
        // (and re-probing a 90s createSession) on every scrape while the backend stays down.
        if (browser != null || playwright != null) {
            log.info("Steel session dropped — reconnecting")
            resetConnection()
        }
        ensureConnected()
    }

    /** Drop local connection state (session presumed dead) so [ensureConnected] rebuilds it now. */
    private fun resetConnection() {
        runCatching { playwright?.close() }
        pagesByHost.clear()
        browser = null
        playwright = null
        session = null
        reconnectDeadlineNanos = null  // a dropped live session should reconnect immediately
    }

    private fun ensureConnected() {
        if (browser?.isConnected == true) return
        // Back off after a failed connect: skip until the cooldown deadline elapses. Subtraction
        // keeps the comparison correct across nanoTime() sign/overflow. Null deadline = attempt now.
        reconnectDeadlineNanos?.let { if (nanoTime() - it < 0) return }

        // Retry the connect a few times with exponential backoff before giving up: a transient
        // createSession 500 (a SingletonLock race while Chrome relaunches) usually clears within a
        // second, so a couple of quick retries recover the current job instead of dropping it to thin
        // email-only JD text. Each attempt's HTTP call is bounded by the createSession timeout.
        var lastError: Exception? = null
        for (attempt in 1..connectMaxAttempts) {
            try {
                val s = client.createSession(store.load(), sessionTimeoutMs)
                val pw = Playwright.create()
                browser = pw.chromium().connectOverCDP(hostToIp(resolveWsEndpoint(baseUrl, s.websocketUrl)))
                playwright = pw
                session = s
                reconnectDeadlineNanos = null
                consecutiveConnectFailures = 0  // healthy again — close the circuit breaker
                log.info("Connected to Steel session {} at {}", s.id, baseUrl)
                return
            } catch (e: Exception) {
                lastError = e
                runCatching { playwright?.close() }
                playwright = null
                browser = null
                session = null
                if (attempt < connectMaxAttempts) {
                    val backoffMs = connectBackoffBaseMs shl (attempt - 1)  // 500, 1000, 2000…
                    log.warn("Steel connect attempt {}/{} failed: {} — retrying in {}ms",
                        attempt, connectMaxAttempts, e.message, backoffMs)
                    runCatching { sleep(backoffMs) }
                }
            }
        }
        onConnectExhausted(lastError)
    }

    /**
     * All in-scrape connect attempts failed. Advance the circuit breaker and arm the cooldown: for the
     * first [circuitBreakerThreshold]-1 consecutive failed cycles use the normal [reconnectCooldownMs]
     * (so a briefly-restarting backend is re-probed promptly); once the breaker opens, widen the
     * cooldown to [circuitOpenCooldownMs] so a genuinely wedged backend isn't hammered every job while
     * the host steel-watchdog restarts the container. The breaker closes on the next successful connect.
     */
    private fun onConnectExhausted(lastError: Exception?) {
        consecutiveConnectFailures++
        val breakerOpen = consecutiveConnectFailures >= circuitBreakerThreshold
        val cooldownMs = if (breakerOpen) circuitOpenCooldownMs else reconnectCooldownMs
        reconnectDeadlineNanos = nanoTime() + cooldownMs * 1_000_000
        if (breakerOpen) {
            log.error("Steel connect failed {} cycles in a row — circuit breaker OPEN; backing off {}ms " +
                "(host steel-watchdog restarts the wedged container). Last error: {}",
                consecutiveConnectFailures, cooldownMs, lastError?.message)
        } else {
            log.warn("Could not connect to Steel at {} after {} attempts: {} — retrying after {}ms",
                baseUrl, connectMaxAttempts, lastError?.message, cooldownMs)
        }
    }

    /**
     * Return a healthy, foregrounded tab dedicated to [host] from the Steel session's context
     * (which holds the injected/logged-in cookies), reused across jobs. Recreated if closed or
     * parked on a login/checkpoint/captcha page. Call only when [isAvailable] is true.
     */
    @Synchronized
    override fun pageForDomain(host: String): Page {
        ensureLive()
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
            // Merge (not overwrite) so cookies for boards this batch didn't visit — incl. the shared
            // Google-SSO session — are preserved. Persist before release to capture natural refresh.
            runCatching { client.exportContext(s.id)?.let { store.merge(it) } }
            runCatching { client.releaseSession(s.id) }
        }
        pagesByHost.values.forEach { runCatching { it.close() } }
        pagesByHost.clear()
        runCatching { browser?.close() }
        runCatching { playwright?.close() }
        browser = null
        playwright = null
        session = null
        reconnectDeadlineNanos = null
        consecutiveConnectFailures = 0
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
