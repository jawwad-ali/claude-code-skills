# Docker Compose Advanced Patterns

Detailed patterns for multi-service orchestration with Docker Compose. Read this when you need override files, profiles, init scripts, secrets management, or complex service topologies.

## Table of Contents

1. [Override Files](#override-files)
2. [Profiles](#profiles)
3. [Init and Entrypoint Scripts](#init-and-entrypoint-scripts)
4. [Extension Fields (DRY)](#extension-fields-dry)
5. [Hot-Reload for Development](#hot-reload-for-development)
6. [Database Patterns](#database-patterns)
7. [Secrets Management](#secrets-management)
8. [Networking](#networking)
9. [Resource Limits](#resource-limits)
10. [Full-Stack Template](#full-stack-template)

---

## Override Files

Compose automatically loads `docker-compose.yml` + `docker-compose.override.yml`. Use overrides to customize for different environments without modifying the base file.

### docker-compose.yml (base — production-like)
```yaml
services:
  api:
    build:
      context: .
      target: runtime
    ports:
      - "8000:8000"
    restart: always
```

### docker-compose.override.yml (auto-loaded — development)
```yaml
services:
  api:
    build:
      target: builder          # Use builder stage (has dev tools)
    volumes:
      - ./api:/app/api         # Hot-reload source
      - ./agent:/app/agent
    command: ["--reload"]      # Appended to ENTRYPOINT
    restart: "no"              # Don't restart during dev
```

### docker-compose.prod.yml (explicit — production)
```yaml
services:
  api:
    restart: always
    deploy:
      replicas: 3
      resources:
        limits:
          cpus: "0.5"
          memory: 512M
```

### Usage
```bash
# Development (auto-loads override)
docker compose up

# Production (explicit file selection)
docker compose -f docker-compose.yml -f docker-compose.prod.yml up

# Testing (combine base + test override)
docker compose -f docker-compose.yml -f docker-compose.test.yml up
```

### Merge rules
- **Scalars** (image, command): Override replaces base
- **Lists** (ports, volumes): Override merges with base
- **Maps** (environment, labels): Override merges with base (per-key)

---

## Profiles

Profiles let you define optional services that only start when explicitly requested.

```yaml
services:
  api:
    build: .
    # No profile — starts by default

  postgres:
    image: postgres:16
    # No profile — starts by default

  pgadmin:
    image: dpage/pgadmin4
    profiles: ["debug"]        # Only starts with --profile debug
    ports:
      - "5050:80"

  redis-commander:
    image: rediscommander/redis-commander
    profiles: ["debug"]
    ports:
      - "8081:8081"

  prometheus:
    image: prom/prometheus
    profiles: ["monitoring"]   # Only starts with --profile monitoring

  grafana:
    image: grafana/grafana
    profiles: ["monitoring"]
```

### Usage
```bash
# Default services only (api, postgres)
docker compose up

# Include debug tools
docker compose --profile debug up

# Include monitoring stack
docker compose --profile monitoring up

# Include everything
docker compose --profile debug --profile monitoring up
```

---

## Init and Entrypoint Scripts

### PostgreSQL init scripts

PostgreSQL's official image runs scripts in `/docker-entrypoint-initdb.d/` on first startup (when the data directory is empty).

```yaml
services:
  postgres:
    image: pgvector/pgvector:pg16
    volumes:
      - postgres_data:/var/lib/postgresql/data
      # Mount init scripts (read-only)
      - ./database/migrations/001_initial_schema.sql:/docker-entrypoint-initdb.d/001_schema.sql:ro
      - ./database/migrations/init.sh:/docker-entrypoint-initdb.d/002_init.sh:ro
```

**Execution rules:**
- Files execute in alphabetical order
- Supports `.sql`, `.sql.gz`, and `.sh` files
- `.sh` files are sourced (have access to `POSTGRES_DB`, `POSTGRES_USER`, etc.)
- Only runs when data volume is empty (first startup)
- To re-run: `docker compose down -v` (deletes data volume)

### Custom entrypoint script (wait-for-dependencies)

```bash
#!/bin/bash
# entrypoint.sh — Run migrations, then start the app

set -e

echo "Waiting for PostgreSQL..."
until pg_isready -h postgres -p 5432 -U postgres; do
    sleep 1
done

echo "Running migrations..."
python -m database.migrations.run_migration

echo "Starting application..."
exec "$@"    # Execute CMD (important: use exec for PID 1 signal handling)
```

```yaml
services:
  api:
    build: .
    entrypoint: ["/app/entrypoint.sh"]
    command: ["uvicorn", "api.main:app", "--host", "0.0.0.0", "--port", "8000"]
```

**Important:** Always end entrypoint scripts with `exec "$@"` — this replaces the shell with the CMD process, ensuring it gets PID 1 and receives SIGTERM for graceful shutdown.

### Alternative: depends_on + healthcheck (preferred)

Instead of a custom wait script, use Compose's built-in dependency mechanism:

```yaml
services:
  postgres:
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 5s
      retries: 5

  api:
    depends_on:
      postgres:
        condition: service_healthy    # Wait until postgres passes healthcheck
```

This is cleaner than a wait loop — Compose handles the ordering, and healthchecks are reusable.

---

## Extension Fields (DRY)

Use YAML anchors and Compose extension fields (x-) to avoid repetition.

```yaml
# Extension fields start with x- and are ignored by Compose
x-common-env: &common-env
  OPENAI_MODEL: gpt-4o
  LOG_LEVEL: info

x-healthcheck-defaults: &healthcheck-defaults
  interval: 10s
  timeout: 5s
  retries: 3
  start_period: 15s

x-restart-policy: &restart-policy
  restart: unless-stopped

services:
  api:
    <<: *restart-policy
    environment:
      <<: *common-env
      DATABASE_URL: postgresql://postgres:pass@postgres:5432/crm
      REDIS_URL: redis://redis:6379
    healthcheck:
      <<: *healthcheck-defaults
      test: ["CMD-SHELL", "curl -f http://localhost:8000/health || exit 1"]

  worker:
    <<: *restart-policy
    environment:
      <<: *common-env
      DATABASE_URL: postgresql://postgres:pass@postgres:5432/crm
      REDIS_URL: redis://redis:6379
    healthcheck:
      <<: *healthcheck-defaults
      test: ["CMD-SHELL", "curl -f http://localhost:8001/health || exit 1"]
```

---

## Hot-Reload for Development

### Python with uvicorn --reload
```yaml
services:
  api:
    build:
      context: .
      target: builder             # Use builder stage (has all source)
    command: ["--reload"]          # Appended to ENTRYPOINT
    volumes:
      - ./api:/app/api            # Mount source for live changes
      - ./agent:/app/agent
    environment:
      PYTHONDONTWRITEBYTECODE: 1  # Don't create __pycache__
```

### Node.js with Next.js dev mode
```yaml
services:
  web:
    build:
      context: ./web
      target: deps                # Only install dependencies
    command: ["npm", "run", "dev"]
    volumes:
      - ./web/src:/app/src        # Mount source
      - ./web/public:/app/public
    environment:
      WATCHPACK_POLLING: "true"   # Enable polling (needed on some OS/mount combos)
```

### Performance notes
- **Linux**: Bind mounts are native, no performance penalty
- **macOS/Windows**: Bind mounts use a file-sharing layer. Large node_modules can be slow. Mitigate with `:cached` flag or anonymous volume for node_modules:
  ```yaml
  volumes:
    - ./web/src:/app/src
    - /app/node_modules           # Anonymous volume — keeps container's node_modules
  ```

---

## Database Patterns

### PostgreSQL with pgvector
```yaml
services:
  postgres:
    image: pgvector/pgvector:pg16
    environment:
      POSTGRES_DB: crm
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-postgres}
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ./database/migrations/001_initial_schema.sql:/docker-entrypoint-initdb.d/001_schema.sql:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres -d crm"]
      interval: 5s
      timeout: 3s
      retries: 5
      start_period: 10s

volumes:
  postgres_data:
```

### Redis with AOF persistence
```yaml
services:
  redis:
    image: redis:7-alpine
    command: redis-server --appendonly yes --appendfsync everysec
    ports:
      - "6379:6379"
    volumes:
      - redis_data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 5

volumes:
  redis_data:
```

**AOF options:**
- `--appendfsync always`: Every write (safest, slowest)
- `--appendfsync everysec`: Sync once per second (good balance)
- `--appendfsync no`: OS decides (fastest, may lose up to 30s of data)

### Redis without persistence (pure cache)
```yaml
services:
  redis:
    image: redis:7-alpine
    command: redis-server --maxmemory 256mb --maxmemory-policy allkeys-lru
    # No volume — data is ephemeral
```

---

## Secrets Management

### .env file (development)
```yaml
# docker-compose.yml
services:
  api:
    env_file: .env               # Load all vars from .env
    environment:
      DATABASE_URL: postgresql://postgres:${POSTGRES_PASSWORD}@postgres:5432/crm
```

**.env file** (never committed to git):
```bash
OPENAI_API_KEY=sk-...
POSTGRES_PASSWORD=secure-password
```

### Docker secrets (production Compose)
```yaml
services:
  api:
    secrets:
      - openai_api_key
      - postgres_password
    environment:
      OPENAI_API_KEY_FILE: /run/secrets/openai_api_key

secrets:
  openai_api_key:
    file: ./secrets/openai_api_key.txt
  postgres_password:
    file: ./secrets/postgres_password.txt
```

**Note:** The application must read from the file path (`/run/secrets/...`), not from environment variables. Some frameworks support `*_FILE` env var convention.

### Environment variable precedence
1. `environment:` in compose file (highest)
2. Shell environment variables on the host
3. `env_file:` entries
4. Dockerfile `ENV` instructions (lowest)

---

## Networking

### Default network (usually sufficient)
```yaml
services:
  api:
    # Accessible as "api" from other services
    ports:
      - "8000:8000"    # Also accessible from host

  postgres:
    # Accessible as "postgres" from other services
    # NOT accessible from host (no ports: mapping)
```

All services in a compose file share a default bridge network. They reach each other by service name (DNS resolution).

### Custom networks (service isolation)
```yaml
services:
  api:
    networks:
      - frontend
      - backend

  web:
    networks:
      - frontend           # Can reach api, not postgres

  postgres:
    networks:
      - backend            # Can reach api, not web

networks:
  frontend:
  backend:
```

### External networks (cross-project)
```yaml
# Project A defines the network
networks:
  shared:
    name: shared-network

# Project B joins it
networks:
  shared:
    external: true
    name: shared-network
```

---

## Resource Limits

```yaml
services:
  api:
    deploy:
      resources:
        limits:
          cpus: "0.5"       # Max 50% of one CPU core
          memory: 512M       # Max 512MB RAM (OOM-killed if exceeded)
        reservations:
          cpus: "0.1"       # Guaranteed 10% CPU
          memory: 256M       # Guaranteed 256MB RAM
```

**Note:** `deploy.resources` works in both Compose V2 and K8s. In Compose, you may need `--compatibility` flag for older versions.

### Recommended limits by service type

| Service | CPU Limit | Memory Limit | Notes |
|---------|-----------|-------------|-------|
| Python API (FastAPI) | 0.5 | 512M | uvicorn + async workers |
| Node.js (Next.js) | 0.2 | 256M | SSR can spike memory |
| PostgreSQL | 0.5 | 512M | Shared buffers + connections |
| Redis | 0.2 | 256M | In-memory, predictable |

---

## Full-Stack Template

A complete template combining all patterns above.

```yaml
# docker-compose.yml — Production-like base
x-healthcheck: &healthcheck
  interval: 10s
  timeout: 5s
  retries: 3
  start_period: 15s

services:
  postgres:
    image: pgvector/pgvector:pg16
    restart: unless-stopped
    environment:
      POSTGRES_DB: ${POSTGRES_DB:-app}
      POSTGRES_USER: ${POSTGRES_USER:-postgres}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?Set POSTGRES_PASSWORD in .env}
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ./database/init:/docker-entrypoint-initdb.d:ro
    healthcheck:
      <<: *healthcheck
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER:-postgres}"]
    deploy:
      resources:
        limits: { cpus: "0.5", memory: 512M }

  redis:
    image: redis:7-alpine
    restart: unless-stopped
    command: redis-server --appendonly yes --appendfsync everysec
    volumes:
      - redis_data:/data
    healthcheck:
      <<: *healthcheck
      test: ["CMD", "redis-cli", "ping"]
    deploy:
      resources:
        limits: { cpus: "0.2", memory: 256M }

  api:
    build:
      context: .
      target: runtime
    restart: unless-stopped
    ports:
      - "8000:8000"
    env_file: .env
    environment:
      DATABASE_URL: postgresql://${POSTGRES_USER:-postgres}:${POSTGRES_PASSWORD}@postgres:5432/${POSTGRES_DB:-app}
      REDIS_URL: redis://redis:6379
    depends_on:
      postgres: { condition: service_healthy }
      redis: { condition: service_healthy }
    healthcheck:
      <<: *healthcheck
      test: ["CMD-SHELL", "curl -f http://localhost:8000/health || exit 1"]
    deploy:
      resources:
        limits: { cpus: "0.5", memory: 512M }

  web:
    build:
      context: ./web
      target: runner
      args:
        NEXT_PUBLIC_API_URL: ${NEXT_PUBLIC_API_URL:-http://localhost:8000}
    restart: unless-stopped
    ports:
      - "3000:3000"
    depends_on:
      api: { condition: service_healthy }
    healthcheck:
      <<: *healthcheck
      test: ["CMD-SHELL", "curl -f http://localhost:3000 || exit 1"]
    deploy:
      resources:
        limits: { cpus: "0.2", memory: 256M }

volumes:
  postgres_data:
  redis_data:
```

```yaml
# docker-compose.override.yml — Development (auto-loaded)
services:
  api:
    build:
      target: builder
    command: ["--reload"]
    volumes:
      - ./api:/app/api
      - ./agent:/app/agent
    restart: "no"

  web:
    build:
      target: deps
    command: ["npm", "run", "dev"]
    volumes:
      - ./web/src:/app/src
    restart: "no"
```
