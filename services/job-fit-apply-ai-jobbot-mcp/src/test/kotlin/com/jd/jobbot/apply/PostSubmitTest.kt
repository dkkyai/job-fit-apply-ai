package com.jd.jobbot.apply

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PostSubmitTest {
    private val legend = "A verification code was sent to dkkytech@gmail.com. To submit your application, enter the 8-character code to confirm you're a human."
    private fun boxes(n: Int) = (0 until n).map { Element("c$it", "input", "text", if (it == 0) "Security code" else "", inForm = true, maxLength = 1) }
    private val submit = Element("s", "button", "submit", text = "Submit application", inForm = true, formFilled = true)

    @Test
    fun `eight single-character boxes under a verification legend are a code prompt`() {
        val step = PostSubmit.classify(Snapshot("https://job-boards.greenhouse.io/x/jobs/1", elements = listOf(Element("e1", "input", "text", "First name", value = "R", inForm = true)) + boxes(8) + submit, text = "Apply\n$legend\nSecurity code"))
        assertIs<SubmitStep.CodePrompt>(step)
        assertEquals(8, step.length)
        assertEquals(listOf("c0", "c1", "c2", "c3", "c4", "c5", "c6", "c7"), step.boxes.map { it.id })
        assertTrue(step.prompt.contains("8-character code"), step.prompt)
    }

    @Test
    fun `one field labelled as a code is a code prompt without a length`() {
        val step = PostSubmit.classify(Snapshot("https://x.com/a", elements = listOf(Element("v", "input", "text", "Verification code", inForm = true), submit), text = "We emailed you a verification code"))
        assertIs<SubmitStep.CodePrompt>(step)
        assertEquals(null, step.length)
        assertEquals(listOf("v"), step.boxes.map { it.id })
    }

    @Test
    fun `single-character boxes with no word about a code are not one (a date field, say)`() {
        val step = PostSubmit.classify(Snapshot("https://x.com/a", elements = boxes(8), text = "Date of birth"))
        assertIs<SubmitStep.Unknown>(step)
    }

    @Test
    fun `a confirmation is a confirmation, and a code prompt beats a footer's thank you`() {
        assertIs<SubmitStep.Confirmed>(PostSubmit.classify(Snapshot("https://x.com/done", text = "Thank you for applying!")))
        val prompt = PostSubmit.classify(Snapshot("https://x.com/a", elements = boxes(8), text = "$legend\nThank you for your interest in Skylight."))
        assertIs<SubmitStep.CodePrompt>(prompt)
    }

    @Test
    fun `anything else is unknown, with the error lines and a CAPTCHA noted`() {
        val step = PostSubmit.classify(Snapshot("https://x.com/a", text = "Apply\nFirst name\nThis field is required\nCompleted all that?", captcha = true))
        assertIs<SubmitStep.Unknown>(step)
        assertTrue(step.captcha)
        assertTrue(step.excerpt.contains("This field is required"), step.excerpt)
    }
}
