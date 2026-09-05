# Production Ops: Probes, Shutdown, Logging, Config, Metrics, Resilience

Spring Boot 4.1.0 / Spring Framework 7.0.8. Read this before adding Actuator, wiring probes,
configuring logging, binding configuration, exposing metrics, scheduling work across replicas, or
adding retries and circuit breakers.

The recurring theme: a money service must fail in ways that are *recoverable*. Most of the mistakes
below convert a small dependency problem into a fleet-wide outage.

---

## 1. Liveness checks in-process state. Readiness checks dependencies. Never swap them.

The Actuator docs are blunt: "The 'liveness' probe should not depend on health checks for external
systems… if an external system (such as a database, a Web API, or an external cache) fails, Kubernetes
might restart all application instances and create cascading failures."

That is the whole argument. A liveness failure means **restart**, which cannot fix Postgres and instead
discards your warm Hikari pool and any in-flight work — amplifying the incident. A readiness failure
means "stop routing to me", which is recoverable and reversible.

Probes are **enabled by default since Boot 4.0** (in 3.x they auto-enabled only on detected
Kubernetes/Cloud Foundry, which is why old code sets the property explicitly). They are exposed at
`/actuator/health/liveness` and `/actuator/health/readiness`, and
`management.endpoint.health.probes.enabled` disables them.

```yaml
management:
  endpoints:
    web:
      exposure:
        include: "health,info,prometheus"   # default is [health] ONLY. Never "*".
  endpoint:
    health:
      probes:
        enabled: true              # already the default in 4.x; explicit as documentation
        add-additional-paths: true # also serves /livez and /readyz on the MAIN server port
      show-details: never          # or when-authorized. Never `always` on a public ingress.
      show-components: never
      validate-group-membership: true   # a typo'd indicator id FAILS STARTUP instead of
      group:                            # silently giving you a group that checks nothing
        liveness:
          include: "livenessState"        # in-process only. Never `db`. Ever.
        readiness:
          include: "readinessState,db"    # cannot serve money without Postgres
```

```yaml
# ❌ A Postgres blip now restarts every replica simultaneously, on top of the outage.
management:
  endpoint:
    health:
      group:
        liveness:
          include: "livenessState,db,redis"
```

Include a dependency in **readiness** only if the service can serve *no useful request* without it. The
database qualifies. A notification service, a cache, or a non-critical SAP adapter usually does not —
degrade those in the response, not in the probe.

`include: "*"` is not the fix for a 404 on `/actuator/prometheus`: it also publishes `/actuator/env`,
`/actuator/configprops`, `/actuator/heapdump` and `/actuator/threaddump`. List the endpoints you want.

### Keep Actuator on the main port on single-ingress platforms

Azure Container Apps exposes one ingress target port per container and its probes are HTTP(S) or TCP on
that port — **exec probes are not supported**, nor gRPC, nor named ports, and there is one probe of each
type per container. So `management.server.port=9090` makes your probes unreachable and every probe 404s.
`add-additional-paths: true` publishes short unauthenticated `/livez` and `/readyz` on `server.port`,
which any orchestrator can hit.

Another ACA-specific hazard: if you enable ingress and define **no** probes, you get auto-added **TCP**
probes on the ingress port. A TCP accept succeeds while the JVM is deadlocked, OOM-degraded, or holding
a broken DataSource — so traffic gets routed to a replica that will 500 every request. Always define
explicit HTTP probes.

### Custom health indicators moved package in Boot 4

```java
package com.example.ledger.health;

import org.springframework.boot.health.contributor.Health;          // NOT boot.actuate.health
import org.springframework.boot.health.contributor.HealthIndicator; // verified: since 4.0.0
import org.springframework.stereotype.Component;

/** Health id = bean name minus the HealthIndicator suffix, i.e. "sapClient". */
@Component
class SapClientHealthIndicator implements HealthIndicator {

    private final SapReachabilityProbe probe;

    SapClientHealthIndicator(SapReachabilityProbe probe) {
        this.probe = probe;
    }

    @Override
    public Health health() {
        SapReachabilityProbe.Snapshot snapshot = this.probe.latest();   // a CACHED field read
        if (snapshot.reachable()) {
            return Health.up()
                    .withDetail("checkedAt", snapshot.checkedAt().toString())
                    .withDetail("latencyMs", snapshot.latencyMs())
                    .build();
        }
        return Health.down()
                .withDetail("checkedAt", snapshot.checkedAt().toString())
                .withDetail("failure", snapshot.failureType())
                .build();
    }
}
```

The id derived from the bean name is what you then list in a health group. (`Health.up()`,
`Health.down()`, `withDetail`, `build()` carry over from 3.x; `Health.down(Throwable)` and
`withException` were not verified, so the sample avoids them.)

### Never make a network call inside `health()`

A readiness probe on a 5s period across 20 replicas is 4 requests/second of pure health traffic against
the downstream. Worse, if the downstream hangs then `health()` hangs, the probe times out (Boot warns
past 10s), and a slow health check can consume the request threads that were supposed to serve real
traffic.

```java
package com.example.ledger.health;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class SapReachabilityProbe {

    public record Snapshot(boolean reachable, Instant checkedAt, long latencyMs,
                           String failureType) {}

    private final RestClient restClient;
    private final AtomicReference<Snapshot> latest =
            new AtomicReference<>(new Snapshot(false, Instant.EPOCH, -1, "not-yet-checked"));

    SapReachabilityProbe(RestClient sapRestClient) {
        this.restClient = sapRestClient;
    }

    public Snapshot latest() {
        return this.latest.get();
    }

    /** ONE call per interval per replica, regardless of probe frequency. */
    @Scheduled(fixedDelayString = "PT5S", initialDelayString = "PT2S")
    void refresh() {
        long start = System.nanoTime();
        try {
            this.restClient.get().uri("/health").retrieve().toBodilessEntity();
            this.latest.set(new Snapshot(true, Instant.now(), elapsedMs(start), null));
        }
        catch (RuntimeException ex) {
            this.latest.set(new Snapshot(false, Instant.now(), elapsedMs(start),
                    ex.getClass().getSimpleName()));
        }
    }

    private static long elapsedMs(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }
}
```

### Readiness fails before liveness during shutdown — do not invert that

On context close, Boot flips `ReadinessState` to `REFUSING_TRAFFIC` while `LivenessState` stays
`CORRECT`. That combination says "stop sending me new work, but I am healthy and finishing what I have
— do not kill me". If liveness also went down, the orchestrator would SIGKILL the container
mid-transaction: a half-written outbox row and an ambiguous ledger.

To drain a replica deliberately, publish the **readiness** event only:

```java
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class TrafficGate {

    private final ApplicationEventPublisher publisher;

    TrafficGate(ApplicationEventPublisher publisher) { this.publisher = publisher; }

    public void refuseTraffic() {
        AvailabilityChangeEvent.publish(this.publisher, this, ReadinessState.REFUSING_TRAFFIC);
    }

    public void acceptTraffic() {
        AvailabilityChangeEvent.publish(this.publisher, this, ReadinessState.ACCEPTING_TRAFFIC);
    }
}
// Never publish LivenessState.BROKEN: the platform will SIGKILL you mid-transaction.
```

`ApplicationAvailability`, `AvailabilityChangeEvent`, `LivenessState` and `ReadinessState` stayed in
`org.springframework.boot.availability` in Boot 4.

### Use a startup probe

During startup Boot reports liveness broken until the context has refreshed. A cold JVM plus Flyway
migrations against a busy Postgres can exceed a default liveness threshold — at which point the
orchestrator restarts the container **mid-migration**, tries again, is killed again: CrashLoopBackOff
caused entirely by probe configuration, and on a money service a partially applied schema. A startup
probe suspends liveness until the app has answered once.

```yaml
# Kubernetes
startupProbe:
  httpGet: { path: /livez, port: 8080 }
  periodSeconds: 3
  failureThreshold: 40       # 120s of boot budget; liveness does not run until this passes
livenessProbe:
  httpGet: { path: /livez, port: 8080 }
  periodSeconds: 10
  failureThreshold: 3
readinessProbe:
  httpGet: { path: /readyz, port: 8080 }
  periodSeconds: 2
  failureThreshold: 2        # detected in <= 4s, inside a 10s preStop window
```

ACA's *default* startup probe already allows many failures at 1s, but any custom probe block replaces
that generosity — so if you define probes at all, define a startup probe too.

---

## 2. Graceful shutdown, budgeted against the platform

**Graceful shutdown is on by default in Boot 4** — verbatim from the 4.1.0 docs: "Graceful shutdown is
enabled by default with all three embedded web servers (Jetty, Reactor Netty, and Tomcat) and with both
reactive and servlet-based web applications." `server.shutdown: immediate` disables it. So half the
blog posts telling you to set `server.shutdown=graceful` describe a no-op.

What is **not** set for you is the grace period: `spring.lifecycle.timeout-per-shutdown-phase`. And it
is **per phase**, not total — the property javadoc reads "Timeout for the shutdown of any phase (group
of `SmartLifecycle` beans with the same phase value)".

```yaml
server:
  port: 8080
  shutdown: graceful          # already the default; explicit as documentation
spring:
  lifecycle:
    timeout-per-shutdown-phase: 8s   # PER PHASE. 3 phases x 8s = 24s, inside a 30s budget.
```

The ordering rule that actually matters: **the platform's grace period must exceed the sum of your
phases plus your drain delay.** Kubernetes and ACA both default `terminationGracePeriodSeconds` to 30s
(the ACA default is widely reported but not stated on the official probes page — verify against the
current ARM API version). If Spring's window is >= the platform's, the platform wins and in-flight money
transactions are killed anyway: you get all the complexity of graceful shutdown and none of the benefit.

```yaml
# Kubernetes
terminationGracePeriodSeconds: 45      # 10s drain + 24s shutdown + slack
lifecycle:
  preStop:
    exec:
      command: ["sh", "-c", "sleep 10"]     # distroless has no shell: ["/bin/sleep","10"]
```

### The `preStop` sleep is a fix, not a hack

Kubernetes sends SIGTERM and removes the Pod from Service endpoints **concurrently**. Endpoint removal
has to propagate through kube-proxy/iptables or the ingress controller on every node, which takes
hundreds of milliseconds to seconds. Meanwhile the app has already stopped accepting connections, so
anything routed in that window gets a connection refused — visible to clients as a burst of 502s on
*every* rollout, usually blamed on "the network". A `preStop` sleep delays only SIGTERM; the container
keeps serving throughout. Size it >= `readinessProbe.periodSeconds × failureThreshold` plus your
ingress' own propagation.

On ACA you cannot do this — exec probes and `preStop` hooks are not in the documented model — so you
rely on readiness turning 503, which is an argument for a **shorter** readiness period there.

Also: your IDE's stop button is often not a real SIGTERM, which is why graceful shutdown appears not to
work locally. Test it with `docker stop` or `kill -TERM <pid>`.

### Graceful shutdown drains the web server only

`@Scheduled` pollers, `@Async` work and outbox relays are torn down as the context closes. Give them
explicit stop semantics, ordered against the web server. Stop runs in **descending** phase order, and
`WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE` is `SmartLifecycle.DEFAULT_PHASE - 1024`, with
`START_STOP_LIFECYCLE_PHASE` another 1024 below. Sitting below both means: HTTP has drained and the web
server has stopped before you stop, and you stop before singleton destruction closes the DataSource —
the only window in which you can still write to Postgres with no new traffic arriving.

```java
package com.example.ledger.outbox;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.context.WebServerApplicationContext;  // moved in 4.0
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public class OutboxRelay implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private static final int PHASE =
            WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE - 1024;

    private final OutboxBatchProcessor processor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;

    OutboxRelay(OutboxBatchProcessor processor) { this.processor = processor; }

    @Override public int getPhase() { return PHASE; }
    @Override public boolean isRunning() { return this.running.get(); }

    @Override
    public void start() {
        if (!this.running.compareAndSet(false, true)) {
            return;
        }
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("outbox-relay").daemon(false).factory());
        this.scheduler.scheduleWithFixedDelay(this::drain, 0, 500, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        if (!this.running.compareAndSet(true, false)) {
            return;
        }
        this.scheduler.shutdown();
        try {
            if (!this.scheduler.awaitTermination(5, TimeUnit.SECONDS)) {   // let the batch commit
                log.warn("outbox relay did not drain in time; forcing shutdown");
                this.scheduler.shutdownNow();
            }
        }
        catch (InterruptedException ex) {
            this.scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void drain() {
        if (!this.running.get()) {
            return;
        }
        try {
            // Bounded batch; claim + publish + mark in ONE transaction, and it checks the
            // running flag between rows so a rollback simply un-claims.
            this.processor.processOneBatch(this.running::get);
        }
        catch (RuntimeException ex) {
            log.warn("outbox batch failed, will retry", ex);   // at-least-once: leave unclaimed
        }
    }
}
```

The dangerous shape is publish-then-mark across **two** transactions: a SIGKILL between them loses the
mark and you re-send. Keep them in one transaction.

---

## 3. Structured logging: built in since Boot 3.4

Delete `logback-spring.xml` and `net.logstash.logback:logstash-logback-encoder`. Boot ships ECS, GELF
and Logstash JSON encoders for both Logback and Log4j2, driven entirely by properties, and MDC
(including trace/span ids) lands in the JSON automatically.

```yaml
# application-prod.yaml -- deployed profiles ONLY
logging:
  structured:
    format:
      console: ecs            # ecs | gelf | logstash | a StructuredLogFormatter FQCN
    ecs:
      service:
        name: ${spring.application.name}
        environment: ${APP_ENV:local}
    json:
      stacktrace:
        root: last
        max-length: 4096
        include-common-frames: false
  level:
    root: INFO
    com.example.ledger: INFO
```

Do **not** set `logging.structured.format.console` in `application-local.yaml` — it turns off the
human-readable console pattern, which makes local development miserable and pushes people to disable
structured logging entirely.

### Put correlation keys on the event, not in the message

```java
// ❌ One opaque string. No field to filter on, and amount formatting depends on locale.
log.info("posting applied key=" + idempotencyKey + " amount=" + amount);
```

```java
// ✅ Queryable fields. All three built-in formats pick up SLF4J key-values and MDC.
log.atInfo()
   .addKeyValue("idempotencyKey", idempotencyKey)
   .addKeyValue("accountId", accountId)
   .addKeyValue("amount", amount.toPlainString())     // never amount.doubleValue()
   .addKeyValue("outcome", "applied")
   .log("posting applied");
```

The whole reason to emit JSON is machine queryability: `addKeyValue` gives you a field you can filter,
count and join across services. At 3am the fields you will want are `idempotencyKey`, `requestId`,
`accountId` and `outcome`.

### Correlation ids: use tracing, not a hand-rolled filter

Add a tracing starter (`spring-boot-starter-opentelemetry` or `spring-boot-starter-zipkin` — both are
new Boot 4 aggregate starters, verified at 4.1.0; on 3.x you hand-assembled a
`micrometer-tracing-bridge-*` plus a reporter) and Boot populates MDC with `traceId`/`spanId` per
request and propagates them over outbound `RestClient` calls, with zero application code.

```yaml
logging:
  pattern:
    correlation: "[${spring.application.name:},%X{traceId:-},%X{spanId:-},%X{idempotencyKey:-}] "
management:
  tracing:
    sampling:
      probability: 1.0            # the default is 0.1, so 90% of traces are never exported
    baggage:
      remote-fields: idempotencyKey       # propagates over the wire
      correlation:
        fields: idempotencyKey            # and lands in MDC for the log pattern
```

Two traps. First, the default sampling probability is `0.1`: MDC population and correlated log lines
still work at any sampling rate, which is why people conclude "tracing is broken" while their logs look
fine. Second, the Boot 4 rename `management.tracing.enabled` → `management.tracing.export.enabled` is
silently ignored if you use the old key.

For a business key like `idempotencyKey`, prefer **baggage** over a bespoke filter — it propagates
remotely and reaches MDC. If you have no tracer at all and must write a filter, extend
`OncePerRequestFilter` and clear MDC in a `finally` block:

```java
// ❌ The id leaks to the next request on that pooled thread whenever doFilter throws.
MDC.put("traceId", id);
chain.doFilter(req, res);
MDC.remove("traceId");
```

Mis-attributed log lines are catastrophic for auditing a money service.

---

## 4. Configuration: validated records, secrets from the environment

A record with a single canonical constructor gets constructor binding with no annotation — immutable,
non-null by construction, no setters. `@Validated` makes Boot run JSR-303 during context refresh, so a
missing base URL or a `batchSize` of 0 fails the **deploy** instead of failing the first transaction at
09:00. Nested types need `@Valid` to cascade. Constructor binding requires `-parameters`, which
`spring-boot-starter-parent` already sets.

```java
package com.example.ledger.config;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "ledger")
public record LedgerProperties(
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String baseCurrency,
        @NotNull @Valid Outbox outbox,
        @NotNull @Valid Sap sap) {

    public record Outbox(
            @Min(1) int batchSize,
            @NotNull Duration pollInterval,
            @Min(1) int maxAttempts) {}

    public record Sap(
            @NotBlank String baseUrl,
            @NotNull Duration connectTimeout,
            @NotNull Duration readTimeout) {}
}
```

Enable with `@ConfigurationPropertiesScan` on the application class.

```java
// ❌ Mutable, unvalidated. baseCurrency is null until someone NPEs; batchSize is silently 0
//    so the poller does nothing, forever, with no error anywhere.
@Component
@ConfigurationProperties(prefix = "ledger")
public class LedgerProperties {
    private String baseCurrency;
    private int batchSize;
    public void setBaseCurrency(String v) { this.baseCurrency = v; }
    public void setBatchSize(int v) { this.batchSize = v; }
}
```

### Secrets come from environment variables only

`application.yml` is packaged inside the jar, inside the image, pushed to a registry, and readable by
anyone who can `docker pull`. It is a **distribution artifact, not a vault**.

Exploit relaxed binding so the secret has no representation in the repository at all:
`SPRING_DATASOURCE_PASSWORD` binds to `spring.datasource.password` with **no YAML key**. That is
strictly stronger than a `${DB_PASSWORD}` placeholder because there is nothing to accidentally commit,
nothing baked into an image layer, and nothing for a future "let's add a default for local dev" commit
to turn into a production credential.

```yaml
# ✅ Declares the shape, never the secret. No `username:` / `password:` keys at all.
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL}
```

```yaml
# ❌ Both of these are how production credentials get committed.
spring:
  datasource:
    password: ${DB_PASSWORD:changeit}   # the default IS the production password one day
---
# application-prod.yml in git
spring:
  datasource:
    password: Pr0d!ledger
```

Keep `/actuator/env` and `/actuator/configprops` off the exposure list in production regardless.

---

## 5. Metrics: business outcomes, and bounded tag cardinality

`http.server.requests` tells you the API returned 200. It cannot tell you that 40% of postings are now
rejected for insufficient funds, or that idempotent replays jumped 10x because a client is stuck in a
retry loop. Those are the metrics that catch a *correctness* incident.

```yaml
management:
  observations:
    annotations:
      enabled: true      # default FALSE. Without it @Timed/@Counted/@Observed do NOTHING.
    key-values:
      service: ${spring.application.name}
```

That switch needs an aspect on the classpath. The starter is **`spring-boot-starter-aspectj`** in
Boot 4 — `spring-boot-starter-aop` is a 404 at 4.1.0, so a half-done upgrade silently disables every
annotation-driven metric. Do not hand-register `TimedAspect`/`CountedAspect` beans; the one property
wires all of them (people add `TimedAspect`, forget `CountedAspect`, and wonder why `@Counted` produces
nothing).

`/actuator/prometheus` also needs `micrometer-registry-prometheus` on the runtime classpath **and**
`prometheus` on the exposure list. Having the dependency alone yields a 404, which usually gets blamed
on the scrape config.

```java
package com.example.ledger.metrics;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class LedgerMetrics {

    private final MeterRegistry registry;
    private final Timer postingLatency;

    LedgerMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.postingLatency = Timer.builder("ledger.posting.duration")
                .description("End-to-end time to durably apply a double-entry posting")
                .publishPercentileHistogram()
                .maximumExpectedValue(Duration.ofSeconds(5))
                .register(registry);
    }

    /** outcome is one of: applied | replayed | rejected_insufficient_funds | failed */
    public void postingCompleted(String currency, String outcome, long nanos) {
        this.postingLatency.record(nanos, TimeUnit.NANOSECONDS);
        Counter.builder("ledger.postings")
                .tag("currency", currency)     // a handful of values
                .tag("outcome", outcome)       // a handful of values
                .register(this.registry)
                .increment();
    }

    /** The single most useful metric for an Idempotency-Key API: are retries being absorbed? */
    public void idempotentReplay(String endpoint) {
        this.registry.counter("ledger.idempotency.replays", "endpoint", endpoint).increment();
    }

    /** Outbox lag: the number that tells you at-least-once delivery is falling behind. */
    public void registerOutboxLag(Supplier<Number> pendingCount) {
        Gauge.builder("ledger.outbox.pending", pendingCount)
                .description("Outbox rows awaiting publication")
                .register(this.registry);
    }
}
```

```java
// ❌ Self-DoS. Every distinct tag value is a separate time series: a fresh UUID per request
//    creates unbounded series, OOMs the in-process registry, then the Prometheus server.
this.registry.counter("postings",
        "idempotencyKey", key,          // unbounded
        "amount", amount.toString(),    // unbounded
        "accountId", accountId).increment();
```

High-cardinality identifiers belong on the **log event** or as an Observation
`highCardinalityKeyValue` (traces) — never as a meter tag. And verify a new metric actually appears on
`/actuator/prometheus` before building a dashboard on it: `@Timed`/`@Counted`/`@Observed` compile, run,
and produce nothing when the aspect is missing.

Micrometer's version is BOM-managed (1.17.0 at Boot 4.1.0) — do not pin it.

---

## 6. Scheduling across replicas

**Assume every `@Scheduled` method runs once per replica per tick.** `@Scheduled` is a per-JVM timer
with no cluster awareness. On ACA with an HTTP-based scale rule, the replica count is decided by request
traffic, which has nothing to do with whether the job is safe to run twice.

```java
// ❌ 3 replicas => interest accrued 3x. Discovered by a customer, not by you.
@Scheduled(cron = "0 0 2 * * *")
public void accrueDailyInterest() {
    this.ledger.accrueInterestForAllAccounts();
}
```

Choose the mechanism per job:

**Queue-shaped work → `SKIP LOCKED`, not a lock.** Every replica runs the same claim statement and
PostgreSQL partitions the rows between them, so the work *scales out* instead of serialising onto one
pod. This is strictly better than a leader lock for outbox drains and retry sweeps. See
`data-access.md` §5 for the statement. Syntax order matters: the locking clause goes last, after
`LIMIT`.

**Genuinely singleton jobs → ShedLock.** ShedLock **7.7.0** requires JVM 17+ and its compatibility
matrix lists Spring Boot 4.x. (Use the 6.x line for Boot 3.3-3.5.) It turns `@Scheduled` into "whoever
gets the row runs it; everyone else skips" — it does not queue the skipped executions, which is the
correct semantic for a cron job.

Two configuration details decide whether it works:

- **`.usingDbTime()`** makes PostgreSQL the single clock. By default ShedLock compares timestamps taken
  from each node's clock, so two replicas whose clocks differ by a few seconds can both believe a lock
  has expired.
- **`lockAtMostFor` must exceed the WORST-case runtime**, not the average. If the job outruns it, the
  lock is released while the job is still running and a second replica starts it.

```java
package com.example.ledger.config;

import javax.sql.DataSource;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT15M")
class SchedulingConfig {

    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()          // the DATABASE clock, not the pod's
                        .build());
    }
}
```

```java
@Scheduled(cron = "0 0 2 * * *", zone = "UTC")
@SchedulerLock(name = "accrueDailyInterest", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
void accrueDailyInterest() {
    LockAssert.assertLocked();   // fails LOUDLY if the aspect was never wired
    // ... one-shot, cluster-wide-exclusive work
}
```

Flyway migration for the lock table:

```sql
create table shedlock(
  name       varchar(64)  not null,
  lock_until timestamp    not null,
  locked_at  timestamp    not null,
  locked_by  varchar(255) not null,
  primary key (name)
);
```

Add `net.javacrumbs.shedlock:shedlock-spring:7.7.0` and
`net.javacrumbs.shedlock:shedlock-provider-jdbc-template:7.7.0` with explicit versions — ShedLock is
not BOM-managed. The exact `usingDbTime()` builder chain is recalled rather than read from the 7.x
javadoc; verify it compiles.

---

## 7. Resilience: retry is not resilience

Framework 7 ships retry in core (verified: `@Retryable` and `@EnableResilientMethods` in
`org.springframework.resilience.annotation`, both since 7.0). spring-retry is maintenance-only. The
migration is not an import swap:

| spring-retry | Framework 7 core |
|---|---|
| `@EnableRetry` | `@EnableResilientMethods` |
| `org.springframework.retry.annotation.Retryable` | `org.springframework.resilience.annotation.Retryable` |
| `maxAttempts` | **`maxRetries`** (verified: `long`, default `3`). Total attempts = `1 + maxRetries` |
| `@Backoff(delay=…, multiplier=…)` | `delay` (default 1000ms), `multiplier`, `jitter`, `maxDelay`, `timeout` on the annotation itself |
| `listeners()` | publish/consume `MethodRetryEvent` with `@EventListener` |

Copying a spring-retry snippet fails to compile on `maxAttempts`; copying the *enabling* annotation
silently leaves retries disabled. Declare `@EnableResilientMethods` explicitly — do not assume Boot
auto-configures it.

```java
import org.springframework.resilience.annotation.Retryable;

/**
 * 1 initial call + up to 3 retries. Backoff 200ms, 600ms, 1800ms (+/- 100ms jitter), capped at
 * 5s, bounded overall by timeout. ONLY transient failures are retried.
 */
@Retryable(includes = SapTransientException.class,
           excludes = SapRejectedException.class,
           maxRetries = 3, delay = 200, multiplier = 3, jitter = 100,
           maxDelay = 5_000, timeout = 10_000)
public SapReceipt post(SapDocument document) {
    return this.restClient.post().uri("/documents")
            .header("Idempotency-Key", document.idempotencyKey())   // from the OUTBOX ROW
            .body(document).retrieve().body(SapReceipt.class);
}
```

The default `@Retryable` retries on **any** exception, so always set `includes` — a blanket policy will
happily retry a 400 four times, and retry a business rejection. And **only retry idempotent
operations**: a POST that timed out may well have succeeded on the server, so retrying is safe only
when the server honours an `Idempotency-Key` you reuse byte-identically. Self-invocation bypasses the
proxy here exactly as it does for `@Transactional`; put the retried call on a separate collaborator.

### Add a circuit breaker, because retries make a long outage worse

Framework 7 core resilience gives you `@Retryable` and `@ConcurrencyLimit` — there is **no circuit
breaker**. If a downstream is down for three hours, retry-only behaviour means every request burns its
full backoff ladder: threads and connections pinned for seconds each, caller latency multiplied by
`maxRetries`, your own pool exhausted, and a retry storm hammering the dependency exactly while it is
trying to recover. A breaker converts a 10s timeout-and-retry into a sub-millisecond fail-fast, and
probes for recovery with a handful of half-open calls instead of your entire traffic.

On Boot 4 the artifact is **`io.github.resilience4j:resilience4j-spring-boot4`**, added in 2.4.0 — not
`resilience4j-spring-boot3`, and do not leave both on the classpath. Declare an explicit version:
resilience4j-spring-boot4 was omitted from the resilience4j BOM in 2.4.0 (issue #2427), so BOM-based
resolution can fail. Re-check Maven Central for the current version before pinning; the 2.4.0 figure
here was not re-verified in this pass.

```yaml
resilience4j:
  circuitbreaker:
    instances:
      sap:
        registerHealthIndicator: false    # see the warning below
        slidingWindowType: COUNT_BASED
        slidingWindowSize: 50
        minimumNumberOfCalls: 20
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
        permittedNumberOfCallsInHalfOpenState: 5
        automaticTransitionFromOpenToHalfOpenEnabled: true
        recordExceptions:
          - com.example.ledger.sap.SapTransientException
          - java.io.IOException
        ignoreExceptions:
          - com.example.ledger.sap.SapRejectedException
```

**Never put the circuit-breaker health indicator in the readiness group.** With
`registerHealthIndicator: true` an OPEN breaker reports DOWN; if readiness includes it, one flaky
non-critical downstream drains **every** replica at once, your service becomes unavailable for requests
that never touch that dependency, and the autoscaler cannot help because new replicas fail readiness
too. Show it on `/actuator/health` for humans if you like — keep it out of the group.

**Aspect ordering between Spring's `@Retryable` and Resilience4j's `@CircuitBreaker` is undefined.**
Resilience4j documents its own order as `Retry ( CircuitBreaker ( RateLimiter ( TimeLimiter ( Bulkhead
( fn ) ) ) ) )`, configurable via `resilience4j.retry.retryAspectOrder` — but that governs only
Resilience4j's own aspects. Mixing the two families gives you whichever order the proxy happened to
build. Either use Resilience4j for both, or set explicit `@Order` on your own advisors.

A fallback must never invent success:

```java
// ❌ Silent money loss.
private SapReceipt postUnavailable(SapDocument d, Throwable t) {
    return new SapReceipt("UNKNOWN", "ASSUMED_OK");
}
```

```java
// ✅ Leave the outbox row PENDING with a pushed-out next_attempt_at. Never fake a receipt.
private SapReceipt postUnavailable(SapDocument document, Throwable cause) {
    throw new SapUnavailableException(document.idempotencyKey(), cause);
}
```

---

## 8. Outbound HTTP: `RestClient`, with timeouts

`RestTemplate` is deprecated as of Framework 7 — but **at the documentation level only**: the class is
not annotated `@Deprecated` and does not appear in the javadoc deprecated-list, so you get no compiler
warning while writing strategically dead API. Migrate deliberately. `WebClient` remains correct for
genuinely reactive code; pulling `spring-boot-starter-webflux` into an otherwise-blocking service just
to get `WebClient` is a common mistake.

Always set connect **and** read timeouts. The JDK `HttpClient`'s default read timeout is **infinite**,
and an outbound call with no read timeout inside a `@Transactional` method holding `SELECT ... FOR
UPDATE` row locks is a self-inflicted outage: locks held for the life of the hung request, pool
exhausts, readiness fails, the orchestrator restarts the container mid-transaction.

```yaml
# Spring Boot 4.x namespace: PLURAL "clients". The 3.4/3.5 singular form is silently ignored,
# which leaves you with an infinite read timeout and no warning.
spring:
  http:
    clients:
      connect-timeout: 2s
      read-timeout: 5s
      redirects: dont-follow
```

Inject the auto-configured `RestClient.Builder` rather than calling `RestClient.builder()` from
scratch — the injected one keeps observability instrumentation, so outbound calls appear in traces and
carry the correlation context. Centralise the 4xx/5xx decision in a `defaultStatusHandler` so callers
never see a raw `RestClientResponseException` leaking upstream detail into your `ProblemDetail`, and
decode upstream problem bodies with `getResponseBodyAs(ProblemDetail.class)` rather than string
matching.

```java
// ❌ No timeouts, loses autoconfigured observability, and mints a NEW key per attempt
//    -> duplicate postings upstream.
RestClient c = RestClient.builder().baseUrl("https://sap.internal/api").build();
c.post().uri("/postings")
        .header("Idempotency-Key", UUID.randomUUID().toString())   // BUG
        .body(body).retrieve().body(PostingAck.class);
```

The `HttpClientSettings` / `ClientHttpRequestFactoryBuilder` types used to build a timeout-configured
factory in Boot 4 were **not** verified in this pass (Boot 3.4/3.5 used a different type,
`ClientHttpRequestFactorySettings`). Prefer the `spring.http.clients.*` properties above, which are
verified, and confirm the programmatic API against the javadoc before using it.

---

## 9. Virtual threads: only when the bottleneck is blocking I/O concurrency

`spring.threads.virtual.enabled=true` (Boot 3.2+, Java 21+) swaps the Tomcat request executor and the
auto-configured `TaskExecutor`/`TaskScheduler` for virtual-thread variants. Boot's docs are explicit
that thread-pool properties then **have no effect**: `spring.task.execution.pool.*`,
`spring.task.scheduling.pool.size`, `server.tomcat.threads.max` all become inert.

The safety property those pools were quietly providing was **admission control**. 200 platform threads
meant at most 200 concurrent DB calls. With unbounded virtual threads, 10,000 concurrent requests all
queue on a 20-connection Hikari pool, every one blocking for `connection-timeout`, and you convert a
throughput problem into a timeout cascade. This bites hardest exactly where `SELECT ... FOR UPDATE`
contention spikes.

```yaml
spring:
  threads:
    virtual:
      enabled: true       # Java 21+; 24+ recommended (see below)
  main:
    keep-alive: true      # virtual threads are DAEMONS: a scheduler-only app would exit
  datasource:
    hikari:
      maximum-pool-size: 20
      connection-timeout: 3000    # MUST be set: fail fast into a 503 rather than park forever
server:
  tomcat:
    max-connections: 2000         # re-introduce admission control at the connection layer
    accept-count: 100
```

Bound concurrency at the code level where it matters with Framework 7's
`@ConcurrencyLimit(16)` (`org.springframework.resilience.annotation`) around a fragile downstream.

Before enabling, audit for:

- **`ThreadLocal` used as a cache.** A virtual thread is created per task and discarded, so a
  `ThreadLocal<SimpleDateFormat>` or a pooled buffer goes from pool-sized to *request-rate*-sized.
  Request-scoped `ThreadLocal` use (MDC, SecurityContext, transaction sync) is fine, because it is set
  and cleared within the task.
- **Thread-name-based logic.** Meaningless when every task has a fresh unnamed thread.
- **Libraries that pin.** JEP 491 (delivered in JDK 24, per the OpenJDK jdk-dev thread and Inside Java
  Newscast — the JEP page itself returned 403, so treat the JEP number as reported) stopped
  `synchronized` from pinning, which is why the Boot docs now say "Java 24 or later is strongly
  recommended". `Object.wait()` and native/JNI frames still pin. Detect it with the JFR
  `jdk.VirtualThreadPinned` event or `jcmd <pid> Thread.dump_to_file -format=json`, not by reasoning
  about your code.
