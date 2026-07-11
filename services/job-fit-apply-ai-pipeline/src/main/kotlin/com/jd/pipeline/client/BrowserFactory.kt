package com.jd.pipeline.client

import com.jd.pipeline.config.Config

/**
 * Selects the browser backend from config, so the scraper is agnostic to which one runs:
 *  - `STEEL_BASE_URL` set → [SteelBrowser] (self-hosted Steel: sessions, persisted auth, phone re-auth);
 *  - else `CHROME_CDP_ENDPOINT` set → [ChromeCdpBrowser] (raw host Chrome, legacy path);
 *  - else → [ChromeCdpBrowser] with a blank endpoint, which reports unavailable (browser scraping off).
 *
 * Steel takes precedence so flipping `STEEL_BASE_URL` on/off is the single cutover/fallback switch.
 */
object BrowserFactory {
    fun create(): CdpBrowser =
        if (Config.STEEL_BASE_URL.isNotBlank()) SteelBrowser()
        else ChromeCdpBrowser()
}
