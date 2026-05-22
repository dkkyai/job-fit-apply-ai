# Security Policy

## Supported Versions

Only the latest commit on `main` is actively maintained.

## Reporting a Vulnerability

Please **do not** open a public GitHub issue for security vulnerabilities.

Report vulnerabilities by emailing the maintainer directly (see the GitHub profile). Include:
- A description of the vulnerability and its potential impact
- Steps to reproduce or a proof-of-concept
- Any suggested mitigations

You will receive a response within 7 days. If the issue is confirmed, a fix will be prioritized and a patched release will be made before public disclosure.

## Scope

This pipeline handles:
- **Gmail OAuth tokens** — stored locally in `tokens/` (gitignored). Treat these like passwords.
- **API keys** — loaded from `.env` (gitignored). Never commit real keys.
- **LLM prompt injection** — recruiter email content is treated as untrusted input. The `DRAFT_REPLY_SKILL.md` prompt includes an explicit injection-resistance instruction. If you discover a bypass, please report it.

## Best Practices for Operators

- Store all secrets in `.env`, never hardcode them.
- Rotate API keys if you suspect exposure.
- Revoke and re-issue Gmail tokens (`--reauth`) if the token file is ever accidentally committed.
- Run with the principle of least privilege — the Gmail scope is `gmail.modify`, which is the minimum needed to read emails and create drafts.
