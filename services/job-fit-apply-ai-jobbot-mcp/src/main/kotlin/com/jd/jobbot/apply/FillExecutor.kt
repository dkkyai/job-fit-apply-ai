package com.jd.jobbot.apply

import com.jd.jobbot.actions.ResumeUpload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Clock

/** What the fill loop knows about the job and the candidate. */
data class JobContext(
    val ref: String,
    val company: String?,
    val title: String?,
    val jobUrl: String,
    val accountEmail: String,
    val profileYaml: String?,
    val resumeYaml: String?,
    val coverLetter: String?,
    val resumePdf: ByteArray?,
    /** The name and type the site sees for [resumePdf]. */
    val resumeFileName: String = FillExecutor.RESUME_NAME,
    val resumeMime: String = "application/pdf",
    /** True when [resumePdf] is a file Richard sent, not the tailored resume. */
    val resumeIsUpload: Boolean = false,
    /** The packet's application-answers.md, for a jobbot-cargo application: answers for the form, matching the résumé. */
    val answers: String? = null,
) {
    /**
     * This job with the resume Richard supplied. The tailored resume and its cover letter describe a different
     * resume, so they are dropped; answers still come from candidate_profile.yaml.
     */
    fun withUploadedResume(upload: ResumeUpload) = copy(
        resumeYaml = null, coverLetter = null, resumePdf = upload.bytes,
        resumeFileName = upload.fileName, resumeMime = upload.mime, resumeIsUpload = true,
    )
}

/** A string field of a JSON object, or null if absent or not a string. */
internal fun JsonObject.text(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

sealed interface FillResult {
    /** The form is filled and only the final submit remains. */
    data class Ready(val fields: List<Pair<String, String>>, val screenshot: ByteArray, val note: String) : FillResult {
        fun withNote(extra: String) = copy(note = listOf(note, extra).filter { it.isNotBlank() }.joinToString(" "))
    }
    /** A step only Richard can do (CAPTCHA, phone check, unknown required answer). */
    data class NeedsHuman(val reason: String, val screenshot: ByteArray?) : FillResult
    data class Failed(val reason: String, val screenshot: ByteArray?) : FillResult
}

/**
 * The hands of a job-application fill: executes one symbolic action on the apply browser under hard rules that
 * whoever is planning the fill (the model behind the `fill_*` tools) cannot argue with. It never plans anything.
 *  - it never clicks a submit control (SitePolicy.isSubmitControl): submitting is a ✅ Submit tap;
 *  - it never shows a password: `password` actions are filled from the credential store, bound to the current
 *    site, and a new site gets a freshly generated, saved-before-use password;
 *  - a site that offers Sign in with Google gets Google, never a password account;
 *  - Google consent is approved only for basic sign-in scopes; anything else is a hand-off;
 *  - verification codes and links come only from that site's own email;
 *  - it only addresses elements the page snapshot produced, and only navigates where SitePolicy allows.
 */
class FillExecutor(
    private val credentials: Credentials,
    private val verifier: Verifier?,
) {
    private val log = LoggerFactory.getLogger(FillExecutor::class.java)

    /** Runs one action. The result is what the planner is told: "ok", "error: …", "refused: …" or "handoff: …". */
    internal fun execute(
        a: JsonObject, page: ApplyPage, snap: Snapshot, job: JobContext, started: Long,
        secrets: MutableSet<String>,
        onPending: (String, String) -> Unit,
    ): String {
        val id = a.text("id")
        // Only ids the snapshot produced: the model cannot address anything it did not see.
        val el = id?.takeIf { PlaywrightApplyBrowser.ID.matches(it) }?.let { i -> snap.elements.firstOrNull { it.id == i } }
        return when (a.text("do")) {
            "fill" -> {
                el ?: return "error: no element $id"
                if (el.type == "password") return "refused: use the password action for password fields"
                page.fill(el.id, a.text("value").orEmpty()); "ok"
            }
            "select" -> { el ?: return "error: no element $id"; page.select(el.id, a.text("option").orEmpty()); "ok" }
            "check" -> {
                el ?: return "error: no element $id"
                page.check(el.id, a["checked"]?.jsonPrimitive?.booleanOrNull ?: true); "ok"
            }
            "upload_resume" -> {
                el ?: return "error: no element $id"
                val file = job.resumePdf ?: return "error: no resume file for this job"
                page.upload(el.id, job.resumeFileName, job.resumeMime, file); "ok"
            }
            "upload_cover_letter" -> {
                el ?: return "error: no element $id"
                val text = job.coverLetter ?: return "error: no cover letter for this job"
                page.upload(el.id, "CoverLetter.txt", "text/plain", text.toByteArray()); "ok"
            }
            "click" -> {
                el ?: return "error: no element $id"
                if (SitePolicy.isSubmitControl(el.text.ifBlank { el.label }, el.type, el.formFilled)) {
                    return "refused: '${el.text}' submits the application. Never submit — when only this is left, return status ready."
                }
                page.click(el.id); "ok"
            }
            "goto" -> {
                val url = a.text("url") ?: return "error: no url"
                if (!SitePolicy.navigationAllowed(url, job.jobUrl, job.company)) return "refused: navigation to $url is not allowed"
                page.goto(url); "ok"
            }
            "password" -> {
                el ?: return "error: no element $id"
                if (el.type != "password") return "refused: ${el.id} is not a password field"
                val site = Credentials.siteKey(page.url) ?: return "error: no site for ${page.url}"
                // Richard's rule: Google wherever it is offered. A site already holding a password account keeps it.
                val google = snap.elements.firstOrNull { it.google }
                if (google != null && credentials.get(site)?.method != "password") {
                    return "refused: $site offers Sign in with Google (${google.id}) — use google_sign_in on it, not a password"
                }
                val pw = when (a.text("purpose")) {
                    "login" -> credentials.passwordFor(site)
                        ?: return "handoff: I have no saved password for $site, and it wants one to sign in. Sign in in the viewer, then tap Continue."
                    else -> {
                        val existing = credentials.get(site)
                        when {
                            // Never replace a working password: the site already has an account.
                            existing?.status == Credentials.ACTIVE && existing.method == "password" ->
                                return "refused: an account for $site already exists — sign in instead (password purpose login)"
                            existing?.status == Credentials.PENDING -> existing.password!!
                            else -> credentials.createPending(site, page.url, job.ref).also { onPending(site, it) }
                        }
                    }
                }
                secrets += pw
                page.fill(el.id, pw); "ok (password filled from the store, not shown)"
            }
            "email" -> { el ?: return "error: no element $id"; page.fill(el.id, job.accountEmail); "ok" }
            "verification_code" -> {
                el ?: return "error: no element $id"
                val site = Credentials.siteKey(page.url) ?: return "error: no site"
                val code = verifier?.code(site, started) ?: return "handoff: No verification code from $site arrived in the inbox. Enter it in the viewer, then tap Continue."
                page.fill(el.id, code); "ok"
            }
            "open_verification_link" -> {
                val site = Credentials.siteKey(page.url) ?: return "error: no site"
                val link = verifier?.link(site, started) ?: return "handoff: No verification email from $site arrived. Verify in the viewer, then tap Continue."
                // Same guard as goto (the browser's route guard enforces it again at the network layer).
                if (!SitePolicy.navigationAllowed(link, job.jobUrl, job.company)) return "refused: the verification link points off-site"
                page.goto(link); "ok"
            }
            "google_sign_in" -> {
                el ?: return "error: no element $id"
                // Not a way around the click rules: only a control the snapshot marked as Google sign-in.
                if (!el.google) return "refused: ${el.id} is not a Sign in with Google control"
                val site = Credentials.siteKey(page.url)
                val result = when (val w = page.clickGoogle(el.id)) {
                    GoogleWindow.None -> "ok"
                    is GoogleWindow.Popup -> googlePopup(w.page, job)
                    is GoogleWindow.FedCm -> fedCm(w, job)
                }
                // Only a sign-in that went through (or a same-tab redirect to Google) is a Google account — never an error or a hand-off.
                if (site != null && result.startsWith("ok")) credentials.recordGoogle(site, page.url, job.ref)
                result
            }
            else -> "error: unknown action ${a.text("do")}"
        }
    }

    internal sealed interface GoogleOutcome {
        data object Continued : GoogleOutcome
        data class Handoff(val reason: String) : GoogleOutcome
    }

    /**
     * Google's own pages are handled by rule, never by the model: approve basic consent only, pick
     * the account. Consent is checked first — a consent screen also shows the account, as a chip
     * that would reopen the chooser.
     */
    internal fun handleGoogle(page: ApplyPage, snap: Snapshot, job: JobContext): GoogleOutcome? {
        if (host(snap.url) != "accounts.google.com") return null
        val consentButton = snap.elements.firstOrNull { it.kind == "button" && CONSENT_BUTTON.matches(it.text.trim()) }
        if (consentButton != null) {
            val path = runCatching { java.net.URI(snap.url).path.orEmpty() }.getOrDefault("")
            // Google Identity Services (/gsi/) only ever returns a sign-in identity, so its screen is basic unless it says otherwise.
            val basic = SitePolicy.consentIsBasic(snap.text) || (path.startsWith("/gsi/") && !SitePolicy.consentIsSensitive(snap.text))
            if (!basic) {
                return GoogleOutcome.Handoff("Google is asking for more than sign-in (e.g. Gmail or Drive access). Review it in the viewer.")
            }
            page.click(consentButton.id)
            return GoogleOutcome.Continued
        }
        val account = snap.elements.firstOrNull { it.text.contains(job.accountEmail, ignoreCase = true) || it.label.contains(job.accountEmail, ignoreCase = true) }
        if (account != null) {
            page.click(account.id)
            return GoogleOutcome.Continued
        }
        return GoogleOutcome.Handoff("Google wants you to confirm the sign-in for ${job.accountEmail}. Do it in the viewer, then tap Continue.")
    }

    /**
     * Drives Google's sign-in popup by the same rules until it closes itself (signed in) or needs
     * Richard. Bounded twice: at most [POPUP_STEPS] Google screens, and [POPUP_POLLS] half-second
     * waits for the popup to move on by itself (a redirect to the site, then closing).
     */
    private fun googlePopup(popup: ApplyPage, job: JobContext): String {
        var screens = 0
        repeat(POPUP_POLLS) {
            if (popup.isClosed) return SIGNED_IN
            popup.settle()
            val snap = runCatching { popup.snapshot() }.getOrElse { return if (popup.isClosed) SIGNED_IN else "error: ${it.message?.take(120)}" }
            // Off Google (the site's callback, which closes the popup once it has the sign-in), or still loading.
            if (host(snap.url) != "accounts.google.com" || snap.elements.isEmpty()) {
                popup.pause(500)
                return@repeat
            }
            if (++screens > POPUP_STEPS) return@repeat
            when (val outcome = handleGoogle(popup, snap, job)) {
                is GoogleOutcome.Handoff -> return "handoff: ${outcome.reason}"
                else -> {}
            }
        }
        return if (popup.isClosed) SIGNED_IN
        else "handoff: The Google sign-in window didn't finish. Finish it in the viewer, then tap Continue."
    }

    /** Chrome's FedCM dialog shares only name, email and picture by design, so picking the account is the whole consent. */
    private fun fedCm(dialog: GoogleWindow.FedCm, job: JobContext): String {
        if (dialog.dialogType !in setOf("AccountChooser", "AutoReauthn")) {
            return "handoff: Chrome's Google sign-in dialog needs you (${dialog.dialogType}). Finish it in the viewer, then tap Continue."
        }
        val index = dialog.accountEmails.indexOfFirst { it.equals(job.accountEmail, ignoreCase = true) }
        if (index < 0) return "handoff: Chrome's Google sign-in dialog doesn't offer ${job.accountEmail}. Sign in to Google as ${job.accountEmail} in the viewer, then tap Continue."
        dialog.select(index)
        return SIGNED_IN
    }

    internal fun shot(page: ApplyPage) = runCatching { page.screenshot() }.getOrNull()

    internal fun host(url: String) = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull().orEmpty()

    companion object {
        const val RESUME_NAME = "RichardHatcherResume.pdf"
        private const val SIGNED_IN = "ok (signed in with Google)"
        private const val POPUP_STEPS = 10
        private const val POPUP_POLLS = 120
        private val CONSENT_BUTTON = Regex("""^(continue|allow|confirm)$""", RegexOption.IGNORE_CASE)
        private val JSON = Json { encodeDefaults = false; explicitNulls = false }

        /** The filled fields for the review card (password values never appear). */
        fun fieldsOf(snap: Snapshot): List<Pair<String, String>> = snap.elements
            .filter { it.kind in setOf("input", "textarea", "select", "checkbox", "radio") }
            .mapNotNull { el ->
                val v = when {
                    el.type == "password" -> if (el.value.isNullOrBlank()) null else "••••••"
                    el.checked != null -> if (el.checked) "✓" else null
                    else -> el.value?.takeIf { it.isNotBlank() }
                } ?: return@mapNotNull null
                (el.label.ifBlank { el.name ?: el.id }).take(60) to v.take(120)
            }

        /** Every action the executor understands: the whole vocabulary offered to the planner, in one place. */
        val ACTIONS = listOf(
            "fill", "select", "check", "upload_resume", "upload_cover_letter", "click", "goto",
            "password", "email", "google_sign_in", "verification_code", "open_verification_link",
        )
    }
}
