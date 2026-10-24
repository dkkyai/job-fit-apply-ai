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
    assert cfg["platform_toolsets"]["telegram"] == ["web", "memory", "clarify", "session_search", "jfaa"]
    assert {"terminal", "file", "browser", "code_execution", "skills", "delegation"} <= set(cfg["agent"]["disabled_toolsets"])
    assert cfg["platform_toolsets"]["api_server"] == ["fill"], "a fill run gets the fill server and nothing else"
    assert "fill" not in cfg["platform_toolsets"]["telegram"], "the chat is not offered the fill server"
    assert cfg["mcp_servers"]["fill"]["url"] == "http://jobbot-mcp:8790/mcp-fill"
    assert cfg["mcp_servers"]["fill"]["tools"]["include"] == ["fill_context", "fill_snapshot", "fill_act", "fill_finish"]
    assert cfg["mcp_servers"]["fill"]["headers"]["Authorization"] == "Bearer tok"
    assert "api_server" not in cfg.get("platforms", {}), "the API server is switched on by the key in the environment, never by this file"
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


def _registered_tools(*, fill):
    import re
    src = JOBBOT.parents[1] / "services/job-fit-apply-ai-jobbot-mcp/src/main/kotlin/com/jd/jobbot/mcp"
    names = set()
    for f in src.glob("*.kt"):
        if (f.name == "FillTools.kt") == fill:
            names |= set(re.findall(r'name = "([a-z_]+)",\s*\n\s*description', f.read_text()))
    return names


def test_every_tool_jobbot_mcp_registers_is_in_the_allowlist():
    """A tool added to jobbot-mcp but not to the include list is silently invisible to the model."""
    cfg = yaml.safe_load(seed.render_config({"JOBBOT_MCP_TOKEN": "tok"}, JOBBOT))
    registered = _registered_tools(fill=False)
    assert registered and registered == set(cfg["mcp_servers"]["jfaa"]["tools"]["include"])


def test_the_fill_tools_are_their_own_server_and_only_those():
    cfg = yaml.safe_load(seed.render_config({"JOBBOT_MCP_TOKEN": "tok"}, JOBBOT))
    fill = _registered_tools(fill=True)
    assert fill == {"fill_context", "fill_snapshot", "fill_act", "fill_finish"}
    assert fill == set(cfg["mcp_servers"]["fill"]["tools"]["include"])
    assert not fill & set(cfg["mcp_servers"]["jfaa"]["tools"]["include"]), "the chat must not be offered the fill tools"


def test_a_fill_run_can_reach_none_of_the_chat_tools():
    """The planner of a fill reads untrusted page text: mail, drafts, tracks and the cargo repo are not its to call."""
    cfg = yaml.safe_load(seed.render_config({"JOBBOT_MCP_TOKEN": "tok"}, JOBBOT))
    assert cfg["platform_toolsets"]["api_server"] == ["fill"]
    for dangerous in ("get_job_email", "write_reply_draft", "request_send_approval", "cargo_apply", "cargo_append_status", "set_track_status", "get_profile"):
        assert dangerous not in cfg["mcp_servers"]["fill"]["tools"]["include"], dangerous
    assert set(cfg["agent"]["disabled_toolsets"]) >= {"terminal", "file", "code_execution", "browser", "delegation", "cronjob", "skills"}


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


def test_platform_toolsets_name_real_mcp_servers():
    """Hermes treats MCP server NAMES in a platform list as an allowlist; an unknown name is ignored and every server
    is included. `mcp-fill` (with a prefix) once let a fill run see the mail tools, so each name must be a server."""
    cfg = yaml.safe_load(seed.render_config({"JOBBOT_MCP_TOKEN": "tok"}, JOBBOT))
    servers = set(cfg["mcp_servers"])
    builtin = {"web", "memory", "clarify", "session_search"}
    for platform, names in cfg["platform_toolsets"].items():
        mcp_names = [n for n in names if n not in builtin]
        assert mcp_names, f"{platform} lists no MCP server, so Hermes would give it all of them"
        assert set(mcp_names) <= servers, f"{platform} lists {set(mcp_names) - servers}, which is not an MCP server (no 'mcp-' prefix)"
    assert set(cfg["platform_toolsets"]["telegram"]) & servers == {"jfaa"}
    assert set(cfg["platform_toolsets"]["api_server"]) & servers == {"fill"}


def test_main_writes_a_persona_fingerprint_that_changes_with_the_persona_and_the_tools(tmp_path):
    def fingerprint_after(edit=None):
        src = tmp_path / "src"
        shutil.rmtree(src, ignore_errors=True)
        shutil.copytree(JOBBOT, src, ignore=shutil.ignore_patterns("tests", "__pycache__", "*.pyc"))
        if edit:
            edit(src)
        home = tmp_path / f"home{len(list(tmp_path.glob('home*')))}"
        seed.main({"JOBBOT_MCP_TOKEN": "tok"}, owner="no-such-user", src=src, home=home)
        return (home / ".persona-fingerprint").read_text().strip()

    base = fingerprint_after()
    assert len(base) == 64
    assert fingerprint_after() == base, "the same persona and tools always give the same fingerprint"
    assert fingerprint_after(lambda s: (s / "SOUL.md").write_text((s / "SOUL.md").read_text() + "\nNew rule.\n")) != base
    assert fingerprint_after(lambda s: (s / "skills" / "cargo.md").write_text((s / "skills" / "cargo.md").read_text() + "\nNew step.\n")) != base
    assert fingerprint_after(lambda s: (s / "config.yaml.tmpl").write_text((s / "config.yaml.tmpl").read_text().replace("cargo_list,", "cargo_list, cargo_new,", 1))) != base, "a new tool changes it"


def test_the_fingerprint_does_not_change_with_secrets_or_the_model(tmp_path):
    def run(env):
        home = tmp_path / f"h{len(list(tmp_path.glob('h*')))}"
        seed.main(env, owner="no-such-user", src=JOBBOT, home=home)
        return (home / ".persona-fingerprint").read_text()
    a = run({"JOBBOT_MCP_TOKEN": "one", "JOBBOT_MODEL": "x:cloud"})
    assert run({"JOBBOT_MCP_TOKEN": "two", "JOBBOT_MODEL": "y:cloud"}) == a, "rotating a token or switching model is not a reason to ask for /new"

