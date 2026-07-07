/**
 * popup.js
 *
 * On load: read this tab's job state from the background worker and render it, then poll
 * for updates (storage change events don't reach the popup reliably). All mutation goes
 * through the background service worker.
 */

// ── DOM refs ──────────────────────────────────────────────────────────────────
const $ = (id) => document.getElementById(id);

const siteBadge    = $('site-badge');
const jobCard      = $('job-card');
const jobTitle     = $('job-title');
const jobCompany   = $('job-company');
const progressMsg  = $('progress-msg');

const ctaIdle      = $('cta-idle');
const btnGenerate  = $('btn-generate');

const errorBlock   = $('error-block');
const errorMsg     = $('error-msg');
const btnRetry     = $('btn-retry');

const doneBlock    = $('done-block');
const fitBadge     = $('fit-badge');
const artifactLinks= $('artifact-links');
const btnReset     = $('btn-reset');

const steps = {
  capturing:  $('step-capture'),
  submitting: $('step-submit'),
  processing: $('step-process'),
  complete:   $('step-complete'),
};

const ORDER = ['capturing', 'submitting', 'processing', 'complete'];
const connectors = document.querySelectorAll('.pipe-connector');

// ── State ─────────────────────────────────────────────────────────────────────

let activeTabId = null;
let pollTimer   = null;

// ── Boot ──────────────────────────────────────────────────────────────────────

(async () => {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab) return;
  activeTabId = tab.id;

  try {
    const host = new URL(tab.url).hostname.replace(/^www\./, '');
    siteBadge.textContent = host.length > 28 ? host.slice(0, 26) + '…' : host;
  } catch { siteBadge.textContent = '—'; }

  await refreshState();
  startPolling();
})();

// ── Polling ───────────────────────────────────────────────────────────────────

function startPolling() {
  clearInterval(pollTimer);
  pollTimer = setInterval(refreshState, 1500);
}

async function refreshState() {
  if (!activeTabId) return;
  const state = await getJobState(activeTabId);
  render(state);
}

// ── Render ────────────────────────────────────────────────────────────────────

function render(state) {
  const status = state?.status || 'idle';

  if (state?.jdTitle || state?.company) {
    jobCard.style.display = '';
    jobTitle.textContent   = state.jdTitle || 'Untitled Role';
    jobCompany.textContent = state.company || '—';
  } else {
    jobCard.style.display = 'none';
  }

  progressMsg.textContent = state?.progressMessage || '';

  updatePipeline(status);

  ctaIdle.hidden    = !['idle', 'error'].includes(status);
  errorBlock.hidden = status !== 'error';
  doneBlock.hidden  = status !== 'complete';

  if (status === 'error') {
    errorMsg.textContent = state.error || 'Unknown error.';
  }

  if (status === 'complete' && state?.artifacts) {
    renderArtifacts(state.artifacts);
    renderFit(state.fitScore);
    clearInterval(pollTimer); // terminal — stop polling
  }

  const busy = ['capturing', 'submitting', 'processing'].includes(status);
  btnGenerate.disabled = busy;
}

function updatePipeline(status) {
  const idx = ORDER.indexOf(status);

  ORDER.forEach((step, i) => {
    const el = steps[step];
    if (!el) return;
    el.classList.remove('active', 'done', 'error');

    if (status === 'error' && i <= Math.max(idx, 0)) {
      if (i === Math.max(idx, 0)) el.classList.add('error');
      else el.classList.add('done');
    } else if (i < idx) {
      el.classList.add('done');
    } else if (i === idx) {
      el.classList.add('active');
    }
  });

  connectors.forEach((c, i) => {
    c.classList.remove('done', 'active');
    if (i < idx - 1) c.classList.add('done');
    else if (i === idx - 1) c.classList.add('active');
  });
}

function renderFit(fitScore) {
  if (fitScore == null) { fitBadge.hidden = true; return; }
  fitBadge.hidden = false;
  fitBadge.textContent = `fit ${fitScore}`;
}

function renderArtifacts(artifacts) {
  artifactLinks.innerHTML = '';

  const items = [
    { key: 'resume_pdf',       label: 'Resume',       icon: '📄', type: 'PDF' },
    { key: 'cover_letter_txt', label: 'Cover Letter', icon: '✉️',  type: 'TXT' },
  ];

  for (const { key, label, icon, type } of items) {
    const url = artifacts[key];
    if (!url) continue;

    const a = document.createElement('a');
    a.href      = url;
    a.target    = '_blank';
    a.rel       = 'noopener noreferrer';
    a.className = 'artifact-link';
    a.innerHTML = `
      <span class="artifact-icon">${icon}</span>
      <span>${label}</span>
      <span class="artifact-type">${type}</span>
    `;
    artifactLinks.appendChild(a);
  }
}

// ── Button handlers ─────────────────────────────────────────────────────────────

btnGenerate.addEventListener('click', async () => {
  btnGenerate.disabled = true;
  await chrome.runtime.sendMessage({ type: 'POPUP_TRIGGER' });
  startPolling();
  await refreshState();
});

btnRetry.addEventListener('click', async () => {
  await clearJobState(activeTabId);
  startPolling();
  await refreshState();
});

btnReset.addEventListener('click', async () => {
  await clearJobState(activeTabId);
  startPolling();
  await refreshState();
});

// ── Messaging helpers ────────────────────────────────────────────────────────────

function getJobState(tabId) {
  return new Promise(resolve =>
    chrome.runtime.sendMessage({ type: 'GET_JOB_STATUS', tabId }, resolve)
  );
}

function clearJobState(tabId) {
  return new Promise(resolve =>
    chrome.runtime.sendMessage({ type: 'CLEAR_JOB', tabId }, resolve)
  );
}
