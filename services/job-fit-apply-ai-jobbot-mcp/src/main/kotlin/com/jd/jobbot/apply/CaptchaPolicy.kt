package com.jd.jobbot.apply

import kotlinx.serialization.Serializable

/** What the page script saw of one CAPTCHA-looking element: facts only; [CaptchaPolicy] decides whether it matters. */
@Serializable
data class CaptchaWidget(
    /** The iframe's address, or empty for a plain element. */
    val src: String = "",
    val tag: String = "",
    /** The element's `data-size` ("invisible", "normal", "compact"), if any. */
    val size: String = "",
    val width: Int = 0,
    val height: Int = 0,
    /** Rendered and on the page: not display:none, not visibility:hidden, not transparent, not parked off-screen. */
    val shown: Boolean = false,
)

/**
 * Whether a CAPTCHA on the page needs a person. JobBot never solves one, so a real one is handed off to Richard. But
 * most application forms (Ashby, Greenhouse, Lever…) carry an *invisible* reCAPTCHA that scores the visitor in the
 * background and never asks anything: its badge is an iframe, so "is there a reCAPTCHA iframe?" is the wrong question,
 * and asking it stopped every one of those forms at the first step. The question is whether a person would see a
 * challenge to solve.
 */
object CaptchaPolicy {
    /** A widget smaller than this is a tracking pixel or a collapsed container, not something to click. */
    private const val MIN_WIDTH = 40
    private const val MIN_HEIGHT = 30

    /** reCAPTCHA v3 / Enterprise and hCaptcha put `size=invisible` in the frame's address. */
    private val INVISIBLE_IN_URL = Regex("""[?&#]size=invisible\b""", RegexOption.IGNORE_CASE)

    /** A submit button, or a link, that carries a site key is reCAPTCHA's invisible trigger, not a widget. */
    private val TRIGGER_TAGS = setOf("button", "input", "a")

    fun blocks(widgets: List<CaptchaWidget>): Boolean = widgets.any { blocks(it) }

    fun blocks(w: CaptchaWidget): Boolean = when {
        !w.shown -> false                                    // e.g. reCAPTCHA's challenge frame, waiting off-screen to be triggered
        w.width < MIN_WIDTH || w.height < MIN_HEIGHT -> false
        w.size.equals("invisible", ignoreCase = true) -> false
        INVISIBLE_IN_URL.containsMatchIn(w.src) -> false
        w.src.isEmpty() && w.tag.lowercase() in TRIGGER_TAGS -> false
        else -> true
    }
}
