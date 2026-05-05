---
name: oss-contribute
description: Use this skill when the user wants to contribute to an open-source GitHub repository — finding good first issues, claiming an issue, opening a pull request that matches the project's contributor guide, running the project's own verification stack locally, and shepherding the PR through CI and review. Trigger on phrases like "contribute to <repo>", "open source contribution", "find good first issues in <repo>", "open a PR for issue #N in <repo>", "help me land a PR on <github URL>", "fix this issue in <repo URL>", "submit a PR to <repo>". Works for any language (Python, TypeScript, Go, Rust, Java, Ruby) by delegating to whatever the target repo's own AGENTS.md / CONTRIBUTING.md / Makefile prescribes — this skill never invents conventions, it follows the target project's rules.
license: Complete terms in LICENSE.txt
---

# Open-Source Contribution Workflow

## Overview

This skill walks Claude through the full lifecycle of landing a pull request in any GitHub-hosted open-source repository: discovering a tractable issue, deeply understanding the target codebase, designing the change, implementing it under the project's own verification stack, opening a maintainer-friendly PR, and watching it through CI.

**This skill does not invent conventions.** Every formatting, linting, testing, commit-message, and PR-description rule is read out of the target repo (`AGENTS.md` / `CLAUDE.md` / `CONTRIBUTING.md` / `Makefile` / `.github/workflows/` / `.github/PULL_REQUEST_TEMPLATE/`). When the target repo is silent on a rule, fall back to the language-default in `references/verification-stacks.md`.

**Quality bar.** A PR landed via this skill should be indistinguishable from one written by a careful human contributor: small blast radius, tests covering the new behavior, clean against the project's own lint/type/test commands, a PR description that fills the project's template, and zero references to AI tooling in the PR body or commit messages unless the user explicitly asks for attribution.

---

# Process

The workflow is eight phases. **Pause for explicit user confirmation before any action that pushes code, opens a PR, or posts a public comment.** A list of every confirm-before-acting checkpoint is at the bottom under "Confirmation gates."

## Phase 1 — Repo recon

Before reading any issue, learn the project's rules. See `references/repo-recon-checklist.md` for the full list.

Read in this order. Use `mcp__github__get_file_contents` for read-only repo browsing; fall back to `gh api repos/<owner>/<repo>/contents/<path>` if the MCP server is unavailable.

1. `AGENTS.md` (root) — modern contributor charters live here. May supersede `CONTRIBUTING.md`.
2. `CLAUDE.md` (root) — usually points at AGENTS.md but may add Claude-specific rules.
3. `CONTRIBUTING.md` (root or `.github/`).
4. `README.md` — for stack and quickstart.
5. `Makefile` / `pyproject.toml` / `package.json` / `go.mod` / `Cargo.toml` — to identify the verification stack.
6. `.github/workflows/*.yml` — to enumerate CI checks that block merge. Note which jobs are `Required`.
7. `.github/PULL_REQUEST_TEMPLATE/` (or `.github/pull_request_template.md`) — for the body shape.
8. `.github/ISSUE_TEMPLATE/` — useful for understanding how issues are labeled and categorized.

Extract a one-page recon brief (in your own working memory, not as a file) covering:

- Verification stack: exact commands to run locally before committing.
- Code style: line length, comment style (full sentences? imperative?), import order.
- Public-API rules: positional-compatibility, deprecation policy.
- Commit / branch naming: e.g. `feat/<short>` vs `feat/<issue#>-<short>`.
- PR template sections: what every section must contain.
- Known landmines: "coordinated updates across N files when adding X" rules.

If `AGENTS.md` says to use a specific skill (e.g. `$code-change-verification`), invoke that skill in Phase 6 — don't override its commands with our defaults.

## Phase 2 — Issue triage

Goal: surface 3–5 candidate issues ranked by mergeability for a first-time contributor.

```
gh issue list -R <owner>/<repo> --state open --limit 100
```

Or via MCP: `mcp__github__list_issues` with `state: OPEN`, `orderBy: CREATED_AT`, `direction: DESC`, `perPage: 50`.

If the response is too large for the context window, the tool will save it to disk and return a path. Delegate parsing to a subagent with explicit instructions to slice the file in 80k-character spans and return a structured table with `number / title / labels / comment_count / created / one-line summary`. Do not try to read a 70k+ character JSON blob inline.

Score each issue with the rubric in `references/issue-scoring-rubric.md`. Six axes: blast radius, scope clarity, controversy, prior-art status, contributor-guide alignment, age. Present the top 5 in a table:

```
| # | Title | Why it ranks | Watchouts |
|---|-------|--------------|-----------|
```

Wait for the user to pick. Do not advance past this gate without an explicit pick.

## Phase 3 — Deep read of the chosen issue

Once the user picks issue `#N`:

1. Read `mcp__github__issue_read get` for the body.
2. Read `mcp__github__issue_read get_comments` for the discussion.
3. Read `mcp__github__issue_read get_sub_issues` if the issue tracker uses sub-issues.
4. Search for prior-art PRs:
   - `gh pr list -R <owner>/<repo> --search "is:pr #N"`
   - `gh pr list -R <owner>/<repo> --search "<verbatim title fragment>"`
   - Scan recent merged PRs touching the same files.
5. If a prior PR exists, read its diff and check_runs. Determine: is it stale? does it have tests? is it dirty? Decide whether to defer, supersede with explicit acknowledgment, or differentiate the scope.

If the issue is already actively being worked (a non-stale PR exists, or a recent comment from someone saying "I'll work on this") — surface this to the user and pause. Do not proceed without explicit go-ahead.

## Phase 4 — Tree-of-thoughts implementation design

Never jump to the easiest patch without considering alternatives.

Present 2–4 implementation shapes in a comparison table:

```
| Shape | Scope | Merge probability | Verdict |
|-------|-------|-------------------|---------|
| A. Minimal …    | … | … | reject/chosen |
| B. With tests … | … | … | … |
| C. + Refactor … | … | … | … |
| D. Full scope … | … | … | … |
```

Each row needs a one-line "why" for its merge-probability score. Pick one with a short justification.

Write a concise plan to a working memory location (do not write it into the target repo). Include:

- Files to modify (with line-number anchors if known).
- Functions/utilities to reuse from the existing codebase.
- Tests to add (count, names, coverage matrix).
- Out of scope items, with explicit reasoning.
- Risks with mitigations.

**Confirmation gate:** show the user the plan and the chosen shape. Wait for confirmation before any code edits. If the user picks a different shape, regenerate the plan and confirm again.

## Phase 5 — Setup (idempotent)

All steps are idempotent. Detect existing artifacts and reuse rather than recreating.

### Fork

```bash
gh auth status                                            # confirm authenticated as user
gh repo view <user>/<repo> >/dev/null 2>&1 \
  || gh repo fork <owner>/<repo> --clone=false            # fork only if missing
```

Some MCP GitHub tokens lack `repo` scope and will 403 on `mcp__github__fork_repository`. If that happens, run the gh CLI fallback. If gh isn't authenticated, see `references/troubleshooting.md` for the device-flow login.

### Local clone

```bash
CLONE_DIR="${OSS_HOME:-$HOME/oss}/<repo>"
[ -d "$CLONE_DIR/.git" ] \
  || gh repo clone <user>/<repo> "$CLONE_DIR"
cd "$CLONE_DIR"
```

On Windows, default to `D:/open-source/<repo>` if `D:` exists, else `~/oss/<repo>`.

### Upstream remote and main sync

```bash
git remote get-url upstream >/dev/null 2>&1 \
  || git remote add upstream https://github.com/<owner>/<repo>.git
git fetch upstream main 2>&1 | tail -3
git checkout main
git merge upstream/main --ff-only
```

If main is the wrong default branch, detect via `gh repo view <owner>/<repo> --json defaultBranchRef --jq .defaultBranchRef.name`.

### Feature branch

Branch naming follows the contributor guide. Default if not specified: `feat/<issue#>-<short-kebab>` for features, `fix/<issue#>-<short-kebab>` for bugs, `docs/<issue#>-<short-kebab>` for docs.

```bash
BRANCH="feat/<N>-<short>"
git show-ref --quiet "refs/heads/$BRANCH" \
  && git checkout "$BRANCH" \
  || git checkout -b "$BRANCH"
```

If the branch already exists with unrelated commits, ask the user before resetting it. Do not silently clobber prior work.

### Defensive git auth setup

```bash
gh auth setup-git
```

Run this once before any push. It overrides Windows Credential Manager's stale credentials, which is the #1 cause of mysterious 403s on push.

## Phase 6 — Implement

### Apply edits

Use the `Edit` tool for surgical changes; `Write` only for new files. Preserve existing indentation and comment style. Do not reformat unrelated lines.

Style rules to internalize from Phase 1's recon brief:

- Comments as full sentences ending with periods (if the project enforces this).
- Imports kept sorted by the project's tool (`isort`, `ruff`, `eslint --fix`).
- Public APIs: respect positional-argument compatibility — append new params, never insert.
- Docstrings: match the project's existing style (Google / NumPy / one-liner / etc.).

### Run the verification stack

Use the exact commands from the project's contributor guide. If absent, route through the cookbook in `references/verification-stacks.md`. Run in this order; do not skip steps:

1. **Format** (`make format` / `uv run ruff format` / `pnpm format` / `cargo fmt` / `gofmt -w`).
2. **Lint** (`make lint` / `ruff check` / `pnpm lint` / `cargo clippy` / `go vet`).
3. **Typecheck** (`make typecheck` / `mypy` + `pyright` / `tsc --noEmit` / N/A for Go/Rust beyond compile).
4. **Tests** (`make tests` / `pytest` / `pnpm test` / `cargo test` / `go test ./...`).

Do not run the test suite in parallel with the format/lint stages on the first pass — failures cascade and obscure root causes.

### Diagnose failing tests

If a test fails, run it in isolation with `-v` to see the full traceback. Before assuming the failure is yours:

```bash
git stash
<re-run the same test command>          # fail or pass on clean main?
git stash pop
git checkout <auto-modified files>      # discard lockfile/uv.lock noise
```

If the test fails on clean main too, it is **not your bug** — note it in the PR description's "Test plan" section as a pre-existing failure unrelated to this change. Do not try to fix it.

### Type-narrowing footguns

Common type-checker complaints in this exact pattern:

- mypy: `Incompatible return value type (got "object | Any", expected "str | None")` on TypedDict union narrowing → wrap with `cast(str | None, ...)` or `str(x) if x is not None else None`.
- mypy: `dict[str, V].get()` on a heterogeneous TypedDict union returns `object` → same fix.
- pyright: complaint on `Any | None` when union has Pydantic models → use `getattr(obj, "x", None)` not direct attribute access.

See `references/troubleshooting.md` for the full list.

### Lockfile noise

`uv sync`, `pnpm install`, `cargo update`, `go mod tidy` may rewrite lockfiles even when no semantic change is needed. After verification, run `git status` and `git checkout <lockfile>` if the diff is unrelated to the change. Never commit lockfile churn unrelated to the actual change.

## Phase 7 — Commit, push, open PR

### Commit

Match the project's commit-message convention (Conventional Commits, 50/72, custom). Default to:

```
<type>(<scope>): <short imperative summary>

<body explaining the why, wrapped at 72>

Closes #<N>
```

If the repo requires DCO sign-off (look for `dco.yml` workflow or `Signed-off-by` in recent commits): use `git commit -s`.

**Confirmation gate:** show the user the diff (`git diff --stat` + `git diff`) and the proposed commit message. Wait for explicit OK before committing.

### Push

```bash
gh auth setup-git                                     # defensive, no-op if already set
git push -u origin "$BRANCH"
```

If push 403s with "denied to <wrong-account>", that's stale Windows credentials despite `setup-git`. See `references/troubleshooting.md`.

**Confirmation gate:** confirm with the user before pushing.

### Open the PR

Use `gh pr create -R <owner>/<repo>`. Body fills the project's template (read from Phase 1).

Default body shape — adapt to the project's actual template:

```markdown
### Summary

<2–4 sentences: gap, fix, scope.>

### Test plan

<Bullet list of verification commands that passed and the new tests added.>

### Issue number

Closes #<N>

### Notes

<Optional: prior PR acknowledgment, known limitations, follow-up issues.>

### Checks

- [x] I've added new tests (if relevant)
- [x] I've added/updated the relevant documentation
- [x] I've run `make lint` and `make format`
- [x] I've made sure tests pass
```

Tick checks **truthfully**. If you didn't add tests, leave the box unchecked and say so.

Default flags:
- `--maintainer-can-modify` so reviewers can rebase without ping-pong.
- No `--draft` unless there's a real reason; ready for review beats draft for landing speed.
- `--base <default-branch>` (read in Phase 5).
- `--head <fork-user>:<branch>`.

**Confirmation gate:** show the user the rendered title and body. Wait for explicit OK before opening.

See `references/pr-description-template.md` for three real-world examples (small bug fix / feature add / docs change).

## Phase 8 — Watch CI and review

Start a `Monitor` task on the PR's check runs:

```bash
prev=""; while :; do
  raw=$(gh pr checks <N> -R <owner>/<repo> --json name,bucket,state 2>/dev/null || echo "[]")
  cur=$(echo "$raw" | jq -r '.[] | select(.bucket!="pending") | "\(.name): \(.bucket)"' | sort)
  diff <(echo "$prev") <(echo "$cur") | grep --line-buffered "^>" | sed 's/^> //'
  prev="$cur"
  pending=$(echo "$raw" | jq -r '.[] | select(.bucket=="pending") | .name' | wc -l)
  [ "$pending" -eq 0 ] && [ -n "$cur" ] && { echo "===ALL_DONE==="; echo "$cur"; break; }
  sleep 25
done
```

### First-time-contributor gate

If the PR shows a yellow `1 workflow awaiting approval` banner with checks in `Expected — Waiting for status to be reported`, this is GitHub's safety gate for new external contributors. **No action needed from us** — a maintainer must click "Approve and run workflows." Surface this calmly to the user; do not escalate.

### CI failure

If a check fails:

1. Read the failure with `gh run view <run-id> --log-failed`.
2. Reproduce locally.
3. Fix.
4. Push to the same branch (the open PR auto-updates; no need to open a new one).
5. Wait for CI to re-run.

### Reviewer feedback

If a reviewer requests changes:

1. Read every comment carefully — push back politely on requests that violate your understanding of the issue, but default to deferring to maintainers on style.
2. Address each comment in a separate commit when feasible (helps reviewers re-read).
3. Reply to the review thread with a one-liner pointing to the commit that addressed the comment.
4. After all comments addressed, request re-review via the PR UI.

### Don't open another PR

While this PR is gated, in review, or has unresolved comments — **do not open another PR to the same repo**. Two open PRs from a fresh contributor look like contribution spam and dilute reviewer attention.

---

# Confirmation gates (mandatory pauses)

Before any of the following actions, pause and ask the user explicitly:

1. Picking an issue (Phase 2 → wait for user pick).
2. Implementation shape (Phase 4 → wait for OK on the chosen branch).
3. Plan acceptance (Phase 4 → wait for OK on the plan file content).
4. Commit (Phase 7 → show diff + message, wait for OK).
5. Push (Phase 7 → wait for OK).
6. PR creation (Phase 7 → show rendered title + body, wait for OK).
7. Comments on the issue or PR by us (any phase → wait for OK on the exact comment text).
8. Force-push, branch reset, or destructive git ops (any phase → wait for OK).

Skip a confirmation gate only if the user has explicitly granted blanket approval for the rest of this session ("just go").

---

# Anti-patterns

See `references/anti-patterns.md` for the full list with reasoning. Brief version:

- Don't open multiple PRs while the first is gated.
- Don't bikeshed-refactor surrounding code in the same PR.
- Don't `--amend` after pushing — push a new commit.
- Don't `# noqa` / `# type: ignore` to silence checker errors unless the project itself uses that escape hatch.
- Don't add a CHANGELOG entry unless the contributor guide says to.
- Don't write "tests added" in the PR Test plan; list the test names.
- Don't promise verification you didn't run.
- Don't reference Claude or AI assistance in the PR body or commits unless the user explicitly opts in.

---

# Output formats

## Issue scoring table (Phase 2 output)

```
| # | Title (truncated) | Score | Why | Watchouts |
|---|---|---|---|---|
| 2886 | Add convenience properties to ToolCallItem | 9/10 | Additive, reference PR exists, tight scope | PR #2887 stale draft — supersede with note |
```

## Implementation-shape comparison table (Phase 4 output)

```
| Shape | Scope (files / lines) | Merge probability | Verdict |
|-------|----------------------|-------------------|---------|
| A. Code only | items.py, ~22 lines | low — no value-add over existing draft | reject |
| **B. Code + tests** | items.py + test file, ~125 lines | **high — clear improvement** | **chosen** |
```

## Plan summary message (Phase 4 → user)

> Picked **Shape B** — adds `tool_name` / `call_id` properties on `ToolCallItem` (lines 362–376 of `src/agents/items.py`) plus 8 unit tests in `tests/test_items_helpers.py`. Reuses the existing `ToolApprovalItem._extract_call_id` pattern. Out of scope: runner threading for `ToolCallOutputItem.tool_name` (issue defers this; AGENTS.md flags multi-file coordination cost).
>
> Verification: `make format && make lint && make typecheck && make tests`. Expected new test count: 26 → 34.
>
> Risks: a draft PR (#2887) already exists for this issue. Plan acknowledges it in our PR body and offers to defer.
>
> Reply **go** to start coding, or push back with edits.

## Final summary (after PR opens)

> ✅ PR opened: <url>
> Branch: `<branch>` on `<user>/<repo>`
> Diff: <files changed>, <+lines>/<-lines>
> Verification (all green locally): `<commands>`
> CI status: <queued / running / N approvals needed / first-time gate>
> Next step: <wait / address review / etc.>

---

# Skill memory

If the user has the auto-memory system enabled (i.e. `MEMORY.md` and `feedback_*.md` files exist under `~/.claude/projects/.../memory/`), record:

- The user's preferred OSS contribution destination (e.g. always under `D:/open-source/`).
- Their GitHub handle.
- Any project-specific feedback they give during a contribution ("don't reference Claude in PR bodies for this org").

Do not store credentials, API keys, or anything sensitive.

---

# When to NOT use this skill

- The user is asking a general question about a library — use `claude-code-guide` or `context7` instead.
- The user is debugging *their own* code, not contributing upstream.
- The target is a private repo with no PR workflow — just commit and push.
- The target is on GitLab / Codeberg / Gitea / Bitbucket — this skill is GitHub-specific in v1.
- The user wants to do something destructive (force-push to main, mass-close issues) — refuse and recommend a safer path.
