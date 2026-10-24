package com.jd.jobbot.apply

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which CAPTCHAs stop a fill. A real one needs Richard; an invisible reCAPTCHA badge (on Ashby, Greenhouse, Lever…)
 * does not, and treating it as one stopped every such form at step one. The widgets below are what the page script
 * reports for real pages.
 */
class CaptchaPolicyTest {
    private fun iframe(src: String, w: Int = 304, h: Int = 78, shown: Boolean = true) = CaptchaWidget(src = src, tag = "iframe", width = w, height = h, shown = shown)
    private fun element(tag: String = "div", w: Int = 304, h: Int = 78, size: String = "", shown: Boolean = true) =
        CaptchaWidget(tag = tag, size = size, width = w, height = h, shown = shown)

    private val enterpriseBadge = "https://www.google.com/recaptcha/enterprise/anchor?ar=1&k=6LfAbCdEfGhIjKlMnOpQrStUvWxYz&co=aHR0cHM6Ly9qb2JzLmFzaGJ5aHEuY29tOjQ0Mw..&hl=en&v=x1y2z3&size=invisible&badge=bottomright&anchor-ms=20000&execute-ms=15000&cb=abc123"

    // ── no person needed ─────────────────────────────────────────────────────────────────────

    @Test
    fun `Ashby's invisible reCAPTCHA Enterprise badge does not stop the fill`() {
        assertFalse(CaptchaPolicy.blocks(iframe(enterpriseBadge, 256, 60)))
    }

    @Test
    fun `an invisible reCAPTCHA v3 badge does not stop the fill`() {
        assertFalse(CaptchaPolicy.blocks(iframe("https://www.google.com/recaptcha/api2/anchor?ar=1&k=6Labc&co=x&hl=en&v=1&size=invisible&badge=inline&cb=9", 256, 60)))
    }

    @Test
    fun `reCAPTCHA's challenge frame waiting off-screen does not stop the fill`() {
        assertFalse(CaptchaPolicy.blocks(iframe("https://www.google.com/recaptcha/api2/bframe?hl=en&v=1&k=6Labc", 400, 580, shown = false)))
    }

    @Test
    fun `an invisible hCaptcha does not stop the fill`() {
        assertFalse(CaptchaPolicy.blocks(iframe("https://newassets.hcaptcha.com/captcha/v1/abc/static/hcaptcha.html#frame=checkbox&id=0&host=jobs.example.com&size=invisible", 303, 78)))
        assertFalse(CaptchaPolicy.blocks(iframe("https://newassets.hcaptcha.com/captcha/v1/abc/static/hcaptcha.html#frame=challenge&id=0", 400, 600, shown = false)))
    }

    @Test
    fun `a container marked invisible does not stop the fill`() {
        assertFalse(CaptchaPolicy.blocks(element(size = "invisible")))
        assertFalse(CaptchaPolicy.blocks(element(size = "INVISIBLE")))
    }

    @Test
    fun `a submit button carrying a site key is reCAPTCHA's trigger, not a widget`() {
        assertFalse(CaptchaPolicy.blocks(element(tag = "button", w = 160, h = 44)))
        assertFalse(CaptchaPolicy.blocks(element(tag = "input", w = 160, h = 44)))
        assertFalse(CaptchaPolicy.blocks(element(tag = "a", w = 160, h = 44)))
    }

    @Test
    fun `tracking pixels and collapsed containers do not stop the fill`() {
        assertFalse(CaptchaPolicy.blocks(iframe("https://www.google.com/recaptcha/api2/anchor?k=x", 1, 1)))
        assertFalse(CaptchaPolicy.blocks(iframe("https://www.google.com/recaptcha/api2/anchor?k=x", 304, 0)))
        assertFalse(CaptchaPolicy.blocks(element(w = 0, h = 0)))
        assertFalse(CaptchaPolicy.blocks(element(w = 304, h = 12)))
    }

    @Test
    fun `a page with no CAPTCHA widgets is clear`() {
        assertFalse(CaptchaPolicy.blocks(emptyList()))
    }

    // ── a person is needed ───────────────────────────────────────────────────────────────────

    @Test
    fun `the reCAPTCHA v2 I'm not a robot checkbox stops the fill`() {
        assertTrue(CaptchaPolicy.blocks(iframe("https://www.google.com/recaptcha/api2/anchor?ar=1&k=6Labc&co=x&hl=en&v=1&size=normal&cb=9")))
        assertTrue(CaptchaPolicy.blocks(iframe("https://www.google.com/recaptcha/api2/anchor?ar=1&k=6Labc&co=x&hl=en&v=1&cb=9")), "no size at all: assume a visible checkbox")
        assertTrue(CaptchaPolicy.blocks(iframe("https://www.google.com/recaptcha/api2/anchor?k=x&size=compact", 164, 144)))
    }

    @Test
    fun `an open reCAPTCHA image challenge stops the fill`() {
        assertTrue(CaptchaPolicy.blocks(iframe("https://www.google.com/recaptcha/api2/bframe?hl=en&v=1&k=6Labc", 400, 580)))
    }

    @Test
    fun `a visible hCaptcha stops the fill`() {
        assertTrue(CaptchaPolicy.blocks(iframe("https://newassets.hcaptcha.com/captcha/v1/abc/static/hcaptcha.html#frame=checkbox&id=0&host=x&size=normal", 303, 78)))
        assertTrue(CaptchaPolicy.blocks(iframe("https://newassets.hcaptcha.com/captcha/v1/abc/static/hcaptcha.html#frame=challenge&id=0", 400, 600)))
    }

    @Test
    fun `a visible Cloudflare Turnstile or Arkose challenge stops the fill`() {
        assertTrue(CaptchaPolicy.blocks(iframe("https://challenges.cloudflare.com/cdn-cgi/challenge-platform/h/g/turnstile/if/ov2/av0/rcv0/0/abc/0x4AAAA/light/normal", 300, 65)))
        assertTrue(CaptchaPolicy.blocks(iframe("https://client-api.arkoselabs.com/fc/gc/?public_key=ABC", 400, 500)))
    }

    @Test
    fun `a visible widget element with no frame stops the fill`() {
        assertTrue(CaptchaPolicy.blocks(element(w = 304, h = 78)))                       // .g-recaptcha / .h-captcha
        assertTrue(CaptchaPolicy.blocks(element(w = 300, h = 100)))                      // #px-captcha, press and hold
    }

    @Test
    fun `one visible CAPTCHA among invisible ones still stops the fill`() {
        val widgets = listOf(
            iframe(enterpriseBadge, 256, 60),
            iframe("https://www.google.com/recaptcha/api2/bframe?k=x", 400, 580, shown = false),
            iframe("https://newassets.hcaptcha.com/captcha/v1/abc/static/hcaptcha.html#frame=checkbox&size=normal", 303, 78),
        )
        assertTrue(CaptchaPolicy.blocks(widgets))
        assertFalse(CaptchaPolicy.blocks(widgets.take(2)))
    }

    // ── from the page script's JSON to the fill loop's verdict ──────────────────────────────

    private fun json(widgets: String, captcha: String = "") =
        """{"url":"https://jobs.ashbyhq.com/sesame/abc/application","title":"Apply","elements":[],$captcha"captchaWidgets":$widgets,"text":"Apply for this job"}"""

    @Test
    fun `the Ashby page no longer stops the fill`() {
        val snap = PlaywrightApplyBrowser.decodeSnapshot(json("""[{"src":"$enterpriseBadge","tag":"iframe","size":"","width":256,"height":60,"shown":true}]"""))
        assertFalse(snap.captcha)
        assertEquals(1, snap.captchaWidgets.size, "the facts are kept for logging")
    }

    @Test
    fun `a page with a real checkbox still does`() {
        val snap = PlaywrightApplyBrowser.decodeSnapshot(json("""[{"src":"https://www.google.com/recaptcha/api2/anchor?k=x&size=normal","tag":"iframe","width":304,"height":78,"shown":true}]"""))
        assertTrue(snap.captcha)
    }

    @Test
    fun `a page with nothing reported has no CAPTCHA`() {
        assertFalse(PlaywrightApplyBrowser.decodeSnapshot(json("[]")).captcha)
        assertFalse(PlaywrightApplyBrowser.decodeSnapshot("""{"url":"https://x.co/","elements":[],"text":""}""").captcha)
    }

    @Test
    fun `an explicit captcha flag from an older script is still honoured`() {
        assertTrue(PlaywrightApplyBrowser.decodeSnapshot(json("[]", captcha = """"captcha":true,""")).captcha)
    }

    @Test
    fun `the fill loop hands off on a real CAPTCHA and goes on past an invisible one`() {
        val dir = java.nio.file.Files.createTempDirectory("captcha")
        val job = JobContext("#J9", "Acme", "SDET", "https://jobs.example.com/apply", "dkkytech@gmail.com", "p", null, null, null)
        val agent = { llm: com.jd.jobbot.llm.Llm -> FillAgent(llm, Credentials(dir.resolve("c.json"), "dkkytech@gmail.com"), null) }
        val form = Snapshot("https://jobs.example.com/apply", elements = listOf(Element("e1", "input", "text", "First name", inForm = true)))

        val blocked = agent(com.jd.jobbot.llm.Llm { _, _ -> error("the model must not be asked") }).run(
            FakePage(PlaywrightApplyBrowser.decodeSnapshot(json("""[{"src":"https://www.google.com/recaptcha/api2/anchor?k=x&size=normal","tag":"iframe","width":304,"height":78,"shown":true}]"""))), job,
        )
        assertTrue(blocked is FillResult.NeedsHuman && blocked.reason.contains("CAPTCHA"), blocked.toString())

        var asked = 0
        val proceeds = agent(com.jd.jobbot.llm.Llm { _, _ -> asked++; com.jd.jobbot.llm.OpenAiCompatibleLlm.parseJsonObject("""{"status":"ready"}""") }).run(
            FakePage(form.copy(captchaWidgets = listOf(CaptchaWidget(src = enterpriseBadge, tag = "iframe", width = 256, height = 60, shown = true)))), job,
        )
        assertTrue(proceeds is FillResult.Ready, proceeds.toString())
        assertEquals(1, asked, "the model was consulted: the invisible badge did not stop the loop")
    }
}
