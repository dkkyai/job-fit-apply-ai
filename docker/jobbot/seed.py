"""Seeds JobBot's Hermes home from the image on every container start (s6 cont-init, as root).

Runs after the image's own setup (01-hermes-setup) and before the gateway starts. Everything it
writes is overwritten on the next start, so nothing the agent changes at runtime — config, persona,
plugins — survives a restart. The sources live in the image under /opt/jobbot (root-owned).
"""
import hashlib
import os
import pwd
import shutil
import string
import sys
from pathlib import Path

SRC = Path("/opt/jobbot")

DEFAULTS = {
    "JOBBOT_MODEL": "deepseek-v4.1-flash:cloud",
    "JOBBOT_OLLAMA_URL": "http://host.docker.internal:11434/v1",
    "JOBBOT_MLX_URL": "http://host.docker.internal:11436/v1",
    "JOBBOT_MLX_KEY": "11436",
    "JOBBOT_FALLBACK_MODEL": "Qwen3.6-35B-A3B-OptiQ-4bit",
    "JOBBOT_MCP_URL": "http://jobbot-mcp:8790",
}
REQUIRED = ["JOBBOT_MCP_TOKEN"]


def render_config(env, src=SRC):
    values = {**DEFAULTS, **{k: v for k, v in env.items() if v}}
    missing = [k for k in REQUIRED if not values.get(k)]
    if missing:
        raise SystemExit(f"[jobbot-seed] missing required env: {', '.join(missing)}")
    return string.Template((src / "config.yaml.tmpl").read_text()).substitute(values)


def render_soul(src=SRC):
    parts = [(src / "SOUL.md").read_text().rstrip()]
    skills = sorted((src / "skills").glob("*.md"))
    if skills:
        parts.append("# Workflows")
        parts += [p.read_text().rstrip() for p in skills]
    return "\n\n".join(parts) + "\n"


def persona_fingerprint(src=SRC):
    """What the model is told and the tools it may call. Taken from the unrendered template, so a rotated token or a
    different model does not change it. The plugin asks Richard to send /new when it changes."""
    h = hashlib.sha256()
    h.update((src / "config.yaml.tmpl").read_bytes())
    h.update(render_soul(src).encode())
    return h.hexdigest()


def write(path, text, mode):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(text)
    os.chmod(tmp, mode)
    os.replace(tmp, path)


def chown_tree(path, uid, gid):
    for root, dirs, files in os.walk(path):
        os.chown(root, uid, gid)
        for f in files:
            os.chown(os.path.join(root, f), uid, gid)


def main(env=os.environ, owner="hermes", src=SRC, home=None):
    home = Path(home or env.get("HERMES_HOME") or "/opt/data")
    try:
        pw = pwd.getpwnam(owner)
        uid, gid = pw.pw_uid, pw.pw_gid
    except KeyError:
        uid = gid = None

    write(home / "config.yaml", render_config(env, src), 0o640)
    write(home / "SOUL.md", render_soul(src), 0o644)
    write(home / ".persona-fingerprint", persona_fingerprint(src) + "\n", 0o644)

    plugins = home / "plugins"
    plugins.mkdir(parents=True, exist_ok=True)
    for plugin in sorted((src / "plugins").iterdir()):
        if not plugin.is_dir():
            continue
        dest = plugins / plugin.name
        shutil.rmtree(dest, ignore_errors=True)
        shutil.copytree(plugin, dest, ignore=shutil.ignore_patterns("tests", "__pycache__", "*.pyc"))

    if uid is not None:
        for p in (home / "config.yaml", home / "SOUL.md", home / ".persona-fingerprint"):
            os.chown(p, uid, gid)
        chown_tree(plugins, uid, gid)
    print(f"[jobbot-seed] wrote config.yaml (model={env.get('JOBBOT_MODEL') or DEFAULTS['JOBBOT_MODEL']}), SOUL.md, plugins")


if __name__ == "__main__":
    sys.exit(main())
