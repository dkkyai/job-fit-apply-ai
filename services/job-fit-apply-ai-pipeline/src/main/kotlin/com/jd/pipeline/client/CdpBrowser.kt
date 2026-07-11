package com.jd.pipeline.client

import com.microsoft.playwright.Page

/**
 * A browser the scraper drives over the Chrome DevTools Protocol. Two implementations:
 *  - [ChromeCdpBrowser] attaches to a raw, user-launched host Chrome (`connectOverCDP`);
 *  - [SteelBrowser] drives a self-hosted Steel Browser session (REST-created, CDP-connected),
 *    adding persisted auth (sessionContext) and an interactive [debugUrl] for phone re-auth.
 *
 * [BrowserFactory] picks the implementation from config. Callers program to this surface only,
 * so the two paths stay swappable behind a single flag.
 */
interface CdpBrowser {
    /** True when a live browser connection is established (lazily connects on first call). */
    fun isAvailable(): Boolean

    /** A healthy, foregrounded tab dedicated to [host], reused across jobs. Call only when available. */
    fun pageForDomain(host: String): Page

    /** Dispose tabs and disconnect. Does NOT close the user's host Chrome; releases the Steel session. */
    fun close()

    /**
     * Interactive live-view/debug URL for the current session (for phone re-auth), or null when the
     * implementation has none (raw host Chrome). Steel returns its session viewer URL.
     */
    fun debugUrl(): String? = null
}
