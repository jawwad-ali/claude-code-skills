---
name: Knowledge Router
description: This skill should be used internally to decide when to use curated skills versus Context7 MCP for documentation lookups. Use this when handling technical questions about frameworks, libraries, or APIs to determine the optimal knowledge retrieval strategy.
version: 1.0.0
---

# Knowledge Router: Skill + Context7 Hybrid System

This meta-skill provides decision logic for routing knowledge requests between curated skills and Context7 MCP server.

## Decision Framework

```
┌─────────────────────────────────────────────────────────────────┐
│                      QUERY ANALYSIS                              │
└─────────────────────────────┬───────────────────────────────────┘
                              │
                              ▼
                   ┌─────────────────────┐
                   │  Check Query Type   │
                   └──────────┬──────────┘
                              │
        ┌─────────────────────┼─────────────────────┐
        │                     │                     │
        ▼                     ▼                     ▼
  ┌───────────┐        ┌───────────┐        ┌───────────┐
  │  PATTERN  │        │  LOOKUP   │        │  HYBRID   │
  │  REQUEST  │        │  REQUEST  │        │  REQUEST  │
  └─────┬─────┘        └─────┬─────┘        └─────┬─────┘
        │                    │                    │
        ▼                    ▼                    ▼
   Use SKILL            Use CONTEXT7         Use BOTH
   (curated)            (dynamic)            (layered)
```

## Query Classification

### Type 1: PATTERN REQUESTS → Use Skill First

**Indicators:**
- "How do I..." / "Create a..." / "Implement..."
- "Best practice for..." / "Recommended way to..."
- "Add X to my project" / "Set up X"
- Common tasks (CRUD, auth, routing, etc.)

**Examples:**
```
"How do I add authentication to FastAPI?" → SKILL
"Create a REST endpoint" → SKILL
"Best practice for error handling" → SKILL
"Set up database connection" → SKILL
```

**Action:** Load relevant skill, use curated patterns.

---

### Type 2: LOOKUP REQUESTS → Use Context7 First

**Indicators:**
- "What is the syntax for..." / "What are the parameters..."
- "Does X support Y?" / "Can I do X with Y?"
- Specific version questions ("in FastAPI 0.100+")
- Error messages (pasted stack traces)
- "Latest" / "New" / "Updated" / "Deprecated"
- Obscure/advanced features

**Examples:**
```
"What parameters does HTTPException accept?" → CONTEXT7
"Does FastAPI support HTTP/2?" → CONTEXT7
"Error: ValidationError for field X" → CONTEXT7
"What's new in Pydantic v2?" → CONTEXT7
"How do I use the new Annotated syntax?" → CONTEXT7
```

**Action:** Query Context7 directly for authoritative docs.

---

### Type 3: HYBRID REQUESTS → Skill + Context7

**Indicators:**
- Complex implementations needing patterns + details
- "Complete example of..." / "Production-ready..."
- Integrations between multiple libraries
- Debugging (need pattern context + error lookup)

**Examples:**
```
"Production-ready JWT auth with refresh tokens" → HYBRID
"Integrate FastAPI with Celery" → HYBRID
"Debug this authentication error [paste]" → HYBRID
"Complete WebSocket chat implementation" → HYBRID
```

**Action:**
1. Load skill for architectural pattern
2. Query Context7 for specific implementation details
3. Synthesize unified response

---

## Routing Decision Matrix

| Signal | Route To | Reason |
|--------|----------|--------|
| Common task keywords | Skill | Pre-validated patterns |
| Error message pasted | Context7 | Search for specific error |
| "Latest", "new", "v2" | Context7 | Need current docs |
| "Best practice", "recommended" | Skill | Curated opinions |
| Specific parameter questions | Context7 | API reference |
| Architecture questions | Skill | Curated structures |
| Third-party integration | Context7 first | May not be in skill |
| "Why does X..." | Hybrid | Pattern + explanation |
| Debugging request | Hybrid | Context + specific error |

---

## Execution Protocol

### When Using SKILL Only

```
1. Identify the relevant skill (fastapi, nextjs, etc.)
2. Load SKILL.md into context
3. Generate response using curated patterns
4. If skill indicates "ESCALATE" for topic → switch to Hybrid
```

### When Using CONTEXT7 Only

```
1. Resolve library ID:
   mcp__context7__resolve-library-id(
     libraryName: "fastapi",
     query: "user's specific question"
   )

2. Query documentation:
   mcp__context7__query-docs(
     libraryId: "/websites/fastapi_tiangolo",
     query: "specific topic from user question"
   )

3. Synthesize response from fetched docs
```

### When Using HYBRID (Recommended for Complex Tasks)

```
1. Load relevant skill for architectural context
2. Generate initial structure using skill patterns
3. Identify gaps or specific details needed
4. Query Context7 for those specifics:
   mcp__context7__query-docs(libraryId, "specific detail needed")
5. Merge skill patterns with Context7 details
6. Return unified, comprehensive response
```

---

## Available Skills & Their Coverage

| Skill | Covers (Tier 1) | Escalate to Context7 For |
|-------|-----------------|--------------------------|
| `fastapi` | Routes, Pydantic, Auth, DI, Middleware | WebSocket scaling, GraphQL, specific drivers |
| `fastapi-hybrid` | Same + built-in escalation triggers | Auto-escalates per topic |
| `nextjs-app-router` | Pages, Layouts, RSC, Server Actions | Specific Next.js config, edge runtime |

---

## Context7 Library Quick Reference

| Technology | Library ID | Best For |
|------------|------------|----------|
| FastAPI | `/websites/fastapi_tiangolo` | Full docs, tutorials |
| FastAPI (GitHub) | `/fastapi/fastapi` | Source-level details |
| Pydantic | `/pydantic/pydantic` | Validation, v2 migration |
| SQLAlchemy | `/sqlalchemy/sqlalchemy` | ORM, async patterns |
| Starlette | `/encode/starlette` | Low-level ASGI details |
| Next.js | `/vercel/next.js` | App router, RSC |
| React | `/facebook/react` | Hooks, patterns |

---

## Response Quality Checklist

Before finalizing a response, verify:

- [ ] **Pattern provided** - Not just API reference, but how to use it
- [ ] **Code is complete** - Imports included, runnable example
- [ ] **Follows conventions** - Matches project structure if known
- [ ] **Error handling shown** - Not just happy path
- [ ] **Version appropriate** - Correct syntax for current versions
- [ ] **Escalation noted** - If topic has nuances, mention where to find more

---

## Example Routing Decisions

### Example 1: "Add JWT authentication to my FastAPI app"

**Analysis:**
- Task: Implementation pattern request
- Complexity: Medium (common task)
- Specificity: General

**Route:** SKILL (fastapi)
- Load fastapi skill
- Provide JWT pattern from curated examples
- Note: If user needs OAuth2 scopes or specific providers, offer to query Context7

---

### Example 2: "What's the difference between Depends and Security in FastAPI?"

**Analysis:**
- Task: Conceptual/API explanation
- Complexity: Low
- Specificity: Specific to FastAPI internals

**Route:** CONTEXT7
- Query: `mcp__context7__query-docs("/websites/fastapi_tiangolo", "Depends vs Security dependency injection")`
- Return explanation from official docs

---

### Example 3: "Build a production-ready chat application with FastAPI WebSockets"

**Analysis:**
- Task: Complex implementation
- Complexity: High
- Specificity: Needs architecture + details

**Route:** HYBRID
1. Load fastapi skill → Get basic WebSocket pattern
2. Query Context7 → "websocket connection manager broadcast rooms"
3. Query Context7 → "websocket authentication"
4. Synthesize → Complete chat architecture with all details

---

## Token Efficiency Guidelines

| Scenario | Strategy | Estimated Tokens |
|----------|----------|------------------|
| Single question, common topic | Skill only | ~2,000 (skill load) |
| Single question, specific lookup | Context7 only | ~1,500 (one query) |
| Multi-turn, same topic | Skill (amortized) | ~2,000 total |
| Complex implementation | Hybrid | ~3,500 (skill + 1-2 queries) |
| Debugging session | Hybrid | ~4,000 (skill + error lookups) |

**Optimization:** For multi-turn conversations on the same technology, load the skill once and only query Context7 for specific gaps.
