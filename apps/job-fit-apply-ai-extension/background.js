/**
 * background.js  (MV3 service worker, ES module)
 *
 * New model: the extension captures the *rendered, viewable* content of the job page the
 * user is looking at (in their authenticated browser session) and ships it to the Bridge.
 * The Processor LLM-extracts the job description server-side, so there are no per-site
 * extractors here and auth-gated pages (LinkedIn, Workday, …) just work.
 *
 * Flow: capture page → POST /api/pages → poll GET /api/jobs/{id} → download artifacts.
 */

import {
  BRIDGE_API_URL,
  POLL_INTERVAL_MS,
  POLL_TIMEOUT_MS,
  MAX_CAPTURE_CHARS,
  MIN_READABILITY_CHARS,
  MIN_CAPTURE_CHARS,
  STATUS,
} from './config.js';

// ── Setup ───────────────────────────────────────────────────────────────────────

chrome.runtime.onInstalled.addListener(() => {
  // Remove first to avoid duplicate-ID errors when the worker restarts post-install.
  chrome.contextMenus.remove('jfa-capture', () => {
    void chrome.runtime.lastError; // ignore "no such menu item" on first install
    chrome.contextMenus.create({
      id:       'jfa-capture',
      title:    '🎯 Send this job page to Job Fit Apply AI',
      contexts: ['page', 'selection'],
    });
  });
});

// ── Event listeners ─────────────────────────────────────────────────────────────

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  if (info.menuItemId === 'jfa-capture') {
    await captureAndSubmit(tab);
  }
});

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  switch (message.type) {

    // Popup clicked "Capture this job page"
    case 'POPUP_TRIGGER': {
      chrome.tabs.query({ active: true, currentWindow: true }, async ([tab]) => {
        if (!tab) { sendResponse({ ok: false, error: 'No active tab' }); return; }
        await captureAndSubmit(tab);
        sendResponse({ ok: true });
      });
      return true;
    }

    case 'GET_JOB_STATUS': {
      getJobState(message.tabId).then(state => sendResponse(state));
      return true;
    }

    case 'CLEAR_JOB': {
      clearJobState(message.tabId).then(() => sendResponse({ ok: true }));
      return true;
    }
  }
});

// Tab navigated away — clear stale state
chrome.tabs.onUpdated.addListener((tabId, changeInfo) => {
  if (changeInfo.status === 'loading') {
    clearJobState(tabId);
  }
});

// ── Core flow ───────────────────────────────────────────────────────────────────

async function captureAndSubmit(tab) {
  await setJobState(tab.id, { status: STATUS.CAPTURING, pageTitle: tab.title, url: tab.url });

  // 1. Capture the rendered page content
  let capture;
  try {
    capture = await capturePage(tab.id);
  } catch (err) {
    await setJobState(tab.id, { status: STATUS.ERROR, error: `Can't read this page: ${err.message}` });
    return;
  }
  if (!capture || capture.text.length < MIN_CAPTURE_CHARS) {
    await setJobState(tab.id, {
      status: STATUS.ERROR,
      error:  `This page doesn't have enough readable text (only ${capture?.text.length ?? 0} chars).`,
    });
    return;
  }

  // 2. Submit to the Bridge
  await setJobState(tab.id, { status: STATUS.SUBMITTING, jdTitle: capture.title, url: capture.url });

  let jobId;
  try {
    jobId = await submitCapture(capture);
  } catch (err) {
    await setJobState(tab.id, {
      status: STATUS.ERROR,
      error:  `Bridge API unreachable: ${err.message}. Is the server running on the M1 Max?`,
    });
    return;
  }

  // 3. Poll for completion
  await setJobState(tab.id, {
    status:  STATUS.PROCESSING,
    jobId,
    jdTitle: capture.title,
    url:     capture.url,
    progressMessage: 'Queued — Job Fit Apply AI is working…',
  });

  pollForCompletion(tab.id, jobId, capture);
}

// ── Page capture (on-demand injection — no always-on content script) ────────────

async function capturePage(tabId) {
  // Inject Readability into the page's isolated world, then run the capture fn there.
  // `activeTab` grants host access for this tab after the user's click; no <all_urls> needed.
  await chrome.scripting.executeScript({ target: { tabId }, files: ['vendor/Readability.js'] });
  const [injection] = await chrome.scripting.executeScript({
    target: { tabId },
    func:   captureFn,
    args:   [MAX_CAPTURE_CHARS, MIN_READABILITY_CHARS],
  });
  return injection?.result ?? null;
}

/**
 * Runs in the page's isolated world — must be fully self-contained (no closure over
 * extension scope; config values arrive via args). Prefers Readability's main-content
 * extraction and falls back to visible innerText when it under-extracts (e.g. SPA boards).
 */
function captureFn(maxChars, minReadable) {
  let text = '';
  try {
    const Readability = globalThis.Readability;
    if (typeof Readability === 'function') {
      const article = new Readability(document.cloneNode(true)).parse();
      const parsed = article && article.textContent ? article.textContent.trim() : '';
      if (parsed.length >= minReadable) text = parsed;
    }
  } catch (_e) {
    // fall through to innerText
  }
  if (!text) {
    text = ((document.body && document.body.innerText) || '').trim();
  }
  return {
    url:   location.href,
    title: document.title,
    text:  text.slice(0, maxChars),
  };
}

// ── Bridge API calls ────────────────────────────────────────────────────────────

async function submitCapture(capture) {
  const res = await fetch(`${BRIDGE_API_URL}/api/pages`, {
    method:  'POST',
    headers: { 'Content-Type': 'application/json' },
    body:    JSON.stringify({
      url:   capture.url,
      title: capture.title,
      text:  capture.text,
    }),
  });

  if (!res.ok) {
    const body = await res.text().catch(() => '');
    throw new Error(`HTTP ${res.status}: ${body}`);
  }

  const data = await res.json();
  if (!data.job_id) throw new Error('Bridge API returned no job_id');
  return data.job_id;
}

async function pollForCompletion(tabId, jobId, capture) {
  const deadline = Date.now() + POLL_TIMEOUT_MS;

  const tick = async () => {
    if (Date.now() > deadline) {
      await setJobState(tabId, { status: STATUS.ERROR, error: 'Timed out after 5 minutes waiting for Job Fit Apply AI.' });
      return;
    }

    let data;
    try {
      const res = await fetch(`${BRIDGE_API_URL}/api/jobs/${jobId}`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      data = await res.json();
    } catch {
      setTimeout(tick, POLL_INTERVAL_MS); // network blip — retry
      return;
    }

    // Bridge terminal status is "done" (JobStatus enum: pending | claimed | done | error).
    if (data.status === 'done') {
      // A "done" job with no artifacts means the pipeline produced nothing to download —
      // e.g. the page wasn't a job posting or the fit was too low to tailor.
      if (!data.artifacts) {
        await setJobState(tabId, {
          status: STATUS.ERROR,
          error:  data.error || "This page didn't produce a resume — it may not be a job posting.",
        });
        return;
      }
      await setJobState(tabId, {
        status:    STATUS.COMPLETE,
        jobId,
        jdTitle:   data.title   || capture.title,
        company:   data.company || '',
        fitScore:  data.fit_score,
        artifacts: absolutizeArtifacts(data.artifacts), // { resume_pdf, cover_letter_txt }
      });

      chrome.notifications.create(`jfa-done-${jobId}`, {
        type:    'basic',
        iconUrl: 'icons/icon48.png',
        title:   '🎯 Job Fit Apply AI: Documents Ready',
        message: `Resume & cover letter ready${data.fit_score != null ? ` — fit ${data.fit_score}` : ''}.`,
      });
      return;
    }

    if (data.status === 'error') {
      await setJobState(tabId, { status: STATUS.ERROR, error: data.error || 'Job Fit Apply AI returned an error.' });
      return;
    }

    // Still processing (pending | claimed). The bridge has no progress_message field,
    // so synthesize a message from the real status.
    const current = await getJobState(tabId) || {};
    await setJobState(tabId, {
      ...current,
      progressMessage: progressFor(data.status) || current.progressMessage,
    });

    setTimeout(tick, POLL_INTERVAL_MS);
  };

  setTimeout(tick, POLL_INTERVAL_MS);
}

// Map a bridge job status to a human-readable progress message.
function progressFor(status) {
  switch (status) {
    case 'pending': return 'Queued — waiting for a worker…';
    case 'claimed': return 'Extracting the job description & generating your documents…';
    default:        return null;
  }
}

// The bridge returns host-relative artifact URLs (e.g. "/api/jobs/<id>/resume.pdf").
// Used as-is they'd resolve against chrome-extension:// and 404, so prepend the Bridge origin.
function absolutizeArtifacts(artifacts) {
  if (!artifacts) return artifacts;
  const out = {};
  for (const [key, url] of Object.entries(artifacts)) {
    out[key] = (typeof url === 'string' && url.startsWith('/')) ? `${BRIDGE_API_URL}${url}` : url;
  }
  return out;
}

// ── Storage helpers (per-tab job state) ─────────────────────────────────────────

const KEY = (tabId) => `job_${tabId}`;

async function setJobState(tabId, patch) {
  const existing = await getJobState(tabId) || {};
  const next = { ...existing, ...patch, updatedAt: Date.now() };
  return new Promise(resolve =>
    chrome.storage.local.set({ [KEY(tabId)]: next }, resolve)
  );
}

async function getJobState(tabId) {
  return new Promise(resolve =>
    chrome.storage.local.get(KEY(tabId), result =>
      resolve(result[KEY(tabId)] || null)
    )
  );
}

async function clearJobState(tabId) {
  return new Promise(resolve =>
    chrome.storage.local.remove(KEY(tabId), resolve)
  );
}
