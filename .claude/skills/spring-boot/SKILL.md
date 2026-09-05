---
name: spring-boot
description: >-
  Production-grade Spring Boot for Java services that own money and state. This skill MUST be loaded when ANY .java file, pom.xml, build.gradle, build.gradle.kts, application.yml, application.yaml, application.properties, or Flyway migration (V*__*.sql, R__*.sql) belonging to a Spring project is being read, reviewed, edited, or created. Also use when the user asks to "create a Spring Boot app", "scaffold a Spring service", "add a REST controller", "add an endpoint", "create a repository", "write a DAO", "use JdbcClient", "use JdbcTemplate", "write raw SQL in Spring", "add @Transactional", "fix a transaction that isn't rolling back", "make this endpoint idempotent", "handle the Idempotency-Key header", "implement the transactional outbox", "publish events reliably", "fix a race condition", "prevent duplicate charges", "prevent double-posting", "add a Flyway migration", "add liveness and readiness probes", "add Actuator", "graceful shutdown", "containerize a Spring Boot app", "write a Dockerfile for a jar", "write a Testcontainers test", "test with a real Postgres", "why isn't @Valid working", "return RFC 9457 problem details", "upgrade to Spring Boot 4", "migrate from Spring Boot 3", "review this Java service", or mentions Spring Boot, Spring Framework, JdbcClient, JdbcTemplate, NamedParameterJdbcTemplate, HikariCP, Flyway, Actuator, ProblemDetail, ResponseEntityExceptionHandler, MockMvcTester, Testcontainers, @MockitoBean, ON CONFLICT, SELECT FOR UPDATE, SKIP LOCKED, BigDecimal money, idempotency keys, outbox pattern, at-least-once delivery, Maven wrapper, spring-boot-starter-webmvc, or Spring Boot on Docker / Kubernetes / Azure Container Apps.
---

# Production-Grade Spring Boot

Weighted toward services that own money and state: plain JDBC over raw SQL, idempotent writes,
transactional outboxes, correctness under simultaneous requests. Focused on the failures that are
*silent* — a missed transaction boundary and a lost update do not throw, they produce wrong numbers.

## Version reality — establish this before writing a line

Read from live sources on **2026-07-25**. Re-verify at the URLs; this whole line moved in 2025-2026.

| Thing | Current | Source |
|---|---|---|
| Spring Boot GA (Initializr default) | **4.1.0** (June 2026) | <https://start.spring.io/metadata/client> |
| Java | min **17**, max **26**; Initializr default 17 | <https://docs.spring.io/spring-boot/system-requirements.html> |
| Spring Framework | **7.0.8**+ | same page |
| Maven / Gradle floor | Maven **3.6.3+**; Gradle **8.14+** or 9.x (8.0-8.13 unsupported) | same page |
| Servlet baseline | **6.1** — Tomcat 11.0.x / Jetty 12.1.x. Undertow dropped. | same page |
| Jackson | **3.1.4** default; Jackson 2 at 2.21.4, deprecated | BOM ↓ |
| PostgreSQL driver / Flyway | **42.7.11** / **12.4.0** | BOM |
| HikariCP / Micrometer / Tomcat | **7.0.2** / **1.17.0** / **11.0.22** | BOM |
| JUnit Jupiter / Testcontainers | **6.0.3** (JUnit **6**, not 5) / **2.0.5** (breaking major) | BOM |
| PostgreSQL server | **18** (`/docs/current`) | <https://www.postgresql.org/docs/current/> |

BOM, for re-reading any managed version — never pin these yourself:
`https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.0/spring-boot-dependencies-4.1.0.pom`

**Boot 4.x is GA.** Do not write a 3.5-shaped service: the Initializr offers no 3.x `bootVersion` at all,
and 3.5's OSS window closed mid-2026. **Java:** 17 is the floor and the Initializr default; nothing here
*requires* more. Prefer **25** for a long-lived money service — newest LTS, and where the one-step AOT
cache lives; 21 is fine. (The claim that 4.1 raised the baseline to 21 misreads a release note about
**jOOQ** 3.20; the system-requirements page says 17.)

### If you see this in old code or an old tutorial

| Boot 3.x / pre-4 | Boot 4.1 | What happens if you keep the old one (almost all of them still resolve, which is why nobody notices) |
|---|---|---|
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` | Resolves fine; only the POM `<description>` says "deprecated in favor of spring-boot-starter-webmvc". Fully silent. |
| `org.flywaydb:flyway-core` alone | `spring-boot-starter-flyway` **+** `org.flywaydb:flyway-database-postgresql` | Startup failure about an unsupported database, never naming the missing module. |
| `org.testcontainers:postgresql` | `org.testcontainers:testcontainers-postgresql` | 404 at 2.0.5. Every module gained a `testcontainers-` prefix. |
| `org.testcontainers.containers.PostgreSQLContainer<?>` | `org.testcontainers.postgresql.PostgreSQLContainer` — **no type parameter** | The old class still ships in the same jar as a deprecated shim, so 1.x code compiles forever. |
| `spring-boot-starter-test` alone for MockMvc | add `spring-boot-starter-webmvc-test` | `MockMvc`/`@WebMvcTest` do not resolve; looks like a broken starter. |
| `@MockBean` / `@SpyBean` | `@MockitoBean` / `@MockitoSpyBean` | Removed in 4.0 — compile error. |
| `…boot.test.autoconfigure.web.servlet.WebMvcTest` | `…boot.webmvc.test.autoconfigure.WebMvcTest` | Unresolved import whose fix is not obvious. |
| `…boot.actuate.health.HealthIndicator` | `…boot.health.contributor.HealthIndicator` | Unresolved import; people "fix" it by re-adding an old jar. |
| `com.fasterxml.jackson.databind.*` | `tools.jackson.databind.*` — **but** annotations stay `com.fasterxml.jackson.annotation` | Jackson 2 is still on the classpath, so you silently develop against the deprecated path. Two serialization defaults also flipped: alphabetical property order is now on, dates now emit ISO-8601. |
| `org.springframework.lang.Nullable` | `org.jspecify.annotations.Nullable` — TYPE_USE, so `private @Nullable String x` | Deprecated in Framework 7. A null-checker or any Kotlin can now fail the build. |
| `RestTemplate`; `Jackson2ObjectMapperBuilderCustomizer` | `RestClient`; `…boot.jackson.autoconfigure.JsonMapperBuilderCustomizer` | `RestTemplate` is deprecated in Framework 7 **at the docs level only** — no `@Deprecated`, so zero compiler warnings. The renamed customizer means your bean is simply never applied. |
| spring-retry `@Retryable(maxAttempts=…)` + `@EnableRetry` | `org.springframework.resilience.annotation.@Retryable(maxRetries=…)` + `@EnableResilientMethods` | `maxAttempts` does not exist on the new annotation. Total attempts = `1 + maxRetries`. |
| `server.error.*`, `spring.http.client.*` (singular) | `spring.web.error.*`, `spring.http.clients.*` (plural) | Silently ignored — Boot never errors on unknown properties. You leak stack traces, and outbound HTTP gets an **infinite** read timeout. |

More renames bite only in ops and container work, and are covered where you meet them:
`spring-boot-starter-aop` → `spring-boot-starter-aspectj` (a 404, so at least it fails loudly),
`server.shutdown: graceful` (now the default, so a no-op) and the probe properties in
**`references/production-ops.md`**; `-Djarmode=layertools` (**removed** in 4.1) and `-DskipTests` (no
longer skips AOT) in **`references/containerization.md`**. And do **not** build on the 4.0 shims
(`spring-boot-starter-classic`, `spring-boot-jackson2`, `spring.jackson.use-jackson2-defaults`): 4.1
already removed everything deprecated in 4.0, so they are scaffolding with a short fuse.

## Rule #1: Package layout — slice by feature, not by layer

The single-file monolith is the top failure mode in any language. In Java it appears as a
`controller/ service/ repository/ model/` tree where one behavioural change edits four directories and
every class must be `public` so nothing can be encapsulated.

```
src/main/java/com/example/ledger/
  LedgerApplication.java       # @SpringBootApplication — ROOT package, above everything
  config/                      # ResilienceConfig, TxConfig, JacksonConfig
  money/Money.java             # BigDecimal + currency, canonical scale. NO Spring imports
  payment/                     # a FEATURE slice
    PaymentController.java     #   HTTP only: bind, delegate, map to a response
    PaymentService.java        #   orchestration. NOT @Transactional if it does I/O
    PaymentTx.java             #   the @Transactional boundary — a separate bean, on purpose
    PaymentRepository.java     #   JdbcClient + raw SQL, package-private
    Payment.java               #   record
  idempotency/IdempotencyStore.java, outbox/{OutboxRepository,OutboxRelay}.java
  support/{ApiExceptionHandler,PgErrors}.java   # advice -> ProblemDetail; SQLSTATE helpers
src/main/resources/{application.yaml, db/migration/V1__baseline.sql}
```

- **`@SpringBootApplication` must sit in the root package** — its package is the implicit component-scan
  root. In the default package, `@ComponentScan` reads every class in every jar.
- **A feature slice can be package-private.** `PaymentRepository` has no business being visible to the
  outbox code, and here you can enforce that; a layer layout forces everything `public`. `money/` imports
  no Spring at all, because money arithmetic is the highest-value thing to unit-test at microsecond speed.
- **`PaymentTx` is a separate bean from `PaymentService` deliberately** — see Rule #4. Not ceremony: it
  is the only way `@Transactional` and `@Retryable` actually fire.

A class past ~200 lines has more than one responsibility; a controller method past ~20 lines is doing
service work.

## Rule #2: Generate the build file; do not hand-write it

Three coordinates a 3.x-trained model produces are now wrong. Ask the Initializr, and pin every input —
`type` defaults to **gradle-project**, `javaVersion` to **17**, and the default `bootVersion` moves when
4.2 ships.

```bash
curl -sS https://start.spring.io/starter.zip \
  -d type=maven-project -d language=java \
  -d bootVersion=4.1.0 -d javaVersion=25 -d packaging=jar \
  -d groupId=com.example -d artifactId=ledger -d packageName=com.example.ledger \
  -d dependencies=web,jdbc,validation,actuator,postgresql,flyway,testcontainers \
  -o ledger.zip && unzip -q ledger.zip -d ledger && cd ledger && ./mvnw -q clean verify
```

The short ids expand into the renamed 4.x artifacts for you — that is the whole point. Prefer **Maven**:
one unambiguous way to express a dependency, `spring-boot-starter-parent` supplies the BOM and repackage
goal for free, and SBOM tooling assumes a POM. Commit `mvnw`, `mvnw.cmd` and
`.mvn/wrapper/maven-wrapper.properties` — there is **no wrapper jar** any more
(`distributionType=only-script`), so `.gitignore` entries for it are stale.

## Rule #3: Money is `BigDecimal`, and the comparison is `compareTo`

`double` is the canonical error. The subtler one: `BigDecimal.equals` compares *scale as well as value*
while SQL `numeric` equality does not — so the same amount compares differently in Java and in the
database.

```java
// ❌ WRONG — five distinct money bugs
double amount = 19.99;                                       // binary float. never.
BigDecimal bad = new BigDecimal(0.1);                        // 0.10000000000000000555111512...
new BigDecimal("10.00").equals(new BigDecimal("10.0000"));   // false. SQL says true.
new BigDecimal("10.00").divide(new BigDecimal("3"));         // ArithmeticException
Map<BigDecimal, String> m = new TreeMap<>();                 // merges 10.00 and 10.0000
```

```java
// ✅ CORRECT — normalise scale in the constructor, then compareTo for equality.
//    Full class (plus/minus, percent, currency guard) in references/data-access.md.
public final class Money {
    public static final int SCALE = 4;                        // matches NUMERIC(19,4)
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final BigDecimal amount;
    private final String currency;

    private Money(BigDecimal amount, String currency) {
        this.amount = amount.setScale(SCALE, ROUNDING);        // canonical on the way in
        this.currency = Objects.requireNonNull(currency);
    }

    public static Money of(String amount, String currency) {   // String ctor, never double
        return new Money(new BigDecimal(amount), currency);
    }

    // Safe ONLY because every instance is scale-normalised by the constructor.
    @Override public boolean equals(Object o) {
        return o instanceof Money m && this.currency.equals(m.currency)
                && this.amount.compareTo(m.amount) == 0;
    }
    @Override public int hashCode() {
        return Objects.hash(this.currency, this.amount.stripTrailingZeros());
    }
}
```

Column type is `numeric(19,4)` — never PostgreSQL's locale-dependent `money`. Postgres rounds on insert
(ties away from zero) and pads to the declared scale, so a Java value with more precision than the column
loses it *silently*, and an over-large value raises SQLSTATE `22003`. In tests, `isEqualByComparingTo`.

## Rule #4: The transaction boundary is a *bean* boundary

`@Transactional` is a proxy. Only calls arriving **through the proxy** are intercepted. Self-invocation
and `private` methods are not — no warning, no log, no error. You get autocommit-per-statement, which for
a two-write transfer means a half-applied transfer.

```java
// ❌ WRONG — three silent failures in one class
@Service
public class TransferService {

    public void handle(TransferCommand cmd) {
        applyTransfer(cmd);              // SELF-INVOCATION: proxy bypassed, NO transaction
    }

    @Transactional
    public void applyTransfer(TransferCommand cmd) { /* runs in autocommit */ }

    @Transactional
    private void alsoBroken(TransferCommand cmd) { /* private is NEVER proxied */ }

    @Transactional                       // pins a pooled connection across 2s of network
    public void capture(UUID id, BigDecimal amt) { this.psp.capture(id, amt); }
}
```

```java
// ✅ CORRECT — the orchestrator holds no transaction; the boundary is a collaborator bean
@Service
public class TransferService {

    private final LedgerWriter ledger;   // separate bean -> the call goes through the proxy
    private final PspClient psp;         // ... constructor injection omitted

    /** NOT @Transactional. The slow part runs with no DB connection held. */
    public void capture(UUID paymentId, BigDecimal amount) {
        this.ledger.markPending(paymentId, amount);              // tx 1: short
        PspResult result = this.psp.capture(paymentId, amount);  // I/O, no connection held
        this.ledger.recordResult(paymentId, result);             // tx 2: state + outbox row
    }
}

@Component
public class LedgerWriter {
    @Transactional(timeout = 5)          // public, external call -> actually intercepted
    public void markPending(UUID id, BigDecimal amount) { /* one or two statements */ }

    @Transactional(timeout = 5)          // state change + outbox row, atomically
    public void recordResult(UUID id, PspResult r) { /* see Rule #7 */ }
}
```

Make the mistake loud, and fix the default rollback rule — **checked exceptions commit by default**, so a
`@Transactional void settle() throws SettlementException` that throws after a partial write *commits it*:

```java
import static org.springframework.transaction.annotation.RollbackOn.ALL_EXCEPTIONS;

@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(rollbackOn = ALL_EXCEPTIONS)   // 6.2+; default RUNTIME_EXCEPTIONS
public class TxConfig {
    /** Makes non-public @Transactional an ERROR rather than a silent no-op. */
    @Bean TransactionAttributeSource transactionAttributeSource() {
        // org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
        // -- NOT ...transaction.interceptor, which holds the interface and the other impls.
        return new AnnotationTransactionAttributeSource(true);   // publicMethodsOnly
    }
}
```

Read **`references/data-access.md`** before touching propagation, isolation, or retries:
`UnexpectedRollbackException`, `REQUIRES_NEW` vs `NESTED`, and why a retry must re-enter a *new*
transaction all live there.

## Rule #5: Idempotent writes — `ON CONFLICT`, two statements, READ COMMITTED

A client-supplied `Idempotency-Key` reused across retries must produce exactly one effect. The mechanism is
a **unique index plus `ON CONFLICT`**, never a Java `if (!exists)` — check-then-act in application code is
a race that two concurrent requests both win.

```java
// ✅ Two statements, deliberately. READ COMMITTED is load-bearing here.
@Transactional(isolation = Isolation.READ_COMMITTED)
public IdempotencyResult claim(String key, String requestHash) {
    Optional<UUID> inserted = this.db.sql("""
            insert into idempotency_record (key, request_hash) values (:key, :hash)
            on conflict (key) do nothing returning id
            """)
            .param("key", key).param("hash", requestHash)
            .query(UUID.class).optional();
    if (inserted.isPresent()) {
        return new IdempotencyResult(inserted.get(), true);   // we are the owner
    }
    // SEPARATE statement => fresh snapshot under READ COMMITTED => sees the winner's row
    UUID existing = this.db.sql("select id from idempotency_record where key = :key")
            .param("key", key).query(UUID.class).single();
    return new IdempotencyResult(existing, false);
}
```

```sql
-- ❌ WRONG, and it looks completely correct. Returns ZERO ROWS in the actual race: one
-- statement = one snapshot, so the SELECT branch cannot see the row the INSERT branch just
-- lost to. Reproduced on PostgreSQL 18.4 with two racing sessions.
WITH ins AS (INSERT INTO idempotency_record (key, request_hash) VALUES (:key, :hash)
             ON CONFLICT (key) DO NOTHING RETURNING id)
SELECT id, true FROM ins
UNION ALL SELECT id, false FROM idempotency_record
 WHERE key = :key AND NOT EXISTS (SELECT 1 FROM ins);
```

If you need one round trip, a no-op `DO UPDATE SET key = idempotency_record.key RETURNING id, (xmax = 0)
AS inserted` always returns exactly one row and tells you who inserted it, at the cost of a new row
version per retry. That variant is in **`references/data-access.md`**.

**Stay on READ COMMITTED for idempotency claims.** The folklore that "`DO NOTHING` never errors, it just
skips" holds only there: at REPEATABLE READ and SERIALIZABLE the *insert itself* aborts with SQLSTATE
`40001` in the same race, so raising isolation obliges you to write a retry loop you have not written.

## Rule #6: Row locks for read-modify-write; `SKIP LOCKED` for queues

```java
// ❌ LOST UPDATE: drop the `for update` below and this becomes read / compute-in-Java / blind
//    write. Two concurrent debits both read the same balance and only one of them survives.
// ✅ FOR UPDATE at READ COMMITTED re-reads the LATEST COMMITTED row once the lock is granted,
//    so the arithmetic is correct and no retry loop is needed.
@Transactional(isolation = Isolation.READ_COMMITTED, timeout = 10)
public BigDecimal debit(long accountId, BigDecimal amount) {
    this.db.sql("set local lock_timeout = '3s'").update();   // default 0 = wait forever
    BigDecimal balance = this.db.sql("select bal from account where id = :id for update")
            .param("id", accountId).query(BigDecimal.class).single();
    if (balance.compareTo(amount) < 0) throw new InsufficientFundsException(accountId);
    BigDecimal next = balance.subtract(amount);
    this.db.sql("update account set bal = :bal where id = :id")
            .param("bal", next).param("id", accountId).update();
    return next;
}
```

`REPEATABLE READ` here converts the race into SQLSTATE `40001` — correct, but it forces a retry loop.
`SERIALIZABLE` earns its keep only for **write-skew** invariants where the rows you read are not the rows
you write ("total exposure across a customer's accounts must stay under a limit"), always with an outer
retry: 40001 is the protocol, not an error.

```sql
-- ✅ Queue-shaped work: claim-and-mark atomically. Concurrent workers get DISJOINT sets.
update outbox o set published_at = now()
 where o.id in (select id from outbox where published_at is null
                 order by id for update skip locked limit :n)
returning o.id, o.aggregate_id, o.payload::text
```

`SKIP LOCKED` is documented as giving an inconsistent view — right for queue tables, wrong for any read
that must see every matching row. Never paginate a locking query with `OFFSET`: the rows `OFFSET` skipped
over get locked anyway.

## Rule #7: The transactional outbox — one transaction, at-least-once, idempotent consumer

```java
@Transactional
public void capture(UUID paymentId, BigDecimal amount) {
    this.db.sql("update payment set status='CAPTURED', amount=:amt where id=:id")
            .param("amt", amount).param("id", paymentId).update();

    // ❌ WRONG here: this.broker.send(new PaymentCaptured(paymentId));
    //    A DUAL WRITE. If the send succeeds and the commit fails (or the reverse) you
    //    have permanently diverged state and no way to reconcile it.

    // ✅ The event commits in the SAME transaction as the state change; a relay ships it.
    this.db.sql("""
            insert into outbox (aggregate_id, event_type, payload, idempotency_key)
            values (:agg, 'PaymentCaptured', cast(:payload as jsonb), :key)
            """)
            .param("agg", paymentId.toString())
            .param("payload", this.json.write(new PaymentCaptured(paymentId)))
            .param("key", paymentId)      // reused byte-identically on EVERY retry
            .update();
}
```

Exactly-once *effects* come from at-least-once delivery plus a dedupe key the consumer honours — never
from trying to make delivery exactly-once. So: the outbound `Idempotency-Key` is generated **once** and
persisted with the row (`UUID.randomUUID()` inside a retry loop duplicates postings upstream), and the
relay keeps claim + publish + mark in one transaction so a rollback simply un-claims. A plain
`@EventListener` fires *before* commit, so it can publish an event for a transaction that then rolls
back; `@TransactionalEventListener(phase = AFTER_COMMIT)` is the post-commit hook, and with no
transaction running it is skipped entirely unless `fallbackExecution = true`.

## Rule #8: The HTTP surface — validation, and exactly one error contract

```java
// ❌ DOES NOT COMPILE. The single most hallucinated Spring web API.
return ProblemDetail.forStatus(HttpStatus.NOT_FOUND).withTitle("Not Found").withDetail(msg);
```

```java
// ✅ Static factory + void setters. setProperty entries render as TOP-LEVEL JSON members,
//    which is the RFC 9457 extension-member mechanism.
ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
        "This Idempotency-Key was used with a different payload.");
pd.setTitle("Idempotency Key Reuse");
pd.setType(URI.create("https://errors.example.com/idempotency-key-reuse"));
pd.setProperty("code", "IDEMPOTENCY_KEY_REUSE");    // clients branch on this, not on `detail`
pd.setProperty("retriable", Boolean.FALSE);
```

Verified against the 7.0.8 javadoc, `ProblemDetail`'s entire surface is static `forStatus(HttpStatusCode)`,
`forStatus(int)`, `forStatusAndDetail(HttpStatusCode, String)`, then `void` setters and getters.

Four things decide whether validation works at all:

1. **`spring-boot-starter-webmvc` does not bring Bean Validation.** Without
   `spring-boot-starter-validation` there is no Hibernate Validator, so every `@Valid`/`@NotNull` is an
   inert annotation and invalid payloads reach your SQL. The answer to "`@Valid` isn't working".
2. **No class-level `@Validated` on a `@RestController`.** Since Framework 6.1 that *disables* built-in
   method validation and routes through AOP, raising raw `ConstraintViolationException` — not an
   `ErrorResponse`, so **HTTP 500**. Pre-6.1 posts still recommend it; that advice is now harmful.
   `@Validated` stays correct on `@Service`.
3. **No `Errors`/`BindingResult` parameter after a validated parameter** — it *suppresses* the exception
   and invokes your handler anyway, so a transfer with a null amount gets processed.
4. **`@Valid` is a cascade marker, not a constraint** — a nested object or collection element without it
   is never validated. `@NotEmpty List<Leg>` checks the list; `@Valid List<Leg>` descends into elements.

Map **both** validation exceptions: `MethodArgumentNotValidException` fires for `@Valid @RequestBody`
command objects, but a constraint directly on any parameter (`@RequestHeader @NotBlank String key`)
switches the whole method to `HandlerMethodValidationException`, which supersedes the per-parameter path
— so adding one `@Positive` to a query param changes the exception type for the body on the same method.
Extend `ResponseEntityExceptionHandler` for the ~20 built-in mappings; you **cannot** override its
`handleException` (it is `final`), and adding your own `@ExceptionHandler(Exception.class)` to that advice
shadows the built-ins so malformed JSON starts returning 500 instead of 400.

Idempotency-Key status mapping (source caveat at the bottom):

| Situation | Status | `retriable` |
|---|---|---|
| Header missing or malformed | 400 | false — free from Spring if you bind it as `UUID` |
| Same key, **different** payload | 422 | false — client bug; retrying is pointless |
| Same key, same payload, original in flight | 409 + `Retry-After` | true |
| Same key, same payload, completed | 200 + stored body | n/a |

Working controller + advice: **`examples/IdempotentOrderController.java`**.

## Rule #9: Prove correctness under simultaneous requests

The highest-value content here, and the thing almost no Spring tutorial covers. A concurrency test whose
green you have never watched turn red is not evidence — every mistake below yields a **passing test
against broken code**:

- **Pool too small** (Hikari default is 10): 16 workers never produce 16 simultaneous transactions.
- **One latch instead of two:** the gate opens before the late tasks are even scheduled.
- **No `Future.get()`:** `submit()` captures worker exceptions into the Future and swallows them.
- **`@Transactional` on the test:** nothing commits, so no second connection can observe anything.
- **A fake database:** `@JdbcTest` without `@AutoConfigureTestDatabase(replace = NONE)` swaps in an
  embedded DB that cannot reproduce `ON CONFLICT`/`FOR UPDATE` — same error as mocking the repository
  when the invariant is a unique index, since a mock cannot violate a constraint.

The shape: a `CountDownLatch ready(N)` each worker counts down immediately before parking, a
`CountDownLatch gate(1)` the test opens only after `ready.await(...)` returns **true**,
`future.get(timeout, unit)` on every worker, and `@RepeatedTest(10)` because races are probabilistic.
Assert **exactly one `CREATED` outcome**, not just a final row count. Runnable version plus the harness
self-check procedure: **`examples/ConcurrencyProofTest.java`**; read **`references/testing.md`** first.

## Traps that silently corrupt data

1. **`@Transactional` self-invocation, or on a `private` method** — no transaction, no warning; a two-write
   money operation partially applies. Rule #4 makes it an error instead.
2. **Swallowing an exception from an inner `@Transactional` (REQUIRED) method.** The inner scope already
   flagged the *shared* physical transaction rollback-only, so the outer commit throws
   `UnexpectedRollbackException` and **nothing** is written — including work done before and after your
   try/catch. The most confusing transaction bug in Spring. Relatedly, checked exceptions **commit** by
   default: set `rollbackOn = ALL_EXCEPTIONS` or spell out `rollbackFor = Exception.class`.
3. **`@Transactional` on a concurrency test.** Nothing commits, so the test passes against an in-memory
   `HashSet` guard and against a table with no unique index. Test-managed `@Transactional` also *ignores*
   `isolation`, `timeout`, `readOnly` and `rollbackFor`.
4. **`catch (DataIntegrityViolationException)` treated as "already exists".** `23502` (not-null) and
   `23514` (check) land there too and are client bugs, not duplicates. Catch `DuplicateKeyException`
   (23505) specifically — and with more than one unique index, unwrap to `PSQLException` and read
   `getServerErrorMessage().getConstraint()`. Never string-match the driver message.
5. **`BigDecimal.equals` / `double` money** — `"10.00".equals("10.0000")` is `false` while
   `select 1.0::numeric = 1.00::numeric` is `true`. Always `compareTo`. (Rule #3.)
6. **Connection-pool exhaustion from replica scaling.** The pool is **per replica**, so
   `maximumPoolSize × maxReplicas + admin headroom` must fit `max_connections` (run `show
   max_connections`; do not assume 100). Exceed it and an autoscaling event makes every replica fail at
   once with `53300`, fail readiness, restart, repeat. Hikari's guidance is `(cores × 2) + spindles` —
   around 9-10 for a container, not 50.
7. **Retrying inside the failed transaction.** After `40001`/`40P01` Postgres rejects every further
   statement with `25P02`, so the retry must re-enter a **new** transaction: `@Retryable` belongs on an
   outer bean calling the `@Transactional` bean. Co-locating them makes the outcome depend on interceptor
   ordering.
8. **Three exception-mapping surprises.** `lock_timeout`/`NOWAIT` raise SQLSTATE `55P03`, which Spring
   does **not** translate — so `catch (CannotAcquireLockException)` for lock contention never fires.
   `40001` maps to `CannotAcquireLockException` but deadlock `40P01` maps to its parent
   `PessimisticLockingFailureException`, so catch the parent. And `CannotGetJdbcConnectionException`
   (pool exhausted) is classified **non**-transient. Full table: `references/data-access.md`.
9. **`ex.getMessage()` in a response body** leaks SQL fragments, table names and account identifiers. Log
   server-side with the traceId and return a generic `detail`; set
   `spring.web.error.include-message=never` / `include-stacktrace=never` explicitly.

Two more bite only in ops: a **liveness** probe on `/actuator/health` restarts every replica during a
database blip, and `CREATE INDEX CONCURRENTLY` on a unique index makes `ON CONFLICT` fail mid-build.

## Reference files — what to read, and when

| File | Read before you… |
|---|---|
| **`references/data-access.md`** | write any SQL or repository; pick an isolation level; add `@Transactional`; touch propagation or retries; configure Hikari; add a Flyway migration; make a schema change that must survive a rolling deploy. Has the full dependency block and `Money` class, the `JdbcClient` API surface and its batch gap, the SQLSTATE→exception table, advisory locks, expand/contract migrations, pool budgeting. |
| **`references/testing.md`** | write any test. Has the slice decision table, Testcontainers 2.x + `@ServiceConnection` setup, the full concurrency harness and its failure modes, why *context count* is the cost you manage, ArchUnit on JUnit 6, Awaitility, coverage/mutation gates. |
| **`references/production-ops.md`** | add Actuator, probes, structured logging, metrics, config binding, secrets, scheduling, or resilience. Has the liveness/readiness split, graceful-shutdown budgeting against the platform grace period, `SmartLifecycle` phases for an outbox relay, meter-cardinality rules, `@Retryable` + circuit-breaker composition. |
| **`references/containerization.md`** | write or review a Dockerfile, `.dockerignore`, or container runtime config. Has the three-stage layered build, the removed `layertools` jarmode, container-aware JVM flags, read-only-rootfs gotchas, shell-free health probes, the AOT-cache training-run trap. |
| **`examples/`** | copy a starting point: `examples/IdempotentOrderController.java`, `examples/OrderRepository.java`, `examples/ConcurrencyProofTest.java`, `examples/Dockerfile`, `examples/application.yml`. The three Java files were **compiled** against Boot 4.1.0 / Framework 7.0.8 / JUnit 6.0.3 / Testcontainers 2.0.5 on JDK 21 — imports and signatures are verified, not merely plausible. `OrderRepository` needs the pgjdbc driver at **compile** scope, not the Initializr's `runtime`. |

## Honest about what is not verified

- **Support/EOL dates.** `spring.io/projects/spring-boot` no longer renders a support matrix and
  `spring.io/support` redirects to `enterprise.spring.io`. "Only 4.1 and 4.0 have OSS support" is
  corroborated by the Initializr offering no 3.x line, but exact dates come from a third party.
- **`Idempotency-Key` is not a standard.** `draft-ietf-httpapi-idempotency-key-header-07` (2025-10-15) is
  marked *Expired & archived* and was never an RFC. Treat the status table as well-reasoned precedent;
  real APIs diverge (Stripe uses 400 for payload mismatch, not 422). Pick one, document it, be consistent.
  `425 Too Early` is RFC 8470 TLS early-data replay protection, unrelated.
- **PostgreSQL isolation behaviour above READ COMMITTED.** The `40001` from
  `INSERT ... ON CONFLICT DO NOTHING` at REPEATABLE READ is *observed* on 18.4, not quoted from the docs —
  the isolation chapter only covers Read Committed — and depends on the timing of the concurrent commit
  relative to the snapshot.
- **Azure Container Apps specifics** (grace-period semantics, whether Kubernetes probe auto-detection
  triggers there, which `securityContext` fields are honoured) were not verified against Microsoft docs;
  the Spring-side properties and endpoint paths were.
- **Tooling on JUnit Platform 6.** PIT mutation testing is unproven (`pitest-junit5-plugin`'s table
  stops at Jupiter 5.9.2); `archunit-junit5` **cannot** run at all (Platform 1.x vs 6.x, and no
  `archunit-junit6` artifact exists); `StructuredTaskScope` is still preview in Java 25 and 26.

When a version number matters, re-fetch it: being confidently wrong about a coordinate costs an hour of
someone's day, which is worse than saying you do not know.
