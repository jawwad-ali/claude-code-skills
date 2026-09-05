# syntax=docker/dockerfile:1
#
# Production Dockerfile for a Spring Boot 4.1.0 service.
#
# Three stages, each with one job:
#   1. build    — JDK + Maven. Never reaches the final image.
#   2. extract  — split the fat jar into cacheable layers.
#   3. runtime  — JRE only, non-root, container-aware JVM.
#
# Every non-obvious line has a WHY. Two things here differ from essentially every containerization
# tutorial written before 2026, because both changed in Spring Boot 4.1:
#   - `-Djarmode=layertools` is REMOVED. Use `-Djarmode=tools ... extract --layers`.
#   - `-DskipTests` no longer skips AOT processing. Use `-Dmaven.test.skip=true`.

# =====================================================================================
# 1. build
# =====================================================================================
# WHY pin the builder's JDK to the same major as the runtime: letting the base image decide the
# Java version is the most common "works on my laptop" class of incident.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build

# WHY copy ONLY the POM first: Docker invalidates every layer after the first changed one. With
# `COPY . .` up front, editing one .java file re-resolves the entire dependency graph. Pinning the
# resolve layer's cache key to the POM alone is the whole trick.
COPY pom.xml ./

# WHY a BuildKit cache mount: when the POM DOES change, Maven re-downloads only the delta instead
# of all ~300 artifacts, because the cache is cumulative across builds and survives layer
# invalidation. It is not baked into the image, so it costs zero final bytes.
#
# WHY not follow this with `mvn -o package`: dependency:go-offline is best-effort and chronically
# incomplete (it misses lazily-resolved plugin dependencies and some scoped transitives). With a
# cache mount that is harmless — the package step below fetches the remainder into the same
# persistent cache. With -o the build fails on whatever go-offline missed.
RUN --mount=type=cache,target=/root/.m2/repository,sharing=locked \
    mvn -B -ntp dependency:go-offline

# WHY source last: it is the most volatile input.
COPY src ./src

# WHY -Dmaven.test.skip=true and NOT -DskipTests: in Boot 4.1 the Maven plugin's AOT processing no
# longer responds to -DskipTests, so the image build would silently start running AOT processing —
# slower, and liable to fail on build-time infrastructure it cannot reach.
#
# Tests belong in CI (`./mvnw verify`), not in the image build. The image build should be
# deterministic and fast; the test suite needs Docker for Testcontainers, which you do not want
# nested inside a docker build.
RUN --mount=type=cache,target=/root/.m2/repository,sharing=locked \
    mvn -B -ntp -Dmaven.test.skip=true package \
 && mv target/*.jar /build/application.jar

# WHY compile the health probe HERE: `java HealthCheck.java` (single-file source launch) needs a
# COMPILER, so it cannot run on a JRE-only runtime image. Compile in the JDK stage, copy the class.
COPY docker/HealthCheck.java ./
RUN javac -d /healthcheck HealthCheck.java

# =====================================================================================
# 2. extract
# =====================================================================================
# WHY a separate stage: it keeps the JDK, Maven, ~/.m2 and your source out of the final image
# entirely, and makes the four COPY layers below the only thing the registry re-pushes on a code
# change.
FROM eclipse-temurin:25-jre-noble AS extract
WORKDIR /extract
COPY --from=build /build/application.jar ./

# WHY `--layers` explicitly: it is NOT the default for the tools jarmode. Omit it and you get the
# flat lib/ + app-jar layout — fine for an AOT cache, useless for Docker layer caching.
#
# Discover the options with:  java -Djarmode=tools -jar application.jar help extract
# List layers without extracting:  java -Djarmode=tools -jar application.jar list-layers
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# =====================================================================================
# 3. runtime
# =====================================================================================
# WHY -jre and not -jdk: shipping javac, jlink and jshell to production is ~35MB larger and a
# bigger attack surface for zero benefit.
#
# WHY noble (glibc) rather than alpine (musl): the ~30MB saving is worth far less than people think
# — registry layers are shared and cached per node, so you pay the difference once per node, not
# per deploy. The real criteria are glibc-vs-musl behaviour, whether you can exec in during an
# incident, and CVE cadence. For money-handling code, glibc plus a maintained LTS base wins.
#
# Alternatives, and when: gcr.io/distroless/java25-debian13:nonroot for a genuine security mandate
# (no shell, no package manager; break-glass with the debug-nonroot tag);
# eclipse-temurin:25-jre-alpine if you have validated musl.
FROM eclipse-temurin:25-jre-noble
WORKDIR /application

# WHY a NUMERIC uid/gid, created explicitly: Kubernetes `runAsNonRoot` can only VERIFY a numeric
# user. With a named user the kubelet cannot prove the uid is not 0, and admission may reject the
# pod.
RUN groupadd --system --gid 10001 app \
 && useradd  --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app

# WHY four COPYs and this exact order: layers.idx orders them least-volatile first, so a code
# change re-pushes only the small `application` layer instead of ~60MB of unchanged dependency
# jars. Reordering these throws the whole benefit away.
#
# WHY --chown here rather than a later `RUN chown`: a chown after the fact duplicates every file
# into a new layer, doubling the image size.
COPY --from=extract --chown=10001:10001 /extract/extracted/dependencies/          ./
COPY --from=extract --chown=10001:10001 /extract/extracted/spring-boot-loader/    ./
COPY --from=extract --chown=10001:10001 /extract/extracted/snapshot-dependencies/ ./
COPY --from=extract --chown=10001:10001 /extract/extracted/application/           ./
COPY --from=build   --chown=10001:10001 /healthcheck/ /opt/healthcheck/

USER 10001:10001
EXPOSE 8080
STOPSIGNAL SIGTERM

# ------------------------------------------------------------------------------------
# HEALTHCHECK — local dev and docker compose ONLY
# ------------------------------------------------------------------------------------
# Kubernetes uses only the pod spec's probes and does not consult the OCI healthcheck config, and
# Azure Container Apps documents HTTP(S)/TCP probes only ("exec probes aren't supported"). So on
# your real target the useful configuration is the PLATFORM probe pointed at /livez and /readyz.
# This block earns its keep for `docker compose ... depends_on: condition: service_healthy`.
#
# WHY the exec (JSON array) form: mandatory on a shell-less image, and correct everywhere else.
# WHY -Xmx24m -XX:+UseSerialGC -XX:TieredStopAtLevel=1: every probe invocation forks a WHOLE JVM
# whose RSS is charged against the container's memory limit. On a tightly sized replica an
# unconstrained health probe can itself trigger the OOM killer.
# WHY readiness and not liveness: liveness should not gate on anything a restart cannot fix.
# NOTE --start-interval needs Docker 25.0+.
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --start-interval=2s --retries=3 \
  CMD ["java", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Xmx24m", \
       "-cp", "/opt/healthcheck", "HealthCheck", \
       "http://127.0.0.1:8080/actuator/health/readiness"]

# ------------------------------------------------------------------------------------
# ENTRYPOINT
# ------------------------------------------------------------------------------------
# WHY the exec (JSON array) form, and it is not cosmetic: Docker's own docs say the shell form
# "starts your ENTRYPOINT as a subcommand of /bin/sh -c, which does not pass signals", so "your
# executable doesn't receive a SIGTERM from docker stop". Get this wrong and graceful shutdown,
# in-flight request draining and your outbox relay's final batch ALL silently do nothing — the
# container just hangs until SIGKILL, with NOTHING in the logs to indicate why, because the
# shutdown hook never fires. For a money service that means requests dying mid-transaction and
# clients retrying with the same Idempotency-Key.
#
# You do NOT need tini/dumb-init for a plain `java -jar`: the JVM installs its own SIGTERM handler
# and is fine as PID 1. Use `docker run --init` only if your entrypoint forks children. If a
# wrapper script is unavoidable, its last line must be `exec java ...` — without `exec`, sh stays
# PID 1 and swallows the signal.
# Flag-by-flag rationale (kept OUT of the instruction below: a `#` line inside a backslash
# continuation is not portable Dockerfile syntax, so never interleave comments into a JSON array).
#
#   -XX:MaxRAMPercentage=75.0
#     Heap as a PERCENTAGE of the cgroup limit, never a hardcoded -Xmx.
#     -XX:+UseContainerSupport defaults to true on Linux (Java 10+), so the JVM DOES read cgroup
#     limits — but the default MaxRAMPercentage is small (commonly cited as 25, i.e. a ~512MB heap
#     in a 2Gi container). The Java 25 man page does not list the flag, so VERIFY on your JDK:
#       java -XX:+PrintFlagsFinal -version | grep RAMPercentage
#     Hardcoding -Xmx512m is wrong in the other direction: it decouples the JVM from the limit, so
#     resizing the container does nothing and a smaller container OOM-kills instantly.
#     Do NOT push this to 90+. RSS = heap + metaspace + code cache + (~1MB x threads) + direct
#     buffers + GC structures. Exceed the cgroup limit and the KERNEL OOM-kills the container:
#     exit code 137, no Java stack trace, nothing useful logged. 75% is the workable ceiling above
#     512MB; drop to 50-60% below 256MB.
#
#   -XX:InitialRAMPercentage=75.0
#     Initial == Max pins Xms=Xmx: no heap-growth pauses, deterministic memory for a fixed-size
#     replica.
#
#   -XX:MaxMetaspaceSize=256m
#     Cap metaspace so a leak surfaces as an OOME the JVM reports, not an opaque exit 137.
#
#   -XX:+ExitOnOutOfMemoryError
#     Die rather than limp on in an undefined state. For a money service a clean restart by the
#     orchestrator beats a JVM that is technically alive and silently failing writes.
#
#   -XX:+UseG1GC
#     Choose the collector explicitly. GC ergonomics silently select SerialGC on a "small"
#     machine, and the exact thresholds are not something to guess at.
#
#   -XX:+UseCompactObjectHeaders
#     JDK 25 (JEP 519): production-ready but OFF by default. Meaningfully less heap per object.
#     REMOVE this flag on Java 17/21, where it does not exist or is experimental.
#
#   -XX:ActiveProcessorCount=1
#     SET THIS TO MATCH YOUR ACTUAL CPU ALLOCATION. The man page: it "overrides the number of CPUs
#     that the VM will use to calculate the size of thread pools it will use for various operations
#     such as Garbage Collection and ForkJoinPool", and is "honored even if UseContainerSupport is
#     not enabled". With a fractional limit (ACA 0.5 vCPU, K8s cpu: 500m) the derived count is a
#     rounding artefact that silently drives GC threads, ForkJoinPool.commonPool parallelism,
#     Tomcat acceptors, and every Runtime.availableProcessors() caller. Symptom of leaving it
#     unset: jittery p99 latency that does not correlate with load.
#
#   -XX:HeapDumpPath=/tmp
#     Dumps land on the size-capped tmpfs (see the --read-only notes below), so a dump cannot fill
#     the node's disk.
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:InitialRAMPercentage=75.0", \
  "-XX:MaxMetaspaceSize=256m", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-XX:+UseG1GC", \
  "-XX:+UseCompactObjectHeaders", \
  "-XX:ActiveProcessorCount=1", \
  "-XX:HeapDumpPath=/tmp", \
  "-jar", "application.jar"]

# =====================================================================================
# Running it — the parts that live outside this file
# =====================================================================================
#
# docker run -d --name ledger \
#   --read-only \
#   --tmpfs /tmp:rw,noexec,nosuid,size=64m \
#   --cap-drop ALL \
#   --security-opt no-new-privileges \
#   --user 10001:10001 \
#   --memory 1g --memory-swap 1g --cpus 1.0 --pids-limit 256 \
#   -e SPRING_PROFILES_ACTIVE=prod \
#   -e SPRING_DATASOURCE_URL=... \
#   -e SPRING_DATASOURCE_USERNAME=... \
#   -e SPRING_DATASOURCE_PASSWORD=... \
#   -p 8080:8080 ledger:1.0.0
#
# --read-only WILL NOT BOOT Spring Boot without BOTH a writable /tmp AND, in application.yaml:
#
#     server:
#       tomcat:
#         basedir: /tmp/tomcat
#
# The embedded Tomcat factory creates a work directory under /tmp at startup and fails with
# `WebServerException: Unable to create tempDir`. This only appears once the platform enforces
# readOnlyRootFilesystem — i.e. never on your laptop, always in the hardened environment.
#
# --cap-drop ALL is free here because we bind 8080. A port below 1024 would need
# CAP_NET_BIND_SERVICE, which is a reason not to use one.
#
# Verify what the JVM actually detected, from INSIDE the running container:
#   java -Xlog:os+container=trace -version
#   java -XX:+PrintFlagsFinal -version | grep -Ei 'MaxHeapSize|ActiveProcessor|RAMPercentage'
#
# Test graceful shutdown for real. An IDE stop button is often not a true SIGTERM, which is why
# graceful shutdown appears not to work locally:
#   docker stop ledger        # SIGTERM, then SIGKILL after Docker's own timeout
#   kill -TERM <pid>
#
# CI: cache mounts live in the BuildKit builder's LOCAL state and are empty on a fresh runner, so
# the speedup you measured locally silently vanishes. Add registry-backed cache:
#   docker buildx build \
#     --cache-from type=registry,ref=ghcr.io/acme/ledger:buildcache \
#     --cache-to   type=registry,ref=ghcr.io/acme/ledger:buildcache,mode=max \
#     -t ghcr.io/acme/ledger:1.0.0 --push .
#
# ------------------------------------------------------------------------------------
# OPTIONAL: the AOT cache (Java 25+), ~4x faster startup
# ------------------------------------------------------------------------------------
# Append to the runtime stage, and note the trap: the training run boots your REAL
# ApplicationContext inside `docker build`, where there is no Postgres. Without a dedicated
# profile, Hikari's fail-fast aborts the run, Flyway tries to migrate, and the outbox relay starts
# polling a database that does not exist. See references/containerization.md for the full
# application-trainingrun.yaml.
#
#   RUN java -XX:AOTCacheOutput=app.aot \
#            -Dspring.context.exit=onRefresh \
#            -Dspring.profiles.active=trainingrun \
#            -jar application.jar
#   ENTRYPOINT ["java", "-XX:AOTCache=app.aot", "-XX:MaxRAMPercentage=75.0", \
#               "-jar", "application.jar"]
#
# Also budget for it: JEP 514's one-step -XX:AOTCacheOutput forks a cache-creating sub-invocation
# with an equal-sized heap, so PEAK BUILD MEMORY IS DOUBLE your -Xmx. A CI runner sized for the app
# will OOM during the image build, which is a confusing place to debug.
#
# Verify the cache is actually used:
#   java -XX:AOTCache=app.aot -Xlog:class+load:file=aot.log -jar application.jar
#   grep 'source: shared objects file' aot.log | wc -l
