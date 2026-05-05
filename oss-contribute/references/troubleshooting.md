# Troubleshooting

Real failure modes encountered during open-source contributions, with the exact recovery command. Indexed by symptom; check here before improvising a fix.

## Auth and permissions

### `mcp__github__fork_repository` returns 403 "Resource not accessible by personal access token"

**Cause:** The Claude Code GitHub MCP server is using a token without `repo:write` scope, or the org has restricted forking.

**Fix:** Fall back to the gh CLI:

```bash
gh auth login -h github.com -p https -w -s repo,read:org
gh repo fork <owner>/<repo> --clone=false
```

The `-w` flag uses device flow with a browser. The user copies the one-time code into the GitHub login page. Same recovery applies when `mcp__github__create_branch` or `mcp__github__push_files` 403s.

### `git push` fails with "Permission denied to <wrong-account>"

**Cause:** Windows Credential Manager has stale credentials for a different GitHub account, and Git is using those instead of the gh-authenticated user.

**Fix:**

```bash
gh auth setup-git
git push -u origin <branch>
```

`gh auth setup-git` overrides the Windows credential manager for github.com URLs. Run this defensively before every first push from a new clone.

If it still fails, the credential manager is being aggressive:

```bash
git config --global credential.helper ""              # disable credential helper
git remote set-url origin https://<user>:$(gh auth token)@github.com/<user>/<repo>.git
git push -u origin <branch>
```

This embeds the gh token directly in the remote URL — works but slightly less secure. Reset after with `git remote set-url origin https://github.com/<user>/<repo>.git`.

### `gh auth status` says "not logged in"

**Fix:** Run the device-flow login:

```bash
gh auth login -h github.com -p https -w -s repo,read:org
```

If the user is in a sandbox without browser access, they need a Personal Access Token: tell them to generate one at https://github.com/settings/tokens (scope: `repo`, `read:org`) and export it:

```bash
export GH_TOKEN=ghp_xxxxxxxxx
gh auth status
```

## Git noise and lockfiles

### `git stash pop` fails with "your local changes would be overwritten"

**Cause:** Some file was modified between the stash and the pop — usually a lockfile.

**Fix:**

```bash
git checkout <conflicting-file>      # discard the working-tree change
git stash pop
```

Then re-discard the lockfile diff:

```bash
git checkout uv.lock                 # or pnpm-lock.yaml, Cargo.lock, etc.
```

### `uv sync` rewrites `uv.lock` with what looks like CRLF noise

**Cause:** Windows line-ending normalization between `uv` versions or between local and CI versions.

**Fix:** After running verification:

```bash
git checkout uv.lock
```

Don't commit lockfile churn unrelated to the actual change.

### Pre-commit hook rewrites my files

**Cause:** Project uses `pre-commit` with auto-fixing hooks (Black, ruff, eslint --fix).

**Fix:** Stage the rewritten files and re-commit:

```bash
git add <rewritten-files>
git commit -m "<same message>"
```

Don't fight the hook. If it keeps rewriting, your local format command is producing different output than the hook — sync your tool versions to the project's `.pre-commit-config.yaml` versions.

## CI gates

### "1 workflow awaiting approval" yellow banner on the PR

**Cause:** GitHub's first-time-contributor safety gate. New external contributors don't get auto-running workflows.

**Fix:** Nothing on our end. A maintainer must click "Approve and run workflows." Tell the user this is normal and not a problem with their PR. Do NOT:
- Force-push to try to trigger CI (won't help).
- Open another PR (treats reviewer attention badly).
- Comment to ask for approval (rude before a few days have passed).

After 3 business days of silence, a polite single-comment nudge is acceptable: "Hi! Anything blocking review on this?"

### CI fails with errors not seen locally

**Cause:** Local tool versions diverge from CI's pinned versions.

**Fix:** Read `.github/workflows/*.yml` for the pinned tool versions. Match locally:

```bash
# Example: CI uses uv 0.11.7, you have 0.9.x
uv self update --version 0.11.7
```

For Python toolchain mismatches:

```bash
UV_PROJECT_ENVIRONMENT=.venv_py312 uv sync --python 3.12 --all-extras --all-packages --group dev
UV_PROJECT_ENVIRONMENT=.venv_py312 uv run pytest
```

### Tests pass locally on Linux/macOS, fail on Windows CI

**Cause:** Path separators, line endings, file-system case sensitivity.

**Fix:** Look for `os.sep`, hardcoded `/` in path strings, fixtures with case-conflicting filenames. Use `pathlib.Path` for cross-platform safety. If the repo's CI exercises Windows but you can't, mention this in the PR body and trust CI.

## Type-checker complaints

### mypy: "Incompatible return value type (got 'object | Any', expected 'str | None')"

**Cause:** `dict.get(key)` on a heterogeneous TypedDict union returns `object`, not the union of value types.

**Fix:** explicit cast:

```python
from typing import cast
return cast(str | None, raw.get("key") or raw.get("fallback"))
```

Or coerce-and-guard:

```python
val = raw.get("key") or raw.get("fallback")
return str(val) if val is not None else None
```

### pyright: complains on `raw_item.attr` for a Pydantic-or-dict union

**Cause:** Pyright doesn't narrow well across BaseModel + dict unions.

**Fix:** Use `getattr` + isinstance branches:

```python
if isinstance(raw_item, dict):
    return raw_item.get("attr")
return getattr(raw_item, "attr", None)
```

This is the project's own pattern in `ToolApprovalItem._extract_call_id`. Mirror it.

## Pre-existing failures

### A test fails locally but I'm sure I didn't change anything related

**Cause:** Pre-existing flake or actual broken state on `main`.

**Fix:** Confirm with the stash trick:

```bash
git stash
<run the same test command>
git stash pop
```

If it fails with both your changes and without them, it's pre-existing. Note in the PR's Test plan: "Pre-existing tests `<name>` and `<name>` fail on clean `main` too; not introduced by this PR." Don't try to fix them in the same PR.

If you really must fix them, do it in a separate PR that lands first.

## CLA / DCO

### CLA bot blocks the PR

**Cause:** The org requires a Contributor License Agreement.

**Fix:** Tell the user to click the link the bot posted, sign once, push triggers re-check. Subsequent PRs from the same account skip the prompt.

### DCO bot rejects my commits

**Cause:** Repo requires `Signed-off-by:` trailer; your commits don't have it.

**Fix:** Add sign-off retroactively:

```bash
git rebase --signoff <base>          # adds sign-off to every commit since <base>
git push --force-with-lease
```

`--force-with-lease` is safer than `--force` — it refuses if the remote has been updated by someone else since your last fetch. **Force-push only to your own feature branch on your fork**, never to `main` / `master` / `develop` / a shared branch.

## Branch and remote setup

### "remote upstream already exists"

```bash
git remote -v                                 # confirm what's there
git remote set-url upstream https://github.com/<owner>/<repo>.git
git fetch upstream main
```

### Default branch is not `main`

```bash
DEFAULT=$(gh repo view <owner>/<repo> --json defaultBranchRef --jq .defaultBranchRef.name)
git fetch upstream "$DEFAULT"
git checkout "$DEFAULT"
git merge "upstream/$DEFAULT" --ff-only
```

Use `$DEFAULT` everywhere instead of hardcoding `main`. Common alternatives: `master`, `develop`, `trunk`.

### Branch already exists with unrelated commits

Don't silently clobber:

```bash
git branch <branch>                           # if it exists, this errors
# resolve by either:
git checkout <branch>                         # reuse if the commits are yours
# or
git checkout -b "<branch>-2"                  # use a different name
```

Ask the user before resetting any branch with commits.

## When in doubt

If a recovery path isn't documented here:

1. Read the failing tool's error message in full — modern tools include a fix suggestion.
2. Search the repo's issues for the exact error string — likely there's a known workaround.
3. If neither helps, surface the failure to the user with the full output and ask before improvising.

Never `--force`, `--no-verify`, `git reset --hard`, or `rm -rf` to make a problem go away. Investigate the root cause.
