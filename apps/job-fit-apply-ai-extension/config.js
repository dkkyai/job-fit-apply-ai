// config.js — shared constants for the extension
//
// BRIDGE_API_URL: Uses MagicDNS for direct WireGuard/tailnet access.
// The hostname 'richards-macbook-m1-max.tail02d0e.ts.net' resolves to the
// machine's Tailscale IP (100.111.66.8) via the tailnet DNS, ensuring traffic
// stays within the private tailnet without going through Tailscale Funnel.

export const BRIDGE_API_URL = 'http://richards-macbook-m1-max.tail02d0e.ts.net:8765';

export const POLL_INTERVAL_MS = 5_000;    // how often to poll job status
export const POLL_TIMEOUT_MS   = 300_000;  // 5-minute hard timeout

// Page capture — the server LLM extracts the JD, so we send lightly-cleaned text.
export const MAX_CAPTURE_CHARS    = 100_000; // cap the payload (server truncates further for the LLM)
export const MIN_READABILITY_CHARS = 400;    // below this, fall back from Readability to innerText
export const MIN_CAPTURE_CHARS    = 200;     // must match the bridge's /api/pages floor

// Internal extension states (distinct from the bridge's job statuses).
export const STATUS = {
  IDLE:        'idle',
  CAPTURING:   'capturing',
  SUBMITTING:  'submitting',
  PROCESSING:  'processing',
  COMPLETE:    'complete',
  ERROR:       'error',
};
