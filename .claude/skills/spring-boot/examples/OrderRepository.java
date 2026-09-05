/*
 * Plain-JDBC repository for a money service: JdbcClient, INSERT ... ON CONFLICT for idempotent
 * writes, SELECT ... FOR UPDATE for read-modify-write, FOR UPDATE SKIP LOCKED for the outbox, and
 * precise unique-violation handling.
 *
 * Spring Boot 4.1.0 / Spring Framework 7.0.8 / PostgreSQL 18 / pgjdbc 42.7.11 (BOM-managed).
 *
 * All PostgreSQL behaviour asserted in the comments was reproduced with two racing psql sessions
 * against postgres:18 (18.4). Where something is observed rather than documented, it says so.
 */
package com.example.ledger.order;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.postgresql.util.PSQLException;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/*
 * Schema this repository assumes (see db/migration/V1__baseline.sql):
 *
 *   create table idempotency_record (
 *     id            uuid          primary key default gen_random_uuid(),
 *     key           uuid          not null,
 *     request_hash  text          not null,
 *     state         text          not null default 'IN_FLIGHT',   -- IN_FLIGHT | COMPLETED
 *     response_body jsonb,
 *     order_id      uuid,
 *     created_at    timestamptz   not null,
 *     constraint idempotency_record_key_uk unique (key)   -- THE guarantee. Named on purpose.
 *   );
 *
 *   create table account (
 *     account_id text          primary key,
 *     bal        numeric(19,4) not null,
 *     currency   char(3)       not null
 *   );
 *
 *   create table orders (
 *     id              uuid          primary key,
 *     idempotency_key uuid          not null,
 *     source_account  text          not null,
 *     dest_account    text          not null,
 *     amount          numeric(19,4) not null check (amount > 0),
 *     currency        char(3)       not null,
 *     status          text          not null,
 *     created_at      timestamptz   not null,
 *     constraint orders_idempotency_key_uk unique (idempotency_key)
 *   );
 *
 *   create table outbox (
 *     id              bigint generated always as identity primary key,
 *     aggregate_id    text        not null,
 *     event_type      text        not null,
 *     payload         jsonb       not null,
 *     idempotency_key uuid        not null,
 *     attempts        int         not null default 0,
 *     created_at      timestamptz not null,
 *     next_attempt_at timestamptz not null,
 *     published_at    timestamptz
 *   );
 *   create index outbox_unpublished_idx on outbox (id) where published_at is null;
 */

/** What a claim attempt discovered. */
enum ClaimOutcome { CLAIMED, IN_FLIGHT, COMPLETED }

record IdempotencyClaim(ClaimOutcome outcome, UUID recordId, String storedResponse,
                        UUID orderId) {}

record OutboxRow(long id, String aggregateId, String eventType, String payload,
                 UUID idempotencyKey, int attempts) {}

/**
 * WHY package-private: this class lives in the `order` feature slice and nothing outside it has
 * any business issuing these statements. A layer-sliced project (repository/, service/, ...)
 * forces everything public and loses that boundary entirely.
 */
@Repository
class OrderRepository {

    private final JdbcClient db;
    private final Clock clock;

    /**
     * WHY constructor-inject JdbcClient rather than JdbcClient.create(dataSource): Boot
     * auto-configures a JdbcClient bean from spring-boot-starter-jdbc plus a DataSource, and the
     * auto-configured one participates in the managed transaction and the observability wiring.
     *
     * WHY inject a Clock: `now()` in SQL is evaluated by POSTGRESQL's clock at transaction start
     * (identically for every statement in that transaction), so a frozen java.time Clock has
     * ZERO effect on it. Binding the timestamp as a parameter is what makes TTL, expiry, stale-
     * claim reaping and retry-backoff tests deterministic instead of requiring sleeps.
     */
    OrderRepository(JdbcClient db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    // =================================================================================
    // 1. Idempotency claim: unique index + ON CONFLICT. Two statements, on purpose.
    // =================================================================================

    /**
     * Claim the key, or discover who already owns it.
     *
     * WHY isolation is pinned to READ_COMMITTED and that is load-bearing:
     *
     *   Under READ COMMITTED, `ON CONFLICT DO NOTHING` blocks on the concurrent inserter, then
     *   reports `INSERT 0 0` with zero RETURNING rows. A SEPARATE SELECT in the SAME transaction
     *   DOES see the concurrently-committed row, because Read Committed starts each command with
     *   a new snapshot. That is exactly what makes the two-statement shape below correct, and it
     *   was reproduced on 18.4.
     *
     *   Under REPEATABLE READ and SERIALIZABLE the INSERT does not merely fail to see the row —
     *   the insert ITSELF aborts with SQLSTATE 40001 (observed on 18.4, raised from
     *   ExecCheckTupleVisible), taking the whole transaction with it. So the widespread folklore
     *   that "DO NOTHING never errors, it just skips" is FALSE above READ COMMITTED, and raising
     *   isolation here does not make anything safer — it obliges you to write a retry loop.
     *
     * WHY not a single-statement CTE. This looks completely correct and is broken:
     *
     *   WITH ins AS (INSERT ... ON CONFLICT (key) DO NOTHING RETURNING id)
     *   SELECT id, true  FROM ins
     *   UNION ALL
     *   SELECT id, false FROM idempotency_record
     *    WHERE key = :key AND NOT EXISTS (SELECT 1 FROM ins);
     *
     *   It returned ZERO ROWS in the actual race. One statement means one snapshot, so the SELECT
     *   branch cannot see the row the INSERT branch just lost to. The follow-up SELECT as a
     *   separate statement returned the row fine.
     *
     * If you truly need one round trip, see claimSingleStatement() below.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    IdempotencyClaim claim(UUID idempotencyKey, String requestHash) {

        Optional<UUID> inserted = this.db.sql("""
                insert into idempotency_record (key, request_hash, state, created_at)
                values (:key, :hash, 'IN_FLIGHT', :now)
                on conflict (key) do nothing
                returning id
                """)
                .param("key", idempotencyKey)
                .param("hash", requestHash)
                .param("now", nowUtc())
                .query(UUID.class)
                .optional();

        if (inserted.isPresent()) {
            return new IdempotencyClaim(ClaimOutcome.CLAIMED, inserted.get(), null, null);
        }

        // SEPARATE statement => a fresh snapshot under READ COMMITTED => sees the winner's row.
        Existing existing = this.db.sql("""
                select id, request_hash, state, response_body::text as response_body, order_id
                  from idempotency_record
                 where key = :key
                """)
                .param("key", idempotencyKey)
                .query((rs, rowNum) -> new Existing(
                        rs.getObject("id", UUID.class),
                        rs.getString("request_hash"),
                        rs.getString("state"),
                        rs.getString("response_body"),
                        rs.getObject("order_id", UUID.class)))
                .single();

        // Same key with a DIFFERENT payload is a permanent client error (422), not a replay.
        // Compare a hash, never the raw body: it is cheap, fixed-size and order-stable.
        if (!existing.requestHash().equals(requestHash)) {
            throw new IdempotencyKeyReuseException(idempotencyKey);
        }
        if ("IN_FLIGHT".equals(existing.state())) {
            return new IdempotencyClaim(ClaimOutcome.IN_FLIGHT, existing.id(), null, null);
        }
        return new IdempotencyClaim(ClaimOutcome.COMPLETED, existing.id(),
                existing.responseBody(), existing.orderId());
    }

    private record Existing(UUID id, String requestHash, String state, String responseBody,
                            UUID orderId) {}

    /**
     * Single-statement alternative. Always returns exactly one row and tells you whether YOU
     * inserted it.
     *
     * WHY the no-op DO UPDATE: RETURNING only returns rows "that were successfully inserted or
     * updated". DO NOTHING skips, so it returns nothing and cannot tell you the existing id.
     * Touching the conflict column with its own value always produces a returned row, and
     * `xmax = 0` is true only for a freshly inserted tuple. Verified on 18.4: first call
     * inserted=t, second inserted=f AND the ORIGINAL request_hash (not the proposed one).
     *
     * COST: every retry writes a new row version, so heavy retry traffic bloats this table and
     * its indexes. Keep autovacuum aggressive on it, or prefer claim() above.
     *
     * Note DO UPDATE REQUIRES a conflict target (DO NOTHING does not), and it "is not allowed to
     * affect any single existing row more than once" — so de-duplicate any multi-row VALUES list
     * on the arbiter key in Java before sending it.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    IdempotencyClaim claimSingleStatement(UUID idempotencyKey, String requestHash) {
        return this.db.sql("""
                insert into idempotency_record (key, request_hash, state, created_at)
                values (:key, :hash, 'IN_FLIGHT', :now)
                on conflict (key) do update set key = idempotency_record.key
                returning id, (xmax = 0) as inserted, request_hash, state,
                          response_body::text as response_body, order_id
                """)
                .param("key", idempotencyKey)
                .param("hash", requestHash)
                .param("now", nowUtc())
                .query((rs, rowNum) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    if (rs.getBoolean("inserted")) {
                        return new IdempotencyClaim(ClaimOutcome.CLAIMED, id, null, null);
                    }
                    if (!rs.getString("request_hash").equals(requestHash)) {
                        throw new IdempotencyKeyReuseException(idempotencyKey);
                    }
                    ClaimOutcome outcome = "IN_FLIGHT".equals(rs.getString("state"))
                            ? ClaimOutcome.IN_FLIGHT
                            : ClaimOutcome.COMPLETED;
                    return new IdempotencyClaim(outcome, id, rs.getString("response_body"),
                            rs.getObject("order_id", UUID.class));
                })
                .single();
    }

    /** Store the response so a later replay can return the SAME body with 200. */
    void completeClaim(UUID recordId, UUID orderId, String responseJson) {
        this.db.sql("""
                update idempotency_record
                   set state = 'COMPLETED', order_id = :orderId,
                       response_body = cast(:body as jsonb)
                 where id = :id
                """)
                .param("id", recordId)
                .param("orderId", orderId)
                .param("body", responseJson)
                .update();
    }

    // =================================================================================
    // 2. Read-modify-write on money: FOR UPDATE at READ COMMITTED
    // =================================================================================

    /**
     * Debit an account.
     *
     * WHY `for update` and not a plain select: without the lock this is a textbook LOST UPDATE.
     * Two concurrent debits both read the same balance, both compute balance - amount in Java,
     * and the second write clobbers the first. No error, no log — just money that reappears.
     *
     * WHY READ COMMITTED is the RIGHT level here rather than REPEATABLE READ. Verified on 18.4:
     * session B's plain SELECT saw bal=100, then `SELECT ... FOR UPDATE` blocked on session A's
     * uncommitted -30, and after A committed it returned the FRESH value 70 (PostgreSQL's
     * EvalPlanQual re-check), so B's arithmetic produced the correct 60. Under REPEATABLE READ
     * the very same statement aborted with SQLSTATE 40001. Net: READ COMMITTED + a row lock is
     * correct AND needs no retry loop; REPEATABLE READ is also correct but forces you to build
     * one.
     *
     * WHY `set local lock_timeout`: lock_timeout defaults to 0, i.e. an UNBOUNDED wait. One stuck
     * transaction would otherwise pin a Hikari connection until maxLifetime. SET LOCAL is
     * transaction-scoped, which is what you want — the docs say setting it in postgresql.conf "is
     * not recommended because it would affect all sessions". Note it is pointless if
     * statement_timeout is smaller, because that fires first.
     *
     * WHY REQUIRED (the default) and not REQUIRES_NEW: this debit must be atomic with the order
     * insert and the outbox row. It is a participant, not a boundary.
     */
    @Transactional(propagation = Propagation.REQUIRED,
                   isolation = Isolation.READ_COMMITTED,
                   timeout = 10)
    BigDecimal debit(String accountId, BigDecimal amount) {

        this.db.sql("set local lock_timeout = '3s'").update();

        // .single() throws EmptyResultDataAccessException on a miss; use .optional() when
        // "no such account" is an expected outcome rather than a programming error.
        AccountRow account = this.db.sql("""
                select account_id, bal, currency from account where account_id = :id for update
                """)
                .param("id", accountId)
                .query((rs, rowNum) -> new AccountRow(
                        rs.getString("account_id"),
                        rs.getBigDecimal("bal"),     // getBigDecimal, NEVER getDouble
                        rs.getString("currency")))
                .single();

        // compareTo, never equals: BigDecimal.equals compares SCALE as well as value, so
        // new BigDecimal("10.00").equals(new BigDecimal("10.0000")) is false, while SQL's
        // 10.00::numeric = 10.0000::numeric is true. A NUMERIC(19,4) column always round-trips
        // at scale 4, so equals() on money is a bug waiting for a code path.
        if (account.bal().compareTo(amount) < 0) {
            throw new InsufficientFundsException(accountId, account.bal(), amount);
        }

        BigDecimal next = account.bal().subtract(amount);
        this.db.sql("update account set bal = :bal where account_id = :id")
                .param("bal", next)
                .param("id", accountId)
                .update();
        return next;
    }

    private record AccountRow(String accountId, BigDecimal bal, String currency) {}

    // =================================================================================
    // 3. Order insert + outbox row, in ONE transaction
    // =================================================================================

    /**
     * WHY the outbox insert is in the same method (and therefore the same transaction) as the
     * order insert: that IS the transactional outbox. The state change and the intent to publish
     * commit atomically or not at all. A `broker.send(...)` here instead would be a DUAL WRITE —
     * if the send succeeds and the commit fails (or the reverse) you have permanently diverged
     * state and no way to reconcile it.
     *
     * WHY idempotency_key is persisted ON the outbox row: the relay must send the SAME
     * Idempotency-Key upstream on every retry attempt. Generating one inside the relay's retry
     * loop, or inside the request-building method, defeats the entire mechanism and produces
     * duplicate postings upstream. Generate once, persist, replay byte-identically.
     */
    @Transactional(propagation = Propagation.REQUIRED, timeout = 10)
    void insertOrderWithOutbox(UUID orderId, UUID idempotencyKey, String sourceAccount,
            String destAccount, BigDecimal amount, String currency, String eventPayloadJson) {

        this.db.sql("""
                insert into orders (id, idempotency_key, source_account, dest_account,
                                    amount, currency, status, created_at)
                values (:id, :key, :src, :dst, :amount, :currency, 'CONFIRMED', :now)
                """)
                .param("id", orderId)
                .param("key", idempotencyKey)
                .param("src", sourceAccount)
                .param("dst", destAccount)
                .param("amount", amount)           // BigDecimal -> NUMERIC(19,4)
                .param("currency", currency)
                .param("now", nowUtc())
                .update();

        this.db.sql("""
                insert into outbox (aggregate_id, event_type, payload, idempotency_key,
                                    created_at, next_attempt_at)
                values (:agg, 'OrderConfirmed', cast(:payload as jsonb), :key, :now, :now)
                """)
                .param("agg", orderId.toString())
                .param("payload", eventPayloadJson)
                .param("key", idempotencyKey)
                .param("now", nowUtc())
                .update();
    }

    // =================================================================================
    // 4. Outbox claiming: FOR UPDATE SKIP LOCKED
    // =================================================================================

    /**
     * Claim a batch and mark it in ONE statement, so a crash mid-batch can neither lose rows nor
     * double-claim them: a rollback simply un-claims.
     *
     * WHY SKIP LOCKED: verified on 18.4 that two concurrent workers running this exact statement
     * got DISJOINT sets (worker1 -> ids 1,2; worker2 -> ids 3,4), and a third holder's rows were
     * skipped rather than waited on. Plain FOR UPDATE would queue every worker behind worker #1,
     * giving zero parallelism, and with lock_timeout set you would get 55P03 storms instead of
     * throughput.
     *
     * Documented caveats, all worth knowing:
     *   - SKIP LOCKED "provides an inconsistent view of the data, so this is not suitable for
     *     general purpose work" — right for queue tables, wrong for a read that must see every
     *     matching row.
     *   - NOWAIT/SKIP LOCKED "apply only to the row-level lock(s)"; the ROW SHARE table-level
     *     lock is still taken normally, so neither saves you from a concurrent ALTER TABLE.
     *   - Never paginate a locking query with OFFSET: "rows skipped over by OFFSET will get
     *     locked". Keyset-paginate on the ordering column instead.
     */
    @Transactional(timeout = 30)
    List<OutboxRow> claimBatch(int batchSize) {
        return this.db.sql("""
                update outbox o
                   set published_at = :now, attempts = o.attempts + 1
                 where o.id in (
                       select id from outbox
                        where published_at is null and next_attempt_at <= :now
                        order by id
                          for update skip locked
                        limit :n
                 )
                returning o.id, o.aggregate_id, o.event_type, o.payload::text as payload,
                          o.idempotency_key, o.attempts
                """)
                .param("n", batchSize)
                .param("now", nowUtc())
                .query((rs, rowNum) -> new OutboxRow(
                        rs.getLong("id"),
                        rs.getString("aggregate_id"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getObject("idempotency_key", UUID.class),
                        rs.getInt("attempts")))
                .list();
    }

    /** Publication failed: un-claim with exponential backoff so the relay retries later. */
    @Transactional(timeout = 10)
    void releaseForRetry(long outboxId, int backoffSeconds) {
        this.db.sql("""
                update outbox
                   set published_at = null,
                       next_attempt_at = :now + make_interval(secs => :backoff)
                 where id = :id
                """)
                .param("id", outboxId)
                .param("backoff", backoffSeconds)
                .param("now", nowUtc())
                .update();
    }

    // =================================================================================
    // 5. Unique-violation handling that does not lie
    // =================================================================================

    /**
     * Insert an order, distinguishing WHICH unique index fired.
     *
     * WHY not just catch DataIntegrityViolationException and report "already exists": 23502
     * (not-null) and 23514 (check) also land there and are client bugs, not duplicates. Catch
     * DuplicateKeyException (23505) specifically — and it is emphatically NOT retryable.
     *
     * WHY unwrap to PSQLException: with more than one unique index on the table, the SQLState is
     * 23505 for all of them, so DuplicateKeyException alone cannot tell you which one blew up.
     * pgjdbc exposes the constraint NAME. Never string-match the driver message: it changes with
     * driver versions and locale.
     *
     * Also note pgjdbc throws plain PSQLException, NOT the JDBC 4 exception subclasses (pgjdbc
     * issue #963 is still open), so on PostgreSQL Spring always falls through to the SQLState
     * path and `catch (SQLIntegrityConstraintViolationException)` is dead code.
     */
    @Transactional(timeout = 10)
    void insertOrderStrict(UUID orderId, UUID idempotencyKey, BigDecimal amount,
            String currency) {
        try {
            this.db.sql("""
                    insert into orders (id, idempotency_key, source_account, dest_account,
                                        amount, currency, status, created_at)
                    values (:id, :key, 'src', 'dst', :amount, :currency, 'CONFIRMED', :now)
                    """)
                    .param("id", orderId)
                    .param("key", idempotencyKey)
                    .param("amount", amount)
                    .param("currency", currency)
                    .param("now", nowUtc())
                    .update();
        }
        catch (DuplicateKeyException ex) {
            String constraint = constraintName(ex);
            if ("orders_idempotency_key_uk".equals(constraint)) {
                // A replay reached the writer. Not an error: the caller should read and return
                // the existing order.
                throw new IdempotentRequestInFlightException(idempotencyKey);
            }
            throw ex;   // a different unique index: a genuine 409 for the client
        }
    }

    /** Which unique index blew up? The SQLState alone cannot tell you. */
    static String constraintName(Throwable t) {
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

    /**
     * lock_timeout and NOWAIT both raise SQLSTATE 55P03, and Spring does NOT translate class 55
     * — SQLStateSQLExceptionTranslator has no entry for it, so it arrives as
     * UncategorizedSQLException. Code that catches CannotAcquireLockException to handle lock
     * contention will never fire for a lock timeout. Walk the cause chain instead.
     */
    static boolean isSqlState(Throwable t, String sqlState) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException se && sqlState.equals(se.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    // =================================================================================
    // 6. Reads
    // =================================================================================

    /**
     * WHY readOnly = true, and why it is more than a hint on PostgreSQL: Spring applies
     * Connection.setReadOnly(true), and pgjdbc's readOnlyMode defaults to `transaction`, so with
     * autocommit off (the state inside a Spring transaction) the driver sends BEGIN READ ONLY. A
     * write then fails with SQLSTATE 25006 "cannot execute INSERT in a read-only transaction"
     * (reproduced on 18.4) — which is exactly the programming error you want surfaced. It also
     * lets a read/write-split proxy route this to a replica.
     *
     * It does NOT change isolation: READ COMMITTED still takes a fresh snapshot per statement.
     */
    @Transactional(readOnly = true, timeout = 5)
    Optional<OrderView> findById(UUID id) {
        return this.db.sql("""
                select id, status, amount, currency, created_at from orders where id = :id
                """)
                .param("id", id)
                .query((rs, rowNum) -> new OrderView(
                        rs.getObject("id", UUID.class),
                        rs.getString("status"),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    /** Bind the timestamp from the injected Clock, never from SQL now(). */
    private OffsetDateTime nowUtc() {
        return OffsetDateTime.ofInstant(this.clock.instant(), ZoneOffset.UTC);
    }
}

/*
 * NOT SHOWN, and deliberately so:
 *
 * BATCHES. JdbcClient has NO batch API — verified against the 7.0.8 StatementSpec javadoc, whose
 * entire surface is param/params/paramSource, query(...), update(...) plus 7.0's withFetchSize /
 * withMaxRows / withQueryTimeout. A loop of .update() calls is N round trips, not a batch. For a
 * real batch, inject NamedParameterJdbcTemplate and use
 *
 *     namedJdbc.batchUpdate(sql, SqlParameterSourceUtils.createBatch(collection))
 *
 * and add reWriteBatchedInserts=true to the pgjdbc URL, which rewrites N single-row INSERTs into
 * one multi-VALUES INSERT for a documented 2-3x gain. See references/data-access.md.
 *
 * TRANSACTION BOUNDARIES ON THE SERVICE. The @Transactional annotations here are participants
 * (REQUIRED). The real boundary belongs on a separate collaborator bean called by the
 * orchestrating service, because @Transactional is proxy-based: a self-invocation or a private
 * method is silently NOT transactional. And the orchestrator itself must NOT be @Transactional if
 * it does any I/O — a transaction pins a Hikari connection for its whole duration, so a 2s
 * downstream call inside one caps you at (poolSize / 2) transactions per second regardless of CPU.
 *
 * ADVISORY LOCKS. SELECT ... FOR UPDATE cannot lock a row that does not exist yet, so a "first
 * request for this key" race is not solvable with row locks alone. ON CONFLICT is the primary
 * answer (above); when you must guard a multi-statement critical section, use the
 * TRANSACTION-scoped pg_advisory_xact_lock(hashtextextended(key, 0)) — never the session-scoped
 * pg_advisory_lock, whose lock rides a pooled connection back into the pool on failure and blocks
 * unrelated requests forever. See references/data-access.md §5.
 */
