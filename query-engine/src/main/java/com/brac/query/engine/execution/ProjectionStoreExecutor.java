package com.brac.query.engine.execution;

import com.brac.query.engine.error.EngineException;
import com.brac.query.engine.model.ParamSpec;
import com.brac.query.engine.model.ParamType;
import com.brac.query.engine.model.ValidatedQuery;
import io.r2dbc.spi.ColumnMetadata;
import io.r2dbc.spi.R2dbcException;
import io.r2dbc.spi.R2dbcTimeoutException;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Runs a {@link ValidatedQuery}'s sql template against the domain's projection tables, reactively
 * through {@link DatabaseClient} (R2DBC) on the dedicated projection pool.
 *
 * <p>{@link #stream} is the primitive: a {@code Flux} of rows read through a database cursor. The
 * statement's {@code fetchSize} makes the driver fetch that many rows per round trip and the next
 * batch only when downstream asks for more, so a slow consumer slows the database read instead of
 * the rows piling up in memory. {@link #execute} is the bounded variant for callers that need a
 * list (simulate, JSON mode): it stops reading one row past its limit, which cancels the cursor.
 *
 * <p>Each run is wrapped in a read-only transaction ({@link ProjectionDatabaseClient#inReadOnlyTransaction}),
 * so a statement that would write fails in Postgres whatever the SQL says. That transaction is held
 * for as long as the stream is open, the same time the cursor already held the connection.
 *
 * <p>The template's {@code :paramName} placeholders are parsed and bound by name through the
 * driver's native parameter binding (translated to Postgres {@code $N} markers), so argument
 * values are never concatenated into the SQL. The bound names are exactly
 * {@link ValidatedQuery#params()}, the tokens the validator found in the sql.
 */
@Component
public class ProjectionStoreExecutor {

    private static final Logger log = LoggerFactory.getLogger(ProjectionStoreExecutor.class);

    private final ProjectionDatabaseClient projectionDatabaseClient;

    public ProjectionStoreExecutor(ProjectionDatabaseClient projectionDatabaseClient) {
        this.projectionDatabaseClient = projectionDatabaseClient;
    }

    private DatabaseClient.GenericExecuteSpec bindSpec(String queryName, ValidatedQuery query, Map<String, Object> args) {
        DatabaseClient.GenericExecuteSpec spec = projectionDatabaseClient.databaseClient().sql(query.sql());
        for (ParamSpec paramSpec : query.params()) {
            Object value = coerce(queryName, paramSpec, args.get(paramSpec.name()));
            spec = (value == null)
                    ? spec.bindNull(paramSpec.name(), javaType(paramSpec.type()))
                    : spec.bind(paramSpec.name(), value);
        }
        return spec;
    }

    /**
     * Streams the query's rows. Nothing runs until subscribed. Errors are
     * {@link EngineException} (bad argument values) or {@link ProjectionException} (database
     * failures); cancelling the subscription closes the cursor and returns the connection.
     */
    public Flux<Map<String, Object>> stream(String queryName, ValidatedQuery query, Map<String, Object> args, int fetchSize) {
        return Flux.defer(() -> {
            DatabaseClient.GenericExecuteSpec spec = bindSpec(queryName, query, args);
            if (fetchSize > 0) {
                spec = spec.filter(statement -> statement.fetchSize(fetchSize));
            }
            // The author's SQL only ever runs inside BEGIN READ ONLY (see ProjectionDatabaseClient).
            return projectionDatabaseClient.inReadOnlyTransaction(spec.map(ProjectionStoreExecutor::toRow).all());
        }).onErrorMap(ProjectionStoreExecutor::translate);
    }

    /**
     * Executes the query reactively using R2DBC without a database cursor or streaming,
     * collecting all rows into a list. Runs inside BEGIN READ ONLY.
     */
    public Mono<List<Map<String, Object>>> executeDirect(String queryName, ValidatedQuery query, Map<String, Object> args) {
        return Mono.defer(() -> {
            DatabaseClient.GenericExecuteSpec spec = bindSpec(queryName, query, args);
            return projectionDatabaseClient.inReadOnlyTransaction(spec.map(ProjectionStoreExecutor::toRow).all())
                    .collectList();
        }).onErrorMap(ProjectionStoreExecutor::translate);
    }

    /**
     * Reads at most {@code maxRows} rows. Reading stops one row past the limit, which is how
     * {@link Result#truncated()} is known without fetching the rest of the result.
     */
    public Mono<Result> execute(String queryName, ValidatedQuery query, Map<String, Object> args, int maxRows, int fetchSize) {
        return Mono.defer(() -> {
            long start = System.nanoTime();
            return stream(queryName, query, args, Math.min(fetchSize, maxRows + 1))
                    .take(maxRows + 1L)
                    .collectList()
                    .map(rows -> {
                        long latencyMs = Math.max(1, (System.nanoTime() - start) / 1_000_000);
                        boolean truncated = rows.size() > maxRows;
                        return new Result(truncated ? List.copyOf(rows.subList(0, maxRows)) : rows, latencyMs, truncated);
                    });
        });
    }

    /**
     * Asks Postgres whether it accepts the SQL, without running it: the statement is planned with
     * {@code EXPLAIN} (no {@code ANALYZE}), so no rows are read, but syntax errors, unknown tables
     * and columns, unknown functions and type mismatches are all reported. Params are bound with a
     * neutral sample value of their declared type, so a type that does not fit the SQL (a String
     * compared with an integer column, say) is reported too, exactly as a real run would.
     *
     * <p>The result is tri-state: accepted, rejected (with the database's reason), or unavailable
     * (the database was busy, timed out or could not be reached, so nothing is known). Unavailable
     * must not make a draft look invalid, but it must not look checked either; the caller says so.
     */
    public Mono<CheckResult> check(String queryName, ValidatedQuery query) {
        return Mono.defer(() -> {
            DatabaseClient.GenericExecuteSpec spec = projectionDatabaseClient.databaseClient().sql("EXPLAIN " + query.sql());
            for (ParamSpec paramSpec : query.params()) {
                spec = spec.bind(paramSpec.name(), sampleValue(paramSpec.type()));
            }
            return projectionDatabaseClient.inReadOnlyTransaction(spec.fetch().all()).then(Mono.just(CheckResult.accepted()));
        }).onErrorResume(error -> {
            Throwable translated = translate(error);
            if (translated instanceof ProjectionException problem && problem.invalidSql()) {
                return Mono.just(CheckResult.rejected(problem));
            }
            log.warn("Could not check the SQL of '{}' against the database, so it is saved unchecked: {}", queryName, translated.getMessage());
            return Mono.just(CheckResult.unavailable());
        });
    }

    private static Object sampleValue(ParamType type) {
        return switch (type) {
            case STRING -> "";
            case INT -> 0;
            case LONG -> 0L;
            case DECIMAL -> java.math.BigDecimal.ZERO;
            case BOOLEAN -> false;
            case DATE -> LocalDate.EPOCH;
            case DATETIME -> OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC);
        };
    }

    /** Converts a declared, typed argument into a value the R2DBC driver can bind directly. */
    private Object coerce(String queryName, ParamSpec spec, Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return switch (spec.type()) {
                case STRING -> raw.toString();
                case INT -> raw instanceof Number n ? n.intValue() : Integer.parseInt(raw.toString());
                case LONG -> raw instanceof Number n ? n.longValue() : Long.parseLong(raw.toString());
                case DECIMAL -> raw instanceof java.math.BigDecimal bd ? bd : new java.math.BigDecimal(raw.toString());
                case BOOLEAN -> raw instanceof Boolean b ? b : Boolean.parseBoolean(raw.toString());
                case DATE -> LocalDate.parse(raw.toString());
                case DATETIME -> OffsetDateTime.ofInstant(Instant.parse(raw.toString()), ZoneOffset.UTC);
            };
        } catch (DateTimeParseException | NumberFormatException e) {
            throw new EngineException("INVALID_ARGUMENT", HttpStatus.BAD_REQUEST, queryName,
                    spec.name() + " is not a valid " + spec.type() + " value: '" + raw + "'");
        }
    }

    /** Java type used for {@code bindNull} so the driver knows the column type for a null argument. */
    private Class<?> javaType(ParamType type) {
        return switch (type) {
            case STRING -> String.class;
            case INT -> Integer.class;
            case LONG -> Long.class;
            case DECIMAL -> java.math.BigDecimal.class;
            case BOOLEAN -> Boolean.class;
            case DATE -> LocalDate.class;
            case DATETIME -> OffsetDateTime.class;
        };
    }

    private static Map<String, Object> toRow(Row row, RowMetadata meta) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (ColumnMetadata columnMeta : meta.getColumnMetadatas()) {
            String name = columnMeta.getName();
            result.put(name, row.get(name));
        }
        return result;
    }

    /** Classifies a failure from the driver or the pool; leaves engine errors alone. */
    static Throwable translate(Throwable ex) {
        if (ex instanceof EngineException || ex instanceof ProjectionException) {
            return ex;
        }
        R2dbcException r2dbc = null;
        boolean poolTimeout = false;
        int depth = 0;
        for (Throwable t = ex; t != null && depth < 12; t = t.getCause(), depth++) {
            if (t instanceof R2dbcException found && r2dbc == null) {
                r2dbc = found;
            }
            if (t instanceof TimeoutException || t instanceof R2dbcTimeoutException
                    || t.getClass().getName().contains("PoolAcquireTimeout")) {
                poolTimeout = true;
            }
        }
        String sqlState = r2dbc != null ? r2dbc.getSqlState() : null;
        String detail = r2dbc != null ? r2dbc.getMessage() : ex.getMessage();
        if ("57014".equals(sqlState)) {
            return new ProjectionException(ProjectionException.Kind.TIMEOUT, sqlState, detail, ex);
        }
        if (poolTimeout && sqlState == null || sqlState != null && sqlState.startsWith("08")) {
            return new ProjectionException(ProjectionException.Kind.BUSY, sqlState, detail, ex);
        }
        log.warn("Projection query failed (SQLSTATE {}): {}", sqlState, detail);
        return new ProjectionException(ProjectionException.Kind.FAILED, sqlState, detail, ex);
    }

    /** What {@link #check} found out about a query's SQL. */
    public record CheckResult(Outcome outcome, ProjectionException problem) {
        public enum Outcome { ACCEPTED, REJECTED, UNAVAILABLE }

        static CheckResult accepted() {
            return new CheckResult(Outcome.ACCEPTED, null);
        }

        static CheckResult rejected(ProjectionException problem) {
            return new CheckResult(Outcome.REJECTED, problem);
        }

        static CheckResult unavailable() {
            return new CheckResult(Outcome.UNAVAILABLE, null);
        }
    }

    /** A bounded read: at most the requested rows, whether more existed, and how long it took. */
    public record Result(List<Map<String, Object>> rows, long latencyMs, boolean truncated) {
    }
}
