package com.brac.query.engine.store;

import com.brac.query.engine.config.ConnectionSettings;
import com.brac.query.engine.config.EngineStoreProperties;
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
import reactor.core.publisher.Mono;

/**
 * The database access for the engine's own tables: a small pool of its own, a
 * {@link DatabaseClient} on top of it, and a transaction on that same pool. The stores use it
 * instead of the service's {@code DatabaseClient}, so the engine's tables can belong to, and be
 * reached only by, their own database login.
 *
 * <p>Every statement the stores run names the schema ({@code query_engine.published_query}) and
 * none relies on {@code search_path}, so a connection pooler between the service and Postgres
 * cannot send it to the wrong table.
 *
 * <p>Deliberately not exposed as a {@code ConnectionFactory}, {@code DatabaseClient} or
 * transaction manager bean: Spring Boot and the consuming service expect exactly one of each,
 * backed by the service's own pool.
 */
public final class EngineDatabase {

    /** The schema that holds the engine's tables. Fixed, not configurable. */
    public static final String SCHEMA = "query_engine";

    public static final String APPLICATION_NAME = "query-engine-store";

    private final ConnectionFactory connectionFactory;
    private final DatabaseClient client;
    private final TransactionalOperator transaction;
    private final ConnectionPool pool;

    private EngineDatabase(ConnectionFactory connectionFactory, ConnectionPool pool) {
        this.connectionFactory = connectionFactory;
        this.pool = pool;
        this.client = DatabaseClient.create(connectionFactory);
        this.transaction = TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory));
    }

    /** Wraps an existing connection factory, without a pool of its own (for tests). */
    public static EngineDatabase of(ConnectionFactory connectionFactory) {
        return new EngineDatabase(connectionFactory, null);
    }

    public static EngineDatabase create(Environment env, EngineStoreProperties store) {
        ConnectionFactoryOptions.Builder options = ConnectionSettings.resolve(
                env, store.url(), store.username(), store.password(),
                "the engine's own tables", "query-engine.store.url");
        options.option(Option.valueOf("applicationName"), APPLICATION_NAME);
        ConnectionFactory raw = ConnectionFactories.get(options.build());

        ConnectionPool connectionPool = new ConnectionPool(ConnectionPoolConfiguration.builder(raw)
                .name(APPLICATION_NAME)
                .initialSize(store.initialSize())
                .maxSize(store.maxSize())
                .maxIdleTime(store.maxIdleTime())
                .build());
        return new EngineDatabase(connectionPool, connectionPool);
    }

    public DatabaseClient client() {
        return client;
    }

    /** The pool, for the schema initializer. */
    public ConnectionFactory connectionFactory() {
        return connectionFactory;
    }

    /**
     * Runs {@code work} in one transaction on this pool: everything commits together or, if any
     * step fails or the caller cancels, none of it does. The stores' statements join it as long as
     * they are part of {@code work}.
     */
    public <T> Mono<T> inTransaction(Mono<T> work) {
        return transaction.transactional(work);
    }

    /** Closes the pool when the application context shuts down. */
    public void dispose() {
        if (pool != null) {
            pool.dispose();
        }
    }
}
