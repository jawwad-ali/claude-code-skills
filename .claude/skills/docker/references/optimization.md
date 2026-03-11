# Docker Build Optimization

Detailed strategies for faster builds, smaller images, and better caching. Read this when builds are slow, images are too large, or you need BuildKit features.

## Table of Contents

1. [Build Context](#build-context)
2. [Layer Caching Strategy](#layer-caching-strategy)
3. [BuildKit Features](#buildkit-features)
4. [Image Size Reduction](#image-size-reduction)
5. [Build Performance](#build-performance)
6. [Debugging Builds](#debugging-builds)
7. [Security Scanning](#security-scanning)
8. [Platform-Specific Tips](#platform-specific-tips)

---

## Build Context

### How it works

When you run `docker build .`, Docker sends the entire directory (the "build context") to the daemon. Only then does it start processing the Dockerfile.

```bash
# See how large your build context is
docker build . 2>&1 | head -1
# => Sending build context to Docker daemon  847.3MB
```

If this number is large, your builds start slow before any instruction runs.

### Reducing context size

1. **Use .dockerignore** (most important): Exclude `.git/`, `node_modules/`, `.venv/`, `*.md`, `tests/`
2. **Set build context to a subdirectory** when possible:
   ```yaml
   services:
     web:
       build:
         context: ./web    # Only sends web/ directory, not entire repo
   ```
3. **Measure the difference**:
   ```bash
   # Before .dockerignore
   du -sh --exclude=.git .
   # After .dockerignore (simulated)
   tar --exclude-from=.dockerignore -cf - . | wc -c
   ```

### Common context bloat

| Directory | Typical Size | Should Include? |
|-----------|-------------|-----------------|
| `.git/` | 50-500MB | Never |
| `node_modules/` | 200-800MB | Never (npm ci in Dockerfile) |
| `.venv/` | 100-500MB | Never (installed in Dockerfile) |
| `.next/` | 50-200MB | Never (built in Dockerfile) |
| `specs/`, `docs/` | 1-10MB | Never |
| `tests/` | 1-20MB | Usually not (unless needed for build) |

---

## Layer Caching Strategy

### How Docker caches layers

Each Dockerfile instruction creates a layer. Docker caches layers and reuses them if:
1. The instruction hasn't changed
2. All parent layers are still cached
3. For `COPY`/`ADD`: the file contents haven't changed (checksum comparison)

**Critical rule**: When any layer's cache is invalidated, ALL subsequent layers are also invalidated.

### Optimal instruction ordering

```dockerfile
# Layer 1: Base image (almost never changes)
FROM python:3.12-slim

# Layer 2: System dependencies (changes rarely)
RUN apt-get update && apt-get install -y --no-install-recommends curl

# Layer 3: Application dependencies (changes when lockfile changes)
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev

# Layer 4: Application code (changes on every commit)
COPY . .
```

**Why this order?** Each layer below is MORE likely to change than the one above. When you change application code (Layer 4), Layers 1-3 are served from cache. If you put `COPY . .` before `uv sync`, every code change would re-install all dependencies.

### Dependency-first patterns by language

**Python (uv)**:
```dockerfile
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev
COPY . .
```

**Python (pip)**:
```dockerfile
COPY requirements.txt ./
RUN pip install -r requirements.txt
COPY . .
```

**Node.js (npm)**:
```dockerfile
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
```

**Node.js (pnpm)**:
```dockerfile
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
```

**Go**:
```dockerfile
COPY go.mod go.sum ./
RUN go mod download
COPY . .
```

### Splitting COPY for granular caching

```dockerfile
# Instead of one big COPY:
COPY . .    # Invalidated by ANY file change

# Split into sections that change at different rates:
COPY pyproject.toml uv.lock ./          # Changes rarely
RUN uv sync --frozen
COPY database/migrations/ ./database/   # Changes occasionally
COPY api/ ./api/                        # Changes frequently
COPY agent/ ./agent/                    # Changes frequently
```

---

## BuildKit Features

BuildKit is Docker's modern build engine. It's the default in Docker Desktop and `docker buildx`.

### Cache mounts (package manager caches)

Persist package manager caches across builds without including them in the final image.

```dockerfile
# Python (uv)
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --frozen --no-dev

# Python (pip)
RUN --mount=type=cache,target=/root/.cache/pip \
    pip install -r requirements.txt

# Node.js (npm)
RUN --mount=type=cache,target=/root/.npm \
    npm ci

# Node.js (pnpm)
RUN --mount=type=cache,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile

# Go
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    go build -o /app/server
```

### Secret mounts (build-time secrets)

Access secrets during build without baking them into image layers.

```dockerfile
# Mount a secret file (only accessible during this RUN)
RUN --mount=type=secret,id=npmrc,target=/app/.npmrc \
    npm ci

# Build command:
docker build --secret id=npmrc,src=$HOME/.npmrc .
```

The secret is NEVER stored in any image layer. `docker history` won't show it.

### SSH mounts (private repositories)

```dockerfile
RUN --mount=type=ssh \
    pip install git+ssh://git@github.com/private/repo.git

# Build command:
docker build --ssh default .
```

### Parallel stage execution

BuildKit automatically parallelizes independent stages:

```dockerfile
FROM python:3.12-slim AS python-deps
RUN pip install -r requirements.txt

FROM node:22-slim AS node-deps    # Runs IN PARALLEL with python-deps
RUN npm ci

FROM python:3.12-slim AS runtime
COPY --from=python-deps ...
COPY --from=node-deps ...
```

---

## Image Size Reduction

### Base image comparison

| Image | Size | Use Case |
|-------|------|----------|
| `python:3.12` | ~1GB | Development only |
| `python:3.12-slim` | ~150MB | Production Python |
| `python:3.12-alpine` | ~50MB | Only if no C extensions |
| `node:22` | ~1.1GB | Development only |
| `node:22-slim` | ~200MB | Production Node.js |
| `node:22-alpine` | ~130MB | Minimal Node.js |
| `golang:1.23` | ~800MB | Build stage only |
| `gcr.io/distroless/static` | ~2MB | Go static binaries |
| `scratch` | 0 bytes | Go static (no CA certs) |
| `nginx:alpine` | ~40MB | Static file serving |

### Clean up in the same layer

```dockerfile
# BAD — cleanup creates a new layer, but deleted files still exist in the previous layer
RUN apt-get update && apt-get install -y curl
RUN rm -rf /var/lib/apt/lists/*    # Saves nothing!

# GOOD — cleanup in same RUN, so deleted files never exist in any layer
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*
```

### Minimize installed packages

```dockerfile
# BAD — installs recommended packages (documentation, man pages, etc.)
RUN apt-get install -y python3-dev

# GOOD — only the package itself
RUN apt-get install -y --no-install-recommends python3-dev
```

### Multi-stage to drop build dependencies

The biggest size win. Build tools (gcc, make, build-essential, node dev deps) stay in the builder stage:

```
Builder stage: python:3.12-slim + build-essential + gcc = ~500MB
Runtime stage: python:3.12-slim + compiled wheels only = ~180MB
Savings: ~320MB (64%)
```

### Inspect image layers

```bash
# See each layer's size
docker history myimage:latest

# Detailed manifest
docker image inspect myimage:latest | jq '.[0].RootFS.Layers | length'

# Third-party tools for deeper analysis
# dive (interactive layer explorer):  https://github.com/wagoodman/dive
docker run --rm -it -v /var/run/docker.sock:/var/run/docker.sock wagoodman/dive myimage:latest
```

---

## Build Performance

### Measure build time

```bash
# BuildKit shows timing per step
DOCKER_BUILDKIT=1 docker build --progress=plain .

# Time the full build
time docker build -t myimage .
```

### Speed up strategies (ordered by impact)

1. **.dockerignore** — Reduces context transfer time (seconds to minutes saved)
2. **Layer ordering** — Dependency-first pattern (avoid re-installing on code changes)
3. **BuildKit cache mounts** — Persist package manager caches across builds
4. **Multi-stage parallel stages** — BuildKit builds independent stages concurrently
5. **Use slim/alpine base** — Less to download on cold builds

### Remote cache

Share build cache between machines (CI/CD):

```bash
# Push cache to registry
docker build --cache-to type=registry,ref=myregistry/myimage:cache .

# Pull cache on another machine
docker build --cache-from type=registry,ref=myregistry/myimage:cache .
```

### Multi-platform builds

Build for both amd64 and arm64 (e.g., for M-series Macs + Linux servers):

```bash
docker buildx build --platform linux/amd64,linux/arm64 -t myimage .
```

---

## Debugging Builds

### See full build output

```bash
# Plain progress shows all RUN output (not collapsed)
docker build --progress=plain .

# No cache (force rebuild everything)
docker build --no-cache .

# Build up to a specific stage
docker build --target builder .
```

### Inspect a specific layer

```bash
# Run a shell in the builder stage
docker build --target builder -t debug-builder .
docker run -it --rm debug-builder /bin/bash

# Check what's in the image
docker run --rm myimage ls -la /app/
docker run --rm myimage pip list
docker run --rm myimage node -e "console.log(process.versions)"
```

### Common exit codes

| Code | Meaning | Common Cause |
|------|---------|-------------|
| 0 | Success | |
| 1 | General error | Application error, failed health check |
| 2 | Misuse of shell command | Bad CMD/ENTRYPOINT syntax |
| 126 | Permission denied | File not executable |
| 127 | Command not found | Wrong PATH, missing binary |
| 137 | OOM killed (SIGKILL) | Container exceeded memory limit |
| 139 | Segfault (SIGSEGV) | C extension crash, wrong base image |
| 143 | SIGTERM | Graceful shutdown (expected) |

### docker compose troubleshooting

```bash
# See why a service failed
docker compose logs api

# Follow logs in real-time
docker compose logs -f api

# Check service status
docker compose ps

# Rebuild a single service
docker compose up --build api

# Shell into a running container
docker compose exec api /bin/bash

# Shell into a stopped service
docker compose run --rm api /bin/bash
```

---

## Security Scanning

### Docker Scout (built-in)

```bash
# Quick vulnerability overview
docker scout quickview myimage:latest

# Detailed CVE list
docker scout cves myimage:latest

# Compare two versions
docker scout compare myimage:v1 myimage:v2
```

### Trivy (third-party, comprehensive)

```bash
# Scan an image
trivy image myimage:latest

# Scan a Dockerfile for misconfigurations
trivy config Dockerfile

# Scan in CI (fail on HIGH/CRITICAL)
trivy image --exit-code 1 --severity HIGH,CRITICAL myimage:latest
```

### Security best practices checklist

- [ ] Non-root user (`USER app`)
- [ ] Pinned base image version (`python:3.12-slim`, not `latest`)
- [ ] No secrets in layers (no `COPY .env`, no `ARG SECRET=...`)
- [ ] Minimal base image (slim/alpine/distroless)
- [ ] `--no-install-recommends` for apt packages
- [ ] Read-only filesystem where possible (`--read-only` flag)
- [ ] No `--privileged` or extra capabilities

---

## Platform-Specific Tips

### Docker Desktop on Windows (WSL2)

- Docker runs inside WSL2. Files in WSL2 filesystem are fast; files in `/mnt/c/` (Windows filesystem) are slow.
- For best performance, clone your repo inside WSL2 (`~/projects/`) not on the Windows drive.
- If you must use Windows paths, enable `gRPC FUSE` in Docker Desktop settings.

### Docker Desktop on macOS

- Bind mounts are slower than Linux (virtualization layer). Mitigate:
  - Use `:cached` consistency flag: `./src:/app/src:cached`
  - Use anonymous volumes for `node_modules`: `- /app/node_modules`
  - Consider VirtioFS (Docker Desktop > Settings > General > "Use VirtioFS")

### Linux (native)

- Bind mounts are native — no performance penalty
- Add your user to the `docker` group to avoid `sudo`: `sudo usermod -aG docker $USER`
- Use BuildKit by default: `export DOCKER_BUILDKIT=1` in `.bashrc`
