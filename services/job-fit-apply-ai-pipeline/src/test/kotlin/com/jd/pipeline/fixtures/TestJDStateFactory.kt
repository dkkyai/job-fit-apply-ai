package com.jd.pipeline.fixtures

import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.PipelineAction

/**
 * Factory for creating test JDState instances with common configurations.
 * All instances are created via the data class constructor — no mutation needed.
 */
object TestJDStateFactory {

    /**
     * Create a minimal job posting state.
     */
    fun createJobPostingState(): JDState = JDState(
        isJobPosting = true,
        company = "Test Company",
        roleTitle = "Software Engineer",
        location = "San Francisco, CA",
        jdText = "We are looking for a skilled software engineer..."
    )

    /**
     * Create a full job posting state with all fields.
     */
    fun createFullJobPostingState(): JDState = createJobPostingState().copy(
        intake = IntakeContext.Email(
            emailId = "email-123",
            subject = "Job Opportunity: Software Engineer",
            from = "recruiter@testcompany.com",
            rawBody = "Full email body content...",
            htmlBody = "",
            isRecruiter = false,
            isDigest = false,
            isInlineDigest = false,
        ),
        jobUrl = "https://testcompany.com/careers/123",
        salaryRange = "$100k - $150k",
        remotePolicy = "remote",
        yoeRequired = 5,
        techStack = listOf("Java", "Spring", "PostgreSQL", "AWS")
    )

    /**
     * Create a non-job posting state.
     */
    fun createNonJobPostingState(): JDState = JDState(
        isJobPosting = false,
        intake = IntakeContext.Email(
            emailId = "email-456",
            subject = "Re: Meeting tomorrow",
            from = "colleague@company.com",
            rawBody = "Hey, let's meet tomorrow...",
            htmlBody = "",
            isRecruiter = false,
            isDigest = false,
            isInlineDigest = false,
        ),
    )

    /**
     * Create a digest email state.
     */
    fun createDigestState(): JDState = JDState(
        isJobPosting = true,
        intake = IntakeContext.Email(
            emailId = "email-789",
            subject = "Daily Job Digest",
            from = "noreply@jobright.ai",
            rawBody = "Check out these job opportunities...",
            htmlBody = "",
            isRecruiter = false,
            isDigest = true,
            isInlineDigest = false,
        ),
    )

    /**
     * Create a scored state with fit score above threshold.
     */
    fun createHighScoredState(): JDState = createFullJobPostingState().copy(
        fitScore = 80.0f,
        fitReasoning = "Strong match - candidate has all required skills",
        strengths = listOf("Java expertise", "Spring experience", "AWS certified"),
        gaps = listOf("No leadership experience"),
        redFlags = emptyList(),
        pipelineAction = PipelineAction.TAILOR
    )

    /**
     * Create a scored state with fit score below threshold.
     */
    fun createLowScoredState(): JDState = createFullJobPostingState().copy(
        fitScore = 40.0f,
        fitReasoning = "Weak match - missing key requirements",
        strengths = emptyList(),
        gaps = listOf("No relevant experience", "Wrong tech stack"),
        redFlags = listOf("No remote policy"),
        pipelineAction = PipelineAction.SKIP
    )

    /**
     * Create a state for duplicate detection.
     */
    fun createDuplicateState(): JDState = createFullJobPostingState().copy(
        isDuplicate = true,
        duplicateId = 123,
        pipelineAction = PipelineAction.SKIP
    )

    /**
     * Create a state ready for tailoring.
     */
    fun createTailoringState(): JDState = createHighScoredState().copy(
        outputPath = "output/test_company_software_engineer"
    )

    /**
     * Create a JD_Not_Found state (non-job posting).
     */
    fun createJdNotFoundState(): JDState = JDState(
        isJobPosting = false,
        intake = IntakeContext.Email(
            emailId = "jd-not-found-001",
            subject = "Newsletter: Tech Industry Updates",
            from = "newsletter@technews.com",
            rawBody = "This week's tech news...",
            htmlBody = "",
            isRecruiter = false,
            isDigest = false,
            isInlineDigest = false,
        ),
    )

    /**
     * Create a recruiter response required state.
     */
    fun createRecruiterResponseRequiredState(): JDState = createFullJobPostingState().copy(
        isRecruiterResponseRequired = true,
        isJobPosting = true,
        intake = createFullJobPostingState().intake?.let { intake ->
            (intake as? IntakeContext.Email)?.copy(
                emailId = "recruiter-response-001",
                isRecruiter = true
            ) ?: intake
        }
    )

    /**
     * Create an inline digest state.
     */
    fun createInlineDigestState(): JDState = JDState(
        isJobPosting = true,
        intake = IntakeContext.Email(
            emailId = "inline-digest-001",
            subject = "Weekly Job Digest",
            from = "noreply@jobright.ai",
            rawBody = "",
            htmlBody = "",
            isRecruiter = false,
            isDigest = false,
            isInlineDigest = true,
        ),
    )

    /**
     * Create a JSearch-sourced state.
     * Pre-populated with JD text, company, role title, etc. so the pipeline
     * skips scan/scrape/save and routes straight to scoreAndRoute.
     */
    fun createJSearchSourcedState(
        company: String = "JSearch Corp",
        roleTitle: String = "Senior SDET",
        location: String = "Seattle, WA",
        salaryRange: String = "$120k - $160k",
        jdText: String = "We are seeking a Senior SDET with Kotlin experience...",
        jobUrl: String = "https://jsearchcorp.com/jobs/senior-sdet",
        techStack: List<String> = listOf("Kotlin", "JUnit", "Selenium", "AWS")
    ): JDState = JDState(
        intake = IntakeContext.Api(board = "jsearch"),
        isJobPosting = true,
        company = company,
        roleTitle = roleTitle,
        location = location,
        salaryRange = salaryRange,
        jdText = jdText,
        jobUrl = jobUrl,
        remotePolicy = "remote",
        techStack = techStack
    )
}