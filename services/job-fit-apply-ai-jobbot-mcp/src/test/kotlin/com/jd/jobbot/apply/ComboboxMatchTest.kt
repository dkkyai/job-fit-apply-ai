package com.jd.jobbot.apply

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which option of a dropdown answers the words the planner chose. */
class ComboboxMatchTest {
    private fun pick(wanted: String, vararg options: String) = PlaywrightApplyBrowser.bestOption(options.toList(), wanted)

    @Test
    fun `an exact option wins over longer ones that start with it`() {
        assertEquals(1, pick("Yes", "Yes, I am a U.S. citizen", "Yes", "No"))
        assertEquals(2, pick("No", "None of the above", "Not sure", "No"))
    }

    @Test
    fun `case, punctuation and a trailing dial code do not matter`() {
        assertEquals(2, pick("united states", "United Arab Emirates +971", "United Kingdom +44", "United States +1", "Uruguay +598"))
        assertEquals(0, pick("Job site (e.g., Indeed)", "Job site (e.g., Indeed)", "Other"))
        assertEquals(0, pick("job site e g indeed", "Job site (e.g., Indeed)", "Other"))
    }

    @Test
    fun `the shortest option that starts with the words is chosen, then the shortest that contains them`() {
        assertEquals(1, pick("Seattle", "Seattle, WA, USA (King County)", "Seattle, WA"))
        assertEquals(0, pick("LinkedIn", "Recruiter contacted me on LinkedIn", "A much longer option that mentions LinkedIn somewhere in it"))
        assertEquals(1, pick("LinkedIn", "Recruiter contacted me on LinkedIn", "LinkedIn job posting and more words"), "starting with the words beats merely containing them")
    }

    @Test
    fun `no option matching means no choice, never a guess`() {
        assertNull(pick("Billboard", "Recruiter contacted me on LinkedIn", "Job site", "Other"))
        assertNull(pick("", "Other"))
        assertNull(pick("anything"))
    }
}
