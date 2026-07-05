# Tests

Unit tests for the Job Fit Apply AI — JD Capture extension (jsdom + mocked Chrome APIs).

## Structure

```
tests/
├── setup.js             # Jest setup: Chrome API mocks (storage, scripting, tabs, notifications, contextMenus)
├── background.test.js   # background.js — capture → POST /api/pages → poll → artifacts
└── README.md            # This file
```

## Running

```bash
npm install
npm test              # run all tests
npm run test:watch    # watch mode
npm run test:coverage # coverage report
npm run lint
```

## Coverage

`background.test.js` covers the service worker's flow:

- **onInstalled** — creates the `jfa-capture` context menu.
- **runtime.onMessage** — `POPUP_TRIGGER` / `GET_JOB_STATUS` / `CLEAR_JOB`.
- **captureAndSubmit** — on-demand page capture (mocked `chrome.scripting.executeScript`), the
  `POST /api/pages { url, title, text }` body shape, the too-little-text guard, and Bridge-unreachable
  handling.
- **pollForCompletion** — `done` → COMPLETE with host-relative artifact URLs absolutized; `done` with
  no artifacts → "not a job posting"; `error` → ERROR.

JD extraction now happens **server-side** (the Processor's dual-mode `ScrapeJdNode`), so there are no
client-side extractor tests here — those live in the pipeline service.

## Writing tests

`loadBackground()` re-`require`s `background.js` after `jest.resetModules()` so each block gets fresh
listener registrations. Drive the async poll loop with fake timers via the `driveOnePollTick()` helper.
