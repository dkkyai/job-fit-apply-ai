/**
 * tests/background.test.js
 *
 * Unit tests for background.js — the MV3 service worker (capture → submit → poll flow).
 * Each describe block reloads the module to get a fresh listener registration.
 */

// ── Helpers ────────────────────────────────────────────────────────────────────

function getMessageListener() {
  const calls = global.chrome.runtime.onMessage.addListener.mock.calls;
  return calls[calls.length - 1]?.[0];
}

function getContextMenuListener() {
  const calls = global.chrome.contextMenus.onClicked.addListener.mock.calls;
  return calls[calls.length - 1]?.[0];
}

function getInstallListener() {
  const calls = global.chrome.runtime.onInstalled.addListener.mock.calls;
  return calls[calls.length - 1]?.[0];
}

const stateKey = (tabId) => `job_${tabId}`;

/** Make the on-demand page capture resolve with a payload (both executeScript calls). */
function mockCapture({ url = 'https://linkedin.com/jobs/view/1', title = 'Staff SDET', text = 'x'.repeat(400) } = {}) {
  chrome.scripting.executeScript.mockResolvedValue([{ result: { url, title, text } }]);
}

// ── Module loading ─────────────────────────────────────────────────────────────

beforeEach(() => {
  jest.resetModules();
  jest.clearAllMocks();
  global._storageMock._reset();
});

function loadBackground() {
  return require('../background.js');
}

// ── onInstalled ────────────────────────────────────────────────────────────────

describe('onInstalled handler', () => {
  it('removes then recreates the jfa-capture context menu item', () => {
    loadBackground();
    getInstallListener()();

    expect(chrome.contextMenus.remove).toHaveBeenCalledWith('jfa-capture', expect.any(Function));
    const removeCb = chrome.contextMenus.remove.mock.calls[0][1];
    removeCb();
    expect(chrome.contextMenus.create).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'jfa-capture' })
    );
  });
});

// ── Message listener ────────────────────────────────────────────────────────────

describe('runtime.onMessage', () => {
  it('POPUP_TRIGGER responds ok:false when there is no active tab', async () => {
    loadBackground();
    const listener = getMessageListener();
    chrome.tabs.query.mockImplementation((_q, cb) => cb([]));

    const sendResponse = jest.fn();
    listener({ type: 'POPUP_TRIGGER' }, {}, sendResponse);
    await new Promise(r => setTimeout(r, 0));

    expect(sendResponse).toHaveBeenCalledWith({ ok: false, error: 'No active tab' });
  });

  it('GET_JOB_STATUS resolves stored state', async () => {
    loadBackground();
    const listener = getMessageListener();
    const stored = { status: 'processing', jobId: 'abc' };
    _storageMock.get.mockImplementation((key, cb) => cb({ [key]: stored }));

    const sendResponse = jest.fn();
    listener({ type: 'GET_JOB_STATUS', tabId: 7 }, {}, sendResponse);
    await new Promise(r => setTimeout(r, 0));

    expect(sendResponse).toHaveBeenCalledWith(stored);
  });

  it('CLEAR_JOB removes state and responds ok', async () => {
    loadBackground();
    const listener = getMessageListener();

    const sendResponse = jest.fn();
    listener({ type: 'CLEAR_JOB', tabId: 5 }, {}, sendResponse);
    await new Promise(r => setTimeout(r, 0));

    expect(chrome.storage.local.remove).toHaveBeenCalledWith(stateKey(5), expect.any(Function));
    expect(sendResponse).toHaveBeenCalledWith({ ok: true });
  });
});

// ── tabs.onUpdated ─────────────────────────────────────────────────────────────

describe('tabs.onUpdated', () => {
  it('clears job state when a tab starts loading', () => {
    loadBackground();
    const calls = chrome.tabs.onUpdated.addListener.mock.calls;
    calls[calls.length - 1][0](10, { status: 'loading' });
    expect(chrome.storage.local.remove).toHaveBeenCalledWith(stateKey(10), expect.any(Function));
  });
});

// ── captureAndSubmit ─────────────────────────────────────────────────────────────

describe('captureAndSubmit', () => {
  it('captures the page, POSTs {url,title,text} to /api/pages, and starts polling', async () => {
    loadBackground();
    const listener = getMessageListener();

    const tab = { id: 7, title: 'Job', url: 'https://linkedin.com/jobs/view/1' };
    chrome.tabs.query.mockImplementation((_q, cb) => cb([tab]));
    mockCapture({ text: 'a'.repeat(500) });

    global.fetch = jest.fn()
      .mockResolvedValueOnce({ ok: true, json: async () => ({ job_id: 'job-123', status: 'pending' }) })
      .mockResolvedValueOnce({ ok: true, json: async () => ({ status: 'claimed' }) });

    listener({ type: 'POPUP_TRIGGER' }, {}, jest.fn());
    await new Promise(r => setTimeout(r, 50));

    // POST body carries the captured page — no client-side extraction
    const postCall = global.fetch.mock.calls.find(([url]) => url.endsWith('/api/pages'));
    expect(postCall).toBeDefined();
    const body = JSON.parse(postCall[1].body);
    expect(body.url).toBe('https://linkedin.com/jobs/view/1');
    expect(body.text.length).toBeGreaterThanOrEqual(200);
    expect(body).not.toHaveProperty('jd_text');

    const setCalls = chrome.storage.local.set.mock.calls.map(c => c[0]);
    const processing = setCalls.find(c => c[stateKey(7)]?.status === 'processing');
    expect(processing).toBeDefined();
    expect(processing[stateKey(7)].jobId).toBe('job-123');
  });

  it('errors when the captured page has too little text', async () => {
    loadBackground();
    const listener = getMessageListener();

    const tab = { id: 4, title: 'Blank', url: 'https://example.com' };
    chrome.tabs.query.mockImplementation((_q, cb) => cb([tab]));
    mockCapture({ text: 'too short' });
    global.fetch = jest.fn();

    listener({ type: 'POPUP_TRIGGER' }, {}, jest.fn());
    await new Promise(r => setTimeout(r, 20));

    expect(chrome.storage.local.set).toHaveBeenCalledWith(
      expect.objectContaining({
        [stateKey(4)]: expect.objectContaining({ status: 'error', error: expect.stringContaining('readable text') }),
      }),
      expect.any(Function),
    );
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it('errors when the Bridge is unreachable', async () => {
    loadBackground();
    const listener = getMessageListener();

    const tab = { id: 6, title: 'Job', url: 'https://example.com' };
    chrome.tabs.query.mockImplementation((_q, cb) => cb([tab]));
    mockCapture({ text: 'a'.repeat(500) });
    global.fetch = jest.fn().mockRejectedValueOnce(new Error('fetch failed'));

    listener({ type: 'POPUP_TRIGGER' }, {}, jest.fn());
    await new Promise(r => setTimeout(r, 30));

    expect(chrome.storage.local.set).toHaveBeenCalledWith(
      expect.objectContaining({
        [stateKey(6)]: expect.objectContaining({ status: 'error', error: expect.stringContaining('Bridge API unreachable') }),
      }),
      expect.any(Function),
    );
  });

  it('context-menu click on jfa-capture triggers a capture', async () => {
    loadBackground();
    const handler = getContextMenuListener();
    const tab = { id: 1, title: 'A Job', url: 'https://example.com' };
    mockCapture({ text: 'too short' }); // short → stops early after writing CAPTURING

    await handler({ menuItemId: 'jfa-capture' }, tab);

    expect(chrome.storage.local.set).toHaveBeenCalledWith(
      expect.objectContaining({ [stateKey(1)]: expect.objectContaining({ status: 'capturing' }) }),
      expect.any(Function),
    );
  });
});

// ── pollForCompletion ────────────────────────────────────────────────────────────

async function driveOnePollTick() {
  for (let i = 0; i < 20; i++) await Promise.resolve();
  await jest.advanceTimersByTimeAsync(5100);
  for (let i = 0; i < 10; i++) await Promise.resolve();
}

describe('pollForCompletion', () => {
  beforeEach(() => {
    jest.useFakeTimers();
    global.fetch = jest.fn();
  });
  afterEach(() => { jest.useRealTimers(); });

  function startCapture(tabId) {
    const listener = getMessageListener();
    const tab = { id: tabId, title: 'Job', url: 'https://linkedin.com/jobs/view/1' };
    chrome.tabs.query.mockImplementation((_q, cb) => cb([tab]));
    mockCapture({ text: 'a'.repeat(500) });
    listener({ type: 'POPUP_TRIGGER' }, {}, jest.fn());
  }

  it('sets COMPLETE and absolutizes relative artifact URLs when bridge returns done', async () => {
    loadBackground();
    global.fetch
      .mockResolvedValueOnce({ ok: true, json: async () => ({ job_id: 'job-8', status: 'pending' }) })
      .mockResolvedValueOnce({ ok: true, json: async () => ({
        status: 'done', title: 'Staff SDET', fit_score: 82,
        artifacts: { resume_pdf: '/api/jobs/job-8/resume.pdf', cover_letter_txt: '/api/jobs/job-8/cover_letter.txt' },
      }) });

    startCapture(8);
    await driveOnePollTick();

    const completed = chrome.storage.local.set.mock.calls.map(c => c[0])
      .find(c => c[stateKey(8)]?.status === 'complete');
    expect(completed).toBeDefined();
    expect(completed[stateKey(8)].artifacts.resume_pdf).toMatch(/^https?:\/\/.+\/api\/jobs\/job-8\/resume\.pdf$/);
    expect(completed[stateKey(8)].fitScore).toBe(82);
    expect(chrome.notifications.create).toHaveBeenCalled();
  }, 15_000);

  it('treats a done job with no artifacts as "not a job posting"', async () => {
    loadBackground();
    global.fetch
      .mockResolvedValueOnce({ ok: true, json: async () => ({ job_id: 'job-nj', status: 'pending' }) })
      .mockResolvedValueOnce({ ok: true, json: async () => ({ status: 'done' }) });

    startCapture(11);
    await driveOnePollTick();

    const errored = chrome.storage.local.set.mock.calls.map(c => c[0])
      .find(c => c[stateKey(11)]?.status === 'error');
    expect(errored).toBeDefined();
    expect(errored[stateKey(11)].error).toMatch(/job posting/i);
  }, 15_000);

  it('sets ERROR when bridge returns error status', async () => {
    loadBackground();
    global.fetch
      .mockResolvedValueOnce({ ok: true, json: async () => ({ job_id: 'job-e', status: 'pending' }) })
      .mockResolvedValueOnce({ ok: true, json: async () => ({ status: 'error', error: 'Extraction failed' }) });

    startCapture(9);
    await driveOnePollTick();

    const errored = chrome.storage.local.set.mock.calls.map(c => c[0])
      .find(c => c[stateKey(9)]?.status === 'error');
    expect(errored).toBeDefined();
    expect(errored[stateKey(9)].error).toBe('Extraction failed');
  }, 15_000);
});
