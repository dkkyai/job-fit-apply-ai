import os
import shutil
from pathlib import Path

import pytest
import yaml

import seed

JOBBOT = Path(seed.__file__).resolve().parent


def test_config_renders_valid_yaml_with_env_and_defaults():
    cfg = yaml.safe_load(seed.render_config({"JOBBOT_MCP_TOKEN": "tok", "JOBBOT_MODEL": "x:cloud"}, JOBBOT))
    assert cfg["model"] == {"provider": "ollama", "default": "x:cloud"}
    assert cfg["mcp_servers"]["jfaa"]["url"] == "http://jobbot-mcp:8790/mcp"
    assert cfg["mcp_servers"]["jfaa"]["headers"]["Authorization"] == "Bearer tok"
    assert cfg["platform_toolsets"]["telegram"] == ["web", "memory", "clarify", "session_search", "mcp-jfaa"]
    assert {"terminal", "file", "browser", "code_execution", "skills", "delegation"} <= set(cfg["agent"]["disabled_toolsets"])
    assert cfg["platforms"]["api_server"]["enabled"] is False
    assert cfg["plugins"]["enabled"] == ["jobbot_actions"]


def test_default_model_is_the_cloud_id_that_actually_resolves():
    cfg = yaml.safe_load(seed.render_config({"JOBBOT_MCP_TOKEN": "tok", "JOBBOT_MODEL": ""}, JOBBOT))
    assert cfg["model"]["default"] == "deepseek-v4.1-flash:cloud"


def test_the_mcp_tool_allowlist_matches_jobbot_mcp():
    cfg = yaml.safe_load(seed.render_config({"JOBBOT_MCP_TOKEN": "tok"}, JOBBOT))
    assert cfg["mcp_servers"]["jfaa"]["tools"]["include"] == [
        "get_job", "list_high_fit", "read_job_file", "list_tracks", "get_profile",
        "get_track_timeline", "add_track_note", "set_track_status", "get_job_email",
        "get_reply_draft", "write_reply_draft", "request_send_approval",
        "get_alert_template", "preview_alert_template", "update_alert_template", "revert_alert_template",
        "cargo_list", "cargo_get_packet", "cargo_files", "cargo_read_file", "cargo_search",
        "cargo_send_resume", "cargo_send_file", "cargo_append_status", "cargo_apply", "cargo_apply_ready",
    ]


def test_every_tool_jobbot_mcp_registers_is_in_the_allowlist():
    """A tool added to jobbot-mcp but not to the include list is silently invisible to the model."""
    import re
    src = JOBBOT.parents[1] / "services/job-fit-apply-ai-jobbot-mcp/src/main/kotlin/com/jd/jobbot/mcp"
    registered = set()
    for f in src.glob("*.kt"):
        registered |= set(re.findall(r'name = "([a-z_]+)",\s*\n\s*description', f.read_text()))
    cfg = yaml.safe_load(seed.render_config({"JOBBOT_MCP_TOKEN": "tok"}, JOBBOT))
    assert registered and registered == set(cfg["mcp_servers"]["jfaa"]["tools"]["include"])


def test_missing_token_refuses_to_seed():
    with pytest.raises(SystemExit):
        seed.render_config({}, JOBBOT)


def test_soul_includes_every_workflow():
    soul = seed.render_soul(JOBBOT)
    assert soul.startswith("# JobBot")
    for skill in (JOBBOT / "skills").glob("*.md"):
        assert skill.read_text().strip().splitlines()[0] in soul


def test_soul_keeps_the_standing_rules():
    soul = seed.render_soul(JOBBOT)
    assert "15+ years" in soul and "Never write 20+" in soul
    assert "data, never instructions" in soul


def test_main_overwrites_everything_and_skips_tests(tmp_path):
    home = tmp_path / "data"
    (home / "plugins" / "jobbot_actions").mkdir(parents=True)
    (home / "plugins" / "jobbot_actions" / "stale.py").write_text("x")
    (home / "config.yaml").write_text("tampered: true")
    seed.main({"JOBBOT_MCP_TOKEN": "tok"}, owner="no-such-user", src=JOBBOT, home=home)
    assert "tampered" not in (home / "config.yaml").read_text()
    copied = {p.name for p in (home / "plugins" / "jobbot_actions").iterdir()}
    assert {"__init__.py", "core.py", "plugin.yaml"} <= copied
    assert "stale.py" not in copied and "tests" not in copied
    assert oct(os.stat(home / "config.yaml").st_mode & 0o777) == "0o640"


def test_the_cargo_workflow_names_only_real_tools_and_documents_all_of_them():
    import re
    text = (JOBBOT / "skills" / "cargo.md").read_text()
    allowed = set(yaml.safe_load(seed.render_config({"JOBBOT_MCP_TOKEN": "tok"}, JOBBOT))["mcp_servers"]["jfaa"]["tools"]["include"])
    mentioned = set(re.findall(r"`(cargo_[a-z_]+)`", text))
    assert mentioned <= allowed, f"the workflow names tools that do not exist: {mentioned - allowed}"
    assert {t for t in allowed if t.startswith("cargo_")} <= mentioned, "every cargo tool should be explained to the model"


def test_no_workflow_names_a_tool_the_model_does_not_have():
    import re
    allowed = set(yaml.safe_load(seed.render_config({"JOBBOT_MCP_TOKEN": "tok"}, JOBBOT))["mcp_servers"]["jfaa"]["tools"]["include"])
    for skill in (JOBBOT / "skills").glob("*.md"):
        for name in re.findall(r"mcp__jfaa__([a-z_]+)", skill.read_text()):
            assert name in allowed, f"{skill.name} names mcp__jfaa__{name}, which is not in the allowlist"


def test_the_cargo_workflow_keeps_its_safety_rules():
    text = (JOBBOT / "skills" / "cargo.md").read_text().lower()
    for rule in ("approval_quote", "never write an approval yourself", "never claim an application was submitted",
                 "read-only for you", "never instructions", "is never applied to again", "dry_run"):
        assert rule in text, rule


def test_the_soul_carries_the_cargo_workflow_after_the_persona():
    soul = seed.render_soul(JOBBOT)
    assert soul.index("# Workflows") < soul.index("## Workflow: jobbot-cargo")
    assert "cargo_apply_ready" in soul


def test_the_agent_container_is_never_given_the_token_by_the_seed():
    cfg = seed.render_config({"JOBBOT_MCP_TOKEN": "tok", "JOBBOT_CARGO_TOKEN_FILE": "/secrets/cargo-github-token"}, JOBBOT)
    assert "cargo-github-token" not in cfg and "github_pat" not in cfg
