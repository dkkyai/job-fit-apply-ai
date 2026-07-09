package com.jd.pipeline.utils

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.jd.pipeline.models.CareerEntry
import com.jd.pipeline.models.Demographics
import com.jd.pipeline.nodes.tailor.TailoredProfile

/**
 * Serializes a [TailoredProfile] back into the canonical résumé-YAML schema
 * (`resume.yaml` shape) consumed by `src/main/resources/resume/yaml_to_tex.py`,
 * which renders it to LaTeX → PDF (tectonic).
 *
 * The schema is emitted explicitly (string keys, not Jackson annotations) because the
 * in-memory model's serialized names differ from the résumé-YAML names in two ways:
 *  - [CareerEntry] serializes `start_date`/`end_date`; the résumé YAML (and the
 *    LaTeX script) use `start`/`end` (read-side `@JsonAlias` only).
 *  - Projects render as `name`/`link` rows in the LaTeX template; the model reuses
 *    [CareerEntry], so `company` maps to `name` and `location` fills the `link` slot
 *    (matching what the HTML preview shows bottom-right for a project).
 *
 * [demographics] comes from the raw parsed résumé ([com.jd.pipeline.models.ResumeYaml]),
 * not from [TailoredProfile.base]'s identity — `ProfileLoader.merge` drops `postal_code`,
 * which the PDF header renders when present.
 */
object ResumeYamlWriter {

    private val YAML: ObjectMapper = ObjectMapper(
        YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
    )

    /**
     * Render the tailored résumé as YAML text. [demographics] is the raw résumé header
     * block; when null (résumé YAML unreadable — should not happen in a pipeline run,
     * since the profile was built from it) falls back to the identity fields on
     * [TailoredProfile.base], losing only `postal_code`.
     */
    fun render(tailored: TailoredProfile, demographics: Demographics?): String {
        val demo = demographics ?: tailored.base.identity.let {
            Demographics(
                firstName = it.firstName.ifBlank { it.fullName.substringBeforeLast(' ') },
                lastName = it.lastName.ifBlank { it.fullName.substringAfterLast(' ', "") },
                email = it.email,
                phone = it.phone,
                location = it.location,
            )
        }

        val doc = linkedMapOf<String, Any?>(
            "demographics" to linkedMapOf(
                "first_name" to demo.firstName,
                "last_name" to demo.lastName,
                "email" to demo.email,
                "phone" to demo.phone,
                "location" to demo.location,
                "postal_code" to demo.postalCode,
            ),
            "summary" to tailored.summary.trim(),
            "experience" to tailored.careerHistory.map { experienceEntry(it) },
            "projects" to tailored.projects.map { projectEntry(it) },
            "education" to tailored.base.background.education.map {
                linkedMapOf(
                    "degree" to it.degree,
                    "field" to it.fieldOfStudy,
                    "school" to it.school,
                    "location" to (it.location ?: ""),
                    "year" to it.year,
                )
            },
            "skills" to tailored.orderedSkillGroups().map { (label, items) ->
                linkedMapOf("label" to label, "items" to items)
            },
        )
        return YAML.writeValueAsString(doc)
    }

    private fun experienceEntry(e: CareerEntry): Map<String, Any?> = linkedMapOf(
        "role" to e.role,
        "company" to e.company,
        "location" to e.location,
        "start" to e.startDate,
        "end" to e.endDate?.takeIf { it.isNotBlank() },
        "bullets" to bullets(e),
    )

    private fun projectEntry(e: CareerEntry): Map<String, Any?> = linkedMapOf(
        "role" to e.role,
        "name" to e.company,
        "link" to e.location,
        "start" to e.startDate,
        "end" to e.endDate?.takeIf { it.isNotBlank() },
        "bullets" to bullets(e),
    )

    private fun bullets(e: CareerEntry): List<Map<String, String>> =
        e.bullets.map { linkedMapOf("category" to it.category.trim(), "text" to it.text.trim()) }
}
