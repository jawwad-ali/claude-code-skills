# Complete Guide to Claude Code Skills

> **Comprehensive documentation on Claude Code Skills - how to create, configure, and use them effectively.**

---

## Table of Contents

1. [Overview](#overview)
2. [Skill Directory Structure](#skill-directory-structure)
3. [Where Skills Live](#where-skills-live)
4. [SKILL.md Format](#skillmd-format)
5. [Frontmatter Reference](#frontmatter-reference)
6. [Dynamic Substitutions](#dynamic-substitutions)
7. [Skill Invocation Control](#skill-invocation-control)
8. [Allowed Tools Patterns](#allowed-tools-patterns)
9. [Forked Context (Subagents)](#forked-context-subagents)
10. [Hooks in Skills](#hooks-in-skills)
11. [Practical Skill Examples](#practical-skill-examples)
12. [Validation and Testing](#validation-and-testing)
13. [Installation Methods](#installation-methods)
14. [Best Practices](#best-practices)
15. [Plugin Skills](#plugin-skills)
16. [Advanced Patterns](#advanced-patterns)

---

## Overview

**Skills** extend what Claude Code can do. They are markdown files with instructions that Claude adds to its toolkit. Claude uses skills when relevant, or you can invoke them directly with `/skill-name`.

### Key Points

- Skills are the evolution of custom slash commands
- A file at `.claude/commands/review.md` and a skill at `.claude/skills/review/SKILL.md` both create `/review` and work the same way
- Your existing `.claude/commands/` files keep working
- Skills add optional features:
  - A directory for supporting files
  - Frontmatter to control whether you or Claude invokes them
  - The ability for Claude to load them automatically when relevant

### Skills vs Commands

| Feature | Commands (Legacy) | Skills (Current) |
|---------|-------------------|------------------|
| Location | `.claude/commands/` | `.claude/skills/<name>/` |
| File | `command-name.md` | `SKILL.md` |
| Supporting files | No | Yes (examples, scripts, references) |
| Auto-invocation control | Limited | Full control via frontmatter |
| Backward compatible | Yes | Yes |

---

## Skill Directory Structure

### Minimal Structure
```bash
skill-name/
└── SKILL.md           # Required entrypoint
```

The simplest skill structure for basic knowledge documentation. Contains only a single SKILL.md file without additional resources or examples. Best used for straightforward, self-contained skills.

### Standard Structure
```bash
skill-name/
├── SKILL.md           # Main instructions (required)
├── template.md        # Template for Claude to fill in
├── references/
│   └── detailed-guide.md
└── examples/
    └── working-example.sh
```

Recommended skill organization including core documentation, detailed references, and working examples. This structure supports most skills by separating main content from supplementary information.

### Complete Structure
```bash
skill-name/
├── SKILL.md           # Overview and navigation (required)
├── reference.md       # Detailed API docs (loaded when needed)
├── examples.md        # Usage examples (loaded when needed)
├── references/
│   ├── patterns.md    # Common patterns
│   └── advanced.md    # Advanced use cases
├── examples/
│   ├── example1.sh
│   └── example2.json
└── scripts/
    ├── helper.py      # Utility script (executed, not loaded)
    └── validate.sh    # Validation script
```

Comprehensive skill organization for complex domains requiring multiple references, examples, and validation utilities.

### Supporting Files Purpose

| File Type | Purpose | Loaded/Executed |
|-----------|---------|-----------------|
| `SKILL.md` | Main instructions, navigation | Always loaded |
| `template.md` | Structure for Claude to fill in | Loaded on demand |
| `examples/` | Sample outputs showing expected format | Loaded on demand |
| `references/` | Detailed documentation | Loaded on demand |
| `scripts/` | Executable utilities | Executed (not loaded) |

**Important:** Reference supporting files from your `SKILL.md` so Claude knows what they contain and when to load them.

---

## Where Skills Live

Skills can be stored in multiple locations with different scopes. Higher-priority locations take precedence when skills share the same name.

### Priority Order (Highest to Lowest)

| Priority | Location | Scope | Path |
|----------|----------|-------|------|
| 1 | **Enterprise** | All users in organization | Managed via settings |
| 2 | **Personal** | All your projects | `~/.claude/skills/<skill-name>/SKILL.md` |
| 3 | **Project** | Specific project only | `.claude/skills/<skill-name>/SKILL.md` |
| 4 | **Plugin** | Where plugin is active | `plugin-name/skills/<skill-name>/SKILL.md` |

### Creating Skills in Each Location

```bash
# Personal skill (available across all projects)
mkdir -p ~/.claude/skills/my-skill
touch ~/.claude/skills/my-skill/SKILL.md

# Project skill (specific to current project)
mkdir -p .claude/skills/my-skill
touch .claude/skills/my-skill/SKILL.md
```

### Automatic Discovery from Nested Directories

Claude Code automatically discovers skills from nested `.claude/skills/` directories. This is particularly useful in monorepo setups where individual packages might have their own specific skills.

```
monorepo/
├── .claude/skills/           # Root-level skills
├── packages/
│   ├── frontend/
│   │   └── .claude/skills/   # Frontend-specific skills
│   └── backend/
│       └── .claude/skills/   # Backend-specific skills
```

When you're editing a file in `packages/frontend/`, Claude Code also looks for skills in `packages/frontend/.claude/skills/`.

### Namespace Conflicts

- Plugin skills use a `plugin-name:skill-name` namespace to avoid conflicts
- If a skill and a command share the same name, the skill takes precedence

---

## SKILL.md Format

### Basic Example

```markdown
---
name: explain-code
description: Explain code in detail with examples
---

# Explain Code

When explaining code:
1. Start with a high-level overview
2. Break down each function
3. Provide usage examples
4. Note any potential issues or improvements
```

### With All Frontmatter Options

```markdown
---
name: security-review
description: Review code for security vulnerabilities and best practices
argument-hint: [file-or-directory]
allowed-tools: Read, Grep, Glob, Bash(git:*)
model: sonnet
disable-model-invocation: false
user-invocable: true
context: fork
agent: Explore
hooks:
  PostToolUse:
    - matcher: "Edit"
      hooks:
        - type: prompt
          prompt: "Verify the edit didn't introduce security issues"
---

# Security Review

Review $ARGUMENTS for security vulnerabilities:

## Checklist
- [ ] SQL injection
- [ ] XSS vulnerabilities
- [ ] Command injection
- [ ] Hardcoded credentials
- [ ] Insecure dependencies

## Process
1. Scan all files in the target
2. Identify potential vulnerabilities
3. Provide severity ratings
4. Suggest fixes with code examples
```

### Referencing Supporting Files

```markdown
---
name: api-development
description: Guide for developing APIs
---

# API Development Guide

## Quick Reference
See the main patterns and conventions below.

## Additional Resources

### Reference Files
For detailed patterns and techniques, consult:
- **`references/patterns.md`** - Common API patterns
- **`references/authentication.md`** - Auth implementation guide
- **`references/error-handling.md`** - Error response formats

### Example Files
Working examples in `examples/`:
- **`examples/rest-endpoint.js`** - REST endpoint example
- **`examples/graphql-resolver.js`** - GraphQL resolver example

### Scripts
Utility scripts in `scripts/`:
- **`scripts/generate-openapi.sh`** - Generate OpenAPI spec
- **`scripts/validate-schema.py`** - Validate request/response schemas
```

---

## Frontmatter Reference

All frontmatter fields are optional. Only `description` is recommended so Claude knows when to use the skill.

### Complete Field Reference

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `name` | string | directory name | Display name for the skill. Lowercase letters, numbers, and hyphens only (max 64 characters). |
| `description` | string | first paragraph | What the skill does and when to use it. Claude uses this to decide when to apply the skill. |
| `argument-hint` | string | none | Hint shown during autocomplete to indicate expected arguments. Example: `[issue-number]` or `[filename] [format]` |
| `disable-model-invocation` | boolean | `false` | Set to `true` to prevent Claude from automatically loading this skill. Use for workflows you want to trigger manually with `/name`. |
| `user-invocable` | boolean | `true` | Set to `false` to hide from the `/` menu. Use for background knowledge users shouldn't invoke directly. |
| `allowed-tools` | string/array | inherited | Tools Claude can use without asking permission when this skill is active. |
| `model` | string | inherited | Model to use when this skill is active (e.g., `sonnet`, `opus`, `haiku`). |
| `context` | string | none | Set to `fork` to run in a forked subagent context. |
| `agent` | string | none | Which subagent type to use when `context: fork` is set. |
| `hooks` | object | none | Hooks scoped to this skill's lifecycle. |

### Frontmatter Examples

#### Minimal
```yaml
---
description: Review code for issues
---
```

#### Standard
```yaml
---
name: code-review
description: Review code for security and quality issues
allowed-tools: Read, Grep, Glob
argument-hint: [file-or-directory]
---
```

#### Full Configuration
```yaml
---
name: deploy
description: Deploy the application to production
argument-hint: [environment]
disable-model-invocation: true
user-invocable: true
allowed-tools: Read, Bash(*)
model: sonnet
---
```

---

## Dynamic Substitutions

Skills support several dynamic substitution patterns that are replaced at runtime.

### Argument Substitutions

#### All Arguments
```markdown
# Captures everything after the skill name
$ARGUMENTS
```

**Example:** `/fix-issue 123 --priority high` → `$ARGUMENTS` = `123 --priority high`

#### Positional Arguments (0-indexed)

```markdown
# Long form
$ARGUMENTS[0]
$ARGUMENTS[1]
$ARGUMENTS[2]

# Short form
$0
$1
$2
```

**Example:** `/migrate Button React Vue`
- `$0` or `$ARGUMENTS[0]` = `Button`
- `$1` or `$ARGUMENTS[1]` = `React`
- `$2` or `$ARGUMENTS[2]` = `Vue`

#### Usage in Skills

```markdown
---
name: migrate-component
description: Migrate a component from one framework to another
argument-hint: [component] [from-framework] [to-framework]
---

Migrate the $0 component from $1 to $2.
Preserve all existing behavior and tests.
```

### Environment Variables

```markdown
# Unique session identifier
${CLAUDE_SESSION_ID}

# Plugin root directory (for plugin skills)
${CLAUDE_PLUGIN_ROOT}
```

#### Session Logger Example
```markdown
---
name: session-logger
description: Log activity for this session
---

Log the following to logs/${CLAUDE_SESSION_ID}.log:

$ARGUMENTS
```

### Bash Execution (Inline Commands)

Execute shell commands and inject their output using `!` followed by backticks:

```markdown
# Current date
Current date: !`date`

# Git information
Current branch: !`git branch --show-current`
Last commit: !`git log -1 --oneline`

# GitHub CLI
PR diff: !`gh pr diff`
PR comments: !`gh pr view --comments`
Changed files: !`gh pr diff --name-only`

# Custom commands
Project version: !`cat package.json | jq -r '.version'`
```

#### Validation Example
```markdown
---
description: Deploy with validation
argument-hint: [environment]
---

Validate environment: !`echo "$1" | grep -E "^(dev|staging|prod)$" || echo "INVALID"`

If $1 is valid environment:
  Deploy to $1
Otherwise:
  Explain valid environments: dev, staging, prod
  Show usage: /deploy [environment]
```

### File References

Reference files with the `@` symbol:

```markdown
# Reference specific files
Review @src/utils/auth.js

# Reference directories
Analyze structure of @src/components

# Reference with plugin root
Load config from @${CLAUDE_PLUGIN_ROOT}/config/settings.json
```

---

## Skill Invocation Control

Control who can invoke a skill and how.

### User-Only Invocation

Use for actions with side effects that should only be triggered manually:

```yaml
---
name: deploy
description: Deploy the application to production
disable-model-invocation: true
---

Deploy $ARGUMENTS to production:

1. Run the test suite
2. Build the application
3. Push to the deployment target
4. Verify the deployment succeeded
```

**Behavior:** Claude **cannot** automatically invoke this skill. Only users can trigger it with `/deploy`.

### Agent-Only (Background Knowledge)

Use for information Claude should know but users shouldn't invoke directly:

```yaml
---
name: legacy-system-context
description: Explains how the old authentication system works
user-invocable: false
---

# Legacy Authentication System

The old auth system uses session cookies stored in Redis.
Key patterns:
- Session keys: `session:{user_id}:{random}`
- Expiry: 24 hours
- Refresh: On each request

When migrating, ensure backward compatibility with these patterns.
```

**Behavior:** Hidden from `/` menu, but Claude uses this knowledge when relevant.

### Both User and Claude (Default)

Standard skills visible in menu AND Claude can invoke automatically:

```yaml
---
name: code-review
description: Review code for issues
---

Review this code for:
- Security vulnerabilities
- Performance issues
- Best practice violations
```

### Invocation Control Summary

| `disable-model-invocation` | `user-invocable` | User can invoke | Claude can invoke |
|---------------------------|------------------|-----------------|-------------------|
| `false` (default) | `true` (default) | ✅ Yes | ✅ Yes |
| `true` | `true` | ✅ Yes | ❌ No |
| `false` | `false` | ❌ No (hidden) | ✅ Yes |
| `true` | `false` | ❌ No | ❌ No (skill is disabled) |

---

## Allowed Tools Patterns

The `allowed-tools` field specifies which tools Claude can use without asking permission when the skill is active.

### Specific Tools

```yaml
allowed-tools: Read, Write, Edit
allowed-tools: Read, Grep, Glob
allowed-tools: Read, Write, Edit, Grep, Glob
```

### Bash with Pattern Matching

```yaml
# Git commands only
allowed-tools: Bash(git:*)

# npm and yarn commands
allowed-tools: Bash(npm:*), Bash(yarn:*)

# Specific commands
allowed-tools: Bash(echo:*), Bash(date:*)

# All bash commands (use with caution)
allowed-tools: Bash(*)
```

### All Tools

```yaml
allowed-tools: "*"
```

### Combined Patterns

```yaml
allowed-tools: Read, Write, Edit, Bash(git:*), Grep, Glob
allowed-tools: Read, Grep, Glob, Bash(npm:*), Bash(yarn:*)
```

### Common Tool Sets

| Use Case | Recommended Tools |
|----------|-------------------|
| Read-only analysis | `Read, Grep, Glob` |
| Code generation | `Read, Write, Grep` |
| Testing | `Read, Bash, Grep` |
| Git operations | `Read, Bash(git:*)` |
| Full access | `"*"` or omit field |

### Array Format

```yaml
allowed-tools:
  - Read
  - Write
  - Edit
  - Bash(git:*)
```

---

## Forked Context (Subagents)

Run skills in isolated subagent contexts for resource-intensive or specialized tasks.

### Basic Forked Skill

```yaml
---
name: deep-research
description: Research a topic thoroughly
context: fork
agent: Explore
---

Research $ARGUMENTS thoroughly:

1. Find relevant files using Glob and Grep
2. Read and analyze the code
3. Summarize findings with specific file references
```

### How Forked Context Works

When a skill with `context: fork` runs:
1. A new isolated context is created
2. The subagent receives the skill content as its prompt
3. The `agent` field determines the execution environment (model, tools, and permissions)
4. Results are summarized and returned to your main conversation

### Available Agent Types

| Agent | Purpose | Tools |
|-------|---------|-------|
| `Explore` | Fast codebase exploration | Read, Grep, Glob (no Edit/Write) |
| `Plan` | Design implementation plans | Read, Grep, Glob (no Edit/Write) |
| `general-purpose` | Multi-step tasks | All tools |
| `Bash` | Command execution | Bash only |

### Forked Skill Examples

#### PR Summary Skill
```yaml
---
name: pr-summary
description: Summarize changes in a pull request
context: fork
agent: Explore
allowed-tools: Bash(gh *)
---

## Pull request context
- PR diff: !`gh pr diff`
- PR comments: !`gh pr view --comments`
- Changed files: !`gh pr diff --name-only`

Summarize:
1. Key changes made
2. Potential impact
3. Areas that need careful review
```

#### Architecture Analysis
```yaml
---
name: analyze-architecture
description: Analyze codebase architecture
context: fork
agent: Explore
---

Analyze the architecture of $ARGUMENTS:

1. Identify main components and their responsibilities
2. Map dependencies between modules
3. Document data flow patterns
4. Note potential architectural issues
5. Suggest improvements
```

---

## Hooks in Skills

Hooks execute automatically in response to designated Claude Code events. You can scope hooks to specific skills.

### Hook Structure in Frontmatter

```yaml
---
name: secure-edit
description: Edit files with security validation
hooks:
  PreToolUse:
    - matcher: "Write|Edit"
      hooks:
        - type: prompt
          prompt: "Validate file write safety. Check for system paths, credentials, path traversal. Return 'approve' or 'deny'."
  PostToolUse:
    - matcher: "Edit"
      hooks:
        - type: prompt
          prompt: "Analyze edit result for security vulnerabilities or breaking changes."
---
```

### Available Hook Events

| Event | When It Fires | Use Case |
|-------|---------------|----------|
| `PreToolUse` | Before tool execution | Validate, approve/deny, modify inputs |
| `PostToolUse` | After tool execution | Analyze results, provide feedback |
| `SessionStart` | When Claude Code session begins | Load context, set environment |
| `SessionEnd` | When session ends | Cleanup, save state |
| `Stop` | When main agent stops | Final validation |
| `SubagentStop` | When subagent stops | Process subagent results |
| `UserPromptSubmit` | When user submits prompt | Validate/modify user input |
| `PreCompact` | Before context compaction | Save important context |
| `Notification` | On notifications | Logging, external alerts |

### Hook Types

#### Prompt-Based Hook
Uses an LLM to make decisions:

```yaml
hooks:
  PreToolUse:
    - matcher: "Write|Edit"
      hooks:
        - type: prompt
          prompt: "Evaluate if this file operation is safe. Return 'approve' or 'deny' with explanation."
```

#### Command-Based Hook
Executes a shell command:

```yaml
hooks:
  PostToolUse:
    - matcher: "Bash"
      hooks:
        - type: command
          command: "bash ${CLAUDE_PLUGIN_ROOT}/scripts/log-command.sh"
```

### PreToolUse Response Format

```json
{
  "hookSpecificOutput": {
    "permissionDecision": "allow|deny|ask",
    "updatedInput": {
      "field": "modified_value"
    }
  },
  "systemMessage": "Explanation for Claude"
}
```

### Hook Examples

#### Security Validation Hook
```yaml
hooks:
  PreToolUse:
    - matcher: "Write|Edit"
      hooks:
        - type: prompt
          prompt: |
            Validate file write safety. Check:
            - System paths (/etc, /usr, etc.)
            - Credential files (.env, secrets, keys)
            - Path traversal attempts (../)
            - Sensitive content injection
            Return 'approve' if safe, 'deny' if dangerous.
```

#### Post-Edit Analysis Hook
```yaml
hooks:
  PostToolUse:
    - matcher: "Edit"
      hooks:
        - type: prompt
          prompt: |
            Analyze edit result for potential issues:
            - Syntax errors
            - Security vulnerabilities
            - Breaking changes
            Provide feedback if issues found.
```

#### Logging Hook
```yaml
hooks:
  Notification:
    - matcher: "*"
      hooks:
        - type: command
          command: "bash ${CLAUDE_PLUGIN_ROOT}/scripts/log-notification.sh"
```

---

## Practical Skill Examples

### Code Review Skill

```markdown
---
name: review
description: Review code for security, quality, and best practices
allowed-tools: Read, Grep, Glob
argument-hint: [file-or-directory]
---

# Code Review

Review $ARGUMENTS comprehensively:

## 1. Security Review
- [ ] SQL injection vulnerabilities
- [ ] XSS vulnerabilities
- [ ] Command injection risks
- [ ] Hardcoded secrets or credentials
- [ ] Insecure dependencies
- [ ] Input validation issues

## 2. Code Quality
- [ ] Error handling completeness
- [ ] Edge case coverage
- [ ] Performance concerns
- [ ] Memory leaks
- [ ] Resource cleanup

## 3. Best Practices
- [ ] Naming conventions
- [ ] Code organization
- [ ] DRY violations
- [ ] SOLID principles
- [ ] Documentation

## Output Format
For each issue found:
1. File and line number
2. Issue description
3. Severity (CRITICAL/HIGH/MEDIUM/LOW)
4. Suggested fix with code example
```

### Fix GitHub Issue Skill

```markdown
---
name: fix-issue
description: Fix a GitHub issue following project standards
disable-model-invocation: true
argument-hint: [issue-number]
allowed-tools: Read, Write, Edit, Bash(git:*), Bash(gh:*)
---

# Fix GitHub Issue

Fix issue #$ARGUMENTS following our coding standards.

## Process

1. **Read the Issue**
   !`gh issue view $ARGUMENTS`

2. **Understand Requirements**
   - Parse acceptance criteria
   - Identify affected files
   - Note any constraints

3. **Implement the Fix**
   - Follow existing code patterns
   - Add appropriate error handling
   - Maintain backward compatibility

4. **Write Tests**
   - Unit tests for new functionality
   - Integration tests if needed
   - Edge case coverage

5. **Create Commit**
   - Use conventional commit format
   - Reference the issue number
   - Provide clear description
```

### TDD Workflow Skill

```markdown
---
name: tdd
description: Test-driven development workflow (RED-GREEN-REFACTOR)
allowed-tools: Read, Write, Edit, Bash(npm:*), Bash(yarn:*), Bash(pnpm:*)
argument-hint: [feature-description]
---

# TDD Workflow

Implement $ARGUMENTS using Test-Driven Development.

## RED Phase
1. Define the interface/signature
2. Write failing tests that describe expected behavior
3. Run tests - verify they FAIL

## GREEN Phase
1. Write minimal code to make tests pass
2. Don't optimize yet
3. Run tests - verify they PASS

## REFACTOR Phase
1. Improve code quality
2. Remove duplication
3. Optimize if needed
4. Run tests - verify they still PASS

## Coverage Target
- Minimum: 80% code coverage
- Verify with: `npm test -- --coverage`

## Example Flow
```typescript
// Step 1: Define interface
function add(a: number, b: number): number {
  throw new Error('not implemented');
}

// Step 2: Write failing test
test('adds two numbers', () => {
  expect(add(2, 3)).toBe(5);
});

// Step 3: Run tests - should FAIL

// Step 4: Implement
function add(a: number, b: number): number {
  return a + b;
}

// Step 5: Run tests - should PASS

// Step 6: Refactor if needed
```
```

### PR Summary Skill

```markdown
---
name: pr-summary
description: Summarize changes in a pull request
context: fork
agent: Explore
allowed-tools: Bash(gh:*)
---

## Pull Request Context

- **PR Info:** !`gh pr view`
- **Changed Files:** !`gh pr diff --name-only`
- **Diff:** !`gh pr diff`
- **Comments:** !`gh pr view --comments`

## Generate Summary

1. **Overview**
   - What does this PR do?
   - Why are these changes needed?

2. **Key Changes**
   - List major modifications
   - Note any breaking changes
   - Highlight new dependencies

3. **Review Focus Areas**
   - Files that need careful review
   - Potential risk areas
   - Test coverage concerns

4. **Recommendations**
   - Approval recommendation
   - Suggestions for improvement
```

### Deploy Skill

```markdown
---
name: deploy
description: Deploy the application to specified environment
disable-model-invocation: true
argument-hint: [environment: dev|staging|prod]
allowed-tools: Bash(*)
---

# Deploy to $ARGUMENTS

## Pre-flight Checks

Validate environment: !`echo "$1" | grep -E "^(dev|staging|prod)$" && echo "VALID" || echo "INVALID"`

If environment is INVALID:
- Show error: "Invalid environment. Use: dev, staging, or prod"
- Exit

## Deployment Process

1. **Run Tests**
   ```bash
   npm test
   ```

2. **Build Application**
   ```bash
   npm run build
   ```

3. **Deploy**
   ```bash
   npm run deploy:$1
   ```

4. **Verify Deployment**
   - Check health endpoint
   - Verify key functionality
   - Monitor for errors

## Rollback Plan
If deployment fails:
1. Revert to previous version
2. Notify team
3. Document failure reason
```

### Migrate Component Skill

```markdown
---
name: migrate-component
description: Migrate a component from one framework to another
argument-hint: [component-name] [from-framework] [to-framework]
allowed-tools: Read, Write, Edit, Grep, Glob
---

# Migrate Component

Migrate the **$0** component from **$1** to **$2**.

## Migration Steps

1. **Analyze Current Implementation**
   - Find component: `@src/components/$0`
   - Identify props and state
   - Map lifecycle methods
   - Note dependencies

2. **Create New Component**
   - Set up $2 component structure
   - Convert props/state patterns
   - Migrate lifecycle to $2 equivalents
   - Update imports

3. **Preserve Behavior**
   - Maintain all existing functionality
   - Keep same prop interface
   - Preserve event handlers
   - Match styling approach

4. **Update Tests**
   - Migrate test file
   - Update testing utilities
   - Verify all tests pass

5. **Update Imports**
   - Find all usages
   - Update import paths
   - Verify no breaking changes
```

### Session Logger Skill

```markdown
---
name: session-logger
description: Log activity for the current session
allowed-tools: Write, Bash(mkdir:*)
---

# Session Logger

Log the following activity to `logs/${CLAUDE_SESSION_ID}.log`:

## Activity to Log
$ARGUMENTS

## Format
```
[TIMESTAMP] [SESSION_ID] [ACTION]
Details...
```

## Instructions
1. Create logs directory if needed: !`mkdir -p logs`
2. Append to session log file
3. Include timestamp and context
```

### Project Patterns Skill (Generated)

```markdown
---
name: my-app-patterns
description: Coding patterns extracted from my-app repository
version: 1.0.0
source: local-git-analysis
analyzed_commits: 150
---

# My App Patterns

## Commit Conventions

This project uses **conventional commits**:
- `feat:` - New features
- `fix:` - Bug fixes
- `chore:` - Maintenance tasks
- `docs:` - Documentation updates
- `refactor:` - Code refactoring
- `test:` - Adding tests

## Code Architecture

```
src/
├── components/     # React components (PascalCase.tsx)
├── hooks/          # Custom hooks (use*.ts)
├── utils/          # Utility functions (camelCase.ts)
├── types/          # TypeScript type definitions
├── services/       # API and external services
├── stores/         # State management
└── constants/      # Application constants
```

## Common Workflows

### Adding a New Component
1. Create `src/components/ComponentName.tsx`
2. Add tests in `src/components/__tests__/ComponentName.test.tsx`
3. Export from `src/components/index.ts`
4. Add Storybook story if UI component

### Database Migration
1. Modify `src/db/schema.ts`
2. Run `pnpm db:generate`
3. Run `pnpm db:migrate`
4. Update seed data if needed

### Adding an API Endpoint
1. Create handler in `src/api/`
2. Add route in `src/api/routes.ts`
3. Add request/response types
4. Write integration tests

## Testing Patterns

- Test files: `__tests__/` directories or `.test.ts` suffix
- Coverage target: 80%+
- Framework: Vitest
- E2E: Playwright
```

---

## Validation and Testing

### Manual Testing Process

```bash
# 1. Start Claude Code in debug mode
claude --debug

# 2. Check skill appears in help
> /help
# Look for your skill in the list

# 3. Invoke skill without arguments
> /my-skill
# Check for reasonable error or default behavior

# 4. Invoke with valid arguments
> /my-skill arg1 arg2
# Verify expected behavior

# 5. Test edge cases
> /my-skill ""
> /my-skill "argument with spaces"
> /my-skill arg1 arg2 arg3 extra args

# 6. Check debug logs for errors
tail -f ~/.claude/debug-logs/latest
```

### Skill Validation Script

```bash
#!/bin/bash
# validate-skill.sh - Validate Claude Code skill structure

SKILL_DIR="$1"
SKILL_FILE="$SKILL_DIR/SKILL.md"

echo "Validating skill: $SKILL_DIR"

# Check directory exists
if [ ! -d "$SKILL_DIR" ]; then
  echo "ERROR: Directory not found: $SKILL_DIR"
  exit 1
fi

# Check SKILL.md exists
if [ ! -f "$SKILL_FILE" ]; then
  echo "ERROR: SKILL.md not found in $SKILL_DIR"
  exit 1
fi
echo "✓ SKILL.md exists"

# Check file is not empty
if [ ! -s "$SKILL_FILE" ]; then
  echo "ERROR: SKILL.md is empty"
  exit 1
fi
echo "✓ SKILL.md has content"

# Validate YAML frontmatter if present
if head -n 1 "$SKILL_FILE" | grep -q "^---"; then
  # Count frontmatter markers (should be exactly 2)
  MARKERS=$(head -n 50 "$SKILL_FILE" | grep -c "^---")
  if [ "$MARKERS" -lt 2 ]; then
    echo "ERROR: Invalid YAML frontmatter (missing closing '---')"
    exit 1
  fi
  echo "✓ YAML frontmatter syntax valid"

  # Extract and validate frontmatter
  FRONTMATTER=$(sed -n '/^---$/,/^---$/p' "$SKILL_FILE" | head -n -1 | tail -n +2)

  # Check for description (recommended)
  if ! echo "$FRONTMATTER" | grep -q "description:"; then
    echo "⚠ Warning: No description field (recommended for Claude to know when to use skill)"
  else
    echo "✓ Description field present"
  fi
fi

# Check for referenced files
echo ""
echo "Checking referenced files..."
grep -oE '@[a-zA-Z0-9_./-]+' "$SKILL_FILE" | while read ref; do
  ref_path="${ref#@}"
  if [[ "$ref_path" != \$* ]]; then  # Skip variable references
    if [ -f "$SKILL_DIR/$ref_path" ]; then
      echo "✓ Referenced file exists: $ref_path"
    else
      echo "⚠ Warning: Referenced file not found: $ref_path"
    fi
  fi
done

# Check subdirectories
for subdir in references examples scripts; do
  if [ -d "$SKILL_DIR/$subdir" ]; then
    file_count=$(ls -1 "$SKILL_DIR/$subdir" 2>/dev/null | wc -l)
    echo "✓ $subdir/ directory: $file_count files"
  fi
done

echo ""
echo "Validation complete!"
```

### Command Validation Script

```bash
#!/bin/bash
# validate-command.sh - Validate Claude Code command file

COMMAND_FILE="$1"

if [ ! -f "$COMMAND_FILE" ]; then
  echo "ERROR: File not found: $COMMAND_FILE"
  exit 1
fi

# Check .md extension
if [[ ! "$COMMAND_FILE" =~ \.md$ ]]; then
  echo "ERROR: File must have .md extension"
  exit 1
fi
echo "✓ File extension valid"

# Validate YAML frontmatter if present
if head -n 1 "$COMMAND_FILE" | grep -q "^---"; then
  MARKERS=$(head -n 50 "$COMMAND_FILE" | grep -c "^---")
  if [ "$MARKERS" -ne 2 ]; then
    echo "ERROR: Invalid YAML frontmatter (need exactly 2 '---' markers)"
    exit 1
  fi
  echo "✓ YAML frontmatter syntax valid"
fi

# Check for empty file
if [ ! -s "$COMMAND_FILE" ]; then
  echo "ERROR: File is empty"
  exit 1
fi
echo "✓ File has content"

echo "✓ Command file structure valid"
```

### Testing Bash Execution

```bash
# Create test command with bash execution
cat > .claude/commands/test-bash.md << 'EOF'
---
description: Test bash execution in commands
allowed-tools: Bash(echo:*), Bash(date:*)
---

Current date: !`date`
Test output: !`echo "Hello from bash"`

Verify the outputs above are correctly substituted.
EOF
```

---

## Installation Methods

### Create Personal Skill Manually

```bash
# Create skill directory
mkdir -p ~/.claude/skills/my-skill

# Create SKILL.md
cat > ~/.claude/skills/my-skill/SKILL.md << 'EOF'
---
name: my-skill
description: Description of what this skill does
---

# My Skill

Instructions for Claude when this skill is active...
EOF
```

### Create Project Skill

```bash
# Create in project directory
mkdir -p .claude/skills/project-skill

# Create SKILL.md
cat > .claude/skills/project-skill/SKILL.md << 'EOF'
---
name: project-skill
description: Project-specific skill
---

# Project Skill

This skill is specific to this project...
EOF
```

### Install from Repository

```bash
# Clone a skills repository
git clone https://github.com/example/claude-skills.git

# Copy to personal skills
cp -r claude-skills/skills/* ~/.claude/skills/

# Or copy to project
cp -r claude-skills/skills/* .claude/skills/
```

### Install with Templates CLI

```bash
# Install specific commands
npx claude-code-templates@latest --command=check-file --yes
npx claude-code-templates@latest --command=generate-tests --yes

# Install specific agents
npx claude-code-templates@latest --agent=react-performance --yes
npx claude-code-templates@latest --agent=api-security-audit --yes
```

### Manual Download

```bash
# Create directory
mkdir -p .claude/commands

# Download specific command
curl -o .claude/commands/check-file.md \
  https://raw.githubusercontent.com/davila7/claude-code-templates/main/components/commands/check-file.md

curl -o .claude/commands/generate-tests.md \
  https://raw.githubusercontent.com/davila7/claude-code-templates/main/components/commands/generate-tests.md
```

### Copy from Everything Claude Code

```bash
# Clone the repo
git clone https://github.com/affaan-m/everything-claude-code.git

# Copy agents
cp everything-claude-code/agents/*.md ~/.claude/agents/

# Copy rules
cp everything-claude-code/rules/*.md ~/.claude/rules/

# Copy commands
cp everything-claude-code/commands/*.md ~/.claude/commands/

# Copy skills
cp -r everything-claude-code/skills/* ~/.claude/skills/
```

---

## Best Practices

### Skill Organization

1. **Keep SKILL.md focused**
   - Put main instructions and navigation in SKILL.md
   - Move detailed reference material to separate files
   - Large documents don't need to load every time

2. **Use meaningful names**
   - Lowercase letters, numbers, and hyphens only
   - Max 64 characters
   - Be descriptive: `code-review` not `cr`

3. **Always include description**
   - Helps Claude know when to apply the skill
   - Should explain what the skill does AND when to use it

### Tool Access

4. **Apply principle of least privilege**
   - Only grant tools the skill actually needs
   - Use specific Bash patterns instead of `Bash(*)`
   - Prefer `Read, Grep, Glob` for analysis-only skills

5. **Use appropriate invocation control**
   - `disable-model-invocation: true` for actions with side effects
   - `user-invocable: false` for background knowledge
   - Default (both true) for general-purpose skills

### Supporting Files

6. **Reference supporting files explicitly**
   - Tell Claude what each file contains
   - Explain when to load each file
   - Keep file references organized in a section

7. **Organize by purpose**
   ```
   references/  - Detailed documentation
   examples/    - Sample outputs and code
   scripts/     - Executable utilities
   ```

### Testing

8. **Test thoroughly before deployment**
   - Use debug mode: `claude --debug`
   - Test with various argument combinations
   - Verify bash execution works correctly
   - Check debug logs for errors

9. **Validate inputs**
   - Use bash execution to validate arguments
   - Provide helpful error messages
   - Document expected argument formats

### Performance

10. **Use forked context for heavy tasks**
    - Research and exploration: `context: fork` with `agent: Explore`
    - Planning: `context: fork` with `agent: Plan`
    - Keeps main conversation responsive

---

## Plugin Skills

Skills can be packaged as part of Claude Code plugins for distribution.

### Plugin Directory Structure

```
my-plugin/
├── .claude-plugin/
│   └── plugin.json          # Plugin manifest (required)
├── commands/                 # Slash commands (.md files)
├── agents/                   # Subagent definitions (.md files)
├── skills/                   # Agent skills (subdirectories)
│   └── my-skill/
│       ├── SKILL.md         # Required for each skill
│       ├── references/
│       ├── examples/
│       └── scripts/
├── hooks/
│   └── hooks.json           # Event handler configuration
├── .mcp.json                # MCP server definitions
└── scripts/                 # Helper scripts and utilities
```

### Plugin Manifest (plugin.json)

```json
{
  "name": "my-plugin",
  "version": "1.0.0",
  "description": "My awesome Claude Code plugin",
  "author": "Your Name"
}
```

### Plugin Skill with Environment Variables

```markdown
---
name: plugin-skill
description: Skill that uses plugin resources
allowed-tools: Bash(*), Read, Write
---

# Plugin Skill

Cache directory: ${CLAUDE_PLUGIN_ROOT}/cache/

Analyze @$1 and save results to cache:
!`mkdir -p ${CLAUDE_PLUGIN_ROOT}/cache && date > ${CLAUDE_PLUGIN_ROOT}/cache/last-run.txt`

Store analysis for future reference.
```

### Auto-Discovery in Plugins

Skills are automatically discovered by scanning for subdirectories containing `SKILL.md` within the `skills/` directory:

- Skill metadata (name, description) loaded immediately
- SKILL.md body loaded when skill is triggered
- References and examples loaded on demand
- No manual packaging required

### Plugin Skill Namespacing

Plugin skills use `plugin-name:skill-name` namespace to avoid conflicts:

```bash
# Invoke plugin skill
/my-plugin:my-skill arguments
```

---

## Advanced Patterns

### Conditional Logic Pattern

```markdown
---
description: Build for specific environment with validation
argument-hint: [environment]
allowed-tools: Bash(*)
---

Validate environment argument: !`echo "$1" | grep -E "^(dev|staging|prod)$" && echo "VALID" || echo "INVALID"`

Check build script exists: !`test -x ${CLAUDE_PLUGIN_ROOT}/scripts/build.sh && echo "EXISTS" || echo "MISSING"`

Verify configuration available: !`test -f ${CLAUDE_PLUGIN_ROOT}/config/$1.json && echo "FOUND" || echo "NOT_FOUND"`

If all validations pass:
- Load configuration: @${CLAUDE_PLUGIN_ROOT}/config/$1.json
- Execute build: !`bash ${CLAUDE_PLUGIN_ROOT}/scripts/build.sh $1 2>&1`
- Validate results: !`bash ${CLAUDE_PLUGIN_ROOT}/scripts/validate-build.sh $1 2>&1`

If validations fail:
- Explain which validation failed
- Provide expected values/locations
- Suggest corrective actions
```

### Multi-Argument Validation

```markdown
---
description: Create deployment with version
argument-hint: [environment] [version]
---

Validate inputs: !`test -n "$1" -a -n "$2" && echo "OK" || echo "MISSING"`

If both arguments provided:
  Deploy version $2 to $1 environment
Otherwise:
  ERROR: Both environment and version required.
  Usage: /deploy [environment] [version]
  Example: /deploy staging v1.2.3
```

### Chained Skill Execution

```markdown
---
name: full-review
description: Complete code review with multiple passes
allowed-tools: Read, Grep, Glob
---

# Full Code Review Pipeline

Review $ARGUMENTS with multiple specialized passes:

## Pass 1: Security
!`echo "Running security review..."`
- Check for vulnerabilities
- Scan for secrets
- Validate input handling

## Pass 2: Performance
!`echo "Running performance review..."`
- Identify bottlenecks
- Check for memory leaks
- Analyze complexity

## Pass 3: Quality
!`echo "Running quality review..."`
- Check code style
- Verify documentation
- Assess test coverage

## Final Report
Compile findings from all passes into comprehensive report.
```

### Dynamic Context Loading

```markdown
---
name: context-aware-review
description: Review with project-specific context
allowed-tools: Read, Grep, Glob
---

# Context-Aware Code Review

## Load Project Context
- Package info: !`cat package.json 2>/dev/null || echo "No package.json"`
- Git info: !`git log -1 --oneline 2>/dev/null || echo "Not a git repo"`
- CI status: !`cat .github/workflows/*.yml 2>/dev/null | head -20 || echo "No CI config"`

## Project-Specific Rules
Based on detected context, apply appropriate review rules:
- If TypeScript: Check type safety
- If React: Check component patterns
- If Node.js: Check async handling

## Review Target
$ARGUMENTS
```

### Skill with Template Output

```markdown
---
name: api-doc
description: Generate API documentation
argument-hint: [endpoint-file]
---

# Generate API Documentation

Analyze @$1 and generate documentation using this template:

## Template
```markdown
# API Endpoint: {endpoint_name}

## Overview
{brief_description}

## Request
- **Method:** {http_method}
- **Path:** {url_path}
- **Auth:** {auth_type}

### Headers
| Header | Required | Description |
|--------|----------|-------------|
{headers_table}

### Body
```json
{request_body_example}
```

## Response
### Success (200)
```json
{success_response}
```

### Errors
| Code | Description |
|------|-------------|
{error_codes}
```

Fill in the template based on the analyzed endpoint.
```

### Continuous Learning Skill Configuration

```json
{
  "min_session_length": 10,
  "extraction_threshold": "medium",
  "auto_approve": false,
  "learned_skills_path": "~/.claude/skills/learned/",
  "patterns_to_detect": [
    "error_resolution",
    "user_corrections",
    "workarounds",
    "debugging_techniques",
    "project_specific"
  ],
  "ignore_patterns": [
    "simple_typos",
    "one_time_fixes",
    "external_api_issues"
  ]
}
```

---

## Quick Reference

### Create a New Skill

```bash
# Personal skill
mkdir -p ~/.claude/skills/my-skill
cat > ~/.claude/skills/my-skill/SKILL.md << 'EOF'
---
description: What this skill does
---

Skill instructions here...
EOF
```

### Frontmatter Cheatsheet

```yaml
---
name: skill-name                    # Display name
description: What it does           # Required for Claude to know when to use
argument-hint: [arg1] [arg2]        # Autocomplete hint
disable-model-invocation: true      # User-only invocation
user-invocable: false               # Hidden from menu
allowed-tools: Read, Grep, Glob     # Permitted tools
model: sonnet                       # Model override
context: fork                       # Run in subagent
agent: Explore                      # Subagent type
---
```

### Dynamic Substitutions Cheatsheet

| Syntax | Description | Example |
|--------|-------------|---------|
| `$ARGUMENTS` | All arguments | `/skill foo bar` → `foo bar` |
| `$0`, `$1`, `$2` | Positional args | `/skill a b c` → `$0=a, $1=b, $2=c` |
| `$ARGUMENTS[N]` | Same as `$N` | `$ARGUMENTS[0]` = `$0` |
| `${CLAUDE_SESSION_ID}` | Session ID | `abc123-def456` |
| `${CLAUDE_PLUGIN_ROOT}` | Plugin directory | `/path/to/plugin` |
| `!`backticks`` | Bash execution | `!`date`` → `Thu Jan 1 00:00:00` |
| `@path/to/file` | File reference | `@src/main.ts` |

### Common Tool Sets

| Use Case | Tools |
|----------|-------|
| Read-only analysis | `Read, Grep, Glob` |
| Code generation | `Read, Write, Edit, Grep` |
| Git operations | `Read, Bash(git:*)` |
| Testing | `Read, Bash(npm:*), Bash(yarn:*)` |
| Full access | `"*"` or omit field |

---

## Resources

### Official Documentation
- [Claude Code Skills](https://code.claude.com/docs/en/skills)
- [Claude Code Slash Commands](https://code.claude.com/docs/en/slash-commands)
- [Claude Code Plugins](https://code.claude.com/docs/en/plugins)
- [Claude Code Hooks](https://code.claude.com/docs/en/hooks)

### Community Resources
- [Everything Claude Code](https://github.com/affaan-m/everything-claude-code) - Battle-tested configurations
- [Claude Code Templates](https://github.com/davila7/claude-code-templates) - Ready-to-use templates
- [Claude Code Cookbook](https://github.com/wasabeef/claude-code-cookbook) - Settings and custom commands

### Related Topics
- [Agents Development](https://github.com/anthropics/claude-code/blob/main/plugins/plugin-dev/skills/agent-development/SKILL.md)
- [Hook Development](https://github.com/anthropics/claude-code/blob/main/plugins/plugin-dev/skills/hook-development/SKILL.md)
- [MCP Integration](https://github.com/anthropics/claude-code/blob/main/plugins/plugin-dev/skills/mcp-integration/SKILL.md)

---

*This guide was compiled from official Anthropic documentation, community resources, and best practices. Last updated: 2025*
