---
name: github-repo-seo
description: "Optimizes GitHub repositories for maximum discoverability, professional presentation, and portfolio impact. Use this skill EVERY TIME you create a new repository, write a README, set up a GitHub project, prepare a portfolio project for deployment, or when the user mentions 'README', 'GitHub repo', 'push to GitHub', 'make it public', 'portfolio', 'open source', 'repo setup', 'GitHub profile', or 'publish project'. This skill covers repository naming, description optimization, topic tags, README structure, social preview images, badges, profile SEO, Google indexing, and promotion strategies. Trigger even when the user just says 'push this to GitHub' or 'set up the repo' — those are the moments where SEO matters most and is most often forgotten."
---

# GitHub Repository SEO Skill

## Why This Matters

With 200+ million repositories on GitHub, the top 1% receives the overwhelming majority of stars, forks, and traffic. The difference between a repo that gets discovered and one that dies in obscurity is NOT code quality — it's optimization. GitHub SEO is the highest-leverage, lowest-effort thing you can do for any project.

## The Two Search Engines You're Optimizing For

**1. GitHub's Internal Search** — Developers searching directly on GitHub. Driven by repository name, About description, and Topics. Stars/forks are secondary signals.

**2. Google (External Search)** — Google indexes GitHub repos. README content, repo name, and description influence Google ranking. A well-optimized repo can rank on Google's first page.

---

## The 10-Point GitHub SEO System

Complete ALL 10 points for every repository.

---

### 1. Repository Name (Highest Impact)

The single most important ranking factor for GitHub search.

**Rules:**
- Include the primary keyword users would search for
- Use hyphens to separate words (not underscores or camelCase)
- Keep it short: 2-5 words max
- Be specific and descriptive, not clever or generic
- Include the technology if it's a key differentiator

**Examples across different project types:**
```
❌ my-project          ❌ cool-app           ❌ projectX

✅ SaaS / Web Apps:
   nextjs-admin-dashboard
   react-kanban-board
   ai-expense-tracker
   stripe-subscription-starter

✅ Developer Tools:
   markdown-preview-cli
   git-commit-analyzer
   api-rate-limiter
   docker-dev-environment

✅ AI / ML:
   ai-resume-analyzer
   llm-chatbot-template
   image-classification-api
   rag-document-search

✅ Mobile:
   react-native-fitness-app
   flutter-weather-app
```

**Formula:** `[technology]-[what-it-does]` or `[what-it-does]-[platform]`

---

### 2. About Description (Second Highest Impact)

The "About" section in the repository sidebar (Settings → description).

**Rules:**
- Start with the main keyword/phrase
- Keep between 5-15 words
- Describe WHAT it does, not HOW it works
- Include the primary technology
- No emojis or filler words

**Examples:**
```
❌ "A cool project I made"
❌ "My portfolio project built with stuff"

✅ "Real-time project management dashboard built with Next.js and WebSockets"
✅ "AI-powered resume analyzer with feedback scoring using Claude API"
✅ "Full-stack e-commerce platform with Stripe payments and admin dashboard"
✅ "CLI tool for analyzing Git commit patterns and team productivity"
✅ "React Native fitness tracker with workout logging and progress charts"
```

**Formula:** `[Primary keyword] [brief what-it-does] built with [key technologies]`

---

### 3. Topics / Tags (Critical for GitHub Search)

GitHub uses topics as a PRIMARY search filter. You get up to 20 topics — use them all.

**Rules:**
- Fill ALL 20 topic slots
- First 3-5 should be exact keywords people search for
- Include: language, framework, category, use-case, and industry
- Lowercase, hyphens for multi-word topics
- Mix broad and specific

**4-tier topic strategy (adapt per project):**
```
Tier 1 — Primary keywords (what people search):
  [main category], [product type], [problem it solves]
  Examples: admin-dashboard, project-management, expense-tracker

Tier 2 — Technologies used:
  [language], [framework], [database], [key-libraries]
  Examples: nextjs, react, typescript, tailwindcss, prisma, postgresql

Tier 3 — Features/capabilities:
  [standout features], [patterns used]
  Examples: real-time, ai-powered, drag-and-drop, authentication, stripe

Tier 4 — Industry/broader category:
  [domain], [audience], [related terms]
  Examples: saas, developer-tools, open-source, fullstack, portfolio
```

---

### 4. README.md (The Landing Page)

The README determines whether visitors star, fork, or leave. For Google, README content is indexed.

**Structure for any project (in this exact order):**

```
1. Hero section (banner image or logo + title)
2. One-line description with primary keyword
3. Badges row (tech stack + status badges)
4. About section (2-3 sentences)
5. Key Features (5-7 bullets)
6. Tech Stack (table or badge grid)
7. Screenshots (3-5 images/GIFs)
8. Getting Started (prerequisites, install, run)
9. Architecture overview (optional)
10. Live Demo link (CRITICAL for portfolio projects)
11. License
12. Contact / links
```

Read `references/readme-details.md` for complete templates, badge code, screenshot guidelines, and examples for different project types.

---

### 5. Social Preview Image (Open Graph)

When your repo link is shared on Twitter/X, LinkedIn, Slack, Discord — this image is what people see.

**How to set:** Repository Settings → Social preview → Upload image

**Specs:**
- Recommended: 1280 × 640px (2:1 ratio)
- Minimum: 640 × 320px
- Format: PNG, JPG, or GIF (under 1 MB)
- PNG with transparency supported

**What to include:**
- Project name (large, readable text)
- One-line description
- Screenshot/mockup of the app
- Tech stack icons
- Clean, branded design

**Tools:** Figma (free), Canva, og-image.vercel.app, bannerbear.com

**Why:** Posts with images receive 150%+ more engagement on social media.

---

### 6. Shields.io Badges

Badges provide instant visual info about your project. They signal professionalism.

**Essential badges for any project:**
```markdown
<!-- Status -->
![Live Demo](https://img.shields.io/badge/demo-live-brightgreen?style=flat-square)
![License](https://img.shields.io/badge/license-MIT-blue?style=flat-square)

<!-- Tech (pick what applies) -->
![Next.js](https://img.shields.io/badge/Next.js-000?style=flat-square&logo=nextdotjs&logoColor=white)
![React](https://img.shields.io/badge/React-61DAFB?style=flat-square&logo=react&logoColor=black)
![TypeScript](https://img.shields.io/badge/TypeScript-3178C6?style=flat-square&logo=typescript&logoColor=white)
![Python](https://img.shields.io/badge/Python-3776AB?style=flat-square&logo=python&logoColor=white)
![Node.js](https://img.shields.io/badge/Node.js-339933?style=flat-square&logo=nodedotjs&logoColor=white)
![Tailwind CSS](https://img.shields.io/badge/Tailwind-06B6D4?style=flat-square&logo=tailwindcss&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-4169E1?style=flat-square&logo=postgresql&logoColor=white)
![MongoDB](https://img.shields.io/badge/MongoDB-47A248?style=flat-square&logo=mongodb&logoColor=white)
![Stripe](https://img.shields.io/badge/Stripe-008CDD?style=flat-square&logo=stripe&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-2496ED?style=flat-square&logo=docker&logoColor=white)
![Vercel](https://img.shields.io/badge/Vercel-000?style=flat-square&logo=vercel&logoColor=white)

<!-- Dynamic (auto-update) -->
![Stars](https://img.shields.io/github/stars/user/repo?style=flat-square)
![Last Commit](https://img.shields.io/github/last-commit/user/repo?style=flat-square)
```

**Rules:**
- Pick ONE style: `flat-square` (modern) or `for-the-badge` (bold). Never mix.
- 8-12 badges max total
- Status badges first, then tech badges
- Get brand colors from simpleicons.org

---

### 7. GitHub Profile Optimization

Your profile is seen alongside every repo. Optimize it.

**Profile README** (create repo: `username/username`):
- Brief bio + tech stack badges + pinned project links + contact
- Keep it to one screenful — no walls of text

**Pinned repos:**
- Pin 3-6 best projects (best one first — people scan left to right)
- Every pinned repo needs description + topics set

**Profile settings:**
- Professional photo
- Bio with role + technologies + what you build
- Location, website link, social links filled in

---

### 8. Commit History & Activity

Active repos signal quality to both algorithms and humans.

**Rules:**
- Commit regularly (not one massive initial commit)
- Use conventional commits: `feat:`, `fix:`, `docs:`, `style:`, `refactor:`
- Never commit secrets, .env, node_modules
- A green contribution graph signals active development

---

### 9. Repository Hygiene

**Must-have files:**
```
README.md       — Landing page
LICENSE          — MIT recommended for portfolio
.gitignore      — Configured for your stack
.env.example    — Template listing all required env vars
```

**Nice-to-have:**
```
CONTRIBUTING.md    — Collaboration guidelines
CHANGELOG.md       — Version history
CODE_OF_CONDUCT.md — Professional practice
```

**Settings to configure:**
- Description + topics + website URL filled
- Social preview uploaded
- Disable unused sidebar items (Releases, Packages, Deployments if not used)

---

### 10. Promotion & Distribution

**Where to share:**
- **Reddit**: r/webdev, r/reactjs, r/nextjs, r/sideproject, r/programming, r/python
- **X/Twitter**: #buildinpublic, #webdev, #opensource + tag relevant accounts
- **Dev.to / Hashnode**: "How I Built X" article linking to the repo
- **Hacker News**: "Show HN" for interesting projects
- **LinkedIn**: Post with screenshots + live demo link
- **Discord**: Relevant tech community servers

**Post formula:**
- Lead with the PROBLEM it solves
- Include screenshot or GIF
- Link to live demo AND GitHub repo
- Ask a question to encourage engagement

**Encouraging stars:**
- Subtle CTA in README: "If you found this useful, consider giving it a ⭐"
- Star prompt on live demo site (top banner or footer)

---

## Pre-Push Checklist

```
REPOSITORY SETTINGS
□ Name is keyword-rich and hyphen-separated
□ Description is 5-15 words, starts with primary keyword
□ Topics: all 20 slots filled
□ Website URL points to live demo
□ Social preview image uploaded (1280×640px)
□ Unused sidebar items unchecked
□ License set (MIT recommended)

README.md
□ Hero screenshot/banner at the top
□ One-line description with primary keyword
□ Shields.io badges (consistent style)
□ About section (2-3 sentences)
□ Key Features (5-7 bullets)
□ Tech Stack section
□ Screenshots (3-5 images/GIFs)
□ Getting Started with tested, copy-paste commands
□ Live Demo link prominently placed
□ All links work (no broken links)
□ No empty sections or placeholder text

REPOSITORY HYGIENE
□ .gitignore configured properly
□ .env.example lists all required variables
□ LICENSE file present
□ No secrets in commit history
□ Meaningful commit messages

PROFILE
□ Pinned repositories updated
□ Profile README current
□ Bio includes relevant keywords
```

---

## README Templates & Badge Code

See `references/readme-details.md` for:
- Complete README templates for different project types (SaaS, API, CLI, mobile)
- Full badge snippet library (30+ technologies)
- Screenshot formatting and sizing
- Getting Started section that actually works
- Profile README template
- SEO keyword placement strategy
