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
 * <p>The template's {@code :paramName} placeholders are parsed and bound by name through the
 * driver's native parameter binding (translated to Postgres {@code $N} markers), so argument
 * values are never concatenated into the SQL. The bound names are exactly
 * {@link ValidatedQuery#params()}, the tokens the validator found in the sql.
 */
@Component
public class ProjectionStoreExecutor {

    private static final Logger log = LoggerFactory.getLogger(ProjectionStoreExecutor.class);

    private final ProjectionDatabase projection;

    public ProjectionStoreExecutor(ProjectionDatabase projection) {
        this.projection = projection;
    }

    /**
     * Streams the query's rows. Nothing runs until subscribed. Errors are
     * {@link EngineException} (bad argument values) or {@link ProjectionException} (database
     * failures); cancelling the subscription closes the cursor and returns the connection.
     */
    public Flux<Map<String, Object>> stream(String queryName, ValidatedQuery query, Map<String, Object> args, int fetchSize) {
        return Flux.defer(() -> {
            DatabaseClient.GenericExecuteSpec spec = projection.client().sql(query.sql());
            for (ParamSpec paramSpec : query.params()) {
                Object value = coerce(queryName, paramSpec, args.get(paramSpec.name()));
                spec = (value == null)
                        ? spec.bindNull(paramSpec.name(), javaType(paramSpec.type()))
                        : spec.bind(paramSpec.name(), value);
            }
            if (fetchSize > 0) {
                spec = spec.filter(statement -> statement.fetchSize(fetchSize));
            }
            return spec.map(ProjectionStoreExecutor::toRow).all();
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

    /** A bounded read: at most the requested rows, whether more existed, and how long it took. */
    public record Result(List<Map<String, Object>> rows, long latencyMs, boolean truncated) {
    }
}
