package com.jd.pipeline.utils

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.jd.pipeline.models.Bullet
import com.jd.pipeline.models.CandidateBackground
import com.jd.pipeline.models.CandidateIdentity
import com.jd.pipeline.models.CandidateProfile
import com.jd.pipeline.models.CareerEntry
import com.jd.pipeline.models.Demographics
import com.jd.pipeline.models.EducationEntry
import com.jd.pipeline.models.SkillGroup
import com.jd.pipeline.nodes.tailor.TailoredProfile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * ResumeYamlWriter must emit exactly the schema yaml_to_tex.py consumes: `start`/`end`
 * date keys (NOT the model's `start_date`/`end_date`), projects with `name`/`link`,
 * `postal_code` passed through from the raw résumé demographics, and skill items in
 * JD-matched-first order.
 */
class ResumeYamlWriterTest {

    private val yaml = ObjectMapper(YAMLFactory())

    private fun profile() = CandidateProfile(
        identity = CandidateIdentity(
            name = "Jane Doe", firstName = "Jane", lastName = "Doe",
            email = "jane@example.com", phone = "555-1234", location = "Seattle, WA",
        ),
        background = CandidateBackground(
            targetTitle = "SDET",
            yearsExperience = 10,
            summary = "Base summary.",
            education = listOf(
                EducationEntry(degree = "BSc", fieldOfStudy = "Computer Engineering", school = "State U", location = "City, ST", year = "2015")
            ),
            careerHistory = emptyList(),
            coreStrengths = emptyList(),
            languages = emptyList(),
            domainExpertise = emptyList(),
        ),
        skills = emptyList(),
    )

    private fun tailored() = TailoredProfile(
        base = profile(),
        summary = "Tailored summary.",
        careerHistory = listOf(
            CareerEntry(
                role = "Staff SDET", company = "Acme", location = "Remote",
                startDate = "2021-03", endDate = null,
                bullets = listOf(Bullet(category = "Impact", text = "Did a thing.")),
            )
        ),
        projects = listOf(
            CareerEntry(
                role = "Maintainer", company = "CoolTool", location = "github.com/x/cooltool",
                startDate = "2020", endDate = "2022-06",
                bullets = listOf(Bullet(category = "OSS", text = "Shipped releases.")),
            )
        ),
        skillGroups = linkedMapOf("Languages" to listOf("Java", "Kotlin", "Python")),
        jdMatchedSkills = listOf("Kotlin"),
    )

    @Test
    fun `emits the yaml_to_tex schema`() {
        val text = ResumeYamlWriter.render(
            tailored(),
            Demographics(
                firstName = "Jane", lastName = "Doe", email = "jane@example.com",
                phone = "555-1234", location = "Seattle, WA", postalCode = "98101",
            ),
        )
        val doc = yaml.readTree(text)

        // demographics — postal_code preserved from the raw résumé header
        assertEquals("Jane", doc.at("/demographics/first_name").asText())
        assertEquals("98101", doc.at("/demographics/postal_code").asText())

        // experience — start/end keys (not start_date/end_date); null end → "Present" downstream
        val exp = doc.at("/experience/0")
        assertEquals("2021-03", exp.at("/start").asText())
        assertTrue(exp.at("/end").isNull, "open-ended role serializes end: null")
        assertTrue(exp.at("/start_date").isMissingNode, "model-side key must not leak")
        assertEquals("Impact", exp.at("/bullets/0/category").asText())

        // projects — company→name, location fills the link slot; bare-year start passes through
        val proj = doc.at("/projects/0")
        assertEquals("CoolTool", proj.at("/name").asText())
        assertEquals("github.com/x/cooltool", proj.at("/link").asText())
        assertEquals("2020", proj.at("/start").asText())
        assertEquals("2022-06", proj.at("/end").asText())

        // education — field key (not fieldOfStudy)
        assertEquals("Computer Engineering", doc.at("/education/0/field").asText())

        // skills — JD-matched items lead their group
        assertEquals("Languages", doc.at("/skills/0/label").asText())
        assertEquals("Kotlin", doc.at("/skills/0/items/0").asText())

        assertEquals("Tailored summary.", doc.at("/summary").asText())
    }

    @Test
    fun `falls back to identity when raw demographics are unavailable`() {
        val text = ResumeYamlWriter.render(tailored(), demographics = null)
        val doc = yaml.readTree(text)
        assertEquals("Jane", doc.at("/demographics/first_name").asText())
        assertEquals("Doe", doc.at("/demographics/last_name").asText())
        assertEquals("", doc.at("/demographics/postal_code").asText())
    }
}
