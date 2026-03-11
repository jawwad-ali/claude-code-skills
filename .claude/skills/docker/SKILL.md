---
name: docker
description: >-
  This skill MUST be loaded when ANY Dockerfile, docker-compose.yml,
  docker-compose.override.yml, .dockerignore, or Docker-related configuration
  file is being read, reviewed, edited, or created. Also use when the user asks
  to "create a Dockerfile", "write a multi-stage build", "add a .dockerignore",
  "set up docker-compose", "containerize an application", "optimize a Docker
  image", "reduce Docker image size", "fix Docker build issues", "add health
  checks to containers", "set up a local dev environment with Docker", "create
  a multi-service setup", "debug container startup", "configure Docker volumes",
  "set up database containers", or mentions Dockerfile, Docker Compose,
  multi-stage builds, container images, build context, layer caching,
  ENTRYPOINT, CMD, docker-compose.yml, Docker health checks, or container
  orchestration for local development.
---

# Docker Development Guide

This skill provides comprehensive guidance for writing production-ready Dockerfiles, .dockerignore files, and Docker Compose configurations for multi-service applications.

## Core Principles

1. **Ephemeral containers** — Containers can be stopped, destroyed, and replaced at any time. Never store state inside a container; use volumes or external services.
2. **Single process per container** — Each container runs one concern (API, frontend, database, cache). Compose or K8s orchestrates them together.
3. **Minimal surface area** — Use slim/alpine base images. Install only what the application needs. Remove build tools from runtime images via multi-stage builds.
4. **Layer caching matters** — Docker caches each instruction. Put the least-changing layers first (OS deps → app deps → app code). A single changed line invalidates all subsequent layers.
5. **Security by default** — Run as non-root, never bake secrets into layers, pin base image versions, scan for vulnerabilities.
6. **Reproducible builds** — Pin dependency versions (lockfiles), pin base image tags to minor versions (not `latest`), use `--frozen` / `--ci` flags for package managers.

## Dockerfile Fundamentals

### Base Image Selection

| Use Case | Image | Why |
|----------|-------|-----|
| Python apps | `python:3.12-slim` | glibc-based (asyncpg/numpy work), smaller than full |
| Node.js apps | `node:22-slim` | Consistent with CI, npm works natively |
| Node.js (minimal) | `node:22-alpine` | Smallest Node image, good for simple apps |
| Go apps | `golang:1.23` build → `gcr.io/distroless/static-debian12` run | Zero-dependency static binary |
| Static files | `nginx:alpine` | Tiny, battle-tested web server |
| PostgreSQL + pgvector | `pgvector/pgvector:pg16` | pgvector pre-installed, avoids manual extension setup |
| Redis | `redis:7-alpine` | Minimal Redis with optional AOF persistence |

**Why not alpine for Python?** Alpine uses musl libc. Packages like `asyncpg`, `numpy`, and `psycopg2` need glibc or require compilation from source, making builds slower and images potentially larger. Use `slim` (Debian-based, glibc) unless you have no C extensions.

### Instruction Reference

**FROM** — Always pin to a specific minor version. Never use `latest` or bare major tags.
```dockerfile
# Good
FROM python:3.12-slim AS builder
# Bad
FROM python:latest
FROM python:3
```

**RUN** — Consolidate commands with `&&` to reduce layers. Clean up caches in the same layer they're created.
```dockerfile
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*
```

**COPY vs ADD** — Always use `COPY` unless you specifically need tar auto-extraction or remote URL fetching (rare). `COPY` is explicit and predictable.

**ARG vs ENV** — `ARG` exists only during build (not in final image). `ENV` persists into running containers. Use `ARG` for build-time-only values like `NEXT_PUBLIC_API_URL`.
```dockerfile
ARG NEXT_PUBLIC_API_URL=http://localhost:8000
ENV NODE_ENV=production
```

**EXPOSE** — Documentation only. Does not actually publish ports. Always pair with `-p` in `docker run` or `ports:` in Compose.

**ENTRYPOINT vs CMD** — Use together: ENTRYPOINT for the executable, CMD for default arguments. Always use exec form (JSON array), not shell form.
```dockerfile
# Correct: exec form — process gets PID 1, receives SIGTERM directly
ENTRYPOINT ["uvicorn", "api.main:app"]
CMD ["--host", "0.0.0.0", "--port", "8000"]

# Wrong: shell form — wraps in /bin/sh, SIGTERM goes to shell not app
ENTRYPOINT uvicorn api.main:app
```

**USER** — Always create and switch to a non-root user before CMD.
```dockerfile
RUN groupadd -r app && useradd -r -g app -d /app -s /sbin/nologin app
USER app
```

**WORKDIR** — Use absolute paths. Creates the directory if it doesn't exist. Prefer over `RUN mkdir`.

**HEALTHCHECK** — Define health checks at the Dockerfile level. Works in standalone Docker, Compose, and K8s.
```dockerfile
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
    CMD curl -f http://localhost:8000/health || exit 1
```

### Multi-Stage Build Pattern

The most important Docker pattern. Separates build-time dependencies (compilers, build tools) from runtime, producing minimal final images.

```dockerfile
# Stage 1: Builder — has build tools, compiles dependencies
FROM python:3.12-slim AS builder
WORKDIR /app
COPY pyproject.toml uv.lock ./
RUN pip install uv && uv sync --frozen --no-dev

# Stage 2: Runtime — only the app and its runtime dependencies
FROM python:3.12-slim AS runtime
WORKDIR /app
COPY --from=builder /app/.venv /app/.venv
COPY . .
ENV PATH="/app/.venv/bin:$PATH"
USER app
CMD ["uvicorn", "api.main:app", "--host", "0.0.0.0", "--port", "8000"]
```

**Key insight**: The builder stage can be 500MB+ with gcc, make, and header files. The runtime stage copies only the compiled output, typically 100-200MB.

For language-specific multi-stage patterns (Python/uv, Node.js/Next.js, Go), see `references/multi-stage-builds.md`.

## .dockerignore

The `.dockerignore` file controls what Docker sends to the build daemon as "build context." Without it, Docker sends everything — including `.git/` (often 100MB+), `node_modules/`, `.venv/`, and secrets.

**Place one `.dockerignore` per build context** — if your API and frontend have separate Dockerfiles with different build contexts, each needs its own `.dockerignore`.

### Python Project Template
```
.venv/
venv/
__pycache__/
*.pyc
*.pyo
*.egg-info/
dist/
build/
.git/
.env
.env.*
.vscode/
.idea/
*.md
tests/
.coverage
htmlcov/
.pytest_cache/
Dockerfile
.dockerignore
```

### Node.js Project Template
```
node_modules/
.next/
out/
.env
.env.*
.env.local
coverage/
*.tsbuildinfo
.vscode/
.idea/
*.md
Dockerfile
.dockerignore
```

### Universal Exclusions (add to every .dockerignore)
```
.git/
.DS_Store
Thumbs.db
*.tmp
*.swp
*.log
docker-compose*.yml
k8s/
specs/
docs/
```

## Docker Compose Essentials

### Service Definition Pattern

```yaml
services:
  api:
    build:
      context: .
      dockerfile: Dockerfile
      target: runtime            # Select multi-stage target
    ports:
      - "8000:8000"
    environment:
      DATABASE_URL: postgresql://user:pass@postgres:5432/db
      REDIS_URL: redis://redis:6379
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
    restart: unless-stopped
```

### depends_on with Health Checks

The `condition: service_healthy` pattern ensures services start only after their dependencies are truly ready — not just running.

```yaml
services:
  postgres:
    image: postgres:16
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 5s
      timeout: 3s
      retries: 5

  redis:
    image: redis:7-alpine
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 5

  api:
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
```

### Volumes

**Named volumes** for data persistence (survives `docker compose down`):
```yaml
services:
  postgres:
    volumes:
      - postgres_data:/var/lib/postgresql/data

volumes:
  postgres_data:    # Named volume — Docker manages the storage
```

**Bind mounts** for development hot-reload:
```yaml
services:
  api:
    volumes:
      - ./api:/app/api        # Source code mounted for live changes
      - ./agent:/app/agent
```

### Environment Variables

Three ways to inject env vars (in precedence order):
1. `environment:` — inline in compose file (good for non-sensitive defaults)
2. `env_file:` — load from a file (good for shared config)
3. Shell environment — `${VAR}` substitution from the host shell

```yaml
services:
  api:
    env_file: .env                          # Load from file
    environment:
      OPENAI_MODEL: gpt-4o                  # Inline override
      DATABASE_URL: ${DATABASE_URL}         # From host shell
```

### Health Check Patterns by Service

| Service | Health Check |
|---------|-------------|
| PostgreSQL | `pg_isready -U postgres` |
| Redis | `redis-cli ping` |
| HTTP API | `curl -f http://localhost:PORT/health \|\| exit 1` |
| Next.js | `curl -f http://localhost:3000 \|\| exit 1` |

### Port Mapping

```yaml
ports:
  - "8000:8000"    # host:container — accessible from host machine
expose:
  - "8000"         # container only — accessible from other services, not host
```

### Restart Policies

| Policy | Use Case |
|--------|----------|
| `no` | Default. Don't restart. |
| `unless-stopped` | Development — restarts on crash, stops with `docker compose stop` |
| `always` | Production-like — always restarts, even after daemon restart |
| `on-failure:3` | Restart up to 3 times on non-zero exit |

### Database Initialization

Mount init scripts into PostgreSQL's entrypoint directory:
```yaml
services:
  postgres:
    image: postgres:16
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ./database/migrations/001_initial_schema.sql:/docker-entrypoint-initdb.d/001_schema.sql
```

Files in `/docker-entrypoint-initdb.d/` run automatically on first startup (when data volume is empty). Supports `.sql`, `.sql.gz`, and `.sh` files, executed in alphabetical order.

For advanced Compose patterns (override files, profiles, init scripts, secrets, extension fields), see `references/compose-patterns.md`.

## Cross-Cutting Concerns

### Graceful Shutdown

Containers receive `SIGTERM` on stop. The application must handle it to close connections cleanly.

- **Always use exec form** for ENTRYPOINT/CMD — the process gets PID 1 and receives signals directly
- Shell form (`ENTRYPOINT command arg`) wraps in `/bin/sh -c`, which may not forward signals
- Python: uvicorn handles SIGTERM natively. Node.js: Next.js handles it. Go: use `signal.Notify`

### Health Check Portability

The same health check concept works across all levels:
- **Dockerfile**: `HEALTHCHECK` instruction
- **Docker Compose**: `healthcheck:` in service definition
- **Kubernetes**: `livenessProbe` and `readinessProbe` in pod spec

Design health endpoints once (`/health/live`, `/health/ready`), use everywhere.

### Secrets Handling

**Never do this:**
```dockerfile
COPY .env /app/.env                    # Secret baked into image layer
ARG OPENAI_API_KEY                     # Secret visible in build history
ENV OPENAI_API_KEY=sk-...              # Secret in image metadata
```

**Instead:**
- Pass secrets at runtime via `environment:` or `env_file:` in Compose
- Use `docker secret` for Swarm / `kubectl create secret` for K8s
- For build-time secrets: `RUN --mount=type=secret,id=mysecret cat /run/secrets/mysecret`

### Logging

Containers should log to stdout/stderr. The orchestrator (Docker, Compose, K8s) handles log collection, rotation, and forwarding. Never write logs to files inside the container.

## Best Practices

1. **Pin base image versions** to minor (`python:3.12-slim`, not `python:3-slim` or `latest`)
2. **Least-changing layers first** — OS deps → app deps (lockfile) → app source code
3. **Use .dockerignore** — exclude everything not needed in the image
4. **Run as non-root** — create a dedicated user, switch with `USER`
5. **One process per container** — orchestrate with Compose or K8s
6. **Multi-stage builds** — separate build-time from runtime dependencies
7. **Health checks everywhere** — Dockerfile, Compose, and K8s probes
8. **Never store secrets in layers** — pass at runtime, not build time
9. **Prefer COPY over ADD** — explicit and predictable
10. **Use exec form** for ENTRYPOINT/CMD — correct signal handling

## When to Read Reference Files

| Need | File |
|------|------|
| Writing a Dockerfile for Python/uv, Node.js/Next.js, or Go | `references/multi-stage-builds.md` |
| Advanced Compose: override files, profiles, init scripts, secrets | `references/compose-patterns.md` |
| Build is slow, image is too large, need BuildKit features | `references/optimization.md` |

## Example Templates

| Need | File |
|------|------|
| Python/FastAPI Dockerfile with uv | `examples/python-fastapi.Dockerfile` |
| Node.js/Next.js Dockerfile with standalone output | `examples/node-nextjs.Dockerfile` |
| Full-stack docker-compose.yml (API + frontend + DB + cache) | `examples/docker-compose-fullstack.yml` |
