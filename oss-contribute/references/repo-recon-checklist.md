# Repo Recon Checklist

The first ten minutes of any open-source contribution belong to **reading**, not coding. Skipping this phase is the single biggest reason new contributor PRs sit unmerged — they violate a project rule that was written down somewhere obvious.

This checklist runs at the start of Phase 1 in `SKILL.md`. Read every file that exists; note the absences (a missing `AGENTS.md` is itself a signal — fall back to `CONTRIBUTING.md`).

## Read order

| # | Path | Why |
|---|------|-----|
| 1 | `AGENTS.md` (root) | Modern contributor charter. Increasingly the canonical location. May supersede `CONTRIBUTING.md`. |
| 2 | `CLAUDE.md` (root) | Often a one-liner pointing at AGENTS.md, but may add Claude-specific rules. |
| 3 | `CONTRIBUTING.md` (root or `.github/`) | Older convention. Still authoritative when AGENTS.md is absent. |
| 4 | `CODE_OF_CONDUCT.md` | Sets tone; rarely changes contribution logic but tells you how to communicate. |
| 5 | `README.md` | Stack identification + quickstart commands. |
| 6 | `Makefile` | If present, this is the verification stack — every common target is wired up here. |
| 7 | `pyproject.toml` / `package.json` / `go.mod` / `Cargo.toml` / `pom.xml` / `Gemfile` | Identifies language and build tooling. |
| 8 | `.github/workflows/*.yml` | Enumerates CI checks that block merge. Read every required job. |
| 9 | `.github/PULL_REQUEST_TEMPLATE/` (or `.github/pull_request_template.md`) | The PR body shape reviewers expect. |
| 10 | `.github/ISSUE_TEMPLATE/` | Useful for understanding label conventions. |
| 11 | `tests/README.md` (or equivalent) | Snapshot rules, fixture conventions, slow-test markers. |
| 12 | `docs/scripts/` (if exists) | Auto-generation scripts for ref docs — might affect your docstrings. |
| 13 | `.pre-commit-config.yaml` | Hooks that will rewrite files on commit. |
| 14 | `.editorconfig` | Indent / line-ending defaults. |

## Recon brief — what to extract

After reading, hold the following one-page brief in working memory (do not write it to a file in the target repo):

### Verification stack

The exact ordered command list to run before committing. Examples:

- openai/openai-agents-python: `make format && make lint && make typecheck && make tests`
- astral-sh/uv: `cargo fmt && cargo clippy --workspace -- -D warnings && cargo nextest run`
- vercel/next.js: `pnpm install && pnpm lint && pnpm typecheck && pnpm test`

If the project's contributor guide names a meta-skill or wrapper (e.g. `$code-change-verification` in openai-agents-python's AGENTS.md), prefer running that.

### Code style rules

- Line length (defaults: ruff 88, black 88, eslint 80–100, gofmt 80–120).
- Comment style: full sentences with periods? Imperative tense? No comments unless non-obvious?
- Docstring style: Google / NumPy / one-liner / none.
- Import order: alphabetical / by-section / framework-first.

### Public API rules

Look specifically for sections titled "API stability", "Compatibility", "Public API", or "Positional Compatibility". Examples to internalize:

- "Treat the parameter and dataclass field order of exported runtime APIs as a compatibility contract." → never insert new args in the middle.
- "Keep released schema versions readable, and feel free to renumber or squash unreleased schema versions before release." → unreleased schema breaks are OK; released ones are not.
- "Add a deprecation warning for at least one minor release before removing." → never delete in one PR.

### Commit and branch naming

- Commit format: Conventional Commits (`feat:` / `fix:`) / 50-char summary + 72-char body / custom.
- DCO sign-off required? Look for a `dco.yml` workflow or `Signed-off-by:` lines in recent commits.
- Branch name pattern: `feat/<short>` / `<username>/<short>` / `feature/<jira-id>`.

### PR template requirements

- Required sections (Summary / Test plan / Issue number / Checks / Screenshots).
- Required checkboxes — note which are honor-system and which are CI-enforced.
- Issue-linking syntax (`Closes #N` / `Fixes: ABC-123`).

### Known landmines

Anything in the contributor guide that says "when adding X, also update Y, Z, and W" — these are coordinated-update rules that will sink a PR if missed. Examples:

- "New tool item types require coordinated updates across `items.py`, `run_steps.py`, `turn_resolution.py`, `tool_execution.py`, `stream_events.py`, `run_state.py`, `session_persistence.py`."
- "When you bump `CURRENT_SCHEMA_VERSION`, also add or update the matching entry in `SCHEMA_VERSION_SUMMARIES`."

## Language & verification stack detection

Use the cookbook in `verification-stacks.md` to map detection signals to commands. Decision tree:

```
If Makefile has format/lint/typecheck/tests targets:
    use `make <target>` for everything
elif pyproject.toml exists:
    if uv.lock: `uv run <tool>`
    elif poetry.lock: `poetry run <tool>`
    else: `python -m <tool>` in a venv
elif package.json exists:
    if pnpm-lock.yaml: pnpm
    elif yarn.lock: yarn
    elif bun.lockb: bun
    else: npm
elif go.mod exists:
    `go fmt ./... && go vet ./... && go test ./...`
elif Cargo.toml exists:
    `cargo fmt && cargo clippy && cargo test`
elif pom.xml: `./mvnw verify`
elif build.gradle*: `./gradlew check`
```

## Fallbacks when nothing is documented

If the repo has no `AGENTS.md`, `CONTRIBUTING.md`, or visible `Makefile`:

1. Read the last 10 merged PRs. Note the commit message format, the typical PR body shape, and which CI checks gate them.
2. Read the last 5 reviewed-but-not-merged PRs to see what reviewers commonly request changes on.
3. Run the language defaults from `verification-stacks.md`. Don't invent new conventions.

## Time budget

Aim for 10–15 minutes of recon for a repo you've never touched. Less for a repo you've contributed to before. Skipping recon to "save time" usually costs 2–3 round-trips with reviewers later — false economy.

## Recon output

The skill should not write the recon brief to disk. Hold it in working memory and reference it during Phases 4 (implementation design) and 6 (implement). The brief is what the user is paying you to produce — they don't need to read it, they need to see its rules show up in the PR.
