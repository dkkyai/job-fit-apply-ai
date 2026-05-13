package com.jd.pipeline.models

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * CandidatePreferences — answers to common recruiter pre-screening questions.
 * Used by the Draft Reply node to auto-populate responses about relocation,
 * visa status, start date, compensation, etc.
 *
 * All fields are nullable — the LLM will phrase answers as "open to..."
 * when no explicit preference is stored.
 */
data class CandidatePreferences(
    @JsonProperty("willing_to_relocate")
    val willingToRelocate: Boolean = false,

    @JsonProperty("relocation_notes")
    val relocationNotes: String? = null,

    @JsonProperty("visa_status")
    val visaStatus: String = "US Citizen",

    @JsonProperty("visa_sponsorship_required")
    val visaSponsorshipRequired: Boolean = false,

    @JsonProperty("available_to_start_date")
    val availableToStartDate: String? = null,

    @JsonProperty("notice_period_days")
    val noticePeriodDays: Int? = null,

    @JsonProperty("preferred_work_arrangement")
    val preferredWorkArrangement: String = "Remote-first; hybrid Seattle acceptable",

    @JsonProperty("current_base_salary")
    val currentBaseSalary: String? = null,

    @JsonProperty("minimum_total_compensation")
    val minimumTotalCompensation: String? = null,

    @JsonProperty("compensation_notes")
    val compensationNotes: String? = null,

    @JsonProperty("open_to_equity_only_roles")
    val openToEquityOnlyRoles: Boolean = false,

    @JsonProperty("willing_to_travel")
    val willingToTravel: Boolean = false,

    @JsonProperty("travel_percentage")
    val travelPercentage: String? = null,

    @JsonProperty("open_to_contract_roles")
    val openToContractRoles: Boolean = false,

    @JsonProperty("minimum_contract_rate_hourly")
    val minimumContractRateHourly: Int? = null,

    @JsonProperty("additional_notes")
    val additionalNotes: String? = null
)
