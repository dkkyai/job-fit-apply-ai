package com.jd.pipeline.cli

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState

/**
 * DTO for the --jd-json CLI flag. Snake_case keys; converts to JDState via toState().
 * Only includes fields the CLI flag actually accepts (the legacy from(Map) accepted everything;
 * in practice --jd-json is used to seed a job, not a fully scored state).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class JdJsonInput(
    @JsonProperty("email_id") val emailId: String = "",
    @JsonProperty("email_subject") val emailSubject: String = "",
    @JsonProperty("email_from") val emailFrom: String = "",
    @JsonProperty("email_raw") val emailRaw: String = "",
    @JsonProperty("email_html_raw") val emailHtmlRaw: String = "",
    @JsonProperty("job_url") val jobUrl: String = "",
    @JsonProperty("jd_text") val jdText: String = "",
    @JsonProperty("company") val company: String = "",
    @JsonProperty("role_title") val roleTitle: String = "",
    @JsonProperty("location") val location: String = "",
    @JsonProperty("is_job_posting") val isJobPosting: Boolean = false,
    @JsonProperty("is_recruiter_email") val isRecruiterEmail: Boolean = false,
    @JsonProperty("source") val source: String = "email",
) {
    fun toState(): JDState = JDState(
        intake = if (emailId.isNotBlank() || emailSubject.isNotBlank() || emailFrom.isNotBlank()) {
            IntakeContext.Email(
                emailId = emailId,
                subject = emailSubject,
                from = emailFrom,
                rawBody = emailRaw,
                htmlBody = emailHtmlRaw,
                isRecruiter = isRecruiterEmail,
                isDigest = false,
                isInlineDigest = false,
            )
        } else {
            IntakeContext.Synthetic(label = "jd-json-${System.currentTimeMillis()}")
        },
        jobUrl = jobUrl,
        jdText = jdText,
        company = company,
        roleTitle = roleTitle,
        location = location,
        isJobPosting = isJobPosting,
        candidateProfile = JDState.loadCandidateProfile(),
    )
}
