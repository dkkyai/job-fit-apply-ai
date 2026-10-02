#!/usr/bin/env python3
"""Normalize test-runner output into one per-test table, and compare two runs.

scripts/verify-all.sh writes <out>/results.tsv (level, module, test id, status) by calling
the `junit` / `jest` / `pyunit` / `passlines` subcommands below. `compare` then diffs two
such runs, which is how a refactor proves it changed no behaviour: every test that exists
in both runs must keep its status (only a move *to* passed is allowed), every test that
disappeared must be one the refactor deleted on purpose (listed in --expect-removed), every
added test must pass or skip, and no step's exit code may get worse.

  verify_results.py junit  --level unit --module bridge  build/test-results/test/*.xml
  verify_results.py jest   --level unit --module extension  jest.json
  verify_results.py pyunit --level unit --module run-analyzer [--out FILE] <start-dir>
  verify_results.py passlines --level unit --module compose  <log>   # "PASS name" lines
  verify_results.py compare <baseline-dir> <candidate-dir> [--expect-removed FILE]

Statuses: passed, failed, skipped. A test id that occurs more than once (parameterized
cases with identical display names) is suffixed #2, #3, ... in run order, so rows stay
unique and each suffix keeps naming the same case across runs.
"""

from __future__ import annotations

import argparse
import fnmatch
import json
import os
import sys
import unittest
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path


def emit(level: str, module: str, rows: list[tuple[str, str]], out=None) -> None:
    """Write rows given in RUN order. Duplicates are suffixed before sorting: sorting first
    would order same-named cases by status, so two cases swapping pass/fail would look
    identical across runs."""
    seen: Counter[str] = Counter()
    unique = []
    for test_id, status in rows:
        seen[test_id] += 1
        unique.append((f"{test_id}#{seen[test_id]}" if seen[test_id] > 1 else test_id, status))
    for test_id, status in sorted(unique):
        print(f"{level}\t{module}\t{test_id}\t{status}", file=out or sys.stdout)


def cmd_junit(args: argparse.Namespace) -> int:
    rows = []
    for path in args.files:
        root = ET.parse(path).getroot()
        for case in root.iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                status = "failed"
            elif case.find("skipped") is not None:
                status = "skipped"
            else:
                status = "passed"
            rows.append((f"{case.get('classname', '')}::{case.get('name', '')}", status))
    emit(args.level, args.module, rows)
    return 0


def cmd_jest(args: argparse.Namespace) -> int:
    data = json.loads(Path(args.file).read_text())
    suites = data.get("testResults") or []
    root = Path(suites[0].get("name", "/")).anchor if suites else "/"
    rows = []
    for suite in suites:
        name = suite.get("name", "")
        rel = name.split("/apps/", 1)[-1] if "/apps/" in name else name.removeprefix(root)
        for a in suite.get("assertionResults", []):
            status = {"passed": "passed", "failed": "failed"}.get(a.get("status"), "skipped")
            rows.append((f"{rel}::{a.get('fullName', a.get('title', ''))}", status))
    emit(args.level, args.module, rows)
    return 0


class _Collector(unittest.TestResult):
    def __init__(self) -> None:
        super().__init__()
        self.rows: list[tuple[str, str]] = []
        self._subtest_failed: set[str] = set()
        self._rows_before = 0

    def addSuccess(self, test):  # noqa: N802 (unittest API)
        self.rows.append((test.id(), "passed"))

    def addFailure(self, test, err):  # noqa: N802
        super().addFailure(test, err)
        self.rows.append((test.id(), "failed"))

    def addError(self, test, err):  # noqa: N802
        super().addError(test, err)
        self.rows.append((getattr(test, "id", lambda: str(test))(), "failed"))

    def addSkip(self, test, reason):  # noqa: N802
        self.rows.append((test.id(), "skipped"))

    def addExpectedFailure(self, test, err):  # noqa: N802
        self.rows.append((test.id(), "passed"))

    def addUnexpectedSuccess(self, test):  # noqa: N802
        self.rows.append((test.id(), "failed"))

    # A test whose subTest fails gets neither addSuccess nor addFailure; without this it
    # would leave no row at all and vanish from the comparison.
    def addSubTest(self, test, subtest, err):  # noqa: N802
        super().addSubTest(test, subtest, err)
        if err is not None:
            self._subtest_failed.add(test.id())

    def startTest(self, test):  # noqa: N802
        super().startTest(test)
        self._rows_before = len(self.rows)

    def stopTest(self, test):  # noqa: N802
        super().stopTest(test)
        if test.id() in self._subtest_failed and len(self.rows) == self._rows_before:
            self.rows.append((test.id(), "failed"))


def cmd_pyunit(args: argparse.Namespace) -> int:
    # Same discovery as CI (`python3 -m unittest discover -s tests -p 'test_*.py'` run from
    # the analyzer dir): tests/ is not a package, so it is its own top level.
    start = Path(args.start_dir).resolve()
    os.chdir(start)
    tests = start / "tests"
    sys.path.insert(0, str(tests))
    suite = unittest.defaultTestLoader.discover(str(tests), pattern="test_*.py",
                                                top_level_dir=str(tests))
    result = _Collector()
    suite.run(result)
    for _, tb in result.failures + result.errors:
        print(tb, file=sys.stderr)
    if args.out:   # a file, so the runner's own chatter can go to the step log
        with open(args.out, "w") as fh:
            emit(args.level, args.module, result.rows, fh)
    else:
        emit(args.level, args.module, result.rows)
    return 0 if result.wasSuccessful() else 1


def cmd_passlines(args: argparse.Namespace) -> int:
    rows = []
    for line in Path(args.file).read_text().splitlines():
        head, _, name = line.strip().partition(" ")
        # One-token names only: skips summaries like "PASS 9 Compose data-root contract
        # tests", whose count would turn every added/removed test into a renamed row.
        if head in ("PASS", "FAIL") and name and " " not in name:
            rows.append((name, "passed" if head == "PASS" else "failed"))
    emit(args.level, args.module, rows)
    return 0


def load(run_dir: Path) -> dict[tuple[str, str, str], str]:
    rows = {}
    for line in (run_dir / "results.tsv").read_text().splitlines():
        level, module, test_id, status = line.split("\t")
        rows[(level, module, test_id)] = status
    return rows


def load_steps(run_dir: Path) -> dict[tuple[str, str], str]:
    path = run_dir / "steps.tsv"
    if not path.exists():
        return {}
    out = {}
    for line in path.read_text().splitlines():
        level, step, code, _secs = line.split("\t")
        out[(level, step)] = code
    return out


def cmd_compare(args: argparse.Namespace) -> int:
    base, cand = load(Path(args.baseline)), load(Path(args.candidate))
    patterns = []
    if args.expect_removed:
        patterns = [p.strip() for p in Path(args.expect_removed).read_text().splitlines()
                    if p.strip() and not p.lstrip().startswith("#")]

    def expected_gone(key: tuple[str, str, str]) -> bool:
        return any(fnmatch.fnmatchcase(f"{key[1]}::{key[2]}", p) for p in patterns)

    removed = sorted(k for k in base.keys() - cand.keys())
    added = sorted(k for k in cand.keys() - base.keys())
    changed = sorted(k for k in base.keys() & cand.keys() if base[k] != cand[k])
    # Only a move TO passed is an improvement. passed→skipped counts: a test that stops
    # running (unreachable DB, tripped assumption) proves nothing any more.
    improved = [k for k in changed if cand[k] == "passed"]
    regressed = [k for k in changed if cand[k] != "passed"]
    added_failing = [k for k in added if cand[k] == "failed"]
    unexpected = [k for k in removed if not expected_gone(k)]
    unused_patterns = [p for p in patterns
                       if not any(fnmatch.fnmatchcase(f"{k[1]}::{k[2]}", p) for k in removed)]

    def show(title: str, keys, fmt) -> None:
        print(f"\n{title}: {len(keys)}")
        for k in keys:
            print("  " + fmt(k))

    print(f"baseline  {args.baseline}: {len(base)} tests  {dict(Counter(base.values()))}")
    print(f"candidate {args.candidate}: {len(cand)} tests  {dict(Counter(cand.values()))}")
    show("Status REGRESSED (anything but a move to passed)", regressed,
         lambda k: f"[{k[0]}/{k[1]}] {k[2]}: {base[k]} -> {cand[k]}")
    show("Status improved (now passed)", improved,
         lambda k: f"[{k[0]}/{k[1]}] {k[2]}: {base[k]} -> {cand[k]}")
    show("Removed as expected (tests of deleted code)",
         [k for k in removed if expected_gone(k)], lambda k: f"[{k[0]}/{k[1]}] {k[2]}")
    show("Removed UNEXPECTEDLY", unexpected, lambda k: f"[{k[0]}/{k[1]}] {k[2]} ({base[k]})")
    show("Added", added, lambda k: f"[{k[0]}/{k[1]}] {k[2]} ({cand[k]})")
    show("Added tests that FAIL", added_failing, lambda k: f"[{k[0]}/{k[1]}] {k[2]}")
    if unused_patterns:
        show("--expect-removed patterns that matched nothing", unused_patterns, str)

    steps_b, steps_c = load_steps(Path(args.baseline)), load_steps(Path(args.candidate))
    step_diffs = sorted(k for k in steps_b.keys() | steps_c.keys()
                        if steps_b.get(k) != steps_c.get(k))
    fixed_steps = [k for k in step_diffs if steps_c.get(k) == "0"]
    broken_steps = [k for k in step_diffs if steps_c.get(k) != "0"]
    fmt = lambda k: f"[{k[0]}] {k[1]}: {steps_b.get(k, '-')} -> {steps_c.get(k, '-')}"  # noqa: E731
    show("Steps that now pass (were failing or absent in baseline)", fixed_steps, fmt)
    show("Steps that now FAIL (build/lint/typecheck/e2e regressions)", broken_steps, fmt)
    failing = [k for k, c in steps_c.items() if c != "0"]
    show("Candidate steps that did not exit 0", sorted(failing),
         lambda k: f"[{k[0]}] {k[1]}: exit {steps_c[k]}")

    ok = (not regressed and not added_failing and not unexpected and not broken_steps
          and not unused_patterns)
    print("\nRESULT:", "OK — no behaviour change detected" if ok else "DIFFERENCES FOUND")
    return 0 if ok else 1


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    for name, fn, arg in (("junit", cmd_junit, "files"), ("jest", cmd_jest, "file"),
                          ("pyunit", cmd_pyunit, "start_dir"), ("passlines", cmd_passlines, "file")):
        sp = sub.add_parser(name)
        sp.add_argument("--level", required=True)
        sp.add_argument("--module", required=True)
        sp.add_argument(arg, nargs="+" if arg == "files" else None)
        if name == "pyunit":
            sp.add_argument("--out", help="write rows here instead of stdout")
        sp.set_defaults(fn=fn)
    sp = sub.add_parser("compare")
    sp.add_argument("baseline")
    sp.add_argument("candidate")
    sp.add_argument("--expect-removed", help="file of <module>::<test id> glob patterns, one per line")
    sp.set_defaults(fn=cmd_compare)
    args = p.parse_args()
    return args.fn(args)


if __name__ == "__main__":
    sys.exit(main())
