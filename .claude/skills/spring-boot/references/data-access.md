# Data Access: JdbcClient, Transactions, Locks, Flyway, Hikari

Deep material for Spring Boot 4.1.0 / Spring Framework 7.0.8 with plain JDBC on PostgreSQL 18.
Read this before writing SQL, choosing an isolation level, adding `@Transactional`, sizing the pool,
or writing a migration that has to survive a rolling deploy.

Versions here are BOM-managed; never pin them. Re-read the BOM at
`https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.0/spring-boot-dependencies-4.1.0.pom`.

---

## 1. The dependency block

Generated from the live Initializr for Boot 4.1.0, not reconstructed. Three of these differ from
Boot 3.x muscle memory: the web starter is renamed, Flyway needs a starter **and** a per-database
module, and every Testcontainers module gained a prefix.

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>4.1.0</version>
  <relativePath/>
</parent>

<properties>
  <java.version>25</java.version>
</properties>

<dependencies>
  <!-- REST. NOT spring-boot-starter-web: that name is deprecated in 4.x. -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc</artifactId>
  </dependency>
  <!-- Plain JDBC: brings spring-boot-jdbc + HikariCP 7.0.2. No Hibernate. -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
  </dependency>
  <!-- REQUIRED for @Valid to do anything at all. -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
  </dependency>
  <!-- Flyway needs BOTH. The starter is new in Boot 4 (earliest version 4.0.0-M1). -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-flyway</artifactId>
  </dependency>
  <dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
  </dependency>
  <!-- The Initializr generates this at <scope>runtime</scope>, which is right ONLY if you never
       name a pgjdbc type in your own source. The moment you unwrap to PSQLException to read
       getServerErrorMessage().getConstraint() (see the SQLSTATE section below, and
       examples/OrderRepository.java) the driver must be on the COMPILE classpath, or you get:

           package org.postgresql.util does not exist
           cannot find symbol: class PSQLException

       Verified by compiling examples/OrderRepository.java both ways against Boot 4.1.0: it fails
       with runtime scope and succeeds with the scope line removed. Drop the <scope> to inherit
       the default `compile`. -->
  <dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
  </dependency>

  <!-- Tests: per-technology starters. They pull spring-boot-starter-test transitively. -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc-test</artifactId>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc-test</artifactId>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-flyway-test</artifactId>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-testcontainers</artifactId>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-postgresql</artifactId>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-junit-jupiter</artifactId>
    <scope>test</scope>
  </dependency>
</dependencies>
```

Verified on Maven Central at 4.1.0: `spring-boot-starter-webmvc` 200,
`spring-boot-starter-flyway` 200 (earliest ever version `4.0.0-M1`, so it does not exist in 3.x),
`spring-boot-starter-aop` **404** (renamed `spring-boot-starter-aspectj`),
`org.testcontainers:testcontainers-postgresql:2.0.5` 200 while `org.testcontainers:postgresql:2.0.5`
is **404**.

### Boot 4 package moves for JDBC autoconfiguration

Property **names** are unchanged (`spring.datasource.*`, `spring.datasource.hikari.*`,
`spring.jdbc.template.*`, `spring.transaction.*`, `spring.flyway.*`) — only imports break.

| Boot 3.5 | Boot 4.1 |
|---|---|
| `org.springframework.boot.autoconfigure.jdbc.DataSourceProperties` | `org.springframework.boot.jdbc.autoconfigure.DataSourceProperties` |
| `org.springframework.boot.autoconfigure.transaction.TransactionProperties` | `org.springframework.boot.transaction.autoconfigure.TransactionProperties` |
| `org.springframework.boot.autoconfigure.flyway.FlywayProperties` | `org.springframework.boot.flyway.autoconfigure.FlywayProperties` |
| `org.springframework.boot.test.autoconfigure.jdbc.JdbcTest` | `org.springframework.boot.jdbc.test.autoconfigure.JdbcTest` |

The auto-configured transaction manager is `JdbcTransactionManager` — a
`DataSourceTransactionManager` subclass that adds exception translation on commit and rollback.

---

## 2. JdbcClient is the current API; JdbcTemplate is for batches

`JdbcClient` (Spring Framework 6.1+, so always present on Boot 4.x) unifies the positional
`JdbcTemplate` and named `NamedParameterJdbcTemplate` behind one fluent API. Boot auto-configures a
`JdbcClient` bean from `spring-boot-starter-jdbc` plus a `DataSource`, so inject it rather than
calling `JdbcClient.create(dataSource)`.

Verified surface of `JdbcClient.StatementSpec` in 7.0.8: `param` (×5 overloads), `params` (×3),
`paramSource` (×2), `query()` / `query(Class)` / `query(RowMapper)` / `query(RowCallbackHandler)` /
`query(ResultSetExtractor)`, `update()`, `update(KeyHolder)`, `update(KeyHolder, String...)`, and —
new in 7.0 — `withFetchSize(int)`, `withMaxRows(int)`, `withQueryTimeout(int)`.

**There is no batch method.** A loop of `.update()` is N round trips, not a batch.

```java
package com.example.ledger.payment;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

public record Payment(UUID id, String currency, BigDecimal amount, String status) {}

@Repository
class PaymentRepository {

    private final JdbcClient db;

    PaymentRepository(JdbcClient db) {          // auto-configured bean
        this.db = db;
    }

    /** .query(Class) maps into records via SimplePropertyRowMapper. No import needed. */
    Optional<Payment> find(UUID id) {
        return this.db.sql("""
                select id, currency, amount, status from payment where id = :id
                """)
                .param("id", id)
                .query(Payment.class)
                .optional();                    // .single() throws on a miss; .optional() does not
    }

    /** Explicit RowMapper when columns and record components diverge. */
    List<Payment> byStatus(String status, int limit) {
        return this.db.sql("""
                select id, currency, amount, status from payment
                 where status = :status order by created_at limit :limit
                """)
                .param("status", status)
                .param("limit", limit)
                .query((rs, rowNum) -> new Payment(
                        rs.getObject("id", UUID.class),
                        rs.getString("currency"),
                        rs.getBigDecimal("amount"),   // never getDouble
                        rs.getString("status")))
                .list();
    }

    /** paramSource binds a whole record/bean by property name. */
    int insert(Payment p) {
        return this.db.sql("""
                insert into payment (id, currency, amount, status)
                values (:id, :currency, :amount, :status)
                """)
                .paramSource(p)
                .update();
    }
}
```

Prefer `SimplePropertyRowMapper` (`org.springframework.jdbc.core.SimplePropertyRowMapper`, since
6.1) and `SimplePropertySqlParameterSource` over `BeanPropertyRowMapper`, `DataClassRowMapper` and
`BeanPropertySqlParameterSource`. `BeanPropertyRowMapper` needs setters and a no-arg constructor,
which records do not have; the simple variants handle records, canonical constructors, bean
properties and plain fields.

### Batches: drop to NamedParameterJdbcTemplate, and set `reWriteBatchedInserts`

```java
import java.util.Collection;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSourceUtils;

class OutboxBatchWriter {

    private final NamedParameterJdbcTemplate namedJdbc;
    private final JdbcTemplate jdbc;

    OutboxBatchWriter(NamedParameterJdbcTemplate namedJdbc, JdbcTemplate jdbc) {
        this.namedJdbc = namedJdbc;
        this.jdbc = jdbc;
    }

    /** One round trip, named params, records supported. */
    int[] insertAll(Collection<Payment> payments) {
        return this.namedJdbc.batchUpdate("""
                insert into payment (id, currency, amount, status)
                values (:id, :currency, :amount, :status)
                """,
                SqlParameterSourceUtils.createBatch(payments));
    }

    /** Chunked: 500 rows per JDBC batch, per-chunk counts returned. */
    int[][] insertChunked(Collection<Payment> payments) {
        return this.jdbc.batchUpdate(
                "insert into payment (id, currency, amount, status) values (?, ?, ?, ?)",
                payments, 500,
                (ps, p) -> {
                    ps.setObject(1, p.id());
                    ps.setString(2, p.currency());
                    ps.setBigDecimal(3, p.amount());
                    ps.setString(4, p.status());
                });
    }
}
```

Add `reWriteBatchedInserts=true` to the pgjdbc URL: it rewrites N single-row INSERTs into one
multi-VALUES INSERT for a documented 2-3x gain. If a driver cannot report a count it returns `-2`,
so do not assert exact values blindly.

---

## 3. Money: the full value object

```java
package com.example.ledger.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public final class Money {

    public static final int SCALE = 4;                        // matches NUMERIC(19,4)
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final BigDecimal amount;
    private final String currency;

    private Money(BigDecimal amount, String currency) {
        this.amount = amount.setScale(SCALE, ROUNDING);
        this.currency = Objects.requireNonNull(currency, "currency");
    }

    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), currency);   // String ctor, never double
    }

    public static Money fromDb(BigDecimal fromResultSet, String currency) {
        return new Money(fromResultSet, currency);
    }

    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public BigDecimal amount() { return this.amount; }
    public String currency() { return this.currency; }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(this.amount.add(other.amount), this.currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(this.amount.subtract(other.amount), this.currency);
    }

    /** Division ALWAYS needs an explicit scale + mode, or it throws on 1/3. */
    public Money percent(BigDecimal pct) {
        return new Money(this.amount.multiply(pct)
                .divide(new BigDecimal("100"), SCALE, ROUNDING), this.currency);
    }

    public boolean isNegative() { return this.amount.signum() < 0; }

    public boolean isLessThan(Money other) {
        requireSameCurrency(other);
        return this.amount.compareTo(other.amount) < 0;       // compareTo, never equals
    }

    private void requireSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "currency mismatch: " + this.currency + " vs " + other.currency);
        }
    }

    @Override public boolean equals(Object o) {
        return o instanceof Money m && this.currency.equals(m.currency)
                && this.amount.compareTo(m.amount) == 0;
    }

    @Override public int hashCode() {
        return Objects.hash(this.currency, this.amount.stripTrailingZeros());
    }

    @Override public String toString() {
        return this.amount.toPlainString() + " " + this.currency;
    }
}
```

Observed on PostgreSQL 18.4 for `NUMERIC(19,4)`: `10.00005` stores as `10.0001`, `10.00004` as
`10.0000`, `2.5` as `2.5000` (padded, so `getBigDecimal` always hands you scale 4).
`NUMERIC(5,2)` with `12345.678` raises `22003 numeric_value_out_of_range`. Do not use PostgreSQL's
`money` type — it is locale-dependent and fixed-scale.

---

## 4. Transactions

### Propagation and the rollback-only marker

With `PROPAGATION_REQUIRED` the inner and outer scopes share **one physical transaction**; the inner
scope can only set a rollback-only marker. Swallowing its exception therefore does not contain
anything — the outer commit throws `UnexpectedRollbackException` and nothing is written.

```java
// ❌ WRONG — nothing is written, including the work outside the try/catch
@Transactional
public void process(Order o) {
    try {
        this.ledger.post(o);        // @Transactional(REQUIRED): marks the SHARED tx rollback-only
    }
    catch (RuntimeException ex) {
        log.warn("ignoring", ex);   // you did not ignore anything
    }
    this.db.sql("update orders set state='DONE' where id=:id").param("id", o.id()).update();
}   // commit() -> UnexpectedRollbackException
```

```java
// ✅ Three legitimate options
// A. Let it propagate. Usually correct for money.
@Transactional
public void process(Order o) { this.ledger.post(o); }

// B. The step must fail independently -> its OWN physical transaction (own connection:
//    beware pool pressure, the docs call this out).
@Component
class AuditWriter {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void audit(String msg) { /* own commit/rollback */ }
}

// C. Partial rollback inside ONE physical transaction -> a JDBC savepoint.
@Component
class OptionalStep {
    @Transactional(propagation = Propagation.NESTED)
    public void tryIt() { /* rolls back to a savepoint on failure */ }
}
```

Visibility rules changed in Framework 6.0: `protected` and package-visible methods **can** be
transactional for class-based (CGLIB) proxies. `private` never can, in any mode — which makes the
private case easier to get wrong by analogy. Interface-based proxies still require `public` methods
declared on the proxied interface. Also: the proxy must be fully initialised, so do not rely on
`@Transactional` from a `@PostConstruct` method.

### Programmatic boundaries

`TransactionTemplate` instances are thread-safe but their **configuration** is shared state. Make one
instance per distinct setting; never mutate one at call time.

```java
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class SettlementService {

    private final TransactionTemplate writeTx;
    private final TransactionTemplate serializableTx;
    private final JdbcClient db;

    SettlementService(PlatformTransactionManager txManager, JdbcClient db) {
        this.db = db;
        this.writeTx = new TransactionTemplate(txManager);
        this.writeTx.setTimeout(10);                               // seconds

        this.serializableTx = new TransactionTemplate(txManager);   // a SECOND instance
        this.serializableTx.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        this.serializableTx.setTimeout(10);
    }

    String settle(String key) {
        return this.writeTx.execute(status -> {
            int updated = this.db.sql("""
                    update ledger set state='SETTLED' where key=:k and state='PENDING'
                    """).param("k", key).update();
            if (updated == 0) {
                status.setRollbackOnly();      // no exception, still no commit
                return "ALREADY_SETTLED";
            }
            return "SETTLED";
        });
    }
}
```

### `readOnly = true` is genuinely enforced on PostgreSQL

The widely-repeated "Spring's `readOnly` is just a hint" is misleading here. Spring applies
`Connection.setReadOnly(true)`; pgjdbc's `readOnlyMode` defaults to `transaction`, and in that mode,
with autocommit disabled (exactly the state inside a Spring transaction), the driver sends
`BEGIN READ ONLY`. A write then fails with `25006 cannot execute INSERT in a read-only transaction`
(reproduced on 18.4). Use it on every read path — it also lets a read/write-split proxy route to a
replica. Spring's own `setEnforceReadOnly` (default false) additionally issues
`SET TRANSACTION READ ONLY`, which matters only if you also target MySQL/Oracle or set
`readOnlyMode=ignore`.

Note `readOnly` does not change isolation: READ COMMITTED still takes a fresh snapshot per
statement. For a consistent multi-statement read you need REPEATABLE READ.

### Global timeouts

```yaml
spring:
  transaction:
    default-timeout: 15s          # nothing can hang forever
    rollback-on-commit-failure: true
```

---

## 5. Isolation, upserts and locks on PostgreSQL

The behaviours below were reproduced with two racing `psql` sessions against `postgres:18` (18.4).
The isolation chapter of the PostgreSQL docs only covers Read Committed for `ON CONFLICT`, so the
higher-isolation results are **observed**, not quoted.

### Idempotency claim: `DO NOTHING`, then a separate SELECT

Under READ COMMITTED, `ON CONFLICT DO NOTHING` blocks on the concurrent inserter, then reports
`INSERT 0 0` with zero `RETURNING` rows. A **separate** SELECT in the same transaction *does* see the
concurrently-committed row, because Read Committed starts each command with a new snapshot. See
`../examples/OrderRepository.java` for the Java form.

The single-statement CTE version is broken and looks correct — one statement means one snapshot, so
the SELECT branch cannot see the row the INSERT branch just lost to. It returned **zero rows** in the
race. The safe single-statement form is a no-op `DO UPDATE`:

```sql
-- Always returns exactly one row. xmax = 0 only for a freshly inserted tuple.
-- Verified: first call inserted=t, second inserted=f AND the ORIGINAL payload.
insert into idempotency_record (key, request_hash) values (:key, :hash)
on conflict (key) do update set key = idempotency_record.key
returning id, (xmax = 0) as inserted;
```

Cost: a dead tuple per retry, so keep autovacuum aggressive on this table. Two more rules —
`DO UPDATE` **requires** a conflict target (`DO NOTHING` does not), and it "is not allowed to affect
any single existing row more than once", so de-duplicate a multi-row VALUES list on the arbiter key
in Java before sending it.

### Isolation decision table

| Need | Level | Extra work |
|---|---|---|
| Idempotent insert keyed on a unique index | READ COMMITTED | none — `ON CONFLICT` handles it |
| Read-modify-write on one row (debit a balance) | READ COMMITTED + `FOR UPDATE` | none; the lock re-reads the latest committed row |
| Consistent multi-statement read | REPEATABLE READ | retry on `40001` |
| Invariant over a **set** of rows you do not write (write skew) | SERIALIZABLE | retry on `40001`, always |

Never set isolation globally (`spring.datasource.hikari.transaction-isolation`, or
`ALTER DATABASE … SET default_transaction_isolation`). You inherit `40001` on ordinary traffic —
including on `INSERT ... ON CONFLICT DO NOTHING` — with no retry anywhere.

### Bound every lock wait

`lock_timeout` defaults to `0`, i.e. an unbounded wait. Set it per transaction with `SET LOCAL`, not
in `postgresql.conf` (the docs say that is "not recommended because it would affect all sessions").
If `statement_timeout` is smaller, `lock_timeout` never fires.

```java
package com.example.ledger.support;

import java.sql.SQLException;

public final class PgErrors {

    public static final String UNIQUE_VIOLATION      = "23505";
    public static final String NOT_NULL_VIOLATION    = "23502";
    public static final String CHECK_VIOLATION       = "23514";
    public static final String SERIALIZATION_FAILURE = "40001";
    public static final String DEADLOCK_DETECTED     = "40P01";
    public static final String IN_FAILED_SQL_TX      = "25P02";
    public static final String READ_ONLY_SQL_TX      = "25006";
    public static final String LOCK_NOT_AVAILABLE    = "55P03";
    public static final String QUERY_CANCELED        = "57014";
    public static final String TOO_MANY_CONNECTIONS  = "53300";
    public static final String NUMERIC_OUT_OF_RANGE  = "22003";

    private PgErrors() {}

    /** Spring does not translate class 55, so you must walk the cause chain yourself. */
    public static boolean isState(Throwable t, String state) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException se && state.equals(se.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
```

### Outbox claiming with `SKIP LOCKED`

Verified on 18.4: two concurrent workers running the claim statement got **disjoint** sets, and a
third holder's rows were skipped rather than waited on.

```sql
update outbox o
   set published_at = now(), attempts = attempts + 1
 where o.id in (select id from outbox
                 where published_at is null and next_attempt_at <= now()
                 order by id
                   for update skip locked
                 limit :n)
returning o.id, o.aggregate_id, o.event_type, o.payload::text, o.idempotency_key;
```

Documented caveats: `SKIP LOCKED` "provides an inconsistent view of the data, so this is not suitable
for general purpose work"; `NOWAIT`/`SKIP LOCKED` "apply only to the row-level lock(s) — the required
ROW SHARE table-level lock is still taken in the ordinary way", so neither protects you from a
concurrent `ALTER TABLE`; and with `LIMIT`, "rows skipped over by OFFSET will get locked" — so
keyset-paginate instead of using `OFFSET` with a locking clause.

### Advisory locks, when the row does not exist yet

`SELECT ... FOR UPDATE` cannot lock a row that is not there, so "first request for this key" races
are not solvable with row locks alone. Use a **transaction-scoped** advisory lock:

```java
@Transactional
public <T> T underLock(String idempotencyKey, java.util.function.Supplier<T> work) {
    this.db.sql("set local lock_timeout = '3s'").update();
    this.db.sql("select pg_advisory_xact_lock(hashtextextended(:k, 0))")
            .param("k", idempotencyKey)
            .query(Void.class)
            .optional();
    return work.get();
}
```

`pg_advisory_xact_lock` locks are "held until the current transaction ends; there is no provision for
manual release", so a crashed pod cannot leak one. The **session**-level `pg_advisory_lock` is held
until released or the session ends and it stacks (three locks need three unlocks) — from a connection
pool, a leaked session lock rides the connection back into the pool and blocks unrelated requests
forever. Use `pg_try_advisory_xact_lock` when you want to reject fast rather than queue.
`hashtextextended` needs PostgreSQL 11+.

---

## 6. Exception translation

Default translator since Framework 6.0 is `SQLExceptionSubclassTranslator`, falling back to
`SQLStateSQLExceptionTranslator`. pgjdbc throws plain `PSQLException` rather than the JDBC 4
subclasses (pgjdbc issue #963 is still open), so **on PostgreSQL you always land in the SQLState
path** and `catch (SQLIntegrityConstraintViolationException)` is dead code.

| SQLSTATE | Spring exception | Retryable? |
|---|---|---|
| `23505` unique violation | `DuplicateKeyException` | **No** |
| other class 23 (`23502`, `23514`) | `DataIntegrityViolationException` | No — client bug |
| `40001` serialization failure | `CannotAcquireLockException` | Yes |
| `40P01` deadlock detected | `PessimisticLockingFailureException` (the **parent**) | Yes |
| `57014` statement timeout | `QueryTimeoutException` | Maybe |
| `53300` too many connections | `DataAccessResourceFailureException` | Yes, but see below |
| `55P03` lock not available | **untranslated** → `UncategorizedSQLException` | Yes |

Hierarchy: `CannotAcquireLockException` ⊂ `PessimisticLockingFailureException` ⊂
`ConcurrencyFailureException` ⊂ `TransientDataAccessException`; `DuplicateKeyException` ⊂
`DataIntegrityViolationException` ⊂ `NonTransientDataAccessException`. So
`TransientDataAccessException` is your "retryable" marker — **except** that Hikari pool exhaustion
arrives as `CannotGetJdbcConnectionException`, which extends
`NonTransientDataAccessResourceException`. Handle that one explicitly.

With more than one unique index on a table, `DuplicateKeyException` cannot tell you which fired —
the SQLSTATE is `23505` for all of them. Unwrap and read the constraint name:

```java
import org.postgresql.util.PSQLException;

public static String constraintName(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
        if (c instanceof PSQLException pg && pg.getServerErrorMessage() != null) {
            String name = pg.getServerErrorMessage().getConstraint();
            if (name != null) {
                return name;
            }
        }
    }
    return "";
}
```

---

## 7. Retry the whole transaction, from an outer bean

After `40001`/`40P01` PostgreSQL rejects every further statement with `25P02
in_failed_sql_transaction`. Retrying inside the same `@Transactional` method is therefore pointless:
the retry must re-enter a **new** transaction, which means the retry advice sits on a different bean.

Framework 7 ships retry in core (verified: `@Retryable` since 7.0, `@EnableResilientMethods` since
7.0, both in `org.springframework.resilience.annotation`). Declare `@EnableResilientMethods`
explicitly — do not assume Boot auto-configures it.

```java
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.resilience.annotation.Retryable;

@Configuration(proxyBeanMethods = false)
@EnableResilientMethods
class ResilienceConfig {}

@Service
class SettlementFacade {

    private final SettlementTx tx;   // separate bean: the retry re-enters a NEW transaction

    SettlementFacade(SettlementTx tx) { this.tx = tx; }

    // Verified attribute names: maxRetries (long, default 3), delay (default 1000ms), jitter,
    // multiplier, maxDelay, timeout, includes, excludes, timeUnit. Total attempts = 1 + maxRetries.
    @Retryable(includes = PessimisticLockingFailureException.class,
               maxRetries = 4, delay = 50, jitter = 25, multiplier = 2, maxDelay = 1000)
    public void settle(String key) {
        this.tx.settleOnce(key);
    }
}

@Component
class SettlementTx {
    @Transactional(isolation = Isolation.SERIALIZABLE, timeout = 10)
    public void settleOnce(String key) { /* ... */ }
}
```

Do not co-locate `@Retryable` and `@Transactional` on one method: whether the transaction is actually
restarted then depends on interceptor ordering, which is not something to rely on. The default
`@Retryable` retries on **any** exception, so always set `includes`.

Programmatic equivalent, when you want no proxying, is `RetryPolicy.builder()` +
`new RetryTemplate(policy).invoke(...)` from `org.springframework.core.retry`.

---

## 8. HikariCP: size it small, and budget across replicas

HikariCP 7.0.2 is BOM-managed and still the default pool, bound under `spring.datasource.hikari.*`.
Hikari's own sizing wiki gives the PostgreSQL formula `connections = (core_count * 2) +
effective_spindle_count` and the axiom "You want a small pool, saturated with threads waiting for
connections". Their worked example: a 4-core box wants **9** connections and can serve thousands of
users.

```yaml
spring:
  datasource:
    url: >-
      jdbc:postgresql://${DB_HOST}:5432/${DB_NAME}?ApplicationName=ledger-api&connectTimeout=5&socketTimeout=30&tcpKeepAlive=true&reWriteBatchedInserts=true&sslmode=require
    # username/password come from SPRING_DATASOURCE_USERNAME / _PASSWORD via relaxed binding
    connection-fetch: lazy        # NEW in Boot 4.1 only. See the note below.
    hikari:
      pool-name: ledger-pool
      maximum-pool-size: 10       # PER REPLICA
      minimum-idle: 10            # == max: a fixed-size pool, no idle churn
      connection-timeout: 3000    # ms; fail fast instead of queueing behind a stuck pool
      validation-timeout: 2000    # must be < connection-timeout
      max-lifetime: 900000        # keep well under any DB/proxy idle cutoff
      keepalive-time: 120000
      leak-detection-threshold: 20000   # 0 = off. Turn on in staging.
      initialization-fail-timeout: 1    # fail fast at startup, not half-broken
  jdbc:
    template:
      query-timeout: 10s
      fetch-size: 500
```

**Do the arithmetic explicitly**, because the pool is per replica:

```
show max_connections;                        -- ASK the server. Do not assume 100.
max_connections            = 200
reserved (superuser, monitoring, migrations) =  20
max replicas               =  15  ->  15 x 10 = 150  <=  180   OK
```

If the autoscaler can reach 25 replicas, `maximum-pool-size` must come down to 7, or you need
PgBouncer in transaction mode in front of PostgreSQL. Get this wrong and an autoscaling event makes
every replica fail simultaneously with `53300`, fail readiness, restart, and repeat.

### `spring.datasource.connection-fetch: lazy` (Boot 4.1+ only)

New in 4.1: when set to `lazy`, the auto-configured pooled `DataSource` is wrapped with
`LazyConnectionDataSourceProxy` so a physical connection is taken only when a JDBC statement actually
runs. Without it, `DataSourceTransactionManager` grabs a connection the moment the transaction
starts — including for `@Transactional(readOnly = true)` methods that spend most of their time in a
serializer or an HTTP call. It defers the **first** acquisition; it does not release early. On Boot
3.x you must wrap the `DataSource` by hand, so if you migrate such code **and** set the property you
will double-wrap.

### Server-side backstops, per role

```sql
alter role app_user set statement_timeout = '10s';
alter role app_user set idle_in_transaction_session_timeout = '30s';
alter role app_user set lock_timeout = '3s';
```

All of these default to `0` (disabled). An open transaction "prevents vacuuming away recently-dead
tuples", so an idle-in-transaction session contributes to table bloat. Be careful with
`idle_session_timeout` behind a connection pooler — the docs warn about exactly that.

---

## 9. Flyway

`spring-boot-starter-flyway` is **new in Boot 4** (earliest published version `4.0.0-M1`) and it does
**not** bring `org.flywaydb:flyway-database-postgresql`, which Flyway 12 needs for PostgreSQL. Add
both or migrations fail at startup with an unsupported-database error.

Verified defaults from `FlywayProperties` (`spring.flyway.*`): `locations=classpath:db/migration`,
`sqlMigrationPrefix=V`, `repeatableSqlMigrationPrefix=R`, `sqlMigrationSeparator=__`,
`sqlMigrationSuffixes=.sql`, `table=flyway_schema_history`, `baselineOnMigrate=false`,
`outOfOrder=false`, `validateOnMigrate=true`, `cleanDisabled=true`, `mixed=false`, `group=false`,
`failOnMissingLocations=false`, `ignoreMigrationPatterns=[*:future]`,
`validateMigrationNaming=**false**`, `executeInTransaction=true`, `lockRetryCount=50`.

```yaml
spring:
  flyway:
    enabled: true
    locations: "classpath:db/migration"
    baseline-on-migrate: false        # true ONLY to adopt an existing populated schema
    out-of-order: false               # keep false: it hides merge-order mistakes
    validate-on-migrate: true
    validate-migration-naming: true   # OPT IN: catches V2_add.sql (single underscore)
    clean-disabled: true
    fail-on-missing-locations: true   # catch a mis-packaged jar at boot, not at first query
```

Naming mistakes that fail **silently** because `validate-migration-naming` defaults to false:
`V2_add.sql` (single underscore is not the separator), `v2__add.sql` (prefix is case-sensitive),
`V2__add.SQL` (suffix is case-sensitive). Turn the check on.

Never edit an applied migration. The checksum in `flyway_schema_history` will fail validation; create
a new forward migration instead. Flyway itself is concurrency-safe — it takes a lock
(`lockRetryCount` 50), so parallel replica startups serialise rather than double-apply.

### Baseline schema

```sql
-- V1__baseline.sql
create table payment (
  id         uuid          primary key,
  currency   char(3)       not null,
  amount     numeric(19,4) not null check (amount >= 0),
  status     text          not null,
  created_at timestamptz   not null default now()
);

create table idempotency_record (
  id           uuid        primary key default gen_random_uuid(),
  key          text        not null,
  request_hash text        not null,
  created_at   timestamptz not null default now(),
  constraint idempotency_record_key_uk unique (key)     -- THE guarantee. Name it.
);

create table outbox (
  id              bigint generated always as identity primary key,
  aggregate_id    text        not null,
  event_type      text        not null,
  payload         jsonb       not null,
  idempotency_key uuid        not null,
  attempts        int         not null default 0,
  created_at      timestamptz not null default now(),
  next_attempt_at timestamptz not null default now(),
  published_at    timestamptz
);

-- partial index: the only rows the relay ever scans
create index outbox_unpublished_idx on outbox (id) where published_at is null;
```

### Expand / contract — a rolling deploy runs old and new code against ONE schema

Migrations run on the new revision's startup while the old revision is still serving. So for a
window, code that does not know about your change is executing against the changed schema.

```sql
-- ===== Release N: EXPAND. Additive only; old and new code both work. =====
-- V12__add_settlement_currency_expand.sql
alter table payment add column settlement_currency char(3);   -- nullable, no rewrite
update payment set settlement_currency = currency
 where settlement_currency is null;                            -- batch this on a big table
-- New code writes BOTH columns; old code writes only `currency`.

-- Safe NOT NULL in ONE deploy: PostgreSQL 11+ does not rewrite the table for a constant
-- default, and old code omitting the column still succeeds.
alter table payment add column fee numeric(19,4) not null default 0;

-- V13__outbox_index_concurrent.sql   -- needs spring.flyway.execute-in-transaction=false
create index concurrently if not exists outbox_pub_idx
    on outbox (next_attempt_at) where published_at is null;

-- ===== Release N+1: CONTRACT. Only after every replica runs the new code. =====
alter table payment alter column settlement_currency set not null;
alter table payment drop column legacy_currency_code;
```

What breaks a rolling deploy, concretely: dropping or renaming a column the old revision still
references (`42703`); adding `NOT NULL` with no default (old INSERT gets `23502`); adding a CHECK or
UNIQUE that in-flight old-revision writes violate; `CREATE INDEX` without `CONCURRENTLY`, which
blocks writes for the whole build; `CREATE INDEX CONCURRENTLY` inside a transaction (`25001` — hence
`execute-in-transaction=false`); and a long `ALTER TABLE` holding ACCESS EXCLUSIVE while readiness
probes time out.

Renaming a column is never a single `ALTER ... RENAME`: add new, dual-write, backfill, switch reads,
drop old — three releases.

One more: while `CREATE INDEX CONCURRENTLY` or `REINDEX CONCURRENTLY` runs on a **unique** index,
`INSERT ... ON CONFLICT` "may unexpectedly fail with a unique violation" (PostgreSQL docs). That is
your idempotency path. Treat reindexes as maintenance events, not background chores.

---

## 10. Keep transactions short; never do I/O inside one

A transaction pins a Hikari connection for its whole duration. With `maximumPoolSize=10` and a 2s
downstream call inside the transaction you cap yourself at **5 transactions/second** regardless of
CPU. When the pool is exhausted, Hikari throws after `connectionTimeout`, `DataSourceUtils` wraps it
as `CannotGetJdbcConnectionException`, and because the readiness probe usually includes a DataSource
check, pool exhaustion becomes a failed probe, becomes a restart, becomes a cascade.

The orchestrator/transaction split in Rule #4 of `SKILL.md` is the fix. If a downstream call must
happen "as part of" the operation, that is what the outbox is for: commit the intent, ship it
asynchronously, and let the idempotency key make the retry safe.
