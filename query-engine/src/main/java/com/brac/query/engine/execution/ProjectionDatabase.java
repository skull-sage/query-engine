package com.brac.query.engine.execution;

import com.brac.query.engine.config.ConnectionSettings;
import com.brac.query.engine.config.ProjectionPoolProperties;
import com.brac.query.engine.config.StreamProperties;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.Option;
import org.springframework.core.env.Environment;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The database access used for projection reads: its own connection pool plus a
 * {@link DatabaseClient} on top of it. Deliberately not exposed as a {@code ConnectionFactory} or
 * {@code DatabaseClient} bean, because Spring Boot's R2DBC auto-configuration expects exactly one
 * of each, backed by the service's main pool.
 *
 * <p>This pool runs the SQL that authors wrote, so it is locked down in three ways. It should log
 * in as {@code query_engine_ro}, a user that can read data and owns nothing
 * ({@code query-engine.projection.username}). Every connection starts with
 * {@code default_transaction_read_only = on}, and {@link #inReadOnlyTransaction} runs each query
 * inside an explicit {@code BEGIN READ ONLY}, which Postgres enforces even for writes made inside
 * a stored function. See docs/security_protocol.md.
 *
 * <p>Connections also carry {@code statement_timeout} and an {@code application_name} so the
 * pool's sessions are easy to spot in {@code pg_stat_activity}.
 */
public final class ProjectionDatabase {

    public static final String APPLICATION_NAME = "query-engine-projection";

    private final DatabaseClient client;
    private final ConnectionPool pool;
    private final TransactionalOperator readOnly;

    private ProjectionDatabase(DatabaseClient client, ConnectionPool pool, TransactionalOperator readOnly) {
        this.client = client;
        this.pool = pool;
        this.readOnly = readOnly;
    }

    /** Wraps an existing client, without a pool of its own and without a read-only transaction (for tests). */
    public static ProjectionDatabase of(DatabaseClient client) {
        return new ProjectionDatabase(client, null, null);
    }

    /** Like {@link #of(DatabaseClient)} but running every query in a read-only transaction on {@code factory}. */
    public static ProjectionDatabase of(ConnectionFactory factory) {
        return new ProjectionDatabase(DatabaseClient.create(factory), null, readOnlyTransactions(factory));
    }

    public static ProjectionDatabase create(Environment env, ProjectionPoolProperties pool, StreamProperties stream) {
        ConnectionFactoryOptions.Builder options = ConnectionSettings.resolve(
                env, pool.url(), pool.username(), pool.password(),
                "projection reads", "query-engine.projection.url");
        options.option(Option.valueOf("applicationName"), APPLICATION_NAME);
        Map<String, String> startup = new LinkedHashMap<>();
        startup.put("statement_timeout", String.valueOf(stream.statementTimeout().toMillis()));
        startup.put("default_transaction_read_only", "on");
        // The lexer that checks author SQL assumes backslashes are plain inside 'strings'.
        startup.put("standard_conforming_strings", "on");
        options.option(Option.<Map<String, String>>valueOf("options"), startup);
        ConnectionFactory connectionFactory = ConnectionFactories.get(options.build());

        ConnectionPool connectionPool = new ConnectionPool(ConnectionPoolConfiguration.builder(connectionFactory)
                .name(APPLICATION_NAME)
                .initialSize(pool.initialSize())
                .maxSize(pool.maxSize())
                .maxIdleTime(pool.maxIdleTime())
                .maxAcquireTime(stream.acquireTimeout())
                .build());
        return new ProjectionDatabase(DatabaseClient.create(connectionPool), connectionPool,
                readOnlyTransactions(connectionPool));
    }

    private static TransactionalOperator readOnlyTransactions(ConnectionFactory factory) {
        R2dbcTransactionManager manager = new R2dbcTransactionManager(factory);
        // Also send SET TRANSACTION READ ONLY, in case the driver's BEGIN does not carry the flag.
        manager.setEnforceReadOnly(true);
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setReadOnly(true);
        return TransactionalOperator.create(manager, definition);
    }

    public DatabaseClient client() {
        return client;
    }

    /**
     * Runs {@code rows} inside a read-only transaction that opens when it is subscribed and ends
     * when it completes, fails or is cancelled. Postgres refuses every write in such a
     * transaction, whoever the SQL runs as, and the author's SQL cannot switch it back.
     */
    public <T> Flux<T> inReadOnlyTransaction(Flux<T> rows) {
        return readOnly == null ? rows : readOnly.transactional(rows);
    }

    /** Closes the pool when the application context shuts down. */
    public void dispose() {
        if (pool != null) {
            pool.dispose();
        }
    }
}
