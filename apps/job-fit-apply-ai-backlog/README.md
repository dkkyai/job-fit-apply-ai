# job-backlog

The job-tracking dashboard: a Vite + React single-page app that lists the Postgres `tracks`
table and lets you change each job's status. It never talks to the database directly — it
calls the bridge's `GET /api/tracks` and `POST /api/tracks/{id}/status`
(`src/lib/api.ts`).

## Features

- Jobs with company, role, location, remote policy, tech stack and fit score
- Filters by minimum fit score and age; sortable columns
- Status changes (backlog, interested, applied, interviewing, offer, rejected, …) with toasts

## Local development

```bash
npm ci
cp .env.example .env   # VITE_API_BASE_URL = the bridge to talk to
npm run dev            # http://localhost:3001
```

`VITE_API_BASE_URL` defaults to `http://localhost:8765` — on a machine running the stack
that is the **live** bridge, so status changes you make in dev are real. Point it at a test
instance (see [`docs/multi-instance.md`](../../docs/multi-instance.md)) when experimenting.

## Deployment

The `frontend` service in the root `docker-compose.yml` builds this app into an nginx image
served on `127.0.0.1:3030` and exposed over the tailnet with Tailscale Serve.
`VITE_API_BASE_URL` is baked into the bundle at build time from the compose build arg (set
it in the root `.env`).

## Testing

| Command | What it runs |
|---|---|
| `npm run test:unit` | Vitest + Testing Library, with coverage (`coverage/index.html`) |
| `npm run build && npm run test:e2e` | Playwright against `npm run preview` on :8080 — the bridge API is mocked in the spec, so it needs no backend |
| `npm run lint` | ESLint |

Install Playwright's browser once with `npx playwright install chromium`. CI runs the unit
tests; `make verify` at the repo root runs all of the above plus a typecheck.

## Scripts

| Command | Description |
|---|---|
| `npm run dev` | Dev server on :3001 |
| `npm run build` | Production build into `dist/` |
| `npm run preview` | Serve `dist/` on :8080 |
| `npm run lint` | ESLint |
| `npm run test:unit` / `test:watch` | Vitest |
| `npm run test:e2e` | Playwright |
| `npm run test:ci` | Lint, unit tests, build, Playwright |
