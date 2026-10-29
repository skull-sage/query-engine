package com.brac.query.engine.service;

import com.brac.query.engine.config.StreamProperties;
import com.brac.query.engine.error.EngineException;
import com.brac.query.engine.execution.ProjectionException;
import com.brac.query.engine.execution.ProjectionStoreExecutor;
import com.brac.query.engine.execution.StreamGuards;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The runtime side of {@code /data-query}: reading a published query's rows either as a stream
 * ({@link #stream}) or as a bounded list ({@link #bounded}). The checks that must pass before any
 * data is read (query exists, caller allowed, params valid) live in
 * {@link QueryEngineService#prepareInvocation}.
 *
 * <p>Streaming is demand-driven end to end: the rows come from {@link ProjectionStoreExecutor#stream}
 * (a database cursor read {@code fetchSize} rows at a time), pass through {@link StreamGuards}
 * (time limits, no buffering), and are written by the controller as they are requested by the
 * client. Every call ends in exactly one log line saying how it ended.
 */
@Service
public class DataQueryService {

    private static final Logger log = LoggerFactory.getLogger("com.brac.query.engine.data-query");

    private final QueryEngineService engine;
    private final ProjectionStoreExecutor executor;
    private final StreamProperties props;

    public DataQueryService(QueryEngineService engine, ProjectionStoreExecutor executor, StreamProperties props) {
        this.engine = engine;
        this.executor = executor;
        this.props = props;
    }

    public Mono<Invocation> prepare(String name, Map<String, Object> params, List<String> callerRoles) {
        return engine.prepareInvocation(name, params, callerRoles);
    }

    /**
     * Reads all rows for the published query reactively using R2DBC without a database cursor
     * or streaming chunking. Executes the query directly inside BEGIN READ ONLY and collects
     * all rows into a list.
     */
    public Mono<List<Map<String, Object>>> executeDirect(Invocation invocation, List<String> callerRoles) {
        return Mono.defer(() -> {
            Call call = new Call("direct", invocation, callerRoles, 0);
            return executor.executeDirect(invocation.queryName(), invocation.query(), invocation.params())
                    .onErrorMap(ProjectionException.class, e -> e.toEngineException(invocation.queryName(), false))
                    .doOnError(call::failed)
                    .doOnCancel(call::cancelled)
                    .doOnSuccess(rows -> {
                        call.rows.set(rows.size());
                        call.completed();
                    })
                    .doFinally(signal -> call.log());
        });
    }

    /**
     * All rows, as fast as the client reads them. Errors before the first row (database rejects the
     * SQL, no connection in time) surface as {@link EngineException} so they can still be answered
     * with a normal JSON error; after the first row they abort the connection.
     */
    public Flux<Map<String, Object>> stream(Invocation invocation, List<String> callerRoles) {
        return Flux.defer(() -> {
            int fetchSize = props.effectiveFetchSize(invocation.query().fetchSize());
            Call call = new Call("ndjson", invocation, callerRoles, fetchSize);
            Flux<Map<String, Object>> rows =
                    executor.stream(invocation.queryName(), invocation.query(), invocation.params(), fetchSize);
            return StreamGuards.apply(rows, invocation.queryName(), props.stallTimeout(), props.maxDuration())
                    .onErrorMap(ProjectionException.class, e -> e.toEngineException(invocation.queryName(), false))
                    .doOnNext(row -> call.rows.incrementAndGet())
                    .doOnError(call::failed)
                    .doOnCancel(call::cancelled)
                    .doOnComplete(call::completed)
                    .doFinally(signal -> call.log());
        });
    }

    /**
     * At most {@code json-max-rows} rows as a list. A result with more rows is
     * {@code 413 RESULT_TOO_LARGE} (reading stops one row past the limit), never a silent cut.
     */
    public Mono<List<Map<String, Object>>> bounded(Invocation invocation, List<String> callerRoles) {
        return Mono.defer(() -> {
            int fetchSize = props.effectiveFetchSize(invocation.query().fetchSize());
            Call call = new Call("json", invocation, callerRoles, fetchSize);
            return executor.execute(invocation.queryName(), invocation.query(), invocation.params(),
                            props.jsonMaxRows(), fetchSize)
                    .flatMap(result -> {
                        call.rows.set(result.rows().size());
                        if (result.truncated()) {
                            call.capped();
                            return Mono.<List<Map<String, Object>>>error(new EngineException(
                                    "RESULT_TOO_LARGE", HttpStatus.valueOf(413), invocation.queryName(),
                                    "'" + invocation.queryName() + "' returned more than " + props.jsonMaxRows()
                                            + " rows. Send 'Accept: application/x-ndjson' to stream the full result."));
                        }
                        return Mono.just(result.rows());
                    })
                    .onErrorMap(ProjectionException.class, e -> e.toEngineException(invocation.queryName(), false))
                    .doOnError(call::failed)
                    .doOnCancel(call::cancelled)
                    .doOnSuccess(rows -> call.completed())
                    .doFinally(signal -> call.log());
        });
    }

    /** One {@code /data-query} call: counts rows and records how it ended, logged once. */
    private static final class Call {
        final String mode;
        final String queryName;
        final List<String> roles;
        final int fetchSize;
        final long startNanos = System.nanoTime();
        final AtomicLong rows = new AtomicLong();
        volatile String outcome = "cancelled";
        volatile String reason;

        Call(String mode, Invocation invocation, List<String> roles, int fetchSize) {
            this.mode = mode;
            this.queryName = invocation.queryName();
            this.roles = roles;
            this.fetchSize = fetchSize;
        }

        void completed() {
            outcome = "completed";
        }

        void cancelled() {
            outcome = "cancelled";
        }

        void capped() {
            outcome = "capped";
        }

        void failed(Throwable error) {
            if (error instanceof EngineException engine && "RESULT_TOO_LARGE".equals(engine.getCode())) {
                return; // already recorded as capped
            }
            boolean timeout = error instanceof EngineException engine
                    && (StreamGuards.STALLED.equals(engine.getCode()) || StreamGuards.TOO_LONG.equals(engine.getCode())
                    || "QUERY_TIMEOUT".equals(engine.getCode()));
            outcome = timeout ? "timeout" : "error";
            reason = error instanceof EngineException engine ? engine.getCode() : error.getClass().getSimpleName();
        }

        void log() {
            long ms = (System.nanoTime() - startNanos) / 1_000_000;
            boolean problem = "error".equals(outcome) || "timeout".equals(outcome);
            String line = "data-query query=" + queryName + " mode=" + mode + " roles=" + roles
                    + " fetchSize=" + fetchSize + " outcome=" + outcome + " rows=" + rows.get()
                    + " durationMs=" + ms + (reason != null ? " reason=" + reason : "");
            if (problem) {
                log.warn(line);
            } else {
                log.info(line);
            }
        }
    }
}
