# Issue Scoring Rubric

Used in Phase 2 of `SKILL.md`. Score every candidate issue on six axes. The top 5 by total score become the user's pick list.

Labels lie — `good first issue` may be controversial; `help wanted` may be already-claimed; an unlabeled issue may be the cleanest fix in the repo. Score on signals, not labels.

## The six axes (each 0–3)

### 1. Blast radius (0 worst, 3 best)

How many files / how many lines of source need to change?

| Score | Heuristic |
|-------|-----------|
| 3 | Single file, < 30 lines, additive. |
| 2 | Two files (source + test), < 100 lines total. |
| 1 | Three to five files, < 250 lines. |
| 0 | Six+ files, or any change to public APIs / migration / wire format. |

### 2. Scope clarity

Can you state a one-sentence acceptance criterion after reading the issue?

| Score | Heuristic |
|-------|-----------|
| 3 | Issue body has a clear "Acceptance criteria" section or a code snippet showing expected behavior. |
| 2 | Implicit but obvious — title and one paragraph make it clear. |
| 1 | Multiple plausible interpretations; would need to ask the maintainer. |
| 0 | "Investigate why X feels slow" — open-ended exploration, not a fix. |

### 3. Controversy

How many comments? What's the tone?

| Score | Heuristic |
|-------|-----------|
| 3 | 0–2 comments, or unanimous agreement on approach. |
| 2 | 3–10 comments, mostly clarifications. |
| 1 | 10+ comments, multiple competing approaches under debate. |
| 0 | Maintainer has said "we're undecided" or "needs design doc"; or there's a heated thread. |

### 4. Prior-art status

Is there an existing PR for this issue?

| Score | Heuristic |
|-------|-----------|
| 3 | No prior PR. |
| 2 | A merged PR exists for a related issue showing the patterns to follow. |
| 1 | A prior PR exists but is **stale** (≥10 days no update) AND has merge conflicts AND lacks tests — this is a supersede opportunity, but expect maintainer ambiguity about which to merge. |
| 0 | A non-stale PR is open with active reviewer engagement — defer, do not duplicate. |

### 5. Contributor-guide alignment

Does the implementation respect the project's known landmines?

| Score | Heuristic |
|-------|-----------|
| 3 | Issue is in a sandbox area (a single utility module) with no coordinated-update rules in `AGENTS.md`. |
| 2 | Issue touches a well-tested area; coordinated-update rules apply but are documented. |
| 1 | Issue touches public APIs subject to positional compatibility. Tractable but requires care. |
| 0 | Issue requires schema-version bumps, runner-side threading across 6+ files, or anything labeled "design needed". |

### 6. Age

How old is the issue?

| Score | Heuristic |
|-------|-----------|
| 3 | 1–60 days old. Active concern, not yet fixed. |
| 2 | 60–180 days. Likely still relevant. |
| 1 | 180–365 days. May be stale; verify with `git log` that the underlying code still exists. |
| 0 | > 365 days, OR closed-then-reopened multiple times. Often a sign of "maintainers don't want this fixed." |

## Total score interpretation

| Total | Recommendation |
|-------|----------------|
| 15–18 | Excellent first contribution. High merge probability. |
| 12–14 | Good. May require one round of review. |
| 9–11 | Marginal. Tractable but expect review friction. Pick only if user is set on this issue. |
| 6–8 | Skip unless user has domain context that changes the scoring. |
| 0–5 | Strong avoid. |

## Worked example — openai/openai-agents-python

From the 30 issues sampled on 2026-04-25, here are five worked picks:

| # | Title | Blast | Scope | Contro | Prior | Align | Age | Total | Verdict |
|---|-------|-------|-------|--------|-------|-------|-----|-------|---------|
| 2886 | Add convenience properties (tool_name, call_id) | 3 | 3 | 3 | 1 | 3 | 3 | **16** | excellent (chosen) |
| 1849 | Expose Tool Call ID to Lifecycle Hooks | 3 | 3 | 3 | 3 | 2 | 3 | **17** | excellent (next pick) |
| 1859 | max_parallel_tool_calls integer | 3 | 3 | 3 | 3 | 2 | 3 | **17** | excellent |
| 2940 | Realtime missing history_updated event | 2 | 3 | 3 | 3 | 2 | 3 | **16** | excellent |
| 2393 | Trace include_sensitive_data default | 2 | 2 | 2 | 1 | 1 | 3 | **11** | marginal (behavior change) |

Notice that #2886's prior-art score of 1 (PR #2887 is stale, dirty, untested) plus the high blast/scope/alignment scores still produced a strong 16. This is exactly the supersede pattern — score it honestly.

## Avoid patterns

Issues with these signals should drop to 0–5:

- Label `server issue` or "server-side bug" — fix lives outside this codebase.
- Maintainer-only / `v1.0 change` / `breaking change` labels — the project doesn't want external authors here.
- Issue body is "investigate" / "explore" / "discuss" — not a fix.
- Issue assigned to a maintainer (`assignees`) — already claimed.
- Issue labeled `wontfix` / `closed-wontfix` — a contributor reopened it; respect the maintainer decision.
- Issue is the *root* of a sub-issue tree — fix the sub-issues first.
- Title includes "RFC" / "proposal" / "design" — needs upstream alignment, not a PR.

## Output format

Present the user with:

```
## Top candidates

| # | Title | Score | Why | Watchouts |
|---|-------|-------|-----|-----------|
| 1849 | Expose Tool Call ID to Lifecycle Hooks | 17/18 | Tightly scoped, no prior PR, AGENTS.md alignment | None |
| 2886 | Add convenience properties to ToolCallItem | 16/18 | Reference PR #2887 stale + untested → supersede opportunity | Acknowledge #2887 in our PR body |
| 1859 | max_parallel_tool_calls integer | 17/18 | Localized config change, easy tests | None |
| 2940 | Realtime missing history_updated | 16/18 | Reporter pinpointed the line | Realtime path — verify with the realtime test fixtures |
| 2393 | Trace sensitive_data default flip | 11/18 | Secure-by-default flip; PR #2392 already open | Behavior change — needs maintainer sign-off |

Which to pursue?
```

Wait for the user's pick. Do not advance without an explicit choice.
