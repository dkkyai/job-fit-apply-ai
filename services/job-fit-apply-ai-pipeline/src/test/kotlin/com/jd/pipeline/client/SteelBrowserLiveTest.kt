package com.jd.pipeline.client

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.WaitUntilState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration test against a REAL self-hosted Steel Browser. Skipped unless `STEEL_BASE_URL` is
 * set (so it never runs in plain CI), e.g.:
 *
 *   STEEL_BASE_URL=http://localhost:3000 ./gradlew test --tests '*SteelBrowserLiveTest'
 *
 * Exercises the full path the scraper uses: session create → CDP connect → navigate → debug URL →
 * close (which exports the storageState). Requires the Playwright driver bundle (already a dep).
 */
@EnabledIfEnvironmentVariable(named = "STEEL_BASE_URL", matches = ".+")
@DisplayName("SteelBrowser (live)")
class SteelBrowserLiveTest {

    @Test
    @DisplayName("creates a session, drives a navigation over CDP, and persists storageState on close")
    fun drivesAndPersists(@TempDir dir: Path) {
        val base = System.getenv("STEEL_BASE_URL")
        val storePath = dir.resolve("steel-storage-state.json")
        val browser = SteelBrowser(
            baseUrl = base,
            uiBaseUrl = base,
            client = SteelClient(base),
            store = SteelStorageStore(storePath),
        )

        try {
            assertTrue(browser.isAvailable(), "Steel should be reachable at $base")

            val page = browser.pageForDomain("example.com")
            page.navigate(
                "https://example.com",
                Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(30_000.0),
            )
            assertTrue(page.title().contains("Example", ignoreCase = true), "expected example.com content")

            val debug = browser.debugUrl()
            assertNotNull(debug)
            assertTrue(debug.contains("interactive=true"), "debug URL should be interactive: $debug")
        } finally {
            browser.close()
        }

        assertTrue(Files.exists(storePath), "close() should export storageState to $storePath")
    }
}
