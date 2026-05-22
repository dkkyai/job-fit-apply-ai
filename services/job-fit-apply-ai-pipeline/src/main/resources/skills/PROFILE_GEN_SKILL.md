# PROFILE_GEN_SKILL — Resume → Candidate Profile JSON

You will receive a plain-text version of a candidate's resume. Your task is to
return a single JSON object that conforms exactly to the `CandidateProfile`
schema below. The output is consumed directly by Jackson — anything other
than valid JSON will fail.

## Output rules

1. Output ONLY a single JSON object. No prose, no explanation, no markdown
   fences, no leading or trailing text. Start with `{` and end with `}`.
2. Use the EXACT field names and shape shown in the schema. Snake-case keys.
3. Dates: prefer `YYYY-MM` (e.g. `"2024-09"`). If only a year is given, use
   `YYYY` (e.g. `"2024"`). If a role or degree is current/in-progress, set
   `end_date` to `null`. Do NOT invent dates that are not in the resume.
4. **Never invent skills, employers, or accomplishments.** If a field is not
   present in the resume, follow the "missing data" rules below.
5. Bullets: copy each accomplishment as a single string in the `bullets` array
   for that role. Preserve quantified specifics (numbers, percentages, dollar
   amounts) verbatim. Do not paraphrase or rewrite.

## Missing data — what to do per field

- `identity.linkedin_url`, `github_url`, `portfolio_url`, `website_url`:
  set to `null` if not in the resume header. Do not guess.
- `identity.location`: copy verbatim from the header. If only a city is given,
  emit just the city.
- `background.target_title`: if the resume names a target role, use it.
  Otherwise output `"__TODO__: target role title (e.g. 'Staff SDET')"` —
  the user fills this in next.
- `background.summary`: if the resume has a "Summary" / "Profile" /
  "Objective" section, copy it. Otherwise synthesise a 2–3 sentence summary
  strictly from the resume's stated experience and titles. Do NOT add skills
  or claims that are not in the resume.
- `background.years_experience`: integer estimate based on career-history
  date spans. If unable to compute, use `0`.
- `background.education`: list of `{degree, school, location, start_date, end_date}`.
  If the resume only gives a graduation year, set `end_date` to that year and
  `start_date` to `""` (empty string).
- `background.career_history`: list of `{role, company, location, start_date,
  end_date, bullets}`. The most recent role goes first. Use `end_date: null`
  for the current role. `location` is the city/state where the role was
  based (e.g. "Seattle, WA" or "Remote") — copy verbatim from the resume,
  or use `""` (empty string) if the resume does not list one.
- `projects`: same shape as `career_history`. `company` is the project name,
  `role` is the candidate's role on the project. `location` is typically
  `""` for OSS / personal projects. Empty list `[]` if no separate Projects
  section appears in the resume.
- `skills.*`: bucket every skill the resume mentions into the appropriate
  category. Never invent a skill. Empty list `[]` if a category has no
  matching skills.
- `preferences.*`: this section is filled in by the user, not by you. Use
  the `__TODO__: ...` sentinel strings shown in the schema verbatim for
  every string field; for booleans use `false` and for nullable ints use
  `null`. The user edits these in their `$EDITOR` after you write the draft.

## Schema (the exact shape your JSON must match)

```json
{
  "identity": {
    "name": "string",
    "first_name": "string",
    "last_name": "string",
    "email": "string",
    "phone": "string",
    "location": "string",
    "linkedin_url": "string or null",
    "github_url": "string or null",
    "portfolio_url": "string or null",
    "website_url": "string or null"
  },
  "background": {
    "target_title": "string",
    "years_experience": 0,
    "summary": "string",
    "education": [
      { "degree": "string", "school": "string", "location": "string or null", "start_date": "string", "end_date": "string or null" }
    ],
    "career_history": [
      { "role": "string", "company": "string", "location": "string", "start_date": "string", "end_date": "string or null", "bullets": ["string"] }
    ],
    "core_strengths": ["string"],
    "languages": ["string"],
    "domain_expertise": ["string"]
  },
  "skills": {
    "primary_stack": ["string"],
    "mobile_automation": ["string"],
    "ci_cd_platforms": ["string"],
    "web_api_automation": ["string"],
    "infrastructure_observability": ["string"],
    "leadership_abilities": ["string"]
  },
  "preferences": {
    "willing_to_relocate": false,
    "relocation_notes": "__TODO__: free text about relocation/work-arrangement preferences",
    "visa_status": "__TODO__: e.g. 'US Citizen', 'H1B', 'GC'",
    "visa_sponsorship_required": false,
    "available_to_start_date": "__TODO__: e.g. 'Immediately' or 'YYYY-MM-DD'",
    "notice_period_days": null,
    "preferred_work_arrangement": "__TODO__: e.g. 'Remote', 'Hybrid Seattle', 'Onsite NYC only'",
    "current_base_salary": null,
    "minimum_total_compensation": "__TODO__: target TC (USD, integer-ish, e.g. 180000)",
    "compensation_notes": "__TODO__: any context on comp expectations",
    "open_to_equity_only_roles": false,
    "willing_to_travel": false,
    "travel_percentage": "__TODO__: e.g. '<= 10%'",
    "open_to_contract_roles": false,
    "contract_rate": "__TODO__: target contract rate (USD/hr) if open to contract",
    "additional_notes": null
  },
  "projects": []
}
```
