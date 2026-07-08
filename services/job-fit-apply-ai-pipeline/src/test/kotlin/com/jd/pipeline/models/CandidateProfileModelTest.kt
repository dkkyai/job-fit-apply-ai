package com.jd.pipeline.models

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Unit tests for data classes in [CandidateProfile].
 */
@DisplayName("CandidateProfileModelTest")
class CandidateProfileModelTest {

    @Test
    @DisplayName("fullName uses firstName + lastName when both present")
    fun fullNameFirstLast() {
        val identity = CandidateIdentity(
            name = "Legacy",
            firstName = "Jane",
            lastName = "Doe",
            email = "j@example.com",
            phone = "555",
            location = "NYC"
        )
        assertEquals("Jane Doe", identity.fullName)
    }

    @Test
    @DisplayName("fullName falls back to name when first/last are blank")
    fun fullNameFallback() {
        val identity = CandidateIdentity(
            name = "Legacy Name",
            firstName = "",
            lastName = "",
            email = "j@example.com",
            phone = "555",
            location = "NYC"
        )
        assertEquals("Legacy Name", identity.fullName)
    }

    @Test
    @DisplayName("fullName returns empty string when all name fields are blank")
    fun fullNameEmpty() {
        val identity = CandidateIdentity(
            name = "", firstName = "", lastName = "",
            email = "j@example.com", phone = "555", location = "NYC"
        )
        assertEquals("", identity.fullName)
    }

    @Test
    @DisplayName("CareerEntry.dateRange with null endDate renders as Present")
    fun careerEntryDateRange() {
        val entry = CareerEntry(
            role = "Eng", company = "Acme", location = "",
            startDate = "2020-01", endDate = null, bullets = emptyList()
        )
        assertEquals("2020-01 – Present", entry.dateRange)
    }

    @Test
    @DisplayName("EducationEntry.degreeLine joins degree and field with a pipe")
    fun educationEntryDegreeLine() {
        val withField = EducationEntry(
            degree = "B.S.", fieldOfStudy = "Computer Engineering",
            school = "Purdue", location = "West Lafayette, IN", year = "2005"
        )
        assertEquals("B.S. | Computer Engineering", withField.degreeLine)

        val withoutField = EducationEntry(degree = "M.S.", school = "Stanford", year = "2020")
        assertEquals("M.S.", withoutField.degreeLine)
    }

    @Test
    @DisplayName("Bullet carries a category label plus text")
    fun bulletShape() {
        val b = Bullet(category = "Leadership", text = "Led a team of 5.")
        assertEquals("Leadership", b.category)
        assertEquals("Led a team of 5.", b.text)
    }
}
