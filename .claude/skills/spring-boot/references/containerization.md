# Containerization: Layered Jars, Container-Aware JVM, Hardening

Spring Boot 4.1.0 on Docker / Kubernetes / Azure Container Apps. Read this before writing or reviewing
a Dockerfile, a `.dockerignore`, or container runtime configuration.

Two Boot 4.1 changes break essentially every containerization tutorial you will find:
`-Djarmode=layertools` is **removed**, and `-DskipTests` no longer skips AOT processing.

---

## 1. `-Djarmode=layertools` is gone

Verbatim from the 4.1 release notes: "The deprecated layertools jar mode has been removed in this
release." It was deprecated in 3.3 and removed in 4.1. Almost every pre-2025 blog post and Stack
Overflow answer still shows it, so this is the single most likely thing to be wrong in copied code.

```dockerfile
# ❌ Boot 3.x-era pattern. Fails on 4.1: the layertools jarmode no longer exists.
RUN java -Djarmode=layertools -jar application.jar extract
COPY --from=builder dependencies/ ./
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```

```dockerfile
# ✅ The tools jarmode (Boot 3.3+, mandatory from 4.1)
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted
# one COPY per layer, least-volatile first
COPY --from=extract /extract/extracted/dependencies/          ./
COPY --from=extract /extract/extracted/spring-boot-loader/    ./
COPY --from=extract /extract/extracted/snapshot-dependencies/ ./
COPY --from=extract /extract/extracted/application/           ./
ENTRYPOINT ["java", "-jar", "application.jar"]
```

`--layers` is **not** the default for the tools jarmode. Omit it and you get the flat `lib/` +
app-jar layout — good for an AOT cache, useless for Docker layer caching. Discover the options with
`java -Djarmode=tools -jar app.jar help extract`; list layers without extracting via `list-layers`.

The default layers written into `layers.idx`, ordered least-volatile first: `dependencies`,
`spring-boot-loader`, `snapshot-dependencies`, `application`. Layering is enabled by default in the
Maven plugin. The whole point is that a code change re-pushes only the small `application` layer
instead of ~60MB of unchanged dependency jars.

---

## 2. Build-stage ordering and cache mounts

Docker invalidates every layer after the first changed one. `COPY . .` before resolving means editing
one `.java` file re-downloads the entire dependency graph.

```dockerfile
# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build

# 1. Only the files that determine the dependency graph.
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2/repository,sharing=locked \
    mvn -B -ntp dependency:go-offline

# 2. Volatile input last.
COPY src ./src
RUN --mount=type=cache,target=/root/.m2/repository,sharing=locked \
    mvn -B -ntp -Dmaven.test.skip=true package \
 && mv target/*.jar /build/application.jar
```

`-Dmaven.test.skip=true`, **not** `-DskipTests`. In Boot 4.1 the Maven plugin's AOT processing no
longer responds to `-DskipTests`, so your image build suddenly runs AOT processing, gets slower, and
may fail on build-time infrastructure it cannot reach.

The cache mount is the second half of the story: when the POM *does* change, Maven re-downloads only
the delta instead of all ~300 artifacts, because the cache is cumulative across builds and survives
layer invalidation. It is not baked into the image, so it costs zero final bytes.

Do not follow `dependency:go-offline` with `mvn -o package`. `go-offline` is best-effort and
chronically incomplete — it misses plugin dependencies resolved lazily, some test- and provided-scope
transitives, and multi-module reactor artifacts. With a cache mount the incompleteness is harmless
because the `package` step fetches the remainder into the persistent cache; with `-o` the build fails
on whatever `go-offline` missed. (Older guides build a separate "deps" stage for this reason; cache
mounts make that stage redundant.)

### Cache mounts do not survive an ephemeral CI runner

A cache mount lives in the BuildKit builder's local state. On a fresh GitHub Actions or Azure DevOps
runner that state is empty, so the speedup you measured locally silently disappears in CI — the classic
"it's fast on my laptop" report. Add registry-backed layer cache:

```bash
docker buildx build \
  --cache-from type=registry,ref=ghcr.io/acme/ledger:buildcache \
  --cache-to   type=registry,ref=ghcr.io/acme/ledger:buildcache,mode=max \
  -t ghcr.io/acme/ledger:1.0.0 --push .
```

`mode=max` exports intermediate stages too.

---

## 3. The exec-form ENTRYPOINT is not cosmetic

Docker's own words: the shell form "starts your ENTRYPOINT as a subcommand of `/bin/sh -c`, which does
not pass signals", so "your executable doesn't receive a SIGTERM from `docker stop`".

```dockerfile
# ❌ /bin/sh -c wrapper: the JVM never receives SIGTERM.
ENTRYPOINT java -jar application.jar
```

```dockerfile
# ✅ JSON array: the JVM is PID 1 and gets SIGTERM directly.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "application.jar"]
```

```sh
#!/bin/sh
# ✅ If a wrapper is unavoidable, `exec` replaces the shell so the JVM inherits PID 1.
set -eu
exec java ${JAVA_OPTS:-} -jar /application/application.jar "$@"
# ❌ Without `exec`, sh stays PID 1 and swallows the signal.
```

Consequences of getting this wrong: graceful shutdown, in-flight request draining and your outbox
relay's final batch all silently do nothing, and the container just hangs until SIGKILL. **Nothing in
the logs indicates the problem**, because the shutdown hook never fires. For a money service that means
in-flight requests die mid-transaction — Postgres rolls them back when the connection drops, so no
corruption, but the client sees a connection reset and retries with the same `Idempotency-Key`, pushing
correctness load onto your idempotency table that a clean drain would have avoided.

You do **not** need `tini`/`dumb-init` for a plain `java -jar`: the JVM installs its own SIGTERM handler
and works fine as PID 1. You *do* need `--init` if your entrypoint forks child processes.

Add `STOPSIGNAL SIGTERM` for explicitness.

---

## 4. Container-aware JVM flags

`-XX:+UseContainerSupport` defaults to **true** on Linux (Java 10+) — verbatim from the Java 25 man
page: "The default for this flag is true, and container support is enabled by default." So the JVM does
read cgroup memory and CPU limits. The problem is what it does with them.

```dockerfile
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:InitialRAMPercentage=75.0", \
  "-XX:MaxMetaspaceSize=256m", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-XX:+UseG1GC", \
  "-XX:ActiveProcessorCount=1", \
  "-jar", "application.jar"]
```

- **`MaxRAMPercentage`.** The default is commonly cited as 25 (matching the legacy `MaxRAMFraction=4`),
  but the Java 25 man page does not list the RAMPercentage flags at all — **verify on your JDK** with
  `java -XX:+PrintFlagsFinal -version | grep RAMPercentage`. Either way, at 25% a 2Gi container runs a
  ~512MB heap and you pay for 1.5Gi of nothing. 75% is the workable ceiling for containers >= 512MB;
  drop to 50-60% below 256MB.
- **Never hardcode `-Xmx` in the image.** It decouples the JVM from the limit, so resizing the container
  does nothing and a smaller container OOM-kills instantly. Percentage flags track the limit.
- **Do not go to 90-95%.** RSS is heap + metaspace + code cache + (~1MB × threads) + direct buffers + GC
  structures. Exceed the cgroup limit and the *kernel* OOM-kills the container: exit code **137**, no
  Java stack trace, nothing useful in the logs.
- **`InitialRAMPercentage` = `MaxRAMPercentage`** pins Xms=Xmx: no heap-growth pauses, deterministic
  memory behaviour, which is what you want when reasoning about a fixed-size replica.
- **`MaxMetaspaceSize`** caps metaspace so a leak surfaces as an OOME the JVM reports rather than an
  opaque exit 137.
- **`ActiveProcessorCount`.** The man page says it "overrides the number of CPUs that the VM will use to
  calculate the size of thread pools it will use for various operations such as Garbage Collection and
  ForkJoinPool" and "is honored even if UseContainerSupport is not enabled". With a fractional limit
  (ACA 0.5 vCPU, K8s `cpu: 500m`) the derived count is a rounding artefact that silently drives GC
  thread count, `ForkJoinPool.commonPool` parallelism, Tomcat acceptor sizing, and anything calling
  `Runtime.availableProcessors()`. The visible symptom is jittery p99 latency uncorrelated with load.
  Pin it to the whole number of CPUs you provisioned.
- **`UseG1GC` explicitly.** GC ergonomics select SerialGC on a "small" machine; the exact thresholds
  were not verified against JDK 25, so stating them would be guessing. Setting the collector explicitly
  sidesteps the question.
- **`UseCompactObjectHeaders`** (JDK 25, JEP 519, production-ready but **off by default**) cuts heap
  usage meaningfully. Add `-XX:+UseCompactObjectHeaders` on Java 25; drop it on 17/21 where it does not
  exist or is experimental.

Diagnose what the JVM actually detected, from inside the running container:

```bash
java -Xlog:os+container=trace -version
java -XX:+PrintFlagsFinal -version | grep -Ei 'MaxHeapSize|ActiveProcessor|RAMPercentage'
jshell -q -s <<< 'System.out.println(Runtime.getRuntime().availableProcessors());'
```

---

## 5. Hardening, and the read-only rootfs trap that breaks Spring Boot

```bash
docker run -d --name ledger \
  --read-only \
  --tmpfs /tmp:rw,noexec,nosuid,size=64m \
  --cap-drop ALL \
  --security-opt no-new-privileges \
  --user 10001:10001 \
  --memory 1g --memory-swap 1g --cpus 1.0 --pids-limit 256 \
  -p 8080:8080 ledger:1.0.0
```

```yaml
# Kubernetes
securityContext:
  runAsNonRoot: true
  runAsUser: 10001
  runAsGroup: 10001
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true
  capabilities:
    drop: ["ALL"]
  seccompProfile:
    type: RuntimeDefault
volumeMounts:
  - { name: tmp, mountPath: /tmp }
volumes:
  - name: tmp
    emptyDir: { medium: Memory, sizeLimit: 64Mi }
```

- **A numeric `USER`** (`10001:10001`) is what `runAsNonRoot` can actually verify. With a *named* user
  the kubelet cannot prove the uid is not 0 and admission may reject the pod.
- **`--cap-drop ALL` is free** here because you bind 8080. Only a port below 1024 needs
  `CAP_NET_BIND_SERVICE` — which is a reason not to use one.
- **`--read-only` will not boot Spring Boot** without a writable `/tmp`. The embedded Tomcat factory
  creates a work directory under `/tmp` at startup and fails with `WebServerException: Unable to create
  tempDir`. You need **both** a tmpfs at `/tmp` **and**:

```yaml
server:
  tomcat:
    basedir: /tmp/tomcat
```

This only shows up when the platform enforces `readOnlyRootFilesystem` — i.e. never on your laptop and
always in the hardened environment. The tmpfs also gives you a defined, size-capped place for heap
dumps.

Which of these ACA honours was **not** verified; it is a managed platform and does not expose the full
Kubernetes `securityContext` surface. The block above is correct for Kubernetes/AKS.

---

## 6. Health checks in a shell-less image

First ask whether you need `HEALTHCHECK` at all. Kubernetes uses only the pod spec's probes and does not
consult the OCI healthcheck config (stated from the K8s probe model, not fetched). Azure Container Apps
documents HTTP(S) and TCP probes only — "exec probes aren't supported". So on your real target the
useful configuration is the **platform probe pointed at the Actuator paths** (see
`production-ops.md` §1). Keep `HEALTHCHECK` for `docker compose ... depends_on: condition:
service_healthy` in local dev and integration tests.

Distroless has no shell, `curl` or `wget` by design, and even Debian-based JRE images are not guaranteed
to keep `curl` — check before depending on it:

```bash
docker run --rm --entrypoint sh eclipse-temurin:25-jre-noble -c 'command -v curl wget'
```

`java HealthCheck.java` (single-file source launch) does **not** work on a JRE-only image — there is no
compiler. Compile the class in the JDK build stage and copy it in.

```java
// docker/HealthCheck.java — no dependencies, no shell, no curl.
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class HealthCheck {

    private HealthCheck() {}

    public static void main(String[] args) {
        String url = args.length > 0
                ? args[0]
                : "http://127.0.0.1:8080/actuator/health/readiness";
        try (HttpClient client = HttpClient.newBuilder()          // AutoCloseable since Java 21
                .connectTimeout(Duration.ofSeconds(2)).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            HttpResponse<Void> response =
                    client.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            System.exit(status >= 200 && status < 400 ? 0 : 1);   // Docker: 0 healthy, 1 not
        }
        catch (Exception ex) {
            System.err.println("health check failed: " + ex);
            System.exit(1);
        }
    }
}
```

```dockerfile
# build stage
RUN javac -d /healthcheck docker/HealthCheck.java
# runtime stage — exec (JSON array) form is MANDATORY on a shell-less image
COPY --from=build /healthcheck/ /opt/healthcheck/
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --start-interval=2s --retries=3 \
  CMD ["java", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Xmx24m", \
       "-cp", "/opt/healthcheck", "HealthCheck", \
       "http://127.0.0.1:8080/actuator/health/readiness"]
```

`--start-interval` needs Docker 25.0+. On Alpine, BusyBox `wget` is already present and far cheaper:
`HEALTHCHECK CMD ["wget", "-q", "--spider", "http://127.0.0.1:8080/actuator/health/readiness"]`.

**State the cost:** every `HEALTHCHECK` invocation forks a whole JVM whose RSS (~40-80MB, a few hundred
ms) is charged against the container's memory limit. On a tightly sized replica the health probe itself
can trigger the OOM killer — hence the `-Xmx24m -XX:+UseSerialGC -XX:TieredStopAtLevel=1` above.

Other wrong forms: `HEALTHCHECK CMD curl -f … || exit 1` (curl absent), the shell form on a shell-less
image (no `/bin/sh` to run it), the bash-only `/dev/tcp` trick (`/bin/sh` is dash or absent), and
`RUN apt-get install -y curl` in the runtime stage, which reinstalls a package manager and defeats the
purpose.

---

## 7. Base image choice

Compressed sizes fetched from Docker Hub on 2026-07-25 (`full_size`, the sum of compressed layers;
on-disk is roughly 2-2.5x):

| Image | Compressed |
|---|---|
| `eclipse-temurin:25-jre-alpine` | 75.4 MB |
| `eclipse-temurin:25-jre-noble` | 104.2 MB |
| `eclipse-temurin:25-jre` | 116.8 MB |
| `eclipse-temurin:25-jre-ubi10-minimal` | 135.6 MB |
| `eclipse-temurin:25-jdk-alpine` | 109.7 MB |
| `eclipse-temurin:25-jdk` | 150.4 MB |
| `bellsoft/liberica-openjre-debian:25` | 111.5 MB |
| `bellsoft/liberica-openjre-debian:25-cds` | 127.1 MB |

Add ~40-60MB of extracted dependency jars for a Boot web+JDBC+actuator app, so a working image lands
roughly between 150MB (Alpine) and 290MB (Noble, on disk).

**Default to `eclipse-temurin:25-jre-noble`.** The ~100MB spread is worth far less than people think —
registry layers are shared and cached per node, so you pay it once per node, not per deploy. The real
selection criteria are glibc vs musl behaviour, whether you can exec into the container during an
incident, and CVE surface. For money-handling code, glibc plus a maintained LTS base beats 30MB.

- `gcr.io/distroless/java25-debian13:nonroot` for a genuine security mandate — no shell, no package
  manager. Break-glass with the `debug-nonroot` tag, which adds busybox. (Note: **debian13** now, not
  debian12. Its size was not measured; GCR does not expose a Hub-style API.)
- `eclipse-temurin:25-jre-alpine` is the smallest maintained mainstream option — validate musl first.
- `jlink` only after measuring that pull time actually hurts. A custom runtime gets you to roughly
  110-150MB, but those figures come from a third-party blog, not a build run here. If you jlink, do
  **not** trust `jdeps --print-module-deps` alone: TLS handshakes fail at *runtime*, not build time,
  because `jdk.crypto.ec` is never statically referenced. Smoke-test against a real TLS Postgres
  connection.

```dockerfile
# ❌ Never
FROM openjdk:17    # deprecated, unmaintained Docker Hub image
FROM eclipse-temurin:25   # full JDK in production: javac + jlink + jshell shipped to prod
```

Note the official Spring docs use `bellsoft/liberica-openjre-debian:25-cds`, which is **larger** than
plain `:25` (127.1 vs 111.5 MB) because it ships a pre-generated base CDS archive. Copying the docs
Dockerfile verbatim silently costs you 16MB even if you never enable CDS — and on Java 25 you want the
AOT cache, not CDS.

---

## 8. `.dockerignore`

```gitignore
# Build output. MUST NOT enter the context: it busts the dependency layer on every local
# `mvn package`, and with `COPY . .` a stale host-built jar can ship instead of the one your
# build stage produced — unreproducible and, for a money service, unauditable.
target/
build/
out/
bin/

# VCS and CI
.git
.gitignore
.github/

# IDE / editor
.idea/
*.iml
.vscode/
.settings/
.classpath
.project

# Local secrets and keystores — the #1 way credentials end up in an image layer
.env
.env.*
*.pem
*.key
*.p12
*.jks
*.keystore
**/application-local.yaml
**/application-local.yml
**/application-secrets.*

# Docker and infra: not build inputs, and changing them should not rebuild
Dockerfile*
.dockerignore
docker-compose*.yml
compose*.yaml
k8s/
infra/
*.tf
*.tfstate*

# Vendored caches, if anyone committed them
.m2/
.gradle/

# Docs, except the one thing worth keeping for provenance
**/*.md
!README.md
```

Docker's matching rules matter: `**` matches any number of directories, `!` negates, and "the last line
of the .dockerignore that matches a particular file determines whether it's included or excluded" — so
ordering is load-bearing.

But rely on **explicit `COPY`** as the real defence. `COPY src ./src` will happily bake
`src/main/resources/application-local.yaml` with your Postgres password into a layer that survives every
later `RUN`. Keep secrets out of the source tree entirely (see `production-ops.md` §4).

Do **not** gitignore `mvnw`, `mvnw.cmd`, or `.mvn/` — that destroys build reproducibility. And there is
no `maven-wrapper.jar` any more (`distributionType=only-script`), so stale `.gitignore` or setup docs
referencing it are simply wrong.

---

## 9. The AOT cache: 4x startup, and the trap for a database-owning service

Paketo's measured Petclinic numbers on Java 25 give the shape of the trade-off (note the memory column
is **RSS**, not image size):

| Configuration | Startup | RSS |
|---|---|---|
| Plain JVM | 2.379 s | 391.8 MiB |
| Unpacked only | 2.31 s | 319.5 MiB |
| Spring AOT | 2.011 s | 309.5 MiB |
| **AOT cache** | **0.579 s** | 309.5 MiB |
| GraalVM native | 0.117 s | 90.2 MiB |

The AOT cache gets you ~4x for a build-time training run with **no code restrictions**. On Java 25+ it
is one step (JEP 514); Java 24 needs the two-step `-XX:AOTMode=record` then `-XX:AOTMode=create` with
`-XX:AOTConfiguration`; Java 24 and earlier use CDS (`-XX:ArchiveClassesAtExit` /
`-XX:SharedArchiveFile`), which the Boot docs now explicitly call second choice.

**The trap:** the training run boots your real `ApplicationContext` inside `docker build`, where there
is no Postgres. Hikari's fail-fast aborts the run, Flyway tries to migrate, and your outbox relay starts
polling a database that does not exist.

```dockerfile
# ❌ Fails inside `docker build`: HikariPool-1 - Exception during pool initialization,
#    Connection to 127.0.0.1:5432 refused.
RUN java -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh -jar application.jar
```

```dockerfile
# ✅ A dedicated profile whose only job is letting the context refresh offline.
RUN java -XX:AOTCacheOutput=app.aot \
         -Dspring.context.exit=onRefresh \
         -Dspring.profiles.active=trainingrun \
         -jar application.jar
ENTRYPOINT ["java", "-XX:AOTCache=app.aot", "-XX:MaxRAMPercentage=75.0", \
            "-jar", "application.jar"]
```

```yaml
# src/main/resources/application-trainingrun.yaml
spring:
  datasource:
    url: jdbc:postgresql://127.0.0.1:5432/trainingrun
    username: trainingrun
    password: trainingrun
    hikari:
      initialization-fail-timeout: -1   # start the pool even with no DB reachable
      minimum-idle: 0
      connection-timeout: 250
  flyway:
    enabled: false
  sql:
    init:
      mode: never
management:
  health:
    db:
      enabled: false
app:
  outbox:
    relay-enabled: false      # gate your SmartLifecycle relay OFF during training
```

(`spring.context.exit=onRefresh` is a Spring Framework property: it exits the JVM once the context
refreshes, so the cache is written. The `initialization-fail-timeout: -1` semantics are recalled from
HikariCP's README rather than re-fetched — confirm before relying on it.)

Two more AOT-cache facts worth knowing. **JEP 514's one-step `-XX:AOTCacheOutput` forks a
cache-creating sub-invocation with a heap the same size as the training run's**, so peak build-time
memory is **double** your `-Xmx` — a CI runner sized for the app OOMs during image build, which is a
confusing place to debug. And the `.aot` file lands in an image layer and is not small.

Verify the cache is actually used at runtime:

```bash
java -XX:AOTCache=app.aot -Xlog:class+load:file=aot.log -jar application.jar
grep 'source: shared objects file' aot.log | wc -l
```

---

## 10. GraalVM native image: usually the wrong trade here

Only reach for it when scale-to-zero cold start, or replica-count × RSS, is the actual bill. For a
steady-traffic financial service it is not.

What you give up, per the docs: static analysis from `main` with dead-code elimination, a classpath
fixed at build time, no lazy class loading, and reflection/resources/serialization/proxies must be
declared. Spring layers a closed-world assumption on top: "Bean definitions cannot change at runtime",
limited `@Profile` support, and `@ConditionalOnProperty` / `.enabled` toggles evaluated at **build**
time. For a money service that is a direct hit — your feature flags and per-environment profile
switching are exactly the mechanisms that stop working.

```java
// Native build succeeds, then production behaves differently, and your JVM-mode test suite
// caught none of it:
//   @ConditionalOnProperty("app.risk-engine.enabled")  -> evaluated at BUILD time
//   -Dspring.profiles.active=prod at runtime           -> limited effect
//   a RowMapper constructed reflectively               -> ClassNotFoundException
```

Add multi-minute builds needing many GB of RAM, a test suite you must re-run **in native mode** to
trust, lower peak throughput than a warmed JIT without PGO, and degraded JFR/profiler/heap-dump tooling
for the incident you will eventually have. The AOT cache gets you 2.379s → 0.579s with none of that.

Boot 4.0 requires GraalVM native-image v25+. If you do go native, prove it end to end and register the
hints your JDBC/JSON layer needs but AOT cannot infer:

```bash
./mvnw -Pnative native:compile
./mvnw -PnativeTest test            # run the SUITE in native mode
```

```java
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(LedgerHints.class)
class LedgerConfiguration {}

class LedgerHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.reflection().registerType(LedgerEntryRow.class,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_DECLARED_METHODS);
        hints.resources().registerPattern("db/migration/*.sql");
    }
}
```

Native **image sizes** are not stated here because they were not measured. In particular, Paketo's
90.2 MiB native figure is **resident memory, not image size** — misquoting it as an image size is a
specific error to avoid.

---

## 11. Buildpacks: ship both, and be able to explain the Dockerfile

`./mvnw spring-boot:build-image` uses `paketobuildpacks/builder-noble-java-tiny:latest` by default and
names the image `docker.io/library/${project.artifactId}:${project.version}`. It needs a Docker daemon
at build time.

Buildpacks are objectively good engineering: one command, non-root by default, a memory calculator
smarter than `MaxRAMPercentage`, free AOT cache via `BP_JVM_AOTCACHE_ENABLED` (Java 25+; it supersedes
the pre-25 `BP_JVM_CDS_ENABLED`), free native via `BP_NATIVE_IMAGE`, and CVE rebasing without rebuilding
the app layer.

Their honest costs: a Docker daemon requirement (awkward with rootless CI), a >1GB builder pulled on
first run, opaque contents, and customisation limited to the `BP_*`/`BPE_*` extension points — adding
your own health-probe class, a custom CA bundle, or a read-only-friendly layout is fiddly. There are
also open Paketo issues about Java 25 + Boot 4.x AOT-cache build failures, so treat the buildpack AOT
path as less settled than the Dockerfile path.

For a portfolio project, hand-write the Dockerfile **and** wire buildpacks behind a Maven profile with a
README comparison. "The buildpack handled it" is a non-answer to "walk me through your image"; a
commented Dockerfile lets you narrate layer caching, cgroup flags, non-root and signal handling — which
is what is actually being assessed. Shipping both, with measured numbers, shows you evaluated rather
than defaulted.

```xml
<profiles>
  <profile>
    <id>buildpack</id>
    <build>
      <plugins>
        <plugin>
          <groupId>org.springframework.boot</groupId>
          <artifactId>spring-boot-maven-plugin</artifactId>
          <configuration>
            <image>
              <name>ghcr.io/acme/ledger-cnb:${project.version}</name>
              <env>
                <BP_JVM_VERSION>25</BP_JVM_VERSION>
                <BP_JVM_AOTCACHE_ENABLED>true</BP_JVM_AOTCACHE_ENABLED>
                <BPE_APPEND_JAVA_TOOL_OPTIONS>-XX:+UseCompactObjectHeaders</BPE_APPEND_JAVA_TOOL_OPTIONS>
              </env>
            </image>
          </configuration>
        </plugin>
      </plugins>
    </build>
  </profile>
</profiles>
```

```bash
docker build -t ledger:dockerfile .
./mvnw -Pbuildpack spring-boot:build-image
docker images --format '{{.Repository}}:{{.Tag}} {{.Size}}' | grep ledger
```

---

## 12. Runtime interactions specific to this workload

- **The Hikari pool is per replica.** `maxReplicas × maximum-pool-size` can exceed
  `max_connections`; autoscaling then makes the outage worse, because new replicas start, fail to
  connect, fail readiness, and the platform keeps trying. Compute the product against your actual
  `max_connections` before setting either number (`data-access.md` §8).
- **Virtual threads move your concurrency limit to the pool.** With
  `spring.threads.virtual.enabled=true`, Tomcat's thread pool stops being the backpressure valve, so
  5,000 concurrent requests queue on a 10-connection pool. Without an explicit `connection-timeout` they
  park indefinitely instead of failing fast into a 503. This bites hardest where
  `SELECT ... FOR UPDATE` contention spikes.
- **A SIGKILL while a request holds `FOR UPDATE`** can leave the half-open TCP connection — and
  therefore the Postgres backend and its row lock — alive until a keepalive or timeout fires, blocking
  other replicas. Belt and braces: set `idle_in_transaction_session_timeout` and `statement_timeout` on
  the application role so an abandoned lock cannot outlive the container that took it.
- **Boot 4.0 relocated the web-server lifecycle classes** to
  `org.springframework.boot.web.server.context` (e.g. `WebServerApplicationContext`), renamed
  `spring-boot-starter-web` to `spring-boot-starter-webmvc`, moved Jackson to `tools.jackson`, and
  replaced `org.springframework.lang.Nullable` with `org.jspecify.annotations.Nullable`. Copy-pasted 3.x
  container and lifecycle code will not compile, and the import errors do not obviously point at a major
  version change.

See `../examples/Dockerfile` for the complete three-stage build these notes describe.
