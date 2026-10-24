# JobBot

JobBot is the Telegram agent behind **@DkkyAIJobBot**. It handles the buttons on JFAA's high-fit cards and answers when you reply to a card. It is a [Hermes](https://hermes-agent.nousresearch.com) gateway (`docker/jobbot`) plus a small Kotlin service, `jobbot-mcp` (`services/job-fit-apply-ai-jobbot-mcp`), which is its only door into JFAA.

```
Processor ──► Bridge (completed feed: completed_seq, job_url, message_id, is_recruiter, terminal_label…)
                │ GET
                ├──────► Notifier ──sendMessage only──► Telegram @DkkyAIJobBot
                │          card ends with  #J<seq>              │ ▲
                │          buttons carry   <verb>:<seq>          │ │ answers, card edits, replies
                │                                               ▼ │
                │                                  getUpdates (the ONLY reader)
                │                                     jobbot  (Hermes, network `jobbot` only)
                │                                      ├─ jobbot_actions plugin: taps → /plugin/tap
                │                                      └─ agent: JFAA MCP tools (mcp__jfaa__*)
                │                                               │ Bearer JOBBOT_MCP_TOKEN
                └──── GET (route allowlist) ───────── jobbot-mcp  (networks default + jobbot + jobbot-browser)
                      POST only tracks/{id}/events|status,  ├─ pipeline-output (ro), resume/profile YAML (ro)
                      jobs/{id}/tailor                 ├─ poller-secrets (ro): Gmail token, refreshed in memory
                                                       └─ /state/actions.db (taps, undo records, errors)
```

## The rules it is built on

- **One bot, split by direction.** The notifier only calls `sendMessage`; its Telegram client cannot build any other method. The `jobbot` container is the token's **only** `getUpdates` reader. Telegram allows one, and a second reader gets HTTP 409. `make doctor` fails while the retired `pm2 nanobot-jobbot` is online.
- **Alerts never depend on JobBot.** The notifier works with `jobbot` stopped. Without JobBot, cards just have no working action buttons, so leave `NOTIFIER_TELEGRAM_ACTIONS` blank whenever JobBot is down for long.
- **Buttons are code, not prompts.** A tap is decided by `jobbot-mcp`'s `TapService`, which is deterministic Kotlin. The model never sees a tap unless a verb hands work to it (Reply, later).
- **The model's tools are narrow.** Its Telegram toolsets are `web`, `memory`, `clarify`, `session_search` and the JFAA MCP server (`mcp__jfaa__*`), which has up to 26 tools:
  - **Read:** `get_job`, `list_high_fit`, `read_job_file`, `list_tracks`, `get_profile`, `get_track_timeline`, plus `get_job_email` and `get_reply_draft` with Gmail, and `get_alert_template` with a template file.
  - **Tracks:** `add_track_note` and `set_track_status`. They append to a job's history in the bridge's `track_events` as `source=jobbot`, and only on a track matched by `track_id` or `artifact_url`, never a fuzzy match.
  - **Gmail:** `write_reply_draft` (saves a draft, never sends) and `request_send_approval` (posts a ✅ Send preview; see Sending replies). Registered only when the Gmail token and credentials files are set.
  - **jobbot-cargo:** ten `cargo_*` tools over Richard's private packet repo (see Application packets). Registered only when `JOBBOT_CARGO_TOKEN_FILE` is set.
  - **Card template:** `preview_alert_template`, `update_alert_template` and `revert_alert_template` (see Card template). Registered only when `JOBBOT_TEMPLATE_FILE` is set.

  Hermes's terminal, file, browser, code-execution, delegation, cron and skill-editing toolsets are disabled. Config, persona and plugins are re-seeded from the image on every start (`docker/jobbot/seed.py`).
- **Untrusted content is data.** Job postings, reports quoting them, and emails never become instructions. File text from `read_job_file` is prefixed with a notice saying so.

## Card buttons

The notifier sends a verb only when it is listed in `NOTIFIER_TELEGRAM_ACTIONS` **and** the event qualifies. `jobbot-mcp` re-checks the same rules on every tap. Both sides test against `docker/jobbot/contract/button_eligibility.json`.

| Verb | Shown when | Tap does |
|---|---|---|
| `apply` | the job has a `job_url` and was tailored (`pipeline_action` TAILOR). A skipped job, e.g. pay-gated, has no tailored resume to upload | with `JOBBOT_APPLY_ENABLED`: fills the application in JobBot's browser and posts it under **✅ Submit / ✖ Discard** (see Apply below). Otherwise it replies "Not implemented yet" |
| `reply` | `is_recruiter` and a source `message_id` | posts the reply draft (the poller's, or one built from JFAA's `draft_text`, with the resume attached) under **✅ Send / ✖ Cancel**. If neither exists, the agent writes one |
| `archive` | a source `message_id` the poller left in the inbox | removes `INBOX` from that email only; the button becomes **↩ Undo archive** (`undo:<seq>`), which restores exactly the labels it removed. Mail already out of the inbox, archived by Muse for example, gets "Already out of the inbox." |
| `tailor` | the job was offered (`pipeline_action` OFFER): a fit from `FIT_THRESHOLD` up to `AUTO_TAILOR_MIN_SCORE`, scored but not tailored up front | asks the bridge to tailor it (`POST /api/jobs/{job_id}/tailor`, the only queue write JobBot makes). The resume arrives as a new card a few minutes later; the button goes away. A second tap answers "Already tailoring" or "Already tailored" |

`callback_data` is `<verb>:<completed_seq>`. The seq is the bridge's id for the completion, so nothing is registered before the send.

**Every tap is checked in this order:**
1. The verb is known.
2. The user is in `JOBBOT_TELEGRAM_ALLOWED_USERS`. Blank means nobody.
3. The card is at most 7 days old (`JOBBOT_CARD_TTL_DAYS`).
4. The verb is in `JOBBOT_ACTIONS`.
5. The seq resolves on the bridge.
6. The event qualifies for the verb.

Then the verb's handler runs. With `JOBBOT_DRY_RUN=true`, it stops after the checks, logs the tap, and toasts `[dry run] Would …`.

`/jdstatus` shows the mode, the handled verbs, the latest job, pending actions, recent actions and recent errors. Inbox sweeps are not JobBot's: Muse still does them.

## Skipped high fits (e.g. pay-gated)

A job can score at or above `FIT_THRESHOLD` and still be skipped by a hard gate: posted pay more than 40% below the profile's minimum (`minimum_total_compensation`; `minimum_contract_rate_hourly` for hourly contract pay; hourly pay on a full- or part-time role is annualized at 2,080 hours and compared with `minimum_total_compensation`), or an onsite or hybrid office outside Washington. It still gets a card, with three differences:
- The card adds a **Skipped:** line, e.g. "Skipped: Posted pay (max $85K) is more than 40% below the $145K target". The text comes from the completed feed's `skip_reason`. The line also appears under an edited card template.
- **View Report** opens the scoring notes: `report.md` beside `score_fit.txt`, published at `artifact_url`. There is no **View Resume**, because nothing was tailored.
- There is no **Apply**. An Apply tap on an older card is refused, with the reason.

## Apply: filled by code, submitted only by ✅ Submit

**How it works**
- **Tap Apply.** `jobbot-mcp` queues a fill. Fills run one at a time.
- **Model-planned, code-executed.** The fill loop (`FillAgent`) asks the model (`JOBBOT_FILL_MODEL`, which defaults to `JOBBOT_MODEL`) for symbolic steps on element ids, and executes them in **JobBot's browser**: the `jobbot-browser` container, a headed Chromium 154 with a persistent profile.
- **Review card.** When the form is ready, JobBot posts a screenshot and every filled field (passwords masked) under ✅ Submit / ✖ Discard. The screenshot is the whole page, so it is fitted to Telegram's photo limits first (width + height ≤ 9,000, sides within 15:1, file ≤ 9 MB; a very tall page keeps its top). If Telegram refuses the photo anyway, the message and its buttons go out without it and the screenshot follows as a file, so a hand-off or review is never lost for want of a picture.
- **Submit.** ✅ Submit clicks the site's submit button **only** if the form still matches what the card showed (field fingerprint) and exactly one submit control exists. It then sets the track to `applied` and adds an `application_submitted` event.
- **Review window.** A filled form waits **4 h** (`JOBBOT_APPLY_REVIEW_TTL_HOURS`), with a reminder at 3 h. After that it expires and nothing is submitted.
- **Hand-offs.** A CAPTCHA, phone or ID check, a sign-in Google won't auto-complete, or a question the profile can't answer is posted with the viewer link and **▶ Continue**.
- **No mouse fights.** The loop doesn't touch the page while Richard works in the viewer.

**Hard limits, in code (`SitePolicy`, `FillAgent`)**
- **Never submits.** A click on any submit-type control is refused.
- **Navigation is limited.** The page may only go to:
  - the job's own domain
  - known ATS and job-board domains
  - a domain carrying the company's name
  - Google's sign-in and consent pages

  Gmail, Drive and Google account settings are blocked at the network layer, because the browser holds a live Google session for dkkytech@.
- **Accounts.** "Sign in with Google" wherever a site offers it, enforced in code. The password action is refused on such a site unless it already has a password account. The snapshot finds Google's embedded button (an `accounts.google.com/gsi/button` iframe) as well as a site's own "Continue with Google" control. JobBot follows the Google popup, or Chrome's FedCM account dialog, by rule until it closes. Sites without Google get an account as `JOBBOT_ACCOUNT_EMAIL` with a **per-site generated password**.
  - The password is saved `pending` in `jobbot-secrets/site-credentials.json` (mode 600, read only by jobbot-mcp) **before** use, and activated once the fill gets through.
  - The model never sees a password: the snapshot never exposes input values as text, and every prompt is scrubbed of passwords used in the run.
  - `/jdaccounts` lists the accounts.
- **Google consent.** Approved automatically only for basic sign-in scopes (name, email, profile). The account's own address on the screen doesn't count as a Gmail scope. Anything else is handed to Richard.
- **After a restart.** A fill waiting on a hand-off or review loses its tab when jobbot-mcp restarts. ▶ Continue then expires it, and the card's Apply button starts a fresh fill.
- **Verification email.** Codes and links come only from the site's (or its ATS's) own sender, and links must point back at the site.
- **CAPTCHAs** are never solved. One a person would have to solve (a visible "I'm not a robot" checkbox, an open image challenge, Turnstile, hCaptcha) is handed off. An **invisible** reCAPTCHA is not: it scores the visitor in the background and asks nothing, and Ashby, Greenhouse and Lever forms all carry one. The page script reports each widget's address, size and whether it is on screen, and `CaptchaPolicy` decides, ignoring `size=invisible` badges, frames parked off-screen, 1-pixel trackers and submit buttons that merely carry a site key.

**JobBot's browser** (`docker/jobbot-browser`, profile `jobbot-browser`; called `apply-browser` before 2026-10-02)
- **Image:** `linuxserver/chromium`, pinned by digest. Its Selkies web viewer streams over WebRTC/websockets.
- **CDP:** socat exposes Chromium's DevTools on `:9223`, reachable only on the private `jobbot-browser` network (jobbot-browser + jobbot-mcp). DevTools rejects non-IP Host headers, so jobbot-mcp resolves the address first.
- **Google session:** a managed policy turns browser sign-in off (`BrowserSignin: 0`, `SyncDisabled`). Without it, this Chromium (no Google API keys) signed dkkytech@ out of Google on every restart: DICE failed to fetch a browser token after each web sign-in, and the startup reconcile then cleared the web session. Sign in on the web (accounts.google.com) only. Doctor checks the policy is present.
- **Viewer:** published on `127.0.0.1:3200` behind `JOBBOT_BROWSER_PASSWORD` (user `JOBBOT_BROWSER_USER`, default `richard`). `scripts/setup-tailscale-serve.sh` serves it as `https://<tailnet-name>:3200`. The container **refuses to start** without the password.
- **Profile lock:** cleared at init. A stale `SingletonLock` from a previous container made Chromium skip DevTools; this was caught in the spike.
- **Accepted risk:** the image runs Chromium with `--no-sandbox`. The container is the boundary: it has its own network, no host mounts beyond its profile, and a 3 GB memory limit.

**First-time setup (Richard)**
1. Set `JOBBOT_BROWSER_PASSWORD` and `JOBBOT_BROWSER_VIEWER_URL` in the root `.env`, and add `jobbot-browser` to `COMPOSE_PROFILES`. The old names (`APPLY_BROWSER_*`, `JOBBOT_APPLY_VIEWER_URL`, profile `apply`) still work, and doctor warns about them.
2. Run `scripts/setup-tailscale-serve.sh`.
3. Open the viewer and sign in to Google as dkkytech@ once. That also covers 2-step verification.
4. Set `JOBBOT_APPLY_ENABLED=true`.

## Apply with your own resume

Send JobBot a resume from your machine and it applies with that file instead of the tailored one. Two ways, both in Telegram, both with the word **apply** in the caption:

| You send | Caption | What happens |
|---|---|---|
| A PDF or .docx **as a reply to a job's card** | `apply to this` | Applies to that card's job. The card's `#J` reference says which |
| A PDF or .docx **with a job link** | `apply https://jobs.lever.co/acme/123` | Applies at that link. No JFAA card needed |

If you reply to a card *and* put a link in the caption, the card wins. Without "apply" in the caption the file goes to the agent as usual.

**How it runs.** The `jobbot_actions` plugin catches the document before Hermes does and posts it to `jobbot-mcp` (`POST /plugin/apply-resume`). From there it is the same fill as the Apply button: `FillAgent` in JobBot's browser, a review card, and nothing submitted until **✅ Submit**. The model never sees the file and the agent never sees the message.

**What is different from the Apply button**
- **Your file is uploaded as-is**, under its own name (`Richard Hatcher - Senior SDET.pdf`, or `.docx` with the Word content type).
- **Answers still come from `candidate_profile.yaml`** (and `resume.yaml`). The model cannot read your file, so it answers only from the profile, and it is told so. The tailored resume and its cover letter are dropped for this application, because they describe a different resume.
- **No cover letter.** If a form requires one, JobBot hands off with ▶ Continue.
- **Skipped jobs work.** A pay-gated or otherwise skipped card has no Apply button, but a resume reply to it applies, because you chose the file. It still needs a posting URL.
- **The review card says so:** `Resume: <file name> (your file, not the tailored one). No cover letter.`

**Checks, in code, before anything is filled**
1. You are in `JOBBOT_TELEGRAM_ALLOWED_USERS`.
2. For a card reply: the card is at most 7 days old (`JOBBOT_CARD_TTL_DAYS`) and the job resolves on the bridge.
3. Apply is enabled (`apply` in `JOBBOT_ACTIONS`, `JOBBOT_APPLY_ENABLED=true`). Otherwise JobBot says it can't use the file; it is not silently dropped.
4. **Only PDF and .docx**, at most 10 MB. The bytes must really be one: a PDF has a `%PDF-` header; a .docx is a zip with `word/document.xml`, and one carrying `word/vbaProject*` (macros) is refused. Names lose any path and anything that is not plain text.
5. For a link: `http` or `https` only, no username or password in it, and never an IP address, `localhost`, a single-word host or a `.local` / `.internal` / `.lan` name. The apply browser's own network rules (`SitePolicy`) still apply once it is there.

With `JOBBOT_DRY_RUN=true` it runs these checks and stops.

**Same job twice.** A second file for a job that is already being filled, waiting on you, or waiting for review is not used, and JobBot says so (Discard the open one, then send again). Identity is the posting URL, so a link and a card for the same posting count as one job.

**Link applications have no JFAA track.** Submitting one posts `Submitted: Job at <site>` but does not touch a track, because none exists. `/jdstatus` lists them as `(not a JFAA job)`.

**Delivery.** The plugin acknowledges an outbox item only once it has been posted. A failure (Telegram down, a refused message) is retried on the next poll, up to 10 attempts, then logged as an error and dropped so one bad item cannot stall the queue. A failing item never holds up the ones behind it.

**Not supported:** sending the file and the link in two separate messages; a `.doc`, `.pages` or `.rtf`; a second attachment in the same message.

## Application packets (jobbot-cargo)

`jobbot-cargo` is a private GitHub repo (`dkkyai/jobbot-cargo`) of application packets: `applications/<company>/<role>/vNN/` with `APPLICATION_PACKET.md` (status READY/DRAFT, apply URL, the file to upload), `application-answers.md`, `job-posting.md`, the résumé files and an append-only `STATUS.md`. JobBot reads all of it and writes exactly one thing: lines appended to `STATUS.md`.

**What you can ask in Telegram:** "how many are READY", "how many are in DRAFT", "what haven't we applied to", "send me the latest résumé for Apple", "apply to all the ready packets", "apply to the BECU one", or any question about the repo's contents.

| Tool | Does |
|---|---|
| `cargo_list` | Counts by status and by application state, and one row per role with whether it can be applied to now |
| `cargo_get_packet`, `cargo_files`, `cargo_read_file`, `cargo_search` | Read anything in the repo. A `job-posting.md` is marked as untrusted web text |
| `cargo_send_resume`, `cargo_send_file` | Send a file to your Telegram as a document. A company with several roles is not guessed at |
| `cargo_append_status` | The only write: one line on a packet's STATUS.md, committed and pushed |
| `cargo_apply`, `cargo_apply_ready` | Start applications from READY packets (below) |

**How an application runs.** The same fill as a pasted link: JobBot's browser opens the packet's apply URL, uploads the packet's own résumé under its own name, and answers from `application-answers.md` for the application's questions and from `candidate_profile.yaml` for demographics, EEO, pay and preferences. Where the answers say `ask`, or a question isn't covered, it hands off to you. It ends at **✅ Submit / ✖ Discard**; the model can start an application but never submit one. Fills run one at a time, so "apply to all the ready packets" posts one review card after another.

**STATUS.md, written by code.** Each step of a packet application appends a line, commits as `JobBot` and pushes: `in_progress` when the form is opened, `parked_at_review` when it is filled and waiting for your tap, `completed` when you tap Submit and the site confirms, and `blocked` for a hand-off, a failed fill, a Discard, an expired review or an unconfirmed submit. Earlier lines are never edited. A line the model writes with `cargo_append_status` is limited to the same four states, and only to the newest version of a role (an older version takes no lines at all); `completed` and a reopened packet need your own words (`approval_quote`), and a line cannot be forged with an embedded newline.

**Who may be applied to** (the repo's own rules, in `CargoEligibility`):
- READY and `not_started`, or `in_progress` for more than 2 h. A fresher `in_progress` line may be another agent filling the same form, so it is left alone whatever anyone says.
- **`completed` never**, whatever anyone says.
- Needs your say-so: a **DRAFT**, `blocked` / `parked_at_review`, or a role with a **⚠ older version** already worked on. JobBot asks you; only your exact words, passed as `approval_quote` and written into STATUS.md (`draft approved: "…"`), unlock it. `cargo_apply_ready` never applies to these; it skips and lists them with the reason.
- The packet's résumé must match the hash its files table lists, and be a real PDF or .docx. A packet with no apply URL is never started, and its URL gets the same check as a pasted link (public http/https only; never an IP address in any form (`127.1`, `0x7f.0.0.1` and `2130706433` too), `localhost`, a `.internal` name or a link with a password in it).
- A STATUS.md line JobBot can't read (a `- …` item that isn't `- <time> | <state> | <note>`) means its real state is unknown, so the packet needs your say-so. A `completed` line is recognised whatever its note or spacing, in any case.
- Two packets with the same apply URL count as one job.

**Dry run.** With `JOBBOT_DRY_RUN=true`, `cargo_apply` and `cargo_apply_ready` run every eligibility, hash, URL and approval check and then answer `[dry run] Would apply to … with …` (under `would_start`, never `started`). No browser is opened, no fill is queued, no review card is posted, and nothing is written or pushed to STATUS.md; each packet leaves one `dry_run` action record that never blocks the real application. `cargo_append_status` returns the line it would append. Reads, searches and `cargo_send_*` are unaffected, since they change nothing for anyone else. `make doctor` warns while it is on.

**Token.** A fine-grained GitHub token (this repo only, Contents read/write) lives in `<jobbot-secrets>/cargo-github-token`, mode 600, mounted into `jobbot-mcp` only. The `jobbot` container never has it, so the model cannot read it. `git` gets it as an `http.extraHeader` through its environment for network commands only, so it is not in argv, `.git/config` or the remote URL, and it is scrubbed from any output. Rotate it by replacing the file; it is read on every network command, so no restart is needed.

**Checkout.** `<data root>/jobbot-cargo`, mounted at `/cargo` in `jobbot-mcp`. It is refreshed at most once a minute (and before every apply and write). If GitHub is unreachable the tools say so and serve the last copy. STATUS.md is merged with git's union rule in this checkout (`.git/info/attributes`, not in the repo), so another agent appending to the same file at the same moment keeps both lines instead of conflicting. A conflict nothing can merge is aborted, never left half-rebased. A STATUS line that could not be pushed stays committed locally and is pushed by the next sync. `make doctor` checks the token file, its mode and the last sync.

**Limits.** The model has no shell and no file edits: it cannot change a résumé, an answers file or anything else. Only the PDF is uploaded; the .docx and .txt copies are for you (`cargo_send_resume` with `format`). `INDEX.md` is never read for state, because it is only as current as its last sync.

## Card template

Richard can ask JobBot to change how high-fit cards look. Without a template, the built-in card reads:
```
Sparksoft: Automation Engineer (63)
Remote · $80K–$85K · via jobright.ai

Fit:
• Web/API automation tools match
• Healthcare domain expertise overlap
• Core tech stack overlap

Gap: Seniority level is a step down
Skipped: Posted pay (max $85K) is more than 40% below the $145K target
#J7708
```
The company links to the posting and the title to the report. A missing company or title reads "Unknown". Each line is left out when its value is unknown, and the blank lines only frame the Fit block.
- **Tools:** `get_alert_template` and `preview_alert_template` (with a real `#J` or a sample), then `update_alert_template`, which `revert_alert_template` undoes.
- **Storage:** the template lives in `jobbot-templates/high-fit.html`. jobbot-mcp writes it; the notifier mounts it read-only and re-reads it per card once `NOTIFIER_TELEGRAM_TEMPLATE_FILE=/templates/high-fit.html` is set.
- **Rules:**
  - `{placeholder}` substitution only: `{company} {title} {company_link} {title_link} {score} {action} {ref} {details} {strengths} {gap}`
  - `{details}` is location · remote policy · salary · via source (whatever is known), `{strengths}` is the top three as bullets, and `{gap}` is the first gap
  - a line whose placeholders all render empty is dropped, so `<b>Gap:</b> {gap}` disappears when there is no gap
  - values are HTML-escaped
  - links are built in code, and `<a>` is refused
  - tags allowed: `b i u s code pre blockquote`, balanced
  - `{ref}` is required, and the template is at most 1000 characters

  Both sides test against `docker/jobbot/contract/alert_template_vectors.json`.
- **Never costs a card:** a missing or invalid template falls back to the built-in format, and a template Telegram rejects is re-sent once in the built-in format.

## Sending replies: the ✅ Send tap is the only way

The model has **no send tool**.
- **Model tools:** it can `write_reply_draft`, which only ever saves a Gmail draft, and `request_send_approval`.
- **Preview:** `request_send_approval` stores a pending approval with a SHA-256 fingerprint of the draft (recipients, subject, text, attachment names). The plugin's `post_tool_call` hook then posts that exact preview with `send:<id>` / `cancel:<id>` buttons.
- **Checks at Send.** A tap on ✅ Send is checked again in `jobbot-mcp`:
  - the allowlist
  - the preview's age
  - `JOBBOT_SEND_ENABLED` (off by default)
  - `JOBBOT_MAX_SENDS_PER_DAY`
  - the draft still matches the fingerprint
  - every recipient is already in the thread
  - Richard hasn't replied in the thread since, for example via Muse

  Then `drafts.send`. A second tap answers "Already sent."
- **Why not Hermes's approval gate?** Hermes's `pre_tool_call` → `approve` gate is skipped by the gateway's `/yolo` toggle, and no config locks `/yolo`. A deterministic tap is immune to both the model and `/yolo`.

## Gmail (Archive / Undo / get_job_email)

`jobbot-mcp` uses **the poller's token**: the same dkkytech@ account and scopes, since neither is useful without the other.
- **Mount:** `poller-secrets` is mounted read-only as a directory. A poller re-auth recreates the token file, and the new file is picked up without a restart.
- **Never written:** the token file is never written, deleted or re-authorized here. Access tokens stay in memory.
- **Dead token:** `invalid_grant` puts `/jdstatus` on `Gmail: NEEDS RE-AUTH`. The fix is the poller's own `docker compose run --rm poller --reauth`.
- **Production app:** the OAuth app is in Production, so tokens don't expire weekly.
- **What it touches:** Archive and Undo touch only the `INBOX` label. `TRASH` and `SPAM` are refused in code.

## Configuration (root `.env`)

| Variable | Meaning |
|---|---|
| `COMPOSE_PROFILES` | Must include `jobbot` (`intake,jobbot`). |
| `JOBBOT_TELEGRAM_BOT_TOKEN` | The same value as `NOTIFIER_TELEGRAM_BOT_TOKEN`. |
| `JOBBOT_TELEGRAM_ALLOWED_USERS` | Your Telegram user id. |
| `JOBBOT_TELEGRAM_HOME_CHANNEL` | Your chat id. |
| `JOBBOT_MCP_TOKEN` | Shared secret between `jobbot` and `jobbot-mcp` (`openssl rand -hex 32`). |
| `JOBBOT_FILL_MODEL` | The model that plans application fills. Defaults to `JOBBOT_MODEL`; set it to pin the fill model separately, so tuning the chat model never changes how applications are filled. Currently `deepseek-v4.1-flash:cloud`. |
| `JOBBOT_MODEL` | Chosen by the env-llm-tuner skill. Default `deepseek-v4.1-flash:cloud`. Ollama Cloud ids end in `:cloud`; the bare name 404s. The fallback is oMLX `Qwen3.6-35B-A3B-OptiQ-4bit`. |
| `JOBBOT_ACTIONS` | Verbs JobBot acts on. `NOTIFIER_TELEGRAM_ACTIONS` must be a subset. |
| `JOBBOT_DRY_RUN` | `true` runs every check and logs what would happen, but does nothing: taps, resume uploads, `cargo_apply`, `cargo_apply_ready` and `cargo_append_status` (see below). Reading and sending files still work. |
| `JOBBOT_SEND_ENABLED` | Kill switch for ✅ Send (default `false`). |
| `JOBBOT_MAX_SENDS_PER_DAY` | Daily cap on sends (default 10). |
| `NOTIFIER_TELEGRAM_ACTIONS` | Verbs the cards show. |

## Runbook

**Deploy.** Use a release worktree at the merge commit, with the three personal-file symlinks, as for every service. Run from that worktree:

```bash
docker compose --env-file ~/projects/job-fit-apply-ai/.env --profile jobbot up -d --build --no-deps jobbot-mcp jobbot
```

**Check.**
- `make doctor` has a JobBot section.
- `docker logs jobfit-jobbot` should show `[jobbot-seed] wrote config.yaml`, `Wired native handlers from plugin 'jobbot_actions'`, `MCP server 'jfaa' (HTTP): registered 26 tool(s)` (22 without Gmail), and `jobbot-cargo: up to date`, and `Connected to Telegram (polling mode)`.
- Gateway logs live in `${JFAA_DATA_ROOT}/jobbot/logs/`.

**One-shot agent test (no Telegram):**

```bash
docker exec jobfit-jobbot hermes chat -q "Look up JFAA job #J7650 and say why it fits."
```

**Rollback to the old reply bot:**

```bash
docker compose --env-file ~/projects/job-fit-apply-ai/.env --profile jobbot stop jobbot
```

```bash
pm2 start nanobot-jobbot
```

Then set `NOTIFIER_TELEGRAM_ACTIONS=` (blank) and recreate the notifier, so cards stop showing buttons nothing handles.

## Tests

| Level | Where |
|---|---|
| Notifier unit + contract | `services/job-fit-apply-ai-notifier`: `./gradlew test` |
| jobbot-mcp unit, fake-bridge HTTP, real MCP client over HTTP | `services/job-fit-apply-ai-jobbot-mcp`: `./gradlew test` |
| Hermes plugin (stubbed Telegram/Hermes) + seed, including the plugin against a real local HTTP backend | `docker/jobbot`: `python -m pytest tests` |
| **Plugin wire contract**: one JSON file both sides test against (tap and résumé requests, tap responses, outbox items) | `docker/jobbot/contract/plugin_wire.json`; Kotlin `PluginWireContractTest`, Python `test_core.py` / `test_handler.py` |
| **jobbot-cargo**: packet parsing and eligibility, `CargoRepo` against a real local git remote (clone, pull, append, push, conflicts, token handling), the tools | `services/job-fit-apply-ai-jobbot-mcp`: `PacketsTest`, `CargoRepoTest`, `CargoToolsTest`, `ApplyServiceCargoTest` |
| **End to end**: a real MCP client over HTTP, the real server, a git remote and `ApplyService` with a fake browser, from "apply to the ready packets" to the pushed STATUS trail | `CargoEndToEndTest` |
| Tool allowlist matches the tools jobbot-mcp registers; the skill files name only real tools | `docker/jobbot/tests/test_seed.py` |
| **Live jobbot-browser** (opt-in): real Chromium over CDP + a fixture job site + a scripted model | `JOBBOT_BROWSER_CDP_URL=http://127.0.0.1:19223 FIXTURE_HOST=host.docker.internal ./gradlew test --tests '*ApplyBrowserLiveTest*'` |
| Compose isolation (networks, mounts, ports, profile) | `make compose-data-root-test` |
| Buttons at the wire (Bridge → Processor → Notifier) | `make e2e` |

## Known gaps

- **Packet applications upload only the PDF.** A form that cannot take a PDF, or asks for pasted résumé text, hands off to you.

- **Your own resume is never read.** An uploaded PDF or .docx goes to the site untouched, and answers come from the profile. If the file says something the profile doesn't, the form follows the profile.

- **Memory is Hermes's built-in** (`MEMORY.md` / `USER.md`). The planned separate Hindsight bank needs a provider that isn't in the `v2026.9.24` image.
- **Workflows are in the persona, not skills.** They live in `docker/jobbot/skills/*.md` and are appended to the persona at boot. Hermes's skills toolset is disabled, because it would let the agent write its own skills.
