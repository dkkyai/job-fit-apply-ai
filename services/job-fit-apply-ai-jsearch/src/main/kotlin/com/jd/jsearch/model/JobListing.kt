package com.jd.jsearch.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/** A JSearch API job listing (subset). Duplicated per the project's per-service DTO convention. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class JobListing(
    @JsonProperty("job_id")          val jobId: String,
    @JsonProperty("job_title")       val jobTitle: String,
    @JsonProperty("employer_name")   val employerName: String,
    @JsonProperty("job_city")        val jobCity: String? = null,
    @JsonProperty("job_state")       val jobState: String? = null,
    @JsonProperty("job_is_remote")   val jobIsRemote: Boolean = false,
    @JsonProperty("job_description") val jobDescription: String? = null,
    @JsonProperty("job_apply_link")  val jobApplyLink: String? = null,
    @JsonProperty("job_publisher")   val jobPublisher: String? = null,
)
