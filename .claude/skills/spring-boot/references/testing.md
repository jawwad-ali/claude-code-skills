# Testing: Slices, Testcontainers, Concurrency Proofs, Architecture Rules

Spring Boot 4.1.0 / Spring Framework 7.0.8 / JUnit Jupiter **6.0.3** / Testcontainers **2.0.5**.
Read this before writing any test. Boot 4 moved every slice annotation's package, removed
`@MockBean`, stopped auto-providing `MockMvc` under bare `@SpringBootTest`, and Testcontainers 2.0
renamed every module artifact — so 3.x-era test code does not compile, and the errors do not point at
the cause.

---

## 1. Boot 4 test breakages, in the order you will hit them

| Boot 3.x | Boot 4.1 | Symptom |
|---|---|---|
| `org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest` | `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` (verified, since 4.0.0) | Unresolved import |
| `org.springframework.boot.test.autoconfigure.jdbc.JdbcTest` | `org.springframework.boot.jdbc.test.autoconfigure.JdbcTest` | Unresolved import |
| `…test.autoconfigure.jdbc.AutoConfigureTestDatabase` | `org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase` | Unresolved import |
| `org.springframework.boot.test.web.client.TestRestTemplate` | `org.springframework.boot.resttestclient.TestRestTemplate` | Unresolved import |
| `@MockBean` / `@SpyBean` | `@MockitoBean` / `@MockitoSpyBean` (`org.springframework.test.context.bean.override.mockito.*`) | **Removed** in 4.0 — compile error |
| bare `@SpringBootTest` + `@Autowired MockMvc` | add `@AutoConfigureMockMvc` | `NoSuchBeanDefinitionException` at context startup that never mentions the annotation |
| `spring-boot-starter-test` alone | add `spring-boot-starter-webmvc-test` | `MockMvc` / `@WebMvcTest` do not resolve |
| `org.testcontainers:postgresql` | `org.testcontainers:testcontainers-postgresql` | 404 at 2.0.5 |
| `org.testcontainers:junit-jupiter` | `org.testcontainers:testcontainers-junit-jupiter` | 404 at 2.0.5 |
| `org.testcontainers.containers.PostgreSQLContainer<?>` | `org.testcontainers.postgresql.PostgreSQLContainer` (**no** type parameter) | Compiles as a deprecated shim, so you never notice |

`spring-boot-starter-test` still exists and is **not** deprecated: it carries JUnit Jupiter 6.0.3,
AssertJ 3.27.7, Mockito 5.23.0 + mockito-junit-jupiter, Hamcrest 3.0, **Awaitility 4.3.0**, jsonassert,
json-path, xmlunit and spring-test. What it no longer carries is the web test slice. Declare the
per-technology `*-test` starters; they depend on `starter-test` transitively, so you do not need both.

Testcontainers 2.0 also **removed JUnit 4 support entirely** and dropped module container classes'
default constructors. New in `testcontainers-junit-jupiter` 2.x:
`org.testcontainers.junit.jupiter.EnabledIfDockerAvailable`.

### The Testcontainers 2.x generic, verified

Both classes ship in `testcontainers-postgresql-2.0.5.jar`:
`org/testcontainers/postgresql/PostgreSQLContainer.class` (canonical) and
`org/testcontainers/containers/PostgreSQLContainer.class` (legacy shim). Reading the new class's
`Signature` attribute gives its supertype as
`JdbcDatabaseContainer<org.testcontainers.postgresql.PostgreSQLContainer>` — the class itself declares
**no** type parameter, so `PostgreSQLContainer<?>` will not compile against it. Every 1.x tutorial
online writes the wildcard form; that belongs to the legacy class only.

```java
// ❌ WRONG — relocated + deprecated class, and a SELF generic the new class does not have
import org.testcontainers.containers.PostgreSQLContainer;
PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:18-alpine");
```

```java
// ✅ CORRECT — Testcontainers 2.x
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
PostgreSQLContainer pg = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
```

---

## 2. Pick the smallest slice whose failure mode you care about

The cost you are managing is the **number of distinct Spring contexts**, not the number of tests.
Contexts are cached (default max 32, LRU) and the cache key includes `contextCustomizers` — which
explicitly covers `@DynamicPropertySource` methods and bean overrides (`@TestBean`, `@MockitoBean`,
`@MockitoSpyBean`) — plus `activeProfiles`, `propertySourceProperties` and `resourceBasePath`. So one
extra `properties = {...}` entry, one extra `@ActiveProfiles`, or one extra `@MockitoBean` on a single
class builds and retains a whole additional `ApplicationContext`, and with Testcontainers potentially
an extra container and an extra Flyway run. Tune with `spring.test.context.cache.maxSize`.

| Choice | Use it for | Notes |
|---|---|---|
| Plain JUnit, no Spring | money arithmetic, scale/rounding, state machines, key parsing | **Default choice.** Most tests belong here. |
| `@WebMvcTest(X.class)` + `@MockitoBean` | HTTP contract: status codes, JSON shape, bean validation, `@ControllerAdvice` mapping, header handling | No DB, no socket. Configures `MockMvc`/`MockMvcTester` for you. |
| `@JdbcTest` + `@AutoConfigureTestDatabase(replace = NONE)` + container | one SQL statement's semantics | Meta-annotated `@Transactional`, so it **rolls back** |
| `@SpringBootTest(webEnvironment = NONE)` + container, **not** `@Transactional` | service-layer transactions, outbox atomicity, **concurrency** | Fastest full-DB slice |
| `@SpringBootTest` (MOCK, the default) + `@AutoConfigureMockMvc` | full wiring including the web layer, no socket | Client and server share a thread, hence one transaction |
| `@SpringBootTest(RANDOM_PORT)` + `RestTestClient` | filters, real serialisation, probe endpoints, end-to-end retries | Client and server run in **separate** threads, so separate transactions |
| `@SpringBootTest(DEFINED_PORT)` | almost never | A fixed port makes parallel CI flaky |

Express these as **two to four abstract base classes** and let every integration test extend one
without adding its own properties. That is what keeps the context count down.

### `@JdbcTest` silently replaces your PostgreSQL

`@JdbcTest` and `@DataJdbcTest` are meta-annotated with `@AutoConfigureTestDatabase`, whose default is
to **replace** the `DataSource` with an embedded in-memory database. If your invariants live in
`INSERT ... ON CONFLICT`, `SELECT ... FOR UPDATE [SKIP LOCKED]`, `jsonb`, partial unique indexes or
advisory locks, an embedded run either rejects the SQL or — far worse — accepts a dialect-shifted
version with different locking semantics. Either add `replace = Replace.NONE` plus a container, or
skip the slice and use `@SpringBootTest`.

---

## 3. One container for the whole build

Prefer a Spring-bean-managed container over `@Testcontainers`/`@Container` static fields. Boot's docs
warn about the latter: containers "stop after tests run, which can cause issues when Spring caches the
ApplicationContext", with "no guaranteed shutdown order with Spring beans" — so a cached context can
end up pointing at a dead container. Container **beans** are created before all other beans, stopped
after all other beans are destroyed, and retained across test classes.

```java
package com.example.ledger.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * ONE shared config imported by every IT. Because every IT imports the same class, the
 * TestContext cache key is identical -> one context, one container, for the whole build.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainerConfig {

    @Bean
    @ServiceConnection            // Spring Boot 3.1+. Replaces @DynamicPropertySource entirely.
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
                .withDatabaseName("ledger")
                .withUsername("ledger")
                .withPassword("ledger");
    }
}
```

`@ServiceConnection` contributes a `JdbcConnectionDetails` bean derived from the running container and
overrides `spring.datasource.*` wholesale — no property plumbing to get wrong.

```java
// ❌ Obsolete since Boot 3.1. Delete it; @ServiceConnection does this correctly.
@DynamicPropertySource
static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", POSTGRES::getUsername);
    r.add("spring.datasource.password", POSTGRES::getPassword);
}
```

### Run the real app locally against a throwaway Postgres

The single most useful thing when developing an idempotency/outbox service, because you get real
`ON CONFLICT` and real `SELECT ... FOR UPDATE` semantics that no embedded database reproduces.

```java
package com.example.ledger;

import org.springframework.boot.SpringApplication;

public class TestLedgerApplication {

    public static void main(String[] args) {
        SpringApplication.from(LedgerApplication::main)
                .with(com.example.ledger.testsupport.PostgresTestcontainerConfig.class)
                .run(args);
    }
}
```

### Container reuse: local only, never CI

```properties
# ~/.testcontainers.properties  -- USER-level only. NEVER commit this.
testcontainers.reuse.enable=true
```

Reuse cannot be enabled from a classpath `testcontainers.properties` — by design. It also requires
`.withReuse(true)` on the container and that you never call `stop()`, so no try-with-resources and no
`@Container`. Testcontainers documents reuse as experimental; the container is not terminated, so
leftover rows and already-applied migrations survive between runs. Pair it with an explicit `TRUNCATE`
in `@AfterEach` and treat CI as always-fresh. Guard the suite with `@EnabledIfDockerAvailable`.

---

## 4. Never put `@Transactional` on a test that proves a concurrency or visibility invariant

Spring's `TransactionalTestExecutionListener` rolls the test-managed transaction back by default.
Inside one transaction a second thread on a **different** connection cannot see your uncommitted rows,
so any test of "thread B observes what thread A committed" silently tests nothing. Deferrable
constraints and commit-time triggers never fire either, and rollback erases the evidence.

Test-managed `@Transactional` supports only propagation REQUIRED and SUPPORTS, and **ignores**
`isolation`, `timeout`, `readOnly`, `rollbackFor` and `noRollbackFor` — so you cannot dial isolation
up to SERIALIZABLE from the annotation.

The mirror trap: with `RANDOM_PORT` or `DEFINED_PORT`, Boot documents that "the HTTP client and server
run in separate threads and, thus, in separate transactions". So `@Transactional` on a full-stack HTTP
test rolls back only the *client-side* transaction; the server's writes are already committed and your
`@AfterEach` must clean them up.

```java
// ❌ Green even if the guard is an in-memory HashSet, and green with NO unique index at all.
@SpringBootTest
@Transactional
class IdempotencyTest {
    @Test void secondCallIsIdempotent() {
        this.payments.submit(cmd);
        this.payments.submit(cmd);   // same connection, same tx: sees its own writes
        assertThat(count("ledger_entry")).isEqualTo(1);
    }
}
```

```java
// ✅ Commit, then verify from a SEPARATE connection. Truncate between tests.
package com.example.ledger.testsupport;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // >= the widest concurrency fan-out any subclass uses. Declared ONCE, here,
                // so it does not fork a second Spring context per test class.
                "spring.datasource.hikari.maximum-pool-size=32",
                "spring.datasource.hikari.minimum-idle=8",
                "spring.datasource.hikari.connection-timeout=30000"
        })
@Import(PostgresTestcontainerConfig.class)
public abstract class AbstractPostgresIT {

    @Autowired protected JdbcClient jdbc;
    @Autowired protected DataSource dataSource;

    // Deliberately NOT @Transactional: the invariants under test (unique index, ON CONFLICT,
    // FOR UPDATE) are only observable from another connection AFTER a commit.
    @AfterEach
    void truncateAll() {
        this.jdbc.sql("truncate table outbox, ledger_entry, idempotency_record "
                + "restart identity cascade").update();
    }
}
```

When the boundary itself is what you are testing, drive it explicitly with `TestTransaction`
(`org.springframework.test.context.transaction.TestTransaction`) rather than leaning on implicit
rollback: `TestTransaction.flagForCommit()`, `TestTransaction.end()`, `TestTransaction.isActive()`,
`TestTransaction.start()`. Verifying from a hand-opened `dataSource.getConnection()` needs pool size
>= 2 or it deadlocks.

---

## 5. The concurrency proof

Full runnable version: `../examples/ConcurrencyProofTest.java`. The mechanics that matter:

**Two latches, not one.** `for (i<N) pool.submit(() -> { gate.await(); work(); }); gate.countDown();`
is *not* simultaneous if the main thread opens the gate before all N tasks are scheduled. You need a
`ready` latch of N that each worker counts down immediately before parking on the gate, and the main
thread must assert `ready.await(30, SECONDS)` returned **true** before `gate.countDown()`.

**`Future.get()` on every worker.** `ExecutorService.submit()` captures any Throwable into the Future.
If you only `shutdown()`/`awaitTermination()` and then assert row counts, a run where 15 of 16 workers
died on a connection timeout still passes.

**Pool >= THREADS + 2.** Hikari's default `maximumPoolSize` is 10. Fire 16 concurrent requests and 6
workers block on connection acquisition until the first 10 finish, so the database never sees 16
simultaneous transactions — the race is never exercised and the guard goes untested. This is the most
common reason a "concurrency test" silently degrades into a sequential one.

**Assert the outcome mix, not just the row count.** `created == 1` and `replayed == N - 1`. A final
count of 1 is trivially true in several broken configurations.

**`@RepeatedTest(10)`.** Races are probabilistic; one green run proves nothing.

**No preemptive timeouts.** Spring documents that `assertTimeoutPreemptively` (and JUnit 4's
`@Test(timeout=)`, TestNG's `timeOut`) "invoke tests in a new thread, bypassing test-managed
transaction context and preventing proper rollback". A timed-out preemptive test leaves the
transaction open, which on PostgreSQL means a held row lock and an `idle in transaction` connection —
your next test then blocks on `FOR UPDATE` and the suite hangs instead of failing. Bound the waits
(`ready.await(30, SECONDS)`, `future.get(30, SECONDS)`) on the test thread instead.

**No `StructuredTaskScope`.** Still a preview API in Java 25 and 26, and its API changed between them
(`allSuccessfulOrThrow` return type, `anySuccessfulResultOrThrow` renamed). It would need
`--enable-preview` on both the compiler and the Surefire `argLine`. `Executors.newFixedThreadPool` is
final and correct; `newVirtualThreadPerTaskExecutor` (final since Java 21) only helps when N is in the
hundreds *and* the pool is not the bottleneck — which for a 16-way DB race it is. Note `fork()` starts
subtasks immediately, so `StructuredTaskScope` has the same "not actually simultaneous" defect as a
naive submit loop anyway.

### Prove the harness can fail

A green concurrency test you have never seen go red is not evidence. Every trap above yields a passing
test against broken code. Break the guard, watch it go red, restore it — and commit the procedure as a
comment on the test so the next person can re-verify after touching the SQL. See the `HARNESS
SELF-CHECK` block in `../examples/ConcurrencyProofTest.java`.

### Never mock what the database guarantees

```java
// ❌ Green, and it green-lights a pure check-then-act implementation that duplicates money.
@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {
    @Mock IdempotencyRecordRepository records;
    @InjectMocks IdempotencyService service;

    @Test void doesNotProcessTheSameKeyTwice() {
        when(this.records.findByKey(key)).thenReturn(Optional.empty(), Optional.of(existing()));
        this.service.process(key, cmd);
        this.service.process(key, cmd);
        verify(this.records, times(1)).save(any());
    }
}
```

A Mockito mock cannot violate a unique index, cannot serialise on a row lock, and cannot execute
`ON CONFLICT`. If the real guarantee is `unique (idempotency_key)` plus `INSERT ... ON CONFLICT DO
NOTHING`, the test must talk to the engine.

---

## 6. Controller tests: `MockMvcTester` and `@MockitoBean`

`MockMvcTester` (`org.springframework.test.web.servlet.assertj.MockMvcTester`, Framework 6.2+) is the
AssertJ-based API and is auto-configured by `@WebMvcTest` when AssertJ is present. Spring names three
concrete advantages over plain `MockMvc`: no static imports, "unresolved exceptions are handled
consistently so that your tests do not need to throw (or catch) Exception", and "by default, the
result to assert is complete whether the processing is asynchronous or not" — so async controllers need
no `asyncDispatch` dance.

```java
package com.example.ledger.payment;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;   // Boot 4.x package
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

    @Autowired MockMvcTester mvc;

    @MockitoBean PaymentService payments;   // NOT @MockBean: removed in Boot 4

    @Test
    void rejectsNegativeAmountAsProblemJson() {
        assertThat(this.mvc.post().uri("/api/v1/payments")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"sourceAccountId":"A","destinationAccountId":"B",
                         "amount":"-5.00","currency":"USD"}
                        """))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void rejectsMalformedIdempotencyKey() {
        assertThat(this.mvc.post().uri("/api/v1/payments")
                .header("Idempotency-Key", "not-a-uuid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }
}
```

Asserting the `problem+json` shape is what stops error-contract regressions. Note `@MockitoBean` is
allowed on test class **fields but not in `@Configuration` classes**, so the 3.x habit of parking
shared mocks on a `@TestConfiguration` is dead — declare them on the test class or build a composed
annotation.

For money fields, do not use `jsonPath("$.amount").value(100.00)`: that drags the value through a
`double`. Extract a typed `BigDecimal` and compare with `isEqualByComparingTo`, or serialise money as
a JSON string and assert the string.

For real-HTTP tests on Framework 7, `RestTestClient`
(`org.springframework.test.web.servlet.client.RestTestClient`) is the tool — it wraps `RestClient` and
binds four ways (`bindToServer`, `bindToController`, `bindToApplicationContext`,
`bindToRouterFunction`). Do **not** pull `spring-boot-starter-webflux` into a blocking MVC project just
to get `WebTestClient`'s fluent API; that ships Reactor and lets `@SpringBootTest` resolve a reactive
web application type you never wanted.

---

## 7. AssertJ and money

```java
BigDecimal total = this.jdbc.sql("select sum(amount) from ledger_entry where account_id = :id")
        .param("id", "acct-1").query(BigDecimal.class).single();

assertThat(total).isEqualByComparingTo("100.00");       // ✅ value equality, scale-insensitive
assertThat(total.scale()).as("minor units").isEqualTo(4);   // assert scale SEPARATELY if it is
                                                            // part of the contract

// Double-entry invariant: every transaction's postings net to exactly zero
BigDecimal net = this.jdbc.sql("""
        select coalesce(sum(amount), 0) from ledger_entry where txn_id = :id
        """).param("id", txnId).query(BigDecimal.class).single();
assertThat(net).isEqualByComparingTo(BigDecimal.ZERO);
```

```java
assertThat(total).isEqualTo(new BigDecimal("100.00"));   // ❌ "expected 100.00 but was 100.0000"
assertThat(total.doubleValue()).isEqualTo(100.00);       // ❌ reintroduces binary FP into money
```

`BigDecimal.equals` compares unscaled value **and** scale. A `NUMERIC(19,4)` column round-trips as
scale 4 while your Java literal is probably scale 2. The same trap bites `assertThat(list).contains(…)`
and map/set membership: a `TreeMap<BigDecimal, ?>` merges `1.0` and `1.00` into one key while a
`HashMap` keeps them apart — the javadoc's own API note calls the natural ordering "inconsistent with
equals".

---

## 8. Awaitility, not `Thread.sleep`

Awaitility 4.3.0 is a **direct dependency of `spring-boot-starter-test`**, so it is already on your
classpath; adding it yourself only risks a version conflict.

```java
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// 1. Eventually dispatched.
await().atMost(Duration.ofSeconds(10))
       .pollInterval(Duration.ofMillis(50))
       .untilAsserted(() -> {
           Long dispatched = this.jdbc.sql("""
                   select count(*) from outbox
                    where idempotency_key = :k and published_at is not null
                   """).param("k", key).query(Long.class).single();
           assertThat(dispatched).isEqualTo(1L);
       });

// 2. And STAYS at one effect, even though delivery is at-least-once.
//    Awaitility can only prove a condition EVENTUALLY holds; "nothing more happened" is not an
//    eventual condition, so it needs a bounded observation WINDOW.
await().pollDelay(Duration.ofSeconds(2))
       .atMost(Duration.ofSeconds(5))
       .untilAsserted(() -> assertThat(this.publisher.countFor(key)).isEqualTo(1));
```

`untilAsserted` re-runs an AssertJ block, swallowing intermediate `AssertionError`s and rethrowing the
last one at timeout — so the failure message shows the real final state instead of a bare "timed out".
Plain `until(Callable)` does **not** ignore exceptions, so a first-poll `EmptyResultDataAccessException`
errors the whole test instead of retrying; use `untilAsserted` or `ignoreExceptions()`.

`Thread.sleep(2000)` is wrong in both directions: too short and the test is flaky, too long and every
CI run pays for it.

---

## 9. Deterministic time

Inject `java.time.Clock` and freeze it with `Clock.fixed`. The half people forget: `now()` in SQL is
evaluated by **PostgreSQL's** clock at transaction start (and is identical for every statement in that
transaction), so a frozen Java clock has zero effect and a TTL/expiry test becomes untestable without
sleeping. Bind the timestamp as a parameter instead.

```java
// ❌ Uncontrollable: the DB clock decides.
"insert into idempotency_record (key, created_at) values (:key, now())"

// ✅ Controllable: the injected Clock decides.
@Repository
class IdempotencyRecordRepository {

    private final JdbcClient jdbc;
    private final Clock clock;

    IdempotencyRecordRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    Optional<Long> claim(UUID key, String endpoint) {
        return this.jdbc.sql("""
                insert into idempotency_record (idempotency_key, endpoint, created_at)
                values (:key, :endpoint, :now)
                on conflict (idempotency_key) do nothing returning id
                """)
                .param("key", key)
                .param("endpoint", endpoint)
                .param("now", OffsetDateTime.ofInstant(this.clock.instant(), ZoneOffset.UTC))
                .query(Long.class)
                .optional();
    }
}
```

Then in the test, a `@TestConfiguration` supplying `Clock.fixed(T0, ZoneOffset.UTC)` lets you step time
instead of sleeping. (Remember: an extra `@TestConfiguration` is an extra context — put it on the base
class if several tests need the same frozen clock.)

---

## 10. Do not assert on log text

Log text is not an API. A refactor from `"Payment created"` to `"payment.created"` breaks the test
without breaking anything real, log levels differ between local and CI, async appenders make capture
racy, and a log line emitted before a commit that then fails still "passes". Assert on committed state,
outbox rows, Micrometer counters, or HTTP status.

The one legitimate use of `OutputCaptureExtension`
(`org.springframework.boot.test.system.OutputCaptureExtension` + `CapturedOutput`) is **inverted**:
proving a secret is *never* logged.

```java
@ExtendWith(OutputCaptureExtension.class)
class IdempotentReplayObservabilityIT extends AbstractPostgresIT {

    @Test
    void replayIsCountedAndTheKeyIsNeverLogged(CapturedOutput output) {
        this.payments.submit(cmd);
        this.payments.submit(cmd);

        // The real assertions: committed state and a metric.
        Long rows = this.jdbc.sql("""
                select count(*) from ledger_entry where idempotency_key = :k
                """).param("k", cmd.idempotencyKey()).query(Long.class).single();
        assertThat(rows).isEqualTo(1L);

        // Legitimate log assertion: proving a secret is ABSENT.
        assertThat(output).doesNotContain(cmd.idempotencyKey().toString());
    }
}
```

---

## 11. Architecture rules with ArchUnit (plain artifact only)

ArchUnit is **1.4.2**. `archunit-junit5` **cannot** be used on Boot 4: `archunit-junit5-engine-api:1.4.2`
declares a compile dependency on `org.junit.platform:junit-platform-engine:1.14.2` (Platform 1.x) while
Boot 4.1 manages Platform 6.x, and no `archunit-junit6` artifact exists on Maven Central
(TNG/ArchUnit#1556 is open with no fix). So the `@AnalyzeClasses`/`@ArchTest` model is off the table.

Use the plain `archunit` artifact with `ClassFileImporter` + `ArchRule.check()` from an ordinary
`@Test`. `check()` throws `AssertionError`, which Surefire reports as a normal failure — so the build
breaks the moment someone imports the Postgres driver into the domain.

```java
package com.example.ledger.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureRulesTest {

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                .importPackages("com.example.ledger");
    }

    @Test
    void moneyPackageMustNotDependOnSpringOrJdbc() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.example.ledger.money..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "java.sql..", "javax.sql..",
                        "org.postgresql..", "com.zaxxer.hikari..")
                .because("money rules must be unit-testable without a container or a database");
        rule.check(production);
    }

    @Test
    void moneyMustNeverBeADouble() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.example.ledger..")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("java.lang.Double")
                .because("money is BigDecimal; a Double anywhere near it is a rounding bug");
        rule.check(production);
    }
}
```

Add the artifact with an explicit version — ArchUnit is not BOM-managed:

```xml
<dependency>
  <groupId>com.tngtech.archunit</groupId>
  <artifactId>archunit</artifactId>
  <version>1.4.2</version>
  <scope>test</scope>
</dependency>
```

The exact fluent member names above (`ImportOption.Predefined.*`, `resideOutsideOfPackages`,
`accessClassesThat`, `because`) come from recall plus the ArchUnit docs' quoted example — compile them
before relying on them.

---

## 12. Coverage and mutation gates

Gate on **mutation** score for the money and idempotency packages; use JaCoCo only as a cheap floor,
never the reverse. Line coverage on a money service is theatre: a test that calls `transfer()` and
asserts nothing scores 100%. PIT actually deletes your guard clause and asks whether any test notices.

- **JaCoCo**: `jacoco-maven-plugin` **0.8.15** (officially supports Java 26 class files; 0.8.14 stopped
  at 25). `prepare-agent` must run before Surefire (default phase `initialize`); `check` after `report`.
  Scope the rule to `com.example.ledger.money.*` and `…idempotency.*` with a **BRANCH** `COVEREDRATIO`
  minimum, not a global `BUNDLE`/`LINE` gate — a global line gate pushes people to test getters instead
  of the `ON CONFLICT` path. Never `<version>LATEST</version>`.
- **PIT**: `pitest-maven` **1.25.8** (its release note is literally "Fix BigDecimal and BigInteger
  mutators for java 25" — PIT has mutators specific to decimal arithmetic, i.e. exactly your code) with
  `pitest-junit5-plugin` **1.2.3**. Scope `<targetClasses>` narrowly, and exclude container-backed and
  `@RepeatedTest` concurrency tests via `<excludedTestClasses>` — they are far too slow to re-run once
  per mutant.

**Verify PIT runs at all before gating CI on it.** `pitest-junit5-plugin`'s own compatibility table
stops at Jupiter 5.9.2 / Platform 1.10.0-M1 and its last release (May 2025) predates JUnit 6.0 GA. Run
`./mvnw org.pitest:pitest-maven:mutationCoverage` on a scratch module first; otherwise you get a
permanently red build nobody can diagnose.

Finally, a note on JUnit-level parallelism: the technique in this file uses an **in-test**
`ExecutorService`, not JUnit parallel execution. If you ever enable
`junit.jupiter.execution.parallel.*`, verify the property names against the JUnit 6 docs first — and
note that parallel test execution plus a shared Testcontainers database is itself a flakiness source
(tests interfering through committed rows) unless every test namespaces its own data.
