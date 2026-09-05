/*
 * Idempotent order creation: controller + request DTO + RFC 9457 error advice.
 *
 * Spring Boot 4.1.0 / Spring Framework 7.0.8.
 *
 * Everything here is deliberate. The notes marked WHY explain the parts that are easy to get
 * subtly wrong and impossible to notice afterwards.
 *
 * In a real project this file would be FOUR files in one feature slice — the single file here is
 * only so the example reads top to bottom:
 *
 *   order/OrderController.java
 *   order/CreateOrderRequest.java
 *   order/OrderView.java            (+ OrderResult)
 *   support/ApiExceptionHandler.java
 *   support/*Exception.java
 */
package com.example.ledger.order;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

// NOTE the two different packages: DuplicateKeyException is in spring-core's `dao` hierarchy,
// but CannotGetJdbcConnectionException lives in spring-jdbc. Verified by listing
// spring-jdbc-7.0.8.jar -- `org.springframework.dao.CannotGetJdbcConnectionException` does
// not exist and will not compile.
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

// =====================================================================================
// 1. The controller
// =====================================================================================

/**
 * WHY no class-level {@code @Validated}: since Spring Framework 6.1 that DISABLES Spring MVC's
 * built-in method validation and routes validation through an AOP proxy instead, which raises a
 * raw jakarta.validation.ConstraintViolationException. That exception does not implement
 * ErrorResponse, is not handled by ResponseEntityExceptionHandler, and surfaces as HTTP 500.
 * Vast amounts of pre-6.1 blog content still tells you to add it. Keep @Validated for @Service
 * beans, where the AOP proxy is the only available mechanism.
 */
@RestController
@RequestMapping(path = "/api/v1/orders", produces = MediaType.APPLICATION_JSON_VALUE)
class OrderController {

    private final OrderService orders;

    OrderController(OrderService orders) {
        this.orders = orders;
    }

    /**
     * WHY the Idempotency-Key is a UUID-typed @RequestHeader rather than a String read from
     * HttpServletRequest:
     *   - @RequestHeader is required by default, so a MISSING header yields 400 for free;
     *   - declaring the type as UUID makes Spring convert during argument resolution, so a
     *     MALFORMED header also yields 400 for free, as problem+json;
     *   - it appears in the generated OpenAPI document.
     * Reading it by hand costs all three and gives you a second, inconsistent error path.
     *
     * WHY ResponseEntity rather than a bare object plus @ResponseStatus: idempotent creation has
     * two success statuses on one handler — 201 with a Location header for the first call, 200
     * with the stored body for a replay. That is a runtime decision, so the entity's status must
     * win. (Putting @ResponseStatus on a method that returns ResponseEntity is worse than
     * useless: the entity wins and the annotation becomes misleading dead metadata that still
     * shows up in generated docs.)
     *
     * WHY there is no BindingResult/Errors parameter: adding one immediately after a validated
     * parameter SUPPRESSES the exception and invokes this method anyway. In a REST API that means
     * an order with a null amount gets processed unless you remember to check hasErrors(). It is
     * a server-rendered-form idiom; omit it.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OrderView> create(
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {

        OrderResult result = this.orders.submit(idempotencyKey, request);

        if (result.created()) {
            return ResponseEntity
                    .created(URI.create("/api/v1/orders/" + result.view().id()))
                    .body(result.view());                       // 201
        }
        return ResponseEntity.ok()
                .header("Idempotent-Replay", "true")            // tell the client it was absorbed
                .body(result.view());                           // 200, the STORED response
    }

    @GetMapping("/{id}")
    OrderView get(@PathVariable UUID id) {
        return this.orders.require(id);
    }
}

// =====================================================================================
// 2. Request / response DTOs
// =====================================================================================

/**
 * WHY a record: immutable, with a canonical constructor Jackson can bind to. That matters for
 * money DTOs — nothing can mutate an amount after validation.
 *
 * WHY BigDecimal and never double: 0.1 + 0.2 != 0.3 in binary floating point.
 *
 * WHY @Digits: it pins the scale to the column definition, so an unbounded scale cannot reach a
 * NUMERIC(19,4) column and get silently rounded by PostgreSQL on the way in.
 *
 * WHY @Valid appears on the nested types AND on the list: @Valid is only a CASCADE marker, not a
 * constraint. A nested object or collection element without it is never validated, no matter how
 * many @NotNull its own components carry. @NotEmpty validates the LIST; @Valid descends into the
 * ELEMENTS. You usually want both, as here.
 */
record CreateOrderRequest(

        @NotBlank @Size(max = 64) String sourceAccountId,
        @NotBlank @Size(max = 64) String destinationAccountId,

        @NotNull
        @DecimalMin(value = "0.00", inclusive = false)
        @Digits(integer = 15, fraction = 4)
        BigDecimal amount,

        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO-4217 code")
        String currency,

        @Valid @NotNull Metadata metadata,

        @NotEmpty @Valid List<Leg> legs) {

    record Metadata(
            @Size(max = 140) String reference,
            @NotBlank @Size(max = 16) String channel) {}

    record Leg(
            @NotBlank String accountId,
            @NotNull @Digits(integer = 15, fraction = 4) BigDecimal amount) {}
}

record OrderView(UUID id, String status, BigDecimal amount, String currency, Instant createdAt) {}

/** The service returns the OUTCOME, not just the value, so the controller can pick 201 vs 200. */
record OrderResult(OrderView view, boolean created) {}

// =====================================================================================
// 3. Domain exceptions — one per client-visible outcome
// =====================================================================================

/** Same key, DIFFERENT payload. Permanent: the client has a bug. */
final class IdempotencyKeyReuseException extends RuntimeException {

    private final UUID key;

    IdempotencyKeyReuseException(UUID key) {
        super("Idempotency-Key " + key + " reused with a different payload");
        this.key = key;
    }

    UUID key() {
        return this.key;
    }
}

/** Same key, same payload, the original is STILL RUNNING. Retryable. */
final class IdempotentRequestInFlightException extends RuntimeException {

    private final UUID key;

    IdempotentRequestInFlightException(UUID key) {
        super("Idempotency-Key " + key + " is still being processed");
        this.key = key;
    }

    UUID key() {
        return this.key;
    }
}

final class OrderNotFoundException extends RuntimeException {

    OrderNotFoundException(UUID id) {
        super("order " + id + " not found");
    }
}

final class InsufficientFundsException extends RuntimeException {

    private final String accountId;
    private final BigDecimal balance;
    private final BigDecimal requested;

    InsufficientFundsException(String accountId, BigDecimal balance, BigDecimal requested) {
        super("insufficient funds on " + accountId);
        this.accountId = accountId;
        this.balance = balance;
        this.requested = requested;
    }

    String accountId() { return this.accountId; }
    BigDecimal balance() { return this.balance; }
    BigDecimal requested() { return this.requested; }
}

// =====================================================================================
// 4. One advice, one error contract
// =====================================================================================

/**
 * WHY extend ResponseEntityExceptionHandler: it already maps ~20 Spring MVC exceptions
 * (malformed JSON, unsupported media type, missing header, type mismatch, ...) to RFC 9457
 * bodies. Without it — and without spring.mvc.problemdetails.enabled=true, which defaults to
 * FALSE — your own handlers return problem+json while every framework-raised error returns
 * Boot's legacy {timestamp,status,error,path} shape. Two incompatible error contracts in one
 * API breaks any client that parses errors generically.
 *
 * WHY there is no @ExceptionHandler(Exception.class) ABOVE the built-ins: adding a catch-all to
 * an advice that extends this class SHADOWS every built-in mapping, so malformed JSON starts
 * returning 500 instead of 400. The catch-all below is fine because it is the last resort and
 * the built-in handlers are more specific.
 *
 * You also CANNOT override handleException(Exception, WebRequest) — it is declared final.
 * Customise via the protected hooks: handleMethodArgumentNotValid,
 * handleHandlerMethodValidationException, handleExceptionInternal, createResponseEntity.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final String TYPE_BASE = "https://errors.example.com/";

    // ---------------------------------------------------------------------------------
    // Domain exceptions
    // ---------------------------------------------------------------------------------

    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail handleNotFound(OrderNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Order Not Found", ex.getMessage(),
                "ORDER_NOT_FOUND", false);
    }

    @ExceptionHandler(InsufficientFundsException.class)
    ProblemDetail handleInsufficientFunds(InsufficientFundsException ex) {
        ProblemDetail pd = problem(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient Funds",
                "The source account does not have sufficient funds.",
                "INSUFFICIENT_FUNDS", false);
        pd.setProperty("accountId", ex.accountId());
        pd.setProperty("balance", ex.balance());      // BigDecimal -> exact JSON number
        pd.setProperty("requested", ex.requested());
        return pd;
    }

    /**
     * Same key, DIFFERENT payload -> 422 per the (expired) IETF Idempotency-Key draft.
     * Retrying is pointless, so retriable=false. NOTE: Stripe's widely-copied API uses 400 here
     * instead, so the ecosystem genuinely diverges. Pick one, document it, be consistent.
     */
    @ExceptionHandler(IdempotencyKeyReuseException.class)
    ProblemDetail handleKeyReuse(IdempotencyKeyReuseException ex) {
        ProblemDetail pd = problem(HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency Key Reuse",
                "This Idempotency-Key was already used with a different request payload.",
                "IDEMPOTENCY_KEY_REUSE", false);
        pd.setProperty("idempotencyKey", ex.key().toString());
        return pd;
    }

    /** Same key, same payload, original still in flight -> 409 + Retry-After. Retryable. */
    @ExceptionHandler(IdempotentRequestInFlightException.class)
    ResponseEntity<ProblemDetail> handleInFlight(IdempotentRequestInFlightException ex) {
        ProblemDetail pd = problem(HttpStatus.CONFLICT, "Request In Progress",
                "A request with this Idempotency-Key is currently being processed.",
                "IDEMPOTENT_REQUEST_IN_FLIGHT", true);
        pd.setProperty("idempotencyKey", ex.key().toString());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(pd);
    }

    /**
     * 23505 unique violation. NOT retryable — retrying a duplicate key never helps.
     *
     * WHY this is a separate handler from DataIntegrityViolationException: 23502 (not-null) and
     * 23514 (check) also translate to DataIntegrityViolationException and are CLIENT BUGS, not
     * duplicates. Catching the parent and reporting "already exists" mislabels them. If the
     * table has more than one unique index, unwrap to PSQLException and read
     * getServerErrorMessage().getConstraint() to decide which one fired — never string-match the
     * driver message, which changes with driver versions and locale.
     */
    @ExceptionHandler(DuplicateKeyException.class)
    ProblemDetail handleDuplicate(DuplicateKeyException ex) {
        log.warn("unique violation", ex);
        return problem(HttpStatus.CONFLICT, "Duplicate Resource",
                "A resource with the same natural key already exists.", "DUPLICATE_KEY", false);
    }

    /**
     * 40001 serialization failure and 40P01 deadlock. Catching the PARENT
     * (PessimisticLockingFailureException) covers both: 40001 maps to the child
     * CannotAcquireLockException, 40P01 maps to the parent itself.
     *
     * NOTE this does NOT cover lock_timeout / NOWAIT, which raise SQLSTATE 55P03. Spring does
     * not translate class 55 at all, so those arrive as UncategorizedSQLException and must be
     * matched on the SQLState (see support/PgErrors in references/data-access.md).
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleLockContention(PessimisticLockingFailureException ex) {
        log.warn("lock contention", ex);
        ProblemDetail pd = problem(HttpStatus.SERVICE_UNAVAILABLE, "Lock Contention",
                "The resource is temporarily locked. Retry the request with the same "
                        + "Idempotency-Key.", "LOCK_CONTENTION", true);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(pd);
    }

    /**
     * Pool exhausted or the database is unreachable.
     *
     * WHY this needs its own handler: CannotGetJdbcConnectionException extends
     * DataAccessResourceFailureException extends NonTransientDataAccessResourceException — so it
     * is classified NON-transient even though pool exhaustion is the most transient failure
     * there is. Any retry policy keyed on TransientDataAccessException misses it entirely.
     */
    @ExceptionHandler(CannotGetJdbcConnectionException.class)
    ResponseEntity<ProblemDetail> handleNoConnection(CannotGetJdbcConnectionException ex) {
        log.error("could not obtain a database connection", ex);
        ProblemDetail pd = problem(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable",
                "The service is temporarily unable to process requests.",
                "DATABASE_UNAVAILABLE", true);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(pd);
    }

    /**
     * Last resort. NEVER let ex.getMessage() reach the body: it leaks SQL fragments, table names
     * and account identifiers. Log it server-side with the traceId and return the traceId so
     * support can correlate.
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                "An unexpected error occurred.", "INTERNAL_ERROR", true);
    }

    // ---------------------------------------------------------------------------------
    // Built-in override: @Valid @RequestBody failures
    // ---------------------------------------------------------------------------------

    /**
     * MethodArgumentNotValidException fires for @Valid on @RequestBody / @ModelAttribute /
     * @RequestPart command objects.
     *
     * The moment a constraint annotation sits DIRECTLY on a method parameter
     * (e.g. @RequestParam @Positive int page, or @RequestHeader @NotBlank String key), Spring
     * MVC's built-in method validation takes over and raises HandlerMethodValidationException
     * instead — and it SUPERSEDES the per-parameter path, so adding one @Positive to a query
     * param on this method would change the exception type for the body too. Map both, or half
     * your validation failures return the wrong shape.
     *
     * (The HandlerMethodValidationException override is intentionally omitted here rather than
     * guessed: its accessor set and the Visitor interface's exact methods were not verified
     * against the 4.1.0 javadoc. Check the javadoc, then add an override that walks the results
     * and produces the same "errors" array as below.)
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        List<Map<String, String>> errors = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.add(Map.of(
                    "field", fe.getField(),
                    "message", fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage()));
        }
        ex.getBindingResult().getGlobalErrors().forEach(ge -> errors.add(Map.of(
                "field", ge.getObjectName(),
                "message", ge.getDefaultMessage() == null ? "invalid" : ge.getDefaultMessage())));

        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "Validation Failed",
                "One or more fields are invalid.", "VALIDATION_FAILED", false);
        pd.setProperty("errors", errors);

        return handleExceptionInternal(ex, pd, headers, HttpStatus.BAD_REQUEST, request);
    }

    // ---------------------------------------------------------------------------------
    // Stamp every problem body, including the ~20 built-in Spring MVC ones
    // ---------------------------------------------------------------------------------

    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {

        if (body instanceof ProblemDetail pd) {
            decorate(pd, statusCode);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    // ---------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------

    /**
     * WHY this is not a fluent chain: ProblemDetail has NO builder methods. The verified 7.0.8
     * surface is the three static factories plus VOID setters — nothing returns `this`.
     * ProblemDetail.forStatus(x).withTitle(y).withDetail(z) is the single most hallucinated
     * Spring web API and it does not compile.
     *
     * setProperty entries are rendered by Jackson as TOP-LEVEL JSON members, which is exactly
     * the RFC 9457 extension-member mechanism. That is how `code` and `retriable` get out.
     */
    private ProblemDetail problem(HttpStatus status, String title, String detail,
            String code, boolean retriable) {

        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        pd.setType(URI.create(TYPE_BASE + code.toLowerCase().replace('_', '-')));
        pd.setProperty("code", code);          // clients branch on THIS, not on `detail`
        pd.setProperty("retriable", retriable);
        decorate(pd, status);
        return pd;
    }

    /**
     * WHY an explicit `retriable` boolean: HTTP status alone is ambiguous. 409 means "retry
     * shortly" for an in-flight idempotent replay but "never retry" for a duplicate natural key.
     * An at-least-once client needs a programmatic answer, and it must not be forced to regex
     * the human-readable `detail`, which you will reword next sprint.
     */
    private void decorate(ProblemDetail pd, HttpStatusCode status) {
        pd.setProperty("timestamp", Instant.now().toString());

        String traceId = MDC.get("traceId");
        if (traceId != null) {
            pd.setProperty("traceId", traceId);
        }
        if (pd.getProperties() == null || !pd.getProperties().containsKey("retriable")) {
            pd.setProperty("retriable", status.is5xxServerError());
        }
    }
}

/*
 * The published error contract, for reference:
 *
 * {
 *   "type":      "https://errors.example.com/lock-contention",
 *   "title":     "Lock Contention",
 *   "status":    503,
 *   "detail":    "The resource is temporarily locked. Retry the request ...",
 *   "instance":  "/api/v1/orders",
 *   "code":      "LOCK_CONTENTION",     <- stable; branch on this
 *   "retriable": true,                  <- machine-readable; branch on this
 *   "traceId":   "a1b2c3...",
 *   "timestamp": "2026-07-25T10:15:30Z"
 * }
 * plus header:  Retry-After: 1
 *
 * Status mapping for Idempotency-Key, per draft-ietf-httpapi-idempotency-key-header-07 (EXPIRED,
 * never an RFC — design precedent only, not a standard):
 *
 *   header missing or malformed ................ 400   (free: required UUID @RequestHeader)
 *   key reused, DIFFERENT payload .............. 422   retriable=false
 *   key reused, same payload, still running .... 409   retriable=true  + Retry-After
 *   key reused, same payload, completed ........ 200   replay the STORED response
 *
 * 425 Too Early is RFC 8470 (TLS 1.3 early-data replay protection) and has nothing to do with
 * idempotency keys. Do not use it here.
 *
 * Also set these, explicitly rather than trusting defaults (Boot 4 namespace — the Boot <=3.5
 * `server.error.*` spelling is SILENTLY IGNORED on 4.x, which would leave include-stacktrace at
 * its default and leak internals from a money service):
 *
 *   spring.web.error.include-stacktrace: never
 *   spring.web.error.include-message: never
 *   spring.web.error.include-binding-errors: never
 */
