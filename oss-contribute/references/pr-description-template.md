# PR Description Templates

Three real-world templates for the most common contribution shapes. **Always start from the target repo's actual `.github/PULL_REQUEST_TEMPLATE/` content** — these are starting points, not replacements.

## General principles

1. **Lead with why, not what.** Reviewers can read the diff for the what.
2. **Test plan must be specific.** "Added tests" is a non-statement. List the test names and what each one covers.
3. **Tick the checklist truthfully.** If you didn't add tests, leave that box unchecked and explain why in the body.
4. **Link the issue with `Closes #N`** so GitHub auto-closes it on merge. `Refs #N` for related-but-not-closing.
5. **Acknowledge prior PRs.** If your work supersedes someone's draft, say so explicitly and offer to defer. This builds trust with maintainers and the original author.
6. **Keep the title under 70 characters.** Long titles get truncated in the PR list and notification emails.
7. **Don't reference Claude or AI tooling** unless the user opts in. Most maintainers don't care; some explicitly forbid it.

## Template 1 — Small bug fix

For one-file fixes, < 50 lines of source:

```markdown
### Summary

`<Short description of the bug>` — `<one-sentence root cause>`. This PR `<one-sentence fix>`.

Reproducer:

\```python
<minimal repro>
\```

After this change, `<expected behavior>`.

### Test plan

- New regression test: `<test_name>` (`<test_file>`) covering the exact reproducer above.
- Existing tests under `<area>` continue to pass: `<command>`.

Verified locally:
- `<format command>` — pass
- `<lint command>` — pass
- `<typecheck command>` — pass
- `<test command>` — `<n>` passed (was `<n-1>` before; +1 new test)

### Issue number

Closes #<N>

### Checks

- [x] I've added new tests (if relevant)
- [ ] I've added/updated the relevant documentation (no user-facing change)
- [x] I've run `make lint` and `make format`
- [x] I've made sure tests pass
```

## Template 2 — Additive feature

For PRs that add new public surface (a function, property, config option):

```markdown
### Summary

<Existing class/module> exposes <ergonomic accessor> for X. <Sibling class> lacks the same convenience, forcing callers to write `<workaround pattern>`. This PR adds <list of new properties/methods> following the same pattern as <existing reference>.

All changes are additive and backward-compatible. <Out-of-scope thing> is intentionally left out — <why>.

### Test plan

Added <N> unit tests in `<test_file>`:

| # | Subject | Path covered |
|---|---------|--------------|
| 1 | `<name>` | <branch> |
| 2 | `<name>` | <branch> |

Verified locally:
- `<format>` — pass
- `<lint>` — pass
- `<typecheck>` — pass
- `<tests command>` — <n> passed (was <n-N>)
- Broader sweep `-k "<related>"` — <count> passed; <count> pre-existing unrelated failures confirmed via `git stash` round-trip on `main`.

### Issue number

Closes #<N>

### Notes

<Optional acknowledgment of prior PR, if applicable>
PR #<X> by @<author> proposed the same surface but has been a stale draft for <N> days with merge conflicts and no tests. This PR is rebased on current `main` and adds the missing test coverage. Happy to defer if maintainers prefer the original.

### Checks

- [x] I've added new tests (if relevant)
- [x] I've added/updated the relevant documentation (auto-generated <doc-path> picks up the new docstrings via <docs-tool>)
- [x] I've run `make lint` and `make format`
- [x] I've made sure tests pass
```

## Template 3 — Docs change

For documentation-only PRs:

```markdown
### Summary

`<page>` was missing/incorrect about `<topic>`. This PR <fix description>.

Before:
> <quoted before>

After:
> <quoted after>

### Test plan

- `make build-docs` (or `pnpm docs:build`) succeeds locally.
- Spot-checked the rendered page in `<preview command>`.
- No code paths changed; full test suite not re-run.

### Issue number

Closes #<N>

### Checks

- [ ] I've added new tests (if relevant) — N/A, docs only
- [x] I've added/updated the relevant documentation
- [x] I've run `make lint` and `make format` (docs lint where applicable)
- [x] I've made sure tests pass (no code changes)
```

## DCO sign-off

If the repo enforces DCO (look for `.github/workflows/dco.yml` or a recent commit ending in `Signed-off-by: ...`), commits must be signed off:

```bash
git commit -s -m "..."
```

This appends `Signed-off-by: Your Name <your@email>` to the commit body. The PR description does not need any additional DCO mention; the bot reads the commit trailer.

## CLA

If the repo uses a CLA (CLA Assistant, EasyCLA, or similar), a bot will comment on your PR with a sign-link the first time. Tell the user to sign once via the link; subsequent PRs from the same account skip the prompt.

## Title conventions

Match the project's commit-message style. Common patterns:

- **Conventional Commits**: `feat(scope): <imperative>`, `fix(scope): <imperative>`. Many repos enforce this via a CI check.
- **No prefix**: `Add convenience properties to ToolCallItem`. Common in older repos.
- **Issue prefix**: `[#NNNN] Add convenience properties to ToolCallItem`.

Read the last 20 merged PRs to see what landed; mirror that.

## What NOT to put in the PR body

- "Generated with Claude Code" / "Co-authored-by: Claude" — unless the user explicitly opts in.
- Broad performance claims you didn't measure ("makes things faster").
- "This should fix it" — be specific or don't say it.
- Long narrative about your debugging journey — reviewers want the resolution, not the trail.
- Screenshots of failing tests "before" — only include screenshots for visual changes.
- Promises about future PRs ("will follow up with X") unless you actually intend to do them.

## Final pre-submission check

Before clicking "Create pull request", verify:

- [ ] Title is < 70 chars and follows project convention.
- [ ] Body fills every required section of the project's PR template.
- [ ] `Closes #N` line is present (or `Refs #N` if not auto-closing).
- [ ] Every checkbox is honest.
- [ ] No leaked secrets, no `console.log`/`print()` debugging artifacts, no unrelated formatting churn.
- [ ] `maintainer_can_modify: true` flag set (`gh pr create --maintainer-can-modify` is implicit; can be confirmed via `gh pr view --json maintainerCanModify`).
- [ ] Branch is rebased on current `main` (no merge commits unless the project explicitly allows them).
