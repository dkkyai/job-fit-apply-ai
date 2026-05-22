# Contributing

Thank you for your interest in contributing to JD Pipeline.

## Getting Started

1. Fork the repository and create a branch from `main`.
2. Copy `.env.example` to `.env` and fill in at least one LLM backend (Ollama is free and runs locally).
3. Run `./gradlew test` to verify your setup.

## Development Guidelines

- **Nodes** live in `src/main/kotlin/com/jd/pipeline/nodes/`. Each node is a pure function on `JDState` — keep side effects isolated to clients.
- **Prompt files** live in `src/main/resources/skills/`. Edit them without recompiling.
- **Config** is centralized in `Config.kt`. Add new env vars there, not inline.
- Write tests for new nodes. The test fixtures pattern in `src/test/kotlin/com/jd/pipeline/fixtures/` is the preferred approach.

## Pull Request Process

1. Update tests to cover new behavior.
2. Run the full test suite: `./gradlew test`.
3. Keep PRs focused — one logical change per PR.
4. Describe the motivation in the PR description. Link any related issues.

## Reporting Issues

Use [GitHub Issues](../../issues) for bugs and feature requests. Include:
- What you ran and what you expected
- Relevant log output (redact any API keys)
- Your Java version (`java -version`) and OS

## Code of Conduct

This project follows the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md). Be respectful and constructive.
