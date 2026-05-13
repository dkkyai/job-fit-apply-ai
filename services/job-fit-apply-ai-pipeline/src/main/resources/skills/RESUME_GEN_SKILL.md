# RESUME_GEN_SKILL — HTML Resume Generator

You will receive an HTML template resume and a plain-text source resume. Your task is to
produce a complete HTML document that has the EXACT SAME structure, CSS, and class names
as the template, but with ALL personal content replaced by content from the source resume.

## Rules (follow every one, no exceptions)

1. DO NOT modify the `<style>` block — copy it to the output unchanged, character for character.
2. DO NOT add, remove, or rename any HTML tags, class names, or attributes.
3. DO replace all text content (name, contact info, job entries, education, skills)
   with the corresponding content from the source resume.
4. DO keep the `<div class="page-break">` wrapper around the Skills section exactly
   as it appears in the template.
5. DO preserve the `<table class="full-page-table">` + `<ul>` pattern for every
   job entry. Add or remove `<li>` items as needed to reflect the source resume.
6. DO write strong, concise bullet points that match the tone of the template bullets.
   Use `<strong>Category Label:</strong>` inside `<li>` tags, just as the template does.
7. If the source resume has more or fewer jobs than the template, add or remove
   `<table class="full-page-table">` + `<ul>` blocks accordingly.
8. For the contact line, preserve the `<span class="spacer">|</span>` separators
   between email, phone, and location.
9. Output ONLY the HTML document. Start your response with `<!DOCTYPE html>` and end
   with `</html>`. No preamble, no explanation, no markdown fences.

## Tailored Fields Take Precedence

When the source data includes any of the five `tailored_*` fields below (emitted by `ResumeTailoringSubgraph` for a specific job), they OVERRIDE the corresponding fields on the base profile:

| Tailored field | Overrides | Render rule |
|---|---|---|
| `tailored_summary` | `base.background.summary` | Put this exact text inside the `<p>` after `<h2>Summary</h2>`. |
| `tailored_career_history` | `base.background.careerHistory` | Use this list for every Experience entry. The `bullets` array is the authoritative bullet text. |
| `tailored_projects` | `base.projects` | Use this list for the Independent Projects section. Omit the section if the list is empty. |
| `tailored_skill_groups` | the six fixed `base.skills.*` buckets | Render the Skills section as one `<p>` per category in iteration order. Category names come from the map keys (e.g. "Cloud", "Testing Frameworks", "Languages") — do NOT remap to the six fixed bucket names. |
| `jd_matched_skills` | (additive) | Within each skill group, lead with the JD-matched skills (preserving their original spelling). |

If a `tailored_*` field is **missing** from the source data, fall back to the corresponding field on the base profile.

## Using Structured Candidate Data (candidateProfile)

When the pipeline provides a structured `candidateProfile` object (via `input.candidateProfile`),
use it as the authoritative source of candidate information in preference to parsing
HTML resume text. This ensures accurate, up-to-date contact details, employment history,
and skills are always used.

**Priority order:**
1. If `candidateProfile` is available (non-null), extract identity, background, and skills from it.
2. Fall back to the source resume HTML text for additional context not captured in `candidateProfile`.

**Identity fields from `candidateProfile.identity`:**
- `name`, `email`, `phone`, `location`, `linkedinUrl`, `githubUrl`, `portfolioUrl`

**Background fields from `candidateProfile.background`:**
- `targetTitle`, `yearsExperience`, `summary` (professional summary)
- `education` (list of `{degree, school, location, start_date, end_date}` — `end_date` may be null for in-progress)
- `careerHistory` (list of `{role, company, location, start_date, end_date, bullets}` — `end_date` may be null for current roles, `location` is city/state or empty)
- `coreStrengths`, `languages`, `domainExpertise`

**Projects from `candidateProfile.projects`:**
- Same shape as `careerHistory` — list of `{role, company, location, start_date, end_date, bullets}` where `company` is the project name and `location` is typically empty for OSS projects. Render after Career History as a separate "Projects" section if non-empty.

**Skills from `candidateProfile.skills`:**
- `primaryStack`, `mobileAutomation`, `ciCdPlatforms`, `webApiAutomation`
- `infrastructureObservability`, `leadershipAbilities`

When using career history from `candidateProfile`, format each role as:
- Title on first row left, dates on first row right
- Company on second row left, city/state on second row right
- Bullet points as `<li>` items below the table

## Section Mapping

| Template element | What to replace with from source resume |
|---|---|
| `<span class="resume-name">` | Full name |
| `<span class="resume-contact">` | Email (`<a href="mailto:...">...</a>`), phone, city/state |
| `<h2>Summary</h2>` + `<p>` | Professional summary paragraph |
| Each `<table class="full-page-table">` + following `<ul>` | One job entry: title (first row left), dates (first row right), company (second row left), city/state (second row right), then `<ul><li>` bullets |
| `<h2>Education</h2>` section | Degree (first row left), year (first row right), school (second row left), location (city/state) (second row right) |
| Content inside `<div class="page-break">` after `<h2>Skills</h2>` | Skills grouped by category using `<p><strong>Category:</strong> item · item · ...</p>` format |
