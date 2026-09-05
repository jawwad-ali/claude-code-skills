---
name: interview-readme
description: >-
  Rewrite a project's README.md so it doubles as an interview walkthrough guide
  the author can read and confidently explain the project from. Use when the
  user asks to "make the README interview-ready", "rewrite the README as an
  interview guide", "help me explain this project in an interview", "write a
  README I can walk an interviewer through", "prepare this project for
  interviews", or invokes /interview-readme. Also trigger when the user has an
  interview coming up and wants to answer "Can you walk me through this
  project?" for a repo they built. The rewritten README explains, in simple
  English, what the project does, the problem it solves, the overall
  architecture, the technologies used and why they were chosen, how the main
  features work, the request/response flow, key technical decisions and
  trade-offs, challenges faced and how they were solved, and possible future
  improvements. Not for adding badges, topic tags, or GitHub discoverability
  work — use github-repo-seo for that.
---

# Interview-Ready README

Rewrite `README.md` so that it does two jobs at once: it still documents the
project for anyone who lands on the repo, and it also works as a script the
author can read before an interview and use to give a confident five-minute
walkthrough of the project from start to finish.

The test of success is simple. If an interviewer asks, "Can you walk me through
this project?", the author should be able to answer confidently using only what
is written in the README.

## What the README Must Let the Author Explain

The finished README must make each of these easy to explain out loud:

- What the project does
- The problem it solves
- The overall architecture
- The technologies used and why they were chosen
- How the main features work
- The request/response flow
- Key technical decisions and trade-offs
- Challenges faced and how they were solved
- Possible future improvements

Every one of these needs its own clearly labelled section. Do not fold two of
them into one heading, and do not skip one because the codebase makes it hard to
answer.

When the code does not reveal an answer (challenges faced is the usual case),
write what the evidence supports and mark the gap with a visible author note:

```markdown
> **Author note:** describe the hardest bug you hit here in your own words, then delete this line.
```

Use the same marker for anything you inferred rather than found in the repo.
Never present an inferred fact or reason as established. At the end of the
task, list every author note in your final message so the author can resolve
them before the README is published.

## If github-repo-seo Is Also Loaded

This skill's outline governs the README body and its section order. Apply
github-repo-seo only to the repository name, the About text, topic tags,
badges, and the social preview image. Never let it reorder the sections below.

## Step 1: Learn the Project Before Writing

Never write the README from the existing README alone. Read the code first so
every claim is true for this repo.

1. Protect existing work. Run `git status --porcelain README.md`. If the file
   shows uncommitted changes, stop and ask the user to commit or stash them
   first, or copy the file to `README.orig.md` with their consent, before you
   overwrite anything.
2. Read the current `README.md` and keep anything that is still accurate:
   install steps, environment variables, scripts, licence, badges, screenshots.
3. Identify the stack from manifest files: `package.json`, `pyproject.toml`,
   `requirements.txt`, `pom.xml`, `build.gradle`, `go.mod`, `Cargo.toml`,
   `docker-compose.yml`, `Dockerfile`, and framework config files.
4. Find the entry points and the main folders: routes, pages, controllers,
   services, models, database schema, background jobs, and tests.
5. Trace the request behind the project's main feature, the one that crosses
   the most layers (auth, business logic, database, external service). Never
   pick a health check, a static page, or a plain read if a richer path exists.
   Note the actual route, handler, and file at each step. This becomes the
   Request / Response Flow section.
6. Skim history with `git log --oneline -n 300` (use `--since=` on very active
   repos). Big refactors, bug fixes, and migrations are usually the "challenges
   faced". If history is squashed or shallow, also check pull request titles,
   `CHANGELOG.md`, and issue links referenced in code comments.
7. Note anything unusual: a non-default library, a custom auth flow, a caching
   layer, a queue. Each one is a technical decision that needs a "why".
8. Find evidence for every "why". Acceptable sources are commit messages,
   architecture decision records, code comments, configuration, and pull
   request descriptions. If no evidence exists, write the most likely reason
   and prefix it with the author note marker so the author confirms or
   replaces it.
9. Source environment variable names from `.env.example`, `.env.template`,
   config loaders, or CI config. Never open `.env`, `.env.local`, or any
   gitignored secret file, and never copy a real value into the README.
10. Verify install, run, and test commands by reading, not running. Cross-check
    them against the scripts defined in the manifests (`package.json` scripts,
    `Makefile`, `pyproject.toml`, `Taskfile`) and CI config. Do not execute
    install, build, or test commands unless the user asks.

## Step 2: Recommended README Structure

Use this outline in this order so the story flows the way an interview
walkthrough would.

```markdown
# Project Name
One or two plain sentences: what it is and who it is for.

## At a Glance
   - Include only when the README exceeds roughly 150 lines or two screens.
   - Three to five plain sentences the author can say in the first minute.
   - Never name this section after the interview; visitors read it too.
   - Follow it with a short table of contents whose first link is Getting Started.

## What This Project Does
## The Problem It Solves
## How It Works (Architecture)
   - A short diagram or bullet list of the main parts and how they talk.
## Tech Stack and Why
   - List only load-bearing choices: language and runtime, framework,
     database and ORM, auth, queue or cache, hosting, testing framework.
   - Do not list formatters, linters, or trivial utilities.
   - One line per technology: what it is used for, and why it was chosen
     over the obvious alternative.
## Main Features and How They Work
   - For each feature: what the user sees, then what happens in the code.
## Request / Response Flow
   - Numbered steps for the main feature's real request, from the first
     click or API call to the final response, naming the route, handler,
     and file at each step.
## Key Technical Decisions and Trade-offs
   - Decision, why it was made, and what was given up.
## Challenges and How They Were Solved
   - Problem, what was tried, what worked.
## Future Improvements
## Getting Started
   - Install, configure, run, test. Keep whatever already worked.
## Contributing   (only if the original README had it)
## License        (only if the original README had it)
```

Any other section from the original README that is still accurate and does not
map to one of the nine topics (API reference, FAQ, deployment notes,
acknowledgements) is kept verbatim after Getting Started, in its original order.

## Step 3: Writing Style Rules

- Use simple, everyday English. Prefer "uses" over "utilizes", "turns X into Y"
  over "leverages", "stores" over "persists". Keep a precise technical term when
  it is the correct name for the thing (validates, idempotent, cache, index)
  and explain it once in a few words instead of replacing it.
- Avoid complex vocabulary, corporate jargon, buzzwords, and overly technical
  explanations.
- Write so that both technical and non-technical interviewers can easily
  understand the project. A hiring manager should understand the problem and
  the flow; an engineer should still find the key specifics without the section
  turning into a deep technical dive.
- Use clear headings, short paragraphs, and bullet points. Follow a logical
  flow: what, why, how, decisions, lessons, next.
- Use numbered steps for anything that happens in order, especially the
  request/response flow.
- Explain "why" for every technology in the Tech Stack section and for every
  decision, following the evidence rule from Step 1. An interview walkthrough
  without reasons sounds like a feature list.
- Be honest about trade-offs and limitations. Interviewers trust candidates who
  can name what they would do differently.
- Prefer a one-sentence plain-English description over a code snippet whenever
  both would do. Where a snippet genuinely helps, keep it short. The README is
  a walkthrough, not a copy of the source.

## Step 4: Preserve What Already Works

Rewriting the README must not lose useful content that was already there.

- Keep install, run, and test instructions, moving them under Getting Started.
- Keep badges, licence, contribution notes, and links to live demos.
- Keep screenshots or diagrams and place them in the section they explain.
- Keep environment variable documentation, showing placeholder values only.

## Final Checklist

Before finishing, confirm every item:

- [ ] All nine walkthrough topics have their own clearly labelled section.
- [ ] Every technology in the Tech Stack section has a one-line "why", and
      every "why" is either evidenced in the repo or marked with an author note.
- [ ] The Request / Response Flow is a numbered list that follows the main
      feature's real path and names the route, handler, and file at each step.
- [ ] Each Main Feature states what the user sees and what the code does.
- [ ] Each Key Technical Decision names what was given up.
- [ ] Each challenge names the problem, the attempt, and the fix.
- [ ] No corporate jargon, unexplained technical terms, or overly technical
      explanations remain.
- [ ] Install, run, and test instructions are still present and match the
      scripts defined in the manifests.
- [ ] Badges, licence, contribution notes, live-demo links, screenshots, and
      diagrams from the original are still present.
- [ ] Environment variables show placeholder values only. No real secret,
      token, connection string, or internal hostname appears anywhere.
- [ ] Nothing in the README claims something the code does not actually do.
- [ ] No heading or sentence reveals that the README was written for an
      interview.
- [ ] Every author note is listed in the final message to the user.
- [ ] The author could read it top to bottom and give a confident five-minute
      walkthrough.

## The Original Request This Skill Encodes

Please update the README.md so that it can also serve as an interview guide for this project.

The README should be written in simple and easy-to-understand English. Assume that an interviewer asks, "Can you walk me through this project?" I should be able to read the README and confidently explain:

* What the project does
* The problem it solves
* The overall architecture
* The technologies used and why they were chosen
* How the main features work
* The request/response flow
* Key technical decisions and trade-offs
* Challenges faced and how they were solved
* Possible future improvements

Avoid complex vocabulary, corporate jargon, and overly technical explanations. Use clear headings, bullet points, and a logical flow so that both technical and non-technical interviewers can easily understand the project.
