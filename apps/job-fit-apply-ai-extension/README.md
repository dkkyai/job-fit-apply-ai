# Job Fit Apply AI — JD Capture (Chrome Extension)

Capture the job posting you're viewing — **in your own authenticated browser session** — and
send its rendered content to the Job Fit Apply AI pipeline, which extracts the job description
server-side and generates a tailored resume + cover letter.

## Why capture instead of scrape?

The server-side scraper can't see pages behind a login (LinkedIn, Workday, internal ATS portals).
Your browser already rendered the page with your session, so the extension captures the visible
content and ships **that** to the Bridge. The Processor then LLM-extracts the JD from the captured
text — no per-site extractors, works on any layout, and auth-gated pages just work.

## How it works

```
You click the extension on a job page
        │
        ▼
Extension captures visible content  ──►  POST /api/pages { url, title, text }   (Bridge)
(Readability, falling back to innerText)                     │
                                                             ▼
                                       enqueued as JD_PAGE_RAW
                                                             │
                                                             ▼
Processor claims it → LLM-extracts the JD (dual-mode ScrapeJdNode, no fetch)
        → scores fit → tailors resume → cover letter → PDF → uploads artifacts
                                                             │
Extension polls GET /api/jobs/{id}  ◄────────────────────────┘
        → on "done", offers the resume PDF + cover letter for download
```

## Install (unpacked, development)

1. Open `chrome://extensions`
2. Enable **Developer mode**
3. **Load unpacked** → select this folder (`apps/job-fit-apply-ai-extension`)
4. Pin the 🎯 icon to your toolbar

`vendor/Readability.js` (Mozilla Readability, vendored) ships with the extension — no build step.

## Usage

1. Open a job posting (any site, including ones you're logged into).
2. Click the 🎯 toolbar icon → **Capture this job page** (or right-click → *Send this job page to
   Job Fit Apply AI*).
3. Watch the pipeline: **Capture → Submit → Generate → Done**.
4. Download the resume PDF and cover letter when they're ready.

If the page isn't a job posting (or the fit is too low to tailor), the extension says so instead of
producing documents.

## Configuration — `config.js`

```javascript
export const BRIDGE_API_URL       = 'http://…:8765'; // Bridge endpoint (tailnet)
export const POLL_INTERVAL_MS     = 5_000;           // status poll cadence
export const POLL_TIMEOUT_MS      = 300_000;         // 5-minute hard timeout
export const MAX_CAPTURE_CHARS    = 100_000;         // payload cap (server truncates for the LLM)
export const MIN_READABILITY_CHARS = 400;            // below this, use innerText instead of Readability
export const MIN_CAPTURE_CHARS    = 200;             // must match the bridge's /api/pages floor
```

## Bridge API contract

### POST `/api/pages`

Submit a captured page. `text` must be ≥ 200 chars (else `422`). Dedup is by `url`.

```json
{ "url": "https://…/job/123", "title": "Senior SDET — Acme", "text": "<visible page text>" }
```

Response `202` (new) / `200` (deduped): `{ "job_id": "…", "status": "pending", "deduped": false }`.

### GET `/api/jobs/{job_id}`

`status` is `pending | claimed | done | error`.

- **pending / claimed** → status only (the extension synthesizes progress text; there is no
  `progress_message` field).
- **done** → `fit_score`, `pipeline_action`, and `artifacts { resume_pdf, cover_letter_txt }`
  (URLs are **host-relative** — the extension prepends `BRIDGE_API_URL`). A `done` job with **no**
  artifacts means the page wasn't a usable job posting.
- **error** → `error` message.

## Project structure

```
manifest.json          # MV3 manifest (no content_scripts — capture is injected on demand)
config.js              # constants
background.js          # service worker: capture → POST /api/pages → poll → artifacts
vendor/Readability.js  # Mozilla Readability (vendored, exposes globalThis.Readability)
popup/                 # popup UI (html/css/js)
icons/                 # 16/32/48/128 icons
tests/                 # jest tests (background.test.js, setup.js)
```

Capture is performed by injecting `vendor/Readability.js` then a self-contained function via
`chrome.scripting.executeScript` on the active tab (`activeTab` permission — no `<all_urls>`).

## Testing

```bash
npm install
npm test
```

## Related

- [`services/job-fit-apply-ai-bridge`](../../services/job-fit-apply-ai-bridge) — Bridge API (`POST /api/pages`, queue, artifact store)
- [`services/job-fit-apply-ai-pipeline`](../../services/job-fit-apply-ai-pipeline) — Processor: `JD_PAGE_RAW` extraction (dual-mode `ScrapeJdNode`) + resume/cover-letter generation

## License

MIT.
