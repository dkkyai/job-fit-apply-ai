package com.jd.jobbot.apply

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.FilePayload
import com.microsoft.playwright.options.LoadState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** One interactive element on the page, as the fill loop sees it. Passwords are never included. */
@Serializable
data class Element(
    val id: String,
    val kind: String,
    val type: String? = null,
    val label: String = "",
    val name: String? = null,
    val required: Boolean = false,
    val value: String? = null,
    val options: List<String> = emptyList(),
    val checked: Boolean? = null,
    val text: String = "",
    val href: String? = null,
    val inForm: Boolean = false,
    val formFilled: Boolean = false,
)

@Serializable
data class Snapshot(
    val url: String,
    val title: String = "",
    val elements: List<Element> = emptyList(),
    val captcha: Boolean = false,
    val text: String = "",
)

/** A browser tab the fill loop drives. Implemented over Playwright; faked in tests. */
interface ApplyPage {
    val url: String
    fun snapshot(): Snapshot
    fun goto(url: String)
    fun fill(id: String, value: String)
    fun select(id: String, option: String)
    fun check(id: String, checked: Boolean)
    fun upload(id: String, name: String, mime: String, bytes: ByteArray)
    fun click(id: String)
    fun settle()
    fun screenshot(): ByteArray
    fun close()
}

/** Opens tabs in the apply browser. */
fun interface ApplyBrowser {
    fun open(guard: (String) -> Boolean): ApplyPage
}

/**
 * The real apply browser: the long-lived headed Chromium in the `apply-browser` container, over
 * CDP. Tabs open in its default (persistent) context, so site logins and the Google session
 * carry over. Every main-frame navigation passes [guard] first — a refused one is aborted at the
 * network layer, so neither the model nor a page script can reach a forbidden site.
 */
class PlaywrightApplyBrowser(private val cdpUrl: String) : ApplyBrowser {
    private val log = LoggerFactory.getLogger(PlaywrightApplyBrowser::class.java)
    private var playwright: Playwright? = null
    private var browser: Browser? = null

    @Synchronized
    private fun context(): BrowserContext {
        val b = browser?.takeIf { it.isConnected } ?: connect()
        return b.contexts().firstOrNull() ?: b.newContext()
    }

    private fun connect(): Browser {
        // Attach-only: never download Playwright's own browsers into this container.
        val pw = playwright ?: Playwright.create(
            Playwright.CreateOptions().setEnv(mapOf("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" to "1")),
        ).also { playwright = it }
        val b = pw.chromium().connectOverCDP(wsEndpoint())
        browser = b
        log.info("connected to apply browser {}", b.version())
        return b
    }

    /**
     * DevTools rejects any Host that is not an IP or localhost (DNS-rebinding guard), so the
     * service name is resolved to its address first — the processor's SteelBrowser does the same.
     */
    private fun wsEndpoint(): String {
        val uri = URI(cdpUrl)
        val ip = InetAddress.getByName(uri.host).hostAddress
        val base = "${uri.scheme}://$ip:${uri.port}"
        val body = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("$base/json/version")).timeout(Duration.ofSeconds(10)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body()
        val ws = Regex(""""webSocketDebuggerUrl"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
            ?: error("apply browser /json/version had no webSocketDebuggerUrl")
        return ws.replace(Regex("""^ws://[^/]+"""), "ws://$ip:${uri.port}")
    }

    override fun open(guard: (String) -> Boolean): ApplyPage {
        val page = context().newPage()
        page.route("**/*") { route ->
            val req = route.request()
            if (req.isNavigationRequest && req.frame() == page.mainFrame() && !guard(req.url())) {
                log.warn("blocked navigation to {}", req.url())
                route.abort("blockedbyclient")
            } else {
                route.resume()
            }
        }
        return PlaywrightPage(page)
    }

    private class PlaywrightPage(private val page: Page) : ApplyPage {
        override val url: String get() = page.url()

        override fun snapshot(): Snapshot {
            val json = page.evaluate(SNAPSHOT_JS) as String
            return JSON.decodeFromString(Snapshot.serializer(), json)
        }

        override fun goto(url: String) { page.navigate(url); settle() }
        override fun fill(id: String, value: String) { el(id).fill(value) }
        override fun select(id: String, option: String) { el(id).selectOption(option) }
        override fun check(id: String, checked: Boolean) { el(id).setChecked(checked) }
        override fun upload(id: String, name: String, mime: String, bytes: ByteArray) {
            el(id).setInputFiles(FilePayload(name, mime, bytes))
        }
        override fun click(id: String) { el(id).click(); settle() }

        override fun settle() {
            runCatching { page.waitForLoadState(LoadState.DOMCONTENTLOADED, Page.WaitForLoadStateOptions().setTimeout(15_000.0)) }
            runCatching { page.waitForLoadState(LoadState.NETWORKIDLE, Page.WaitForLoadStateOptions().setTimeout(5_000.0)) }
        }

        override fun screenshot(): ByteArray = page.screenshot(Page.ScreenshotOptions().setFullPage(true))
        override fun close() { runCatching { page.close() } }

        private fun el(id: String) = page.locator("[data-jobbot-id=\"${id.replace("\"", "")}\"]").first()
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /**
         * Enumerates the page's interactive elements, tags each with a stable data-jobbot-id, and
         * reports labels, values (never a password's), options, and whether a control sits in a
         * form that already has user input. Also flags CAPTCHA widgets.
         */
        val SNAPSHOT_JS = """
            () => {
              window.__jobbotSeq = window.__jobbotSeq || 0;
              const vis = el => !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length) &&
                getComputedStyle(el).visibility !== 'hidden';
              const clean = s => (s || '').replace(/\s+/g, ' ').trim().slice(0, 200);
              const labelOf = el => {
                if (el.getAttribute('aria-label')) return el.getAttribute('aria-label');
                const by = el.getAttribute('aria-labelledby');
                if (by) { const t = by.split(' ').map(i => document.getElementById(i)).filter(Boolean).map(n => n.innerText).join(' '); if (t) return t; }
                if (el.labels && el.labels.length) return Array.from(el.labels).map(l => l.innerText).join(' ');
                const wrap = el.closest('label'); if (wrap) return wrap.innerText;
                if (el.placeholder) return el.placeholder;
                const prev = el.closest('div,fieldset,li'); return prev ? prev.innerText.slice(0, 120) : '';
              };
              const filled = form => !!form && Array.from(form.querySelectorAll('input,textarea,select')).some(i =>
                !['hidden','submit','button','checkbox','radio','file'].includes((i.type||'').toLowerCase()) && i.value && i.value.trim());
              const out = [];
              const nodes = document.querySelectorAll('input,select,textarea,button,a[href],[role=button],[role=checkbox],[role=radio],[role=combobox]');
              for (const el of nodes) {
                const type = (el.getAttribute('type') || '').toLowerCase();
                if (type === 'hidden' || !vis(el) && type !== 'file') continue;
                if (!el.dataset.jobbotId) el.dataset.jobbotId = 'e' + (++window.__jobbotSeq);
                const tag = el.tagName.toLowerCase();
                const form = el.form || el.closest('form');
                const kind = tag === 'a' ? 'link' : tag === 'button' || el.getAttribute('role') === 'button' || ['submit','button','reset'].includes(type) ? 'button'
                  : tag === 'select' ? 'select' : tag === 'textarea' ? 'textarea' : type === 'file' ? 'file'
                  : ['checkbox','radio'].includes(type) || ['checkbox','radio'].includes(el.getAttribute('role')) ? type || el.getAttribute('role') : 'input';
                out.push({
                  id: el.dataset.jobbotId, kind, type: type || null, label: clean(labelOf(el)), name: el.name || null,
                  required: !!el.required || el.getAttribute('aria-required') === 'true',
                  value: type === 'password' ? (el.value ? '[set]' : '') : (kind === 'input' || kind === 'textarea' || kind === 'select') ? clean(el.value) : null,
                  options: tag === 'select' ? Array.from(el.options).map(o => clean(o.text)).slice(0, 60) : [],
                  checked: ['checkbox','radio'].includes(type) ? !!el.checked : null,
                  // An input's value is user data (or a secret), never its label: only button-like inputs use it as text.
                  text: clean(tag === 'input' ? (['submit','button','reset'].includes(type) ? el.value : '') : (el.innerText || '')),
                  href: tag === 'a' ? el.href : null,
                  inForm: !!form, formFilled: filled(form),
                });
                if (out.length >= 200) break;
              }
              const captcha = !!document.querySelector(
                'iframe[src*="recaptcha"],iframe[src*="hcaptcha"],iframe[src*="challenges.cloudflare"],iframe[src*="arkoselabs"],.g-recaptcha,.h-captcha,[data-sitekey],#px-captcha');
              return JSON.stringify({ url: location.href, title: document.title, elements: out, captcha,
                text: (document.body ? document.body.innerText : '').slice(0, 4000) });
            }
        """.trimIndent()
    }
}
