// config.js — shared constants for the extension
//
// BRIDGE_API_URL: Point this at your bridge server. For Tailscale users, use
// your MagicDNS hostname (e.g. http://your-machine.ts.net:8765). For local
// dev, http://localhost:8765 works when the bridge runs on the same machine.

export const BRIDGE_API_URL = 'http://localhost:8765';

export const POLL_INTERVAL_MS  = 5_000;   // how often to check job status
export const POLL_TIMEOUT_MS   = 300_000; // 5-minute hard timeout
export const MIN_JD_CHARS      = 150;     // reject extractions shorter than this

export const STATUS = {
  IDLE:        'idle',
  EXTRACTING:  'extracting',
  SUBMITTING:  'submitting',
  PROCESSING:  'processing',
  COMPLETE:    'complete',
  ERROR:       'error',
};
