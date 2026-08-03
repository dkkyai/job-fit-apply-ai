#!/usr/bin/env python3
"""Validate production and E2E Compose data-root mount contracts."""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "docker-compose.yml"
E2E = ROOT / "docker-compose.e2e.yml"


def compose_config(*files: Path, environment: dict[str, str]) -> dict:
    env = os.environ.copy()
    for name in (
        "COMPOSE_ENV_FILES",
        "JFAA_DATA_ROOT",
        "JD_PIPELINE_OUTPUT_HOST",
        "JD_PIPELINE_STATE_HOST",
    ):
        env.pop(name, None)
    env.update(environment)

    with tempfile.NamedTemporaryFile(mode="w", suffix=".env") as env_file:
        env_file.flush()
        command = ["docker", "compose", "--env-file", env_file.name]
        for path in files:
            command.extend(("-f", str(path)))
        command.extend(("config", "--format", "json"))

        result = subprocess.run(
            command,
            cwd=ROOT,
            env=env,
            text=True,
            capture_output=True,
        )
    if result.returncode != 0:
        print(result.stdout, file=sys.stderr)
        print(result.stderr, file=sys.stderr)
        raise AssertionError(f"Compose config failed with exit {result.returncode}")
    return json.loads(result.stdout)


def mounts(config: dict, service: str) -> dict[str, dict]:
    return {
        volume["target"]: volume
        for volume in config["services"][service].get("volumes", [])
    }


def assert_mount(
    config: dict,
    service: str,
    target: str,
    source: Path,
    *,
    read_only: bool,
) -> None:
    volume = mounts(config, service)[target]
    actual_source = Path(volume["source"])
    assert actual_source == source, (
        f"{service}:{target} source was {actual_source}, expected {source}"
    )
    assert bool(volume.get("read_only", False)) is read_only, (
        f"{service}:{target} read_only was {volume.get('read_only', False)}, "
        f"expected {read_only}"
    )


def assert_markserv_excludes_state(config: dict, state_source: Path) -> None:
    markserv_mounts = mounts(config, "markserv")
    assert "/app/state" not in markserv_mounts, "Markserv must not mount Processor state"
    actual_sources = {Path(volume["source"]) for volume in markserv_mounts.values()}
    assert state_source not in actual_sources, (
        f"Markserv must not expose Pipeline state source {state_source}"
    )


def test_portable_fallback() -> None:
    home = Path(os.environ["HOME"])
    root = home / ".local/share/jfaa"
    config = compose_config(BASE, environment={})
    assert_mount(config, "processor", "/app/output", root / "pipeline-output", read_only=False)
    assert_mount(config, "processor", "/app/state", root / "pipeline-state", read_only=False)
    assert_mount(config, "markserv", "/data", root / "pipeline-output", read_only=True)
    assert_markserv_excludes_state(config, root / "pipeline-state")


def test_root_with_spaces() -> None:
    root = Path("/tmp/JFAA Application Support")
    config = compose_config(BASE, environment={"JFAA_DATA_ROOT": str(root)})
    assert_mount(config, "processor", "/app/output", root / "pipeline-output", read_only=False)
    assert_mount(config, "processor", "/app/state", root / "pipeline-state", read_only=False)
    assert_mount(config, "markserv", "/data", root / "pipeline-output", read_only=True)
    assert_markserv_excludes_state(config, root / "pipeline-state")


def test_independent_overrides() -> None:
    root = Path("/tmp/production-root-sentinel")
    output = Path("/tmp/custom pipeline output")
    state = Path("/tmp/custom pipeline state")
    config = compose_config(
        BASE,
        environment={
            "JFAA_DATA_ROOT": str(root),
            "JD_PIPELINE_OUTPUT_HOST": str(output),
            "JD_PIPELINE_STATE_HOST": str(state),
        },
    )
    assert_mount(config, "processor", "/app/output", output, read_only=False)
    assert_mount(config, "processor", "/app/state", state, read_only=False)
    assert_mount(config, "markserv", "/data", output, read_only=True)
    assert_markserv_excludes_state(config, state)


def test_harness_is_isolated_from_repository_dotenv() -> None:
    """The suite must not read a developer's real .env.

    compose_config() always passes an empty --env-file, which overrides COMPOSE_ENV_FILES and
    the default .env. This asserts that isolation holds — NOT that the repo .env is inert.
    Setting JFAA_DATA_ROOT there is the supported way to configure a host.
    """
    home = Path(os.environ["HOME"])
    fallback = home / ".local/share/jfaa"
    with tempfile.TemporaryDirectory() as directory:
        production_env = Path(directory) / "production.env"
        production_env.write_text(
            "JFAA_DATA_ROOT=/tmp/production-root-from-dotenv\n"
            "JD_PIPELINE_OUTPUT_HOST=/tmp/production-output-from-dotenv\n"
            "JD_PIPELINE_STATE_HOST=/tmp/production-state-from-dotenv\n"
        )
        config = compose_config(
            BASE,
            environment={"COMPOSE_ENV_FILES": str(production_env)},
        )

    assert_mount(config, "processor", "/app/output", fallback / "pipeline-output", read_only=False)
    assert_mount(config, "processor", "/app/state", fallback / "pipeline-state", read_only=False)
    assert_mount(config, "markserv", "/data", fallback / "pipeline-output", read_only=True)
    assert_markserv_excludes_state(config, fallback / "pipeline-state")


def test_documented_exact_mirror_helper() -> None:
    guide = (ROOT / "docs/data-root-migration.md").read_text()
    blocks = re.findall(r"```bash\n(.*?)```", guide, re.DOTALL)
    required = ("mirror_validate()", "mirror_preview()", "mirror_apply()")
    matches = [b for b in blocks if all(fn in b for fn in required)]
    assert len(matches) == 1, (
        f"expected exactly one bash block defining {required}, found {len(matches)}"
    )
    helper = matches[0]

    with tempfile.TemporaryDirectory() as directory:
        base = Path(directory)
        source = base / "source"
        destination = base / "destination"
        source.mkdir()
        destination.mkdir()

        source_collision = source / "collision.txt"
        destination_collision = destination / "collision.txt"
        source_collision.write_text("SOURCE")
        destination_collision.write_text("TARGET")
        timestamp = 1_700_000_000
        os.utime(source_collision, (timestamp, timestamp))
        os.utime(destination_collision, (timestamp, timestamp))
        (destination / "stale.txt").write_text("stale")
        root_link = base / "root-link"
        root_link.symlink_to("/")

        env = os.environ.copy()
        env.update(
            {
                "TEST_SOURCE": str(source),
                "TEST_DESTINATION": str(destination),
                "TEST_ROOT_LINK": str(root_link),
            }
        )

        def run(command: str) -> subprocess.CompletedProcess[str]:
            return subprocess.run(
                ["bash", "-c", f"set -euo pipefail\n{helper}\n{command}"],
                cwd=ROOT,
                env=env,
                text=True,
                capture_output=True,
            )

        preview = run('mirror_preview "$TEST_SOURCE" "$TEST_DESTINATION"')
        assert preview.returncode == 0, preview.stderr
        assert "collision.txt" in preview.stdout
        assert "*deleting" in preview.stdout and "stale.txt" in preview.stdout

        applied = run('mirror_apply "$TEST_SOURCE" "$TEST_DESTINATION"')
        assert applied.returncode == 0, applied.stderr
        verification = run('mirror_preview "$TEST_SOURCE" "$TEST_DESTINATION"')
        assert verification.returncode == 0, verification.stderr
        assert verification.stdout == "", verification.stdout
        assert destination_collision.read_text() == "SOURCE"
        assert not (destination / "stale.txt").exists()

        for invalid in (
            'mirror_validate / "$TEST_DESTINATION"',
            'mirror_validate "$TEST_SOURCE" "$TEST_SOURCE"',
            'mirror_validate "$TEST_SOURCE" "$TEST_ROOT_LINK"',
        ):
            assert run(invalid).returncode != 0, f"unsafe pair accepted: {invalid}"


def test_e2e_overlay_replaces_production_sources() -> None:
    sentinel_root = Path("/tmp/production-root-must-not-leak")
    sentinel_output = Path("/tmp/production-output-must-not-leak")
    sentinel_state = Path("/tmp/production-state-must-not-leak")
    config = compose_config(
        BASE,
        E2E,
        environment={
            "JFAA_DATA_ROOT": str(sentinel_root),
            "JD_PIPELINE_OUTPUT_HOST": str(sentinel_output),
            "JD_PIPELINE_STATE_HOST": str(sentinel_state),
        },
    )
    assert_mount(config, "processor", "/app/output", ROOT / ".e2e/output", read_only=False)
    assert_mount(config, "processor", "/app/state", ROOT / ".e2e/state", read_only=False)
    assert_mount(config, "markserv", "/data", ROOT / ".e2e/output", read_only=True)
    assert_markserv_excludes_state(config, ROOT / ".e2e/state")

    rendered = json.dumps(config)
    for sentinel in (sentinel_root, sentinel_output, sentinel_state):
        assert str(sentinel) not in rendered, f"production path leaked into E2E config: {sentinel}"


def main() -> None:
    tests = (
        test_portable_fallback,
        test_root_with_spaces,
        test_independent_overrides,
        test_harness_is_isolated_from_repository_dotenv,
        test_documented_exact_mirror_helper,
        test_e2e_overlay_replaces_production_sources,
    )
    for test in tests:
        test()
        print(f"PASS {test.__name__}")
    print(f"PASS {len(tests)} Compose data-root contract tests")


if __name__ == "__main__":
    main()
