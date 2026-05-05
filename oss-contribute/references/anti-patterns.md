# Anti-Patterns

Things that consistently sink open-source PRs. Each comes with the reasoning, because edge cases exist where the rule doesn't apply — knowing the *why* lets you judge.

## Process anti-patterns

### Don't open multiple PRs while the first is still gated

**Why:** Two open PRs from a fresh contributor look like contribution spam. Maintainer triage queues prioritize known contributors with a track record. Until your first PR is merged, in-review, or has reviewer engagement, you have zero track record. A second PR dilutes attention without doubling the merge probability.

**When OK:** After the first PR is in review (someone has reviewed it), the second one signals "I'm here to stay" rather than "I'm cranking out drive-by patches."

### Don't comment on the issue announcing your intent

**Why:** Most issues do not need a "I'll work on this" claim. It clutters the issue and creates social pressure if you don't follow through. Open the PR — that *is* the claim.

**When OK:** Issues explicitly tagged `help wanted` where the maintainer asks contributors to comment first. Some projects use this as a coordination mechanism.

### Don't `--amend` after pushing

**Why:** Reviewers re-read your PR to check whether their feedback was addressed. Amending forces them to re-read the entire diff because git history changed. New commits give them a clear "this commit addresses your comment about X" target.

**When OK:** Maintainers explicitly request a squash before merge — but use the GitHub "Squash and merge" UI button instead, which keeps your history visible until merge.

### Don't open a PR without local verification

**Why:** CI takes 5–20 minutes per round-trip. A PR that fails CI burns reviewer goodwill ("this person didn't even run the tests"). Local format/lint/typecheck/tests is 30 seconds to 2 minutes — strictly faster.

**When OK:** Never. Even docs-only changes should run `make build-docs` locally.

## Code anti-patterns

### Don't bikeshed-refactor surrounding code in the same PR

**Why:** A PR titled "Add convenience properties to ToolCallItem" should diff <50 lines. If your diff also renames three variables, reformats a comment block, and reorders imports, reviewers can't separate "the change" from "the noise." They will ask you to split the PR. Two-week delay.

**When OK:** The refactor is genuinely necessary to make the change work (e.g., the function you're modifying needs to be split first to avoid duplication). Even then, do the refactor as a *separate first commit* in the same branch.

### Don't suppress lint or type errors with `# noqa` / `# type: ignore`

**Why:** Suppressions are a tax on every future maintainer who reads the file. They're also a code smell: the type checker is right ~95% of the time. If the project itself doesn't use suppressions, yours stand out.

**When OK:** The project already uses suppressions in similar contexts (grep first), AND you can articulate why your case is one of them. Always include a comment: `# type: ignore[<rule>]  # <reason>`.

### Don't reformat unrelated lines

**Why:** Same as bikeshed-refactor. Reviewers can't tell what's "the change" vs "the formatter ran on lines I happened to touch."

**When OK:** The project's pre-commit hook auto-formats the entire file on save and the diff is tiny. In practice, configure your editor to format on save only the lines you actually edit.

### Don't add a CHANGELOG entry unless the contributor guide says to

**Why:** Many projects auto-generate CHANGELOG from commit messages or PR titles. Adding a manual entry creates merge conflicts and gets reverted.

**When OK:** The contributor guide explicitly says "add a CHANGELOG.md entry for user-visible changes." Then do it in the PR.

### Don't add `print()` / `console.log()` / `dbg!()` debugging artifacts

**Why:** Obvious. Reviewers will request changes; CI's lint step often catches them anyway.

**When OK:** Never in the final PR. Debug locally; remove before commit.

## Documentation anti-patterns

### Don't write "tests added" — list the test names

**Why:** Reviewers want to know what's covered. "Tests added" tells them nothing. A list of `test_<name>` lines tells them exactly which paths are exercised.

Bad:
> Test plan: tests added.

Good:
> Test plan:
> - `test_tool_name_from_function_call` covers the typed-object branch.
> - `test_tool_name_from_dict` covers the dict branch.
> - `test_tool_name_returns_none_when_missing` covers the absent-attribute fallback.

### Don't promise things in the PR body you didn't verify

**Why:** Reviewers will call you on it. "Works on Windows" without running on Windows; "no performance regression" without benchmarking; "no breaking changes" without checking downstream usages — all easy to falsify, all damage credibility.

**When OK:** Statements you can verify. "All existing tests pass" — verifiable from CI. "Docstring renders correctly in the auto-generated reference page" — verifiable via `make build-docs`.

### Don't reference Claude / AI assistance in the PR body

**Why:** Most maintainers don't care, but a non-trivial minority explicitly forbid it ("we don't accept AI-generated code"). Erring on silence is the safer default.

**When OK:** The user explicitly asks you to. Some projects have a policy *requiring* disclosure (rare).

## Git hygiene anti-patterns

### Don't `git push --force` to a shared branch

**Why:** Force-push on a branch others are tracking destroys their work and confuses CI. Specifically NEVER force-push to `main` / `master` / `develop` / `trunk`.

**When OK:** Force-push to your own feature branch on your fork — `--force-with-lease` is preferred. Use this when you've rebased on updated `main` and need to push the new history.

### Don't bypass commit hooks with `--no-verify`

**Why:** Hooks exist because something went wrong before. Skipping them is admitting you don't understand or care about the failure. CI catches the same issues anyway, so you've just delayed the failure to a slower, more public stage.

**When OK:** Never as a default. The user explicitly authorized a one-time skip in a known-safe context (e.g., the hook itself is broken; you've raised an issue about it).

### Don't commit secrets

**Why:** Public OSS, your secrets are now public. Even rotated secrets can be data-mined by attackers from git history.

**When OK:** Never. Use `.env.example` files instead.

### Don't commit large binary blobs

**Why:** Bloats the repo permanently. Even after deletion, git history retains the bytes.

**When OK:** Never via `git add`. Use Git LFS if the project supports it; otherwise raise an issue first.

## Reviewer interaction anti-patterns

### Don't argue with style requests

**Why:** Style is the maintainer's call, not yours. Arguing burns goodwill. The PR is theirs to merge; if they want a different style, comply.

**When OK:** Substantive disagreements about correctness or behavior — those deserve a polite "Here's why I chose X: <reason>. Happy to change to Y if you still prefer." Then defer to maintainer.

### Don't ignore review comments

**Why:** Marks you as low-effort. Maintainers will close the PR if comments sit unanswered.

**When OK:** Never. Even disagreement is engagement. "Thanks for the suggestion — I think the current approach is X because Y. Let me know if you'd still like me to change." This is engagement, not ignoring.

### Don't push every-15-seconds during review

**Why:** Each push triggers CI. Maintainers get notification spam.

**When OK:** Batch related fixes. Push once per logical unit of feedback.

## Scope anti-patterns

### Don't include unrelated fixes in the same PR

**Why:** "While I was in there..." PRs are notoriously hard to review and slow to merge. Reviewers can't bisect a regression to your scope vs the bonus fix.

**When OK:** Truly trivial (one-line typo on the same file, fixing a stale comment about the function you just modified).

### Don't expand scope mid-review

**Why:** Reviewer agreed to merge a small change; now you've added a refactor; the agreement is void. Even if the refactor is good, it's a *new* PR.

**When OK:** Reviewer explicitly asks for the expansion.

### Don't shrink scope mid-review by removing already-merged-in-spirit features

**Why:** If a reviewer already approved the change, removing parts of it forces re-review.

**When OK:** Reviewer asks you to drop something — comply, then re-request review.

## When NOT to apply these rules

Every rule has edge cases. The pattern: when you think you have one, **state your reasoning in the PR description** so the maintainer doesn't have to ask. "I included a small refactor in the same PR because the new property couldn't be added cleanly without it" beats silently presenting a confusing diff.
