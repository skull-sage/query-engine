package com.brac.query.engine.execution;

import com.brac.query.engine.config.DBConnectionSettings;
import com.brac.query.engine.config.DBProjectionPoolProperties;
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
 * The database access used to run author SQL: its own connection pool plus a {@link DatabaseClient}
 * on top of it. Deliberately not exposed as a {@code ConnectionFactory} or {@code DatabaseClient} bean,
 * because Spring Boot's R2DBC auto-configuration expects exactly one of each, backed by the service's
 * main pool.
 *
 * <p>The pool connects to the service's own datasource ({@code spring.r2dbc.*}), so the engine adds no
 * database login of its own. What keeps author SQL from changing anything is not the login but the
 * transaction: {@link #inReadOnlyTransaction} runs each query inside an explicit {@code BEGIN READ ONLY},
 * which Postgres enforces for every statement in it, DML and DDL alike, and even for writes made inside a
 * stored function. Every connection of this pool also starts with {@code default_transaction_read_only = on},
 * a second layer for anything that slips past the wrapper. Only author SQL ever runs on this pool; the
 * engine's own writes use the service's normal pool. See docs/security_protocol.md.
 *
 * <p>Connections also carry {@code statement_timeout} and an {@code application_name} so the pool's
 * sessions are easy to spot in {@code pg_stat_activity}.
 */
public final class ProjectionDatabaseClient {

    public static final String APPLICATION_NAME = "query-engine-projection";

    private final DatabaseClient databaseClient;
    private final ConnectionPool pool;
    private final TransactionalOperator readOnly;

    private ProjectionDatabaseClient(DatabaseClient databaseClient, ConnectionPool pool, TransactionalOperator readOnly) {
        this.databaseClient = databaseClient;
        this.pool = pool;
        this.readOnly = readOnly;
    }

    /** Wraps an existing connection factory, running every query in a read-only transaction (for tests). */
    public static ProjectionDatabaseClient of(ConnectionFactory factory) {
        return new ProjectionDatabaseClient(DatabaseClient.create(factory), null, readOnlyTransactions(factory));
    }

    public static ProjectionDatabaseClient create(Environment env, DBProjectionPoolProperties pool, StreamProperties stream) {
        ConnectionFactoryOptions.Builder options = DBConnectionSettings.fromService(env);
        options.option(Option.valueOf("applicationName"), APPLICATION_NAME);
        Map<String, String> startup = new LinkedHashMap<>();
        startup.put("statement_timeout", String.valueOf(stream.statementTimeout().toMillis()));
        startup.put("default_transaction_read_only", "on");
        // The SQL guard that checks author SQL assumes backslashes are plain inside 'strings'.
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
        return new ProjectionDatabaseClient(DatabaseClient.create(connectionPool), connectionPool,
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

    /** The client for the pool. Package-private: author SQL must go through {@link ProjectionStoreExecutor}, which wraps it. */
    DatabaseClient databaseClient() {
        return databaseClient;
    }

    /**
     * Runs a statement of the engine's own (a startup self-test) the same way author SQL runs: on this pool,
     * inside the read-only transaction. Rows come back as column-name to value maps.
     */
    public Flux<Map<String, Object>> readOnlyQuery(String sql) {
        return inReadOnlyTransaction(databaseClient.sql(sql).fetch().all());
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
