# Multi-Stage Build Patterns

Detailed, language-specific multi-stage Dockerfile patterns. Read this when building Dockerfiles for Python, Node.js, or Go applications.

## Table of Contents

1. [Why Multi-Stage Builds](#why-multi-stage-builds)
2. [Python with uv](#python-with-uv)
3. [Python with pip](#python-with-pip-fallback)
4. [Node.js with Next.js](#nodejs-with-nextjs)
5. [Node.js with pnpm](#nodejs-with-pnpm)
6. [Go](#go)
7. [Monorepo Patterns](#monorepo-patterns)
8. [Common Pitfalls](#common-pitfalls)

---

## Why Multi-Stage Builds

Multi-stage builds separate build-time dependencies from runtime, producing minimal, secure images.

| Metric | Single-stage | Multi-stage |
|--------|-------------|-------------|
| Image size (Python) | ~800MB | ~200MB |
| Image size (Node.js) | ~1.2GB | ~150MB |
| Image size (Go) | ~1GB | ~15MB |
| Attack surface | High (compilers, build tools) | Low (runtime only) |
| Build cache | Poor | Excellent (per-stage) |

**Key concepts:**
- Each `FROM` starts a new stage
- `COPY --from=stagename` copies files between stages
- Only the final stage goes into the image
- BuildKit builds stages in parallel when possible

---

## Python with uv

uv is the preferred Python package manager — 10-100x faster than pip.

```dockerfile
# ---------------------------------------------------------------------------
# Stage 1: Builder
# ---------------------------------------------------------------------------
FROM python:3.12-slim AS builder

# Install uv from the official image
COPY --from=ghcr.io/astral-sh/uv:latest /uv /usr/local/bin/uv

WORKDIR /app

# Copy dependency definition files FIRST for layer caching
COPY pyproject.toml uv.lock ./

# Install dependencies with BuildKit cache mount
# --frozen: fail if lockfile doesn't match pyproject.toml
# --no-dev: skip development dependencies
# --no-editable: install as regular package (not editable/symlink)
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --frozen --no-dev --no-editable

# Copy application source
COPY api/ ./api/
COPY agent/ ./agent/
COPY database/ ./database/

# Install the project itself (registers console_scripts entry points)
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --frozen --no-dev


# ---------------------------------------------------------------------------
# Stage 2: Runtime
# ---------------------------------------------------------------------------
FROM python:3.12-slim AS runtime

RUN groupadd -r app && useradd -r -g app -d /app -s /sbin/nologin app
WORKDIR /app

# Copy virtual environment (contains all installed packages)
COPY --from=builder /app/.venv /app/.venv

# Copy application code
COPY --from=builder /app/api/ ./api/
COPY --from=builder /app/agent/ ./agent/
COPY --from=builder /app/database/ ./database/

ENV PATH="/app/.venv/bin:$PATH"
ENV PYTHONUNBUFFERED=1
ENV PYTHONDONTWRITEBYTECODE=1

EXPOSE 8000
USER app

ENTRYPOINT ["uvicorn", "api.main:app"]
CMD ["--host", "0.0.0.0", "--port", "8000"]
```

### Key decisions:
- **python:3.12-slim, not alpine**: asyncpg, numpy, and other C-extension packages need glibc. Alpine uses musl, which requires compiling from source (slower builds, larger images).
- **uv sync --frozen**: Ensures the lockfile matches pyproject.toml exactly. Fails if someone changed pyproject.toml without updating the lockfile.
- **BuildKit cache mount**: `--mount=type=cache,target=/root/.cache/uv` persists the uv download cache across builds, making rebuilds much faster.
- **Two uv sync calls**: First installs dependencies (cached layer). Second installs the project itself (needs source code).

---

## Python with pip (fallback)

When uv isn't available or the project uses pip/pip-tools.

```dockerfile
# ---------------------------------------------------------------------------
# Stage 1: Builder
# ---------------------------------------------------------------------------
FROM python:3.12-slim AS builder

WORKDIR /app

# Create virtualenv
RUN python -m venv /app/.venv
ENV PATH="/app/.venv/bin:$PATH"

# Install dependencies FIRST for layer caching
COPY requirements.txt ./
RUN --mount=type=cache,target=/root/.cache/pip \
    pip install --no-deps -r requirements.txt

# Copy and install application
COPY . .
RUN pip install --no-deps .


# ---------------------------------------------------------------------------
# Stage 2: Runtime
# ---------------------------------------------------------------------------
FROM python:3.12-slim AS runtime

RUN groupadd -r app && useradd -r -g app -d /app -s /sbin/nologin app
WORKDIR /app

COPY --from=builder /app/.venv /app/.venv
COPY --from=builder /app/ /app/

ENV PATH="/app/.venv/bin:$PATH"
ENV PYTHONUNBUFFERED=1

EXPOSE 8000
USER app
CMD ["uvicorn", "api.main:app", "--host", "0.0.0.0", "--port", "8000"]
```

### Key difference from uv:
- **Explicit virtualenv creation**: pip doesn't create venvs automatically
- **--no-deps**: Prevents pip from pulling transitive dependencies not in requirements.txt (reproducibility)
- **requirements.txt**: Must be pre-generated via `pip-compile` or `pip freeze`

---

## Node.js with Next.js

Three-stage build leveraging Next.js standalone output mode.

**Prerequisite**: `next.config.ts` must include:
```typescript
const nextConfig = {
  output: 'standalone',
};
```

```dockerfile
# ---------------------------------------------------------------------------
# Stage 1: Dependencies
# ---------------------------------------------------------------------------
FROM node:22-slim AS deps
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts


# ---------------------------------------------------------------------------
# Stage 2: Builder
# ---------------------------------------------------------------------------
FROM node:22-slim AS builder
WORKDIR /app

COPY --from=deps /app/node_modules ./node_modules
COPY . .

# Build-time env vars (baked into client bundle)
ARG NEXT_PUBLIC_API_URL=http://localhost:8000
ENV NEXT_PUBLIC_API_URL=${NEXT_PUBLIC_API_URL}

RUN npm run build


# ---------------------------------------------------------------------------
# Stage 3: Runner
# ---------------------------------------------------------------------------
FROM node:22-slim AS runner
WORKDIR /app
ENV NODE_ENV=production

RUN groupadd -r app && useradd -r -g app -d /app -s /sbin/nologin app

# standalone output includes a minimal server.js and only required node_modules
COPY --from=builder /app/.next/standalone ./
COPY --from=builder /app/.next/static ./.next/static
COPY --from=builder /app/public ./public

EXPOSE 3000
USER app
CMD ["node", "server.js"]
```

### Key decisions:
- **Three stages, not two**: Separating `deps` from `builder` means changing source code doesn't re-run `npm ci`.
- **standalone output**: Next.js traces which `node_modules` are actually used and copies only those into `.next/standalone`. The rest are discarded, dramatically reducing image size.
- **Static assets**: `.next/static` and `public/` aren't included in standalone output automatically — must be copied explicitly.
- **ARG for NEXT_PUBLIC_***: These env vars are baked into the JavaScript bundle at build time. They CANNOT be changed at runtime. Different environments need different builds (or use runtime config).

---

## Node.js with pnpm

Alternative using pnpm (faster installs, stricter dependency resolution).

```dockerfile
FROM node:22-slim AS deps
WORKDIR /app
RUN corepack enable pnpm

COPY package.json pnpm-lock.yaml ./
RUN --mount=type=cache,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile --prod


FROM node:22-slim AS builder
WORKDIR /app
RUN corepack enable pnpm

COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN pnpm run build


FROM node:22-slim AS runner
WORKDIR /app
ENV NODE_ENV=production

RUN groupadd -r app && useradd -r -g app -d /app -s /sbin/nologin app

COPY --from=builder /app/.next/standalone ./
COPY --from=builder /app/.next/static ./.next/static
COPY --from=builder /app/public ./public

EXPOSE 3000
USER app
CMD ["node", "server.js"]
```

### Key difference from npm:
- **corepack**: Node.js ships with corepack, which manages pnpm versions. `corepack enable pnpm` activates it without global install.
- **pnpm store cache**: pnpm uses a content-addressable store. The BuildKit cache mount keeps it across builds.
- **--frozen-lockfile**: Like npm ci — fails if lockfile doesn't match package.json.

---

## Go

Go produces static binaries, enabling the smallest possible runtime images.

```dockerfile
# ---------------------------------------------------------------------------
# Stage 1: Builder
# ---------------------------------------------------------------------------
FROM golang:1.23 AS builder

WORKDIR /app

# Cache go modules
COPY go.mod go.sum ./
RUN go mod download

# Copy source and build
COPY . .

# CGO_DISABLED=1: pure Go binary, no C dependencies
# -ldflags '-s -w': strip debug info (smaller binary)
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags='-s -w' -o /app/server ./cmd/server


# ---------------------------------------------------------------------------
# Stage 2: Runtime (distroless — no shell, no package manager, ~2MB base)
# ---------------------------------------------------------------------------
FROM gcr.io/distroless/static-debian12 AS runtime

COPY --from=builder /app/server /server

EXPOSE 8080
USER nonroot:nonroot
ENTRYPOINT ["/server"]
```

### Key decisions:
- **CGO_ENABLED=0**: Produces a fully static binary. No dependency on glibc or musl.
- **distroless**: Google's minimal image. Contains only the app and ca-certificates. No shell, no ls, no curl — maximally secure.
- **Alternative: `FROM scratch`**: Even smaller (0 bytes base), but lacks CA certificates for HTTPS. Use for internal-only services.
- **-ldflags '-s -w'**: Strips symbol table and DWARF debug info, reducing binary by ~30%.

---

## Monorepo Patterns

When multiple services share a single repository.

### Shared Base + Service-Specific Stages

```dockerfile
# Shared base for all Python services
FROM python:3.12-slim AS python-base
COPY --from=ghcr.io/astral-sh/uv:latest /uv /usr/local/bin/uv
WORKDIR /app

# Service: API
FROM python-base AS api-builder
COPY services/api/pyproject.toml services/api/uv.lock ./
RUN uv sync --frozen --no-dev
COPY services/api/ .
COPY shared/ ./shared/

FROM python:3.12-slim AS api-runtime
COPY --from=api-builder /app/.venv /app/.venv
COPY --from=api-builder /app/ /app/
# ...

# Service: Worker
FROM python-base AS worker-builder
COPY services/worker/pyproject.toml services/worker/uv.lock ./
RUN uv sync --frozen --no-dev
COPY services/worker/ .
COPY shared/ ./shared/
# ...
```

### Building Specific Targets from Compose

```yaml
services:
  api:
    build:
      context: .
      dockerfile: Dockerfile
      target: api-runtime     # Build only the API stages

  worker:
    build:
      context: .
      dockerfile: Dockerfile
      target: worker-runtime  # Build only the worker stages
```

---

## Common Pitfalls

### 1. COPY . . before dependency install (breaks cache)

```dockerfile
# BAD — any source change re-installs all dependencies
COPY . .
RUN pip install -r requirements.txt

# GOOD — dependencies cached unless lockfile changes
COPY requirements.txt ./
RUN pip install -r requirements.txt
COPY . .
```

### 2. Forgetting to copy runtime dependencies

```dockerfile
# BAD — forgot to copy the venv
FROM python:3.12-slim AS runtime
COPY --from=builder /app/ /app/
# ModuleNotFoundError: No module named 'fastapi'

# GOOD — explicitly copy the virtual environment
COPY --from=builder /app/.venv /app/.venv
ENV PATH="/app/.venv/bin:$PATH"
```

### 3. Using alpine when packages need glibc

```dockerfile
# BAD — asyncpg fails to import (compiled for glibc, running on musl)
FROM python:3.12-alpine AS runtime

# GOOD — slim is Debian-based with glibc
FROM python:3.12-slim AS runtime
```

### 4. Running as root

```dockerfile
# BAD — container runs as root (security risk)
CMD ["uvicorn", "api.main:app"]

# GOOD — dedicated non-root user
RUN groupadd -r app && useradd -r -g app app
USER app
CMD ["uvicorn", "api.main:app"]
```

### 5. Shell form ENTRYPOINT (broken signal handling)

```dockerfile
# BAD — wraps in /bin/sh, process is PID 1's child, doesn't get SIGTERM
ENTRYPOINT python -m uvicorn api.main:app

# GOOD — exec form, process IS PID 1, receives SIGTERM directly
ENTRYPOINT ["uvicorn", "api.main:app"]
```

### 6. Baking secrets into layers

```dockerfile
# BAD — secret visible in docker history
ARG API_KEY=sk-secret
ENV API_KEY=$API_KEY

# GOOD — pass at runtime
# docker run -e API_KEY=sk-secret myimage
# OR use BuildKit secrets for build-time only:
RUN --mount=type=secret,id=api_key cat /run/secrets/api_key
```

### 7. Not using .dockerignore

Without `.dockerignore`, the entire directory (including `.git/`, `node_modules/`, `.venv/`) is sent to the Docker daemon as build context. This can add minutes to every build and accidentally include secrets.
