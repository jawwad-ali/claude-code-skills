/*
 * A concurrency test that actually proves a check-then-act race is closed.
 *
 * Spring Boot 4.1.0 / JUnit Jupiter 6.0.3 / Testcontainers 2.0.5 / PostgreSQL 18.
 *
 * This is the highest-value test in a money service and the one almost no Spring tutorial covers.
 * Correctness under simultaneous requests is not something you can inspect by reading code; it has
 * to be demonstrated, and the demonstration has to be shown capable of failing.
 *
 * READ THE HARNESS SELF-CHECK BLOCK BEFORE TRUSTING A GREEN RUN. Every one of the six mistakes
 * listed there produces a PASSING test against BROKEN code.
 */
package com.example.ledger.order;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

// =====================================================================================
// Shared container config — ONE container for the whole build
// =====================================================================================

/**
 * WHY a @Bean rather than @Testcontainers + @Container static fields: Boot's docs warn that with
 * the JUnit extension "containers stop after tests run, which can cause issues when Spring caches
 * the ApplicationContext", and that there is "no guaranteed shutdown order with Spring beans" — so
 * a cached context can end up pointing at a dead container. Container BEANS are created before all
 * other beans, stopped after all other beans are destroyed, and retained across test classes.
 *
 * WHY every IT imports this SAME class: the TestContext cache key then matches, so the whole build
 * shares one context, one container and one Flyway run. Give each test class its own container
 * declaration and you get N containers, N Flyway runs and a build that takes minutes.
 *
 * WHY no @DynamicPropertySource: @ServiceConnection (Boot 3.1+) derives the JDBC URL, username and
 * password from the running container automatically. There is no property plumbing left to get
 * wrong — and every @DynamicPropertySource method is itself part of the context cache key.
 *
 * NOTE the Testcontainers 2.x import and the MISSING type parameter. The class is now
 * org.testcontainers.postgresql.PostgreSQLContainer, and reading the class file's Signature
 * attribute shows its supertype as JdbcDatabaseContainer<PostgreSQLContainer> — the class itself
 * declares NO type parameter, so `PostgreSQLContainer<?>` does not compile against it. The legacy
 * org.testcontainers.containers.PostgreSQLContainer still ships in the same jar as a deprecated
 * shim, which is exactly why 1.x code (and every tutorial's `PostgreSQLContainer<?>`) keeps
 * compiling forever.
 */
@TestConfiguration(proxyBeanMethods = false)
class PostgresTestcontainerConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
                .withDatabaseName("ledger")
                .withUsername("ledger")
                .withPassword("ledger");
    }
}

// =====================================================================================
// Base class — the ONLY place context-shaping properties are declared
// =====================================================================================

/**
 * WHY the Hikari pool is sized here and not on the test class:
 *
 *   1. It MUST be >= THREADS + 2. Hikari's default maximumPoolSize is 10. Fire 16 concurrent
 *      requests at a pool of 10 and the last 6 workers block on connection acquisition until the
 *      first 10 finish — so the DATABASE never sees 16 simultaneous transactions, the race is
 *      never exercised, and those 6 late workers legitimately observe "already exists" even
 *      against code with NO uniqueness guard at all. This is the single most common way a
 *      "concurrency test" silently degrades into a sequential test.
 *
 *   2. `properties` participates in the TestContext cache key, alongside @ActiveProfiles,
 *      @DynamicPropertySource methods and bean overrides (@MockitoBean, @TestBean, ...). Declaring
 *      it once on a shared base class keeps the context count at one. Declaring it per test class
 *      multiplies Spring contexts — and with Testcontainers, containers.
 *
 * WHY webEnvironment = NONE: no web layer means the fastest full-database slice. If you need the
 * HTTP path too, note that with RANDOM_PORT the client and server run in SEPARATE threads and thus
 * separate transactions.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.hikari.maximum-pool-size=32",
                "spring.datasource.hikari.minimum-idle=8",
                "spring.datasource.hikari.connection-timeout=30000",
                "spring.flyway.clean-disabled=true"
        })
@Import(PostgresTestcontainerConfig.class)
abstract class AbstractPostgresIT {

    @Autowired protected JdbcClient jdbc;
    @Autowired protected DataSource dataSource;

    /**
     * WHY this class is deliberately NOT annotated @Transactional:
     *
     * Spring's TransactionalTestExecutionListener rolls the test-managed transaction back by
     * default. Inside one transaction a second thread on a DIFFERENT connection cannot see your
     * uncommitted rows, so a test of "thread B observes what thread A committed" silently tests
     * NOTHING — it would pass against an in-memory HashSet guard and against a table with no
     * unique index at all. Rollback also means DEFERRABLE constraints and commit-time triggers
     * never fire, and it erases the evidence between tests.
     *
     * Test-managed @Transactional also supports only propagation REQUIRED/SUPPORTS and IGNORES
     * isolation, timeout, readOnly, rollbackFor and noRollbackFor — so you could not even dial
     * isolation up to SERIALIZABLE from the annotation.
     *
     * The price of committing is that cleanup is manual. That is the correct trade here.
     */
    @AfterEach
    void truncateAll() {
        this.jdbc.sql("""
                truncate table outbox, orders, idempotency_record, account
                restart identity cascade
                """).update();
    }

    /**
     * A genuinely SEPARATE connection, bypassing DataSourceUtils on purpose, for asserting what
     * another session can see. Requires pool size >= 2 or this deadlocks.
     */
    protected long countOnOwnConnection(String table) throws SQLException {
        try (Connection c = this.dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("select count(*) from " + table);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}

// =====================================================================================
// The proof
// =====================================================================================

class IdempotentOrderConcurrencyTest extends AbstractPostgresIT {

    private static final int THREADS = 16;

    @Autowired OrderService orders;

    /**
     * HARNESS SELF-CHECK — re-run this BY HAND whenever you change the SQL or the fan-out.
     *
     *   1. In OrderRepository.claim(), delete "on conflict (key) do nothing" and drop the
     *      idempotency_record_key_uk index in a scratch migration.
     *   2. Run: ./mvnw -Dtest=IdempotentOrderConcurrencyTest test
     *   3. EXPECT RED: the "exactly one writer wins" assertion reports created > 1.
     *   4. Restore both. EXPECT GREEN.
     *
     * If step 2 comes out GREEN then the HARNESS is broken, not the code. Check, in order:
     *   - spring.datasource.hikari.maximum-pool-size >= THREADS + 2   (see the base class)
     *   - this class is NOT annotated @Transactional
     *   - the container is real PostgreSQL, not @AutoConfigureTestDatabase's embedded DB
     *   - ready.await(...) returned TRUE before gate.countDown()
     *   - every Future.get() is called AND its outcome counted
     *   - the assertion is on the count of CREATED outcomes, not just the final row count
     *
     * Do NOT assert a specific number of duplicates under a broken guard — the count is machine-
     * and load-dependent. Assert created > 1.
     *
     * WHY @RepeatedTest and not @Test: races are probabilistic. One green run proves nothing.
     */
    @RepeatedTest(10)
    void exactlyOneWriterWinsWhenTheSameIdempotencyKeyArrivesConcurrently() throws Exception {
        seedAccount("acct-1", "1000.0000");
        seedAccount("acct-2", "0.0000");

        UUID key = UUID.randomUUID();
        CreateOrderRequest request = sampleRequest();

        // TWO latches, and both are load-bearing.
        //
        // `ready` is what makes the test actually concurrent. The naive form —
        //     for (i<N) pool.submit(() -> { gate.await(); work(); });
        //     gate.countDown();
        // is NOT simultaneous, because the main thread can open the gate before the later tasks
        // have even been scheduled. Each worker counts `ready` down immediately BEFORE parking on
        // the gate, and the main thread refuses to open the gate until all N have checked in.
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch gate = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<OrderResult>> futures = new ArrayList<>(THREADS);
        try {
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit((Callable<OrderResult>) () -> {
                    ready.countDown();
                    gate.await();                       // <-- all THREADS park here
                    return this.orders.submit(key, request);
                }));
            }

            assertThat(ready.await(30, TimeUnit.SECONDS))
                    .as("all %d workers reached the gate", THREADS)
                    .isTrue();

            gate.countDown();                           // real simultaneity starts HERE

            int created = 0;
            int replayed = 0;
            for (Future<OrderResult> f : futures) {
                // WHY get() on EVERY future: ExecutorService.submit() captures any Throwable into
                // the Future and swallows it. A test that only calls shutdown()/awaitTermination()
                // and then asserts row counts goes GREEN even when 15 of 16 workers died on a
                // connection timeout. get() rethrows, so a swallowed failure cannot become a false
                // green. The timeout also turns a deadlock into a TimeoutException instead of a
                // hung build.
                OrderResult result = f.get(30, TimeUnit.SECONDS);
                if (result.created()) {
                    created++;
                }
                else {
                    replayed++;
                }
            }

            // THE assertion. A final row count of 1 is trivially true in several broken
            // configurations; the OUTCOME MIX is what proves the guard actually arbitrated.
            assertThat(created).as("exactly one writer wins").isEqualTo(1);
            assertThat(replayed).as("everyone else replays").isEqualTo(THREADS - 1);
        }
        finally {
            // shutdownNow so a wedged worker cannot outlive the test and hold a row lock into the
            // next one.
            pool.shutdownNow();
        }

        // Committed state, verified after the fact.
        assertThat(rowCount("orders", key)).isEqualTo(1L);
        assertThat(rowCount("idempotency_record", key)).isEqualTo(1L);

        // The outbox row must exist exactly once too: at-least-once DELIVERY is fine, but
        // at-least-once ROW CREATION would mean duplicate business events.
        assertThat(rowCount("outbox", key)).isEqualTo(1L);

        // Money moved exactly once. isEqualByComparingTo, never isEqualTo: BigDecimal.equals
        // compares SCALE as well as value, and a NUMERIC(19,4) column round-trips at scale 4, so
        // isEqualTo would fail with "expected 900.00 but was 900.0000".
        assertThat(balanceOf("acct-1")).isEqualByComparingTo("900.0000");
        assertThat(balanceOf("acct-2")).isEqualByComparingTo("0.0000");   // credit is async
    }

    /**
     * The lost-update proof: N concurrent debits of the same account must sum exactly.
     *
     * Without `for update` in OrderRepository.debit(), concurrent workers all read the same
     * balance, all compute balance - amount in Java, and the last write clobbers the rest. The
     * final balance then comes out too HIGH — money appears from nowhere, with no error anywhere.
     *
     * SELF-CHECK: remove `for update` from the debit query and this must go red.
     */
    @RepeatedTest(10)
    void concurrentDebitsDoNotLoseUpdates() throws Exception {
        seedAccount("acct-1", "1000.0000");

        int debits = 10;
        BigDecimal each = new BigDecimal("10.0000");

        CountDownLatch ready = new CountDownLatch(debits);
        CountDownLatch gate = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(debits);
        List<Future<BigDecimal>> futures = new ArrayList<>(debits);
        try {
            for (int i = 0; i < debits; i++) {
                futures.add(pool.submit((Callable<BigDecimal>) () -> {
                    ready.countDown();
                    gate.await();
                    return this.orders.debitOnly("acct-1", each);
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            gate.countDown();

            for (Future<BigDecimal> f : futures) {
                f.get(30, TimeUnit.SECONDS);       // rethrow any worker failure
            }
        }
        finally {
            pool.shutdownNow();
        }

        assertThat(balanceOf("acct-1"))
                .as("1000 - (10 x 10) with no lost updates")
                .isEqualByComparingTo("900.0000");
    }

    /**
     * The outbox must become visible to another session at the SAME time as the business row —
     * that is the whole point of the transactional outbox, and it is only observable from a
     * separate connection after a commit.
     */
    @Test
    void businessRowAndOutboxRowBecomeVisibleTogether() throws Exception {
        seedAccount("acct-1", "1000.0000");

        assertThat(countOnOwnConnection("orders")).isZero();
        assertThat(countOnOwnConnection("outbox")).isZero();

        this.orders.submit(UUID.randomUUID(), sampleRequest());

        // Both appear, or neither would have. A dual write could show one without the other.
        assertThat(countOnOwnConnection("orders")).isEqualTo(1L);
        assertThat(countOnOwnConnection("outbox")).isEqualTo(1L);
    }

    // ---------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------

    private void seedAccount(String accountId, String balance) {
        this.jdbc.sql("""
                insert into account (account_id, bal, currency) values (:id, :bal, 'USD')
                on conflict (account_id) do update set bal = excluded.bal
                """)
                .param("id", accountId)
                .param("bal", new BigDecimal(balance))   // String ctor, never a double literal
                .update();
    }

    private long rowCount(String table, UUID key) {
        return this.jdbc.sql("select count(*) from " + table + " where idempotency_key = :k")
                .param("k", key)
                .query(Long.class)
                .single();
    }

    private BigDecimal balanceOf(String accountId) {
        return this.jdbc.sql("select bal from account where account_id = :id")
                .param("id", accountId)
                .query(BigDecimal.class)
                .single();
    }

    private static CreateOrderRequest sampleRequest() {
        return new CreateOrderRequest(
                "acct-1", "acct-2",
                new BigDecimal("100.0000"), "USD",
                new CreateOrderRequest.Metadata("ref-1", "api"),
                List.of(new CreateOrderRequest.Leg("acct-1", new BigDecimal("100.0000"))));
    }
}

/*
 * DELIBERATE OMISSIONS, each for a reason:
 *
 * assertTimeoutPreemptively. Spring documents that preemptive timeouts "invoke tests in a new
 * thread, bypassing test-managed transaction context and preventing proper rollback" (and names
 * JUnit's assertTimeoutPreemptively, JUnit 4's @Test(timeout=)/TimeOut, and TestNG's timeOut). A
 * timed-out preemptive test abandons an OPEN transaction, which on PostgreSQL leaves a held row
 * lock and an `idle in transaction` connection — the NEXT test then blocks on FOR UPDATE and the
 * suite HANGS instead of failing. The bounded waits above (ready.await(30, SECONDS) and
 * future.get(30, SECONDS)) give the same protection on the test thread, with a far better failure
 * message.
 *
 * StructuredTaskScope. Still a PREVIEW API in Java 25 (JEP 505) and Java 26 (JEP 525), with the
 * API still moving between previews — in JDK 26 Joiner::allSuccessfulOrThrow changed its return
 * type and anySuccessfulResultOrThrow was renamed. It would need --enable-preview on both the
 * compiler and the Surefire argLine, and it would break on the next JDK bump. It also would not
 * help: fork() starts each subtask immediately, so it has the SAME "not actually simultaneous"
 * defect as a naive submit loop unless you add the latches anyway.
 *
 * Virtual threads. Executors.newVirtualThreadPerTaskExecutor() (final since Java 21) is the right
 * tool when N is in the hundreds AND the bottleneck is genuinely not the connection pool. For a
 * 16-way database race the pool IS the ceiling, so platform threads are correct and boring.
 *
 * Mocks. A Mockito mock cannot violate a unique index, cannot serialise on a row lock, and cannot
 * execute ON CONFLICT. when(repo.findByKey(k)).thenReturn(empty(), of(existing())) goes green
 * against a pure check-then-act implementation that duplicates money under load. When the
 * guarantee lives in the schema, the test must talk to the engine.
 *
 * @JdbcTest. It is meta-annotated with @AutoConfigureTestDatabase, whose default REPLACES your
 * DataSource with an embedded in-memory database — which either rejects ON CONFLICT / FOR UPDATE
 * SKIP LOCKED / jsonb outright or, far worse, accepts a dialect-shifted version with different
 * locking semantics. It is also meta-annotated @Transactional. If you want that slice, you need
 * @AutoConfigureTestDatabase(replace = Replace.NONE) plus this container config.
 */
