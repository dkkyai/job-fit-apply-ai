package com.jd.pipeline.nodes.scan.digest

import java.net.URI

internal fun cleanUrl(url: String): String = url
    .replace("\u0026amp;", "\u0026")
    .replace("\u0026lt;", "\u003c")
    .replace("\u0026gt;", "\u003e")
    .replace("\u0026quot;", "\"")
    .replace("\u0026#39;", "'")
    .replace("\u0026nbsp;", "")
    .replace(Regex("[.,;:!?]+$"), "")

internal fun isEligibleJobUrl(url: String?, senderDomain: String): Boolean {
    if (url == null || !url.startsWith("http")) return false
    val lower = url.lowercase()
    val host = try { URI.create(url).host?.lowercase() ?: "" } catch (_: Exception) { "" }
    if (lower.contains("unsubscribe") || lower.contains("optout") || lower.contains("opt-out") ||
        lower.contains("manage-pref") || lower.contains("privacy") || lower.contains("/account") ||
        lower.contains("/login") || lower.contains("/signin") ||
        lower.matches(Regex(".*\\.(png|jpg|gif|jpeg|svg|css|js|ico)(\\?.*)?$"))) return false

    val group = BoardRegistry.groupFor(host.takeIf { it.isNotBlank() } ?: senderDomain)
    return when (group?.key) {
        "linkedin" -> lower.contains("/jobs/view/") || lower.contains("/comm/jobs/view/")
        "jobright" -> lower.contains("/job") || lower.contains("/jobs/") || lower.contains("jobright.ai")
        "wellfound" -> lower.contains("/jobs/")
        "monster" -> lower.contains("/job-openings/") || lower.contains("/job/")
        "workday" -> lower.contains("/job/") || lower.contains("myworkdayjobs.com") || lower.contains("/en-us/")
        "ats" -> lower.contains("/jobs/") || lower.contains("/job/") || lower.contains("/careers/") || lower.contains("/apply")
        else -> lower.contains("/job") || lower.contains("/position") || lower.contains("/career") ||
            lower.contains("/opening") || lower.contains("/listing") || lower.contains("apply") || lower.contains("requisition")
    }
}
