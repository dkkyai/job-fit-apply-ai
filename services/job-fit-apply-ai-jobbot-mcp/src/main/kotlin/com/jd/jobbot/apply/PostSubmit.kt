package com.jd.jobbot.apply

/** What the page is showing after ✅ Submit was clicked. */
sealed interface SubmitStep {
    /** The site says the application arrived. [words] is the sentence that says so, for the packet's STATUS.md. */
    data class Confirmed(val words: String) : SubmitStep

    /**
     * The site wants a one-time code it emailed before it takes the application (Greenhouse: "enter the 8-character
     * code to confirm you're a human"). [boxes] are the inputs to type it into: one per character when the code is split
     * over single-character boxes, else the one field. [prompt] is the page's own sentence about it.
     */
    data class CodePrompt(val boxes: List<Element>, val prompt: String) : SubmitStep {
        /** How many characters the code has, when the boxes say so. */
        val length: Int? get() = boxes.size.takeIf { it > 1 }
    }

    /** Neither: a validation error, a CAPTCHA, a follow-up question, a page nobody planned for. [excerpt] is what looked relevant. */
    data class Unknown(val excerpt: String, val captcha: Boolean) : SubmitStep
}

/**
 * Reads the page after a Submit click. A click is only the start of submitting: the site may confirm, or ask for more
 * (an emailed code, a CAPTCHA, a field it rejected). Anything but a confirmation is never reported as "submitted".
 */
object PostSubmit {
    private val CONFIRMATION = Regex("""(?i)thank you|application (has been )?(received|submitted)|we('ve| have) received|successfully submitted""")
    private val CODE_WORDS = Regex("""(?i)(verification|security|confirmation|one-time|access) code|code (was|has been|is being) sent|confirm you.?re (a )?human""")
    private val CODE_LABEL = Regex("""(?i)\bcode\b""")
    private val NOTEWORTHY = Regex("""(?i)error|invalid|incorrect|required|failed|unable|try again|must |please |captcha|verify|expired""")

    fun classify(snap: Snapshot): SubmitStep {
        // The code prompt first: it sits inside the form and its words ("thank you" in a footer) must not pass for a confirmation.
        codeBoxes(snap)?.let { return SubmitStep.CodePrompt(it, prompt(snap)) }
        CONFIRMATION.find(snap.text)?.let { return SubmitStep.Confirmed(it.value) }
        return SubmitStep.Unknown(excerpt(snap), snap.captcha)
    }

    /** The boxes a code goes into, or null when the page is not asking for one. */
    fun codeBoxes(snap: Snapshot): List<Element>? {
        if (!CODE_WORDS.containsMatchIn(snap.text)) return null
        val runs = mutableListOf<MutableList<Element>>()
        var previous: Element? = null
        for (el in snap.elements) {
            val box = el.kind == "input" && el.maxLength == 1 && (el.type == null || el.type in setOf("text", "tel", "number"))
            if (box) {
                if (previous != null && previous.kind == "input" && previous.maxLength == 1) runs.last() += el else runs += mutableListOf(el)
            }
            previous = el
        }
        runs.filter { it.size in 4..12 }.maxByOrNull { it.size }?.let { return it }
        // One field for the whole code.
        return snap.elements.firstOrNull { it.kind == "input" && CODE_LABEL.containsMatchIn(it.label) && (it.type == null || it.type == "text") }?.let { listOf(it) }
    }

    private fun prompt(snap: Snapshot): String =
        snap.text.lines().map { it.trim() }.firstOrNull { CODE_WORDS.containsMatchIn(it) && it.length < 300 }.orEmpty()

    /** A few lines of the page that look like an error or an instruction. */
    fun excerpt(snap: Snapshot): String =
        snap.text.lines().map { it.trim() }.filter { it.isNotBlank() && it.length < 300 && NOTEWORTHY.containsMatchIn(it) }
            .distinct().take(4).joinToString(" / ").take(500)
}
