"""HTTP client for the bridge completed-event feed.

Contract (services/job-fit-apply-ai-bridge, Routes.kt):
  GET /api/jobs/completed?since=<seq>&limit=<=200&all=true
      -> JSON array of CompletedJob, ordered by completed_seq ASC, strict `>` on since.
  GET /api/jobs/completed/head  -> {"max_seq": <Long>}

No auth (loopback-published; Tailscale-gated externally). `completed_seq` is
strictly-increasing-but-possibly-sparse — always advance to the returned max, never
assume contiguity. We consume with all=true (full event stream) and keep our own cursor;
we NEVER call writeback-done (that is the poller's job).

Dependency-free (urllib), matching the repo's style.
"""

import json
import urllib.parse
import urllib.request

DEFAULT_BASE_URL = "http://127.0.0.1:8765"
MAX_LIMIT = 200


class BridgeClient:
    def __init__(self, base_url: str = DEFAULT_BASE_URL, timeout: int = 30):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def head_seq(self) -> int:
        """The bridge's current max completed_seq (for cold-start cursor seeding)."""
        raw = self._get("/api/jobs/completed/head")
        return int(json.loads(raw).get("max_seq", 0))

    def fetch_completed(self, since: int, limit: int = MAX_LIMIT, all: bool = True):
        """One page of completed jobs with completed_seq > `since`, ASC, up to `limit`."""
        limit = max(1, min(limit, MAX_LIMIT))
        q = urllib.parse.urlencode(
            {"since": int(since), "limit": limit, "all": "true" if all else "false"}
        )
        raw = self._get(f"/api/jobs/completed?{q}")
        return json.loads(raw)

    def fetch_last(self, n: int):
        """The last ~n completed jobs (rolling context window), independent of any cursor.

        Uses `since=max(0, head-n)`; because completed_seq is strictly-increasing-but-sparse,
        this is an approximate lower bound (may return fewer than n with gaps). Does NOT touch
        the cursor — this is read-only context, not consumption.
        """
        head = self.head_seq()
        return self.fetch_completed(max(0, head - int(n)), limit=int(n))

    def drain(self, since: int, on_page=None, page_size: int = MAX_LIMIT):
        """Page from `since` to head. Returns (records, last_seq).

        `on_page(last_seq)` is invoked after each non-empty page so the caller can
        persist the cursor incrementally (at-least-once, mirroring NotifierLoop.drainOnce).
        Stops when a page returns fewer than `page_size` rows.
        """
        cur = int(since)
        out = []
        while True:
            page = self.fetch_completed(cur, limit=page_size, all=True)
            if not page:
                break
            out.extend(page)
            cur = max(int(r.get("completed_seq", cur)) for r in page)
            if on_page:
                on_page(cur)
            if len(page) < page_size:
                break
        return out, cur

    def _get(self, path: str) -> bytes:
        req = urllib.request.Request(f"{self.base_url}{path}", method="GET")
        with urllib.request.urlopen(req, timeout=self.timeout) as resp:
            return resp.read()
