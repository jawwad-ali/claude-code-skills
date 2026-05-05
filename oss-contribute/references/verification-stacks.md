# Verification Stacks Cookbook

The verification stack is the ordered set of commands you run **locally** before pushing. CI runs the same commands; running them locally first catches issues in seconds vs CI's 5-minute round-trip.

**Always prefer the project's own commands** (`Makefile`, `package.json` scripts, contributor-guide one-liners). Use this cookbook only when the project is silent on a tool.

## Detection table

| Signal in repo | Stack |
|----------------|-------|
| `Makefile` with `format`, `lint`, `typecheck`, `tests` targets | `make format && make lint && make typecheck && make tests` |
| `pyproject.toml` + `uv.lock` | uv-managed Python (below) |
| `pyproject.toml` + `poetry.lock` | poetry-managed Python (below) |
| `pyproject.toml`, no lockfile | bare Python (below) |
| `package.json` + `pnpm-lock.yaml` | pnpm Node (below) |
| `package.json` + `yarn.lock` | yarn Node (below) |
| `package.json` + `bun.lockb` | bun Node (below) |
| `package.json`, no lockfile | npm Node (below) |
| `go.mod` | Go (below) |
| `Cargo.toml` | Rust (below) |
| `pom.xml` | Maven Java (below) |
| `build.gradle` / `build.gradle.kts` | Gradle Java/Kotlin (below) |
| `Gemfile` | Ruby (below) |
| `mix.exs` | Elixir (below) |

## Python (uv)

Most modern Python OSS uses uv. Setup:

```bash
uv sync --all-extras --all-packages --group dev
```

Verification (run in order):

```bash
uv run ruff format             # write fixes; ruff format --check on CI
uv run ruff check              # lint, with --fix to auto-fix
uv run mypy <package_path>     # or `uv run mypy . --exclude site` if repo configures it
uv run pyright                 # if repo has pyrightconfig.json
uv run pytest                  # full suite
uv run pytest -k "<focused>"   # focused subset for fast iteration
```

Common targets per repo (read the Makefile if present):

- `make format` → ruff format
- `make lint` → ruff check
- `make typecheck` → mypy + pyright
- `make tests` → pytest (often parallelized via `pytest -n auto`)
- `make snapshots-fix` → updates inline snapshots
- `make coverage` → coverage with a fail-under threshold

## Python (poetry)

```bash
poetry install --with dev
poetry run black .              # or poetry run ruff format
poetry run ruff check
poetry run mypy <package>
poetry run pytest
```

## Python (bare venv)

```bash
python -m venv .venv && source .venv/bin/activate    # Windows: .venv\Scripts\activate
pip install -e ".[dev]"
ruff format && ruff check && mypy . && pytest
```

## Node.js (pnpm)

```bash
pnpm install
pnpm format        # or `pnpm prettier --write .`
pnpm lint          # `eslint --fix` is often the script body
pnpm typecheck     # `tsc --noEmit`
pnpm test          # vitest / jest / mocha
pnpm build         # if the repo gates on a successful build
```

## Node.js (yarn / bun / npm)

Same scripts, different runner:

```bash
yarn install / bun install / npm install
yarn lint / bun run lint / npm run lint
yarn test  / bun test     / npm test
```

## Go

```bash
gofmt -l .            # lists unformatted files; -w writes fixes
go vet ./...          # static analysis
go test ./...         # full test suite
go test -race ./...   # race-detector run; common in CI
```

If the repo uses `golangci-lint` (visible via `.golangci.yml`):

```bash
golangci-lint run
```

## Rust

```bash
cargo fmt --check
cargo clippy --workspace -- -D warnings
cargo test --workspace
cargo nextest run --workspace      # if the repo uses nextest
```

If the repo has `rust-toolchain.toml`, install that toolchain via `rustup install <version>` before running.

## Java (Maven)

```bash
./mvnw verify              # runs compile + test + integration-test
./mvnw spotless:check      # if Spotless is configured
./mvnw checkstyle:check    # if Checkstyle is configured
```

## Java/Kotlin (Gradle)

```bash
./gradlew check            # default lifecycle: compile + test + verification
./gradlew spotlessCheck    # if Spotless plugin is applied
./gradlew detekt           # if Kotlin and Detekt is configured
```

## Ruby

```bash
bundle install
bundle exec rubocop
bundle exec rspec          # or bundle exec rake test for Minitest
```

## Elixir

```bash
mix deps.get
mix format --check-formatted
mix credo --strict
mix dialyzer
mix test
```

## C/C++ (CMake)

```bash
cmake -S . -B build
cmake --build build
ctest --test-dir build
```

If the repo uses pre-commit, also: `pre-commit run --all-files` to mirror CI.

## Mixed / monorepo

Monorepos need per-package scoping. Look for:

- `pnpm-workspace.yaml` → run `pnpm -F <package>` for the affected package only.
- `lerna.json` → `lerna run test --scope <package>`.
- `nx.json` → `nx affected:test`.
- Cargo workspace (`[workspace]` in root `Cargo.toml`) → `cargo test -p <crate>`.

Don't run the full monorepo suite if you only changed one package — wastes time and may surface unrelated flakes.

## Sandbox & auth quirks

### Python uv on Windows

`UV_PROJECT_ENVIRONMENT=.venv_<py>` lets you run on a specific Python version without conflicting with the default `.venv`.

If `uv sync` rewrites `uv.lock`, run `git checkout uv.lock` after to discard CRLF/EOL noise.

### Node on Windows

Use Git Bash or WSL for the verification stack. PowerShell handles `pnpm` fine, but pre-commit hooks often assume bash.

### Go

`GOFLAGS="-mod=readonly"` enforces lockfile honesty.

### Rust

If `cargo` complains about a missing component, install via `rustup component add <name>` (e.g. `clippy`, `rustfmt`).

## When checks fail

The diagnostic flow is identical regardless of language:

1. Run the failing check in **isolation** with the most verbose output (`-v`, `--verbose`, etc.).
2. Read the failure mode (line number, stack trace, lint rule code).
3. **Stash + checkout-main test** — run the same failing test on clean `main`. If it fails on main, it's not yours; note in PR body.
4. Otherwise: fix, re-run *only* the previously failing check first to confirm, then re-run the full stack.
5. If the failure is in a tool you don't recognize, search the repo for the exact error message — there's likely a known workaround.

## Time budget

A green local stack on a small change should take under 2 minutes for most languages. If your local verification regularly takes > 10 minutes, you're either running the wrong scope (whole monorepo instead of one package) or running tests serially that the project parallelizes in CI.
