"""Persisted consumer cursor — the max bridge `completed_seq` this analyzer has consumed.

Mirrors services/job-fit-apply-ai-notifier/.../state/Cursor.kt: a plaintext file holding a
single decimal seq. The analyzer keeps its OWN cursor, independent of the poller's
server-side writeback_done flag and the notifier's cursor — it consumes the feed with
all=true and never acks writeback (that is poller-only).

Cold start (no file): seed at the bridge head so we skip history rather than re-analyzing
every job ever completed. Best-effort, exactly like the Kotlin original.
"""

from pathlib import Path


class Cursor:
    def __init__(self, path: Path):
        self.path = Path(path)

    def read(self):
        """Last consumed seq, or None if never set (cold start)."""
        if not self.path.exists():
            return None
        try:
            return int(self.path.read_text().strip())
        except (ValueError, OSError):
            return None

    def write(self, seq: int) -> None:
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(str(int(seq)))
        except OSError as e:  # best-effort, matching Cursor.kt
            import sys
            print(f"[cursor] failed to write {self.path}: {e}", file=sys.stderr)
