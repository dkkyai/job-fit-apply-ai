package com.jd.pipeline.cli.commands

import com.jd.pipeline.cli.Command
import com.jd.pipeline.client.SteelBrowser
import com.jd.pipeline.client.SteelClient
import com.jd.pipeline.client.SteelStorageStore
import com.jd.pipeline.config.Config
import java.nio.file.Paths

/**
 * Long-lived Steel sign-in / capture. Opens a Steel session, prints its interactive debug URL
 * (tailnet), and BLOCKS until you press ENTER — so you have time to sign in on your phone. On
 * ENTER it exports the session's cookies and **merges** them into the persisted storageState store,
 * then releases the session. Fills the gap where `--test-steel` closes its session too fast to sign
 * into. Because the boards share Google SSO, one sign-in refreshes them all.
 *
 * Run interactively so stdin is a TTY:
 *   docker exec -it jobfit-processor /app/bin/job-fit-apply-ai-pipeline --steel-signin
 */
object SteelSigninCommandHandler {
    private const val SESSION_TIMEOUT_MS = 30 * 60_000L // 30 min to sign in

    fun run(cmd: Command.SteelSignin) {
        val base = Config.STEEL_BASE_URL
        if (base.isBlank()) {
            println("[steel-signin] Steel is disabled — set STEEL_BASE_URL (e.g. http://steel:3000) first.")
            return
        }
        val client = SteelClient(base)
        val store = SteelStorageStore(Paths.get(Config.STEEL_STORAGE_STATE_PATH))

        val session = try {
            client.createSession(store.load(), SESSION_TIMEOUT_MS)
        } catch (e: Exception) {
            println("[steel-signin] ❌ Could not create a Steel session at $base: ${e.message}")
            return
        }

        val uiBase = Config.STEEL_UI_URL.ifBlank { base }
        val debugUrl = session.debugUrl?.let { SteelBrowser.interactiveDebugUrl(uiBase, it) }
        println("[steel-signin] Session ${session.id} created (stays open ${SESSION_TIMEOUT_MS / 60_000} min).")
        println("[steel-signin] 📱 Open this on your phone (over Tailscale) and sign in:")
        println("[steel-signin]    ${debugUrl ?: "$uiBase — open the live session viewer"}")
        cmd.url?.takeIf { it.isNotBlank() }?.let { println("[steel-signin]    (suggested start: $it)") }
        println("[steel-signin] Sign into every board you use — they share your Google SSO, so one login covers them all.")
        print("[steel-signin] Press ENTER here once you're signed in to capture… ")
        System.out.flush()

        val line = readlnOrNull()
        if (line == null) {
            println("\n[steel-signin] ⚠️ No interactive stdin (run with `docker exec -it …`). Capturing now anyway.")
        }

        val ctx = runCatching { client.exportContext(session.id) }.getOrNull()
        if (ctx == null) {
            println("[steel-signin] ⚠️ Could not export the session context — nothing captured.")
        } else {
            store.merge(ctx)
            val n = ctx.get("cookies")?.size() ?: 0
            println("[steel-signin] ✅ Captured & merged $n cookies into ${Config.STEEL_STORAGE_STATE_PATH}")
        }
        runCatching { client.releaseSession(session.id) }
        println("[steel-signin] Session released. Scrapers will inject this signed-in context on their next run.")
    }
}
