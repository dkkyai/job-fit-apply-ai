"""Persisted 'pending since' marker for the accumulate-until-N-or-T cadence gate.

Holds the epoch seconds at which the first not-yet-analyzed job appeared. The gate uses it
to force an analysis once jobs have been waiting longer than MAX_DEFER, even if fewer than
MIN_BATCH have accumulated (so a slow day still gets analyzed eventually rather than never).
Mirrors the Cursor marker-file pattern. Best-effort; a missing/garbled file reads as "unset".
"""

from pathlib import Path


class Pending:
    def __init__(self, path: Path):
        self.path = Path(path)

    def read(self):
        """Epoch seconds of the oldest pending job, or None if unset."""
        if not self.path.exists():
            return None
        try:
            return float(self.path.read_text().strip())
        except (ValueError, OSError):
            return None

    def write(self, ts: float) -> None:
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(repr(float(ts)))
        except OSError as e:
            import sys
            print(f"[pending] failed to write {self.path}: {e}", file=sys.stderr)

    def clear(self) -> None:
        try:
            self.path.unlink()
        except FileNotFoundError:
            pass
        except OSError:
            pass
