package com.brac.query.engine.execution;

import com.brac.query.engine.config.ProjectionPoolProperties;
import com.brac.query.engine.config.StreamProperties;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.Option;
import org.springframework.core.env.Environment;
import org.springframework.r2dbc.core.DatabaseClient;

import java.util.Map;

/**
 * The database access used for projection reads: its own connection pool plus a
 * {@link DatabaseClient} on top of it. Deliberately not exposed as a {@code ConnectionFactory} or
 * {@code DatabaseClient} bean, because Spring Boot's R2DBC auto-configuration (and every engine
 * store that injects a {@code DatabaseClient}) expects exactly one of each, backed by the
 * service's main pool.
 *
 * <p>Connections carry {@code statement_timeout} (set per connection at startup) and an
 * {@code application_name} so the pool's sessions are easy to spot in {@code pg_stat_activity}.
 */
public final class ProjectionDatabase {

    public static final String APPLICATION_NAME = "query-engine-projection";

    private final DatabaseClient client;
    private final ConnectionPool pool;

    private ProjectionDatabase(DatabaseClient client, ConnectionPool pool) {
        this.client = client;
        this.pool = pool;
    }

    /** Wraps an existing client, without a pool of its own (for tests). */
    public static ProjectionDatabase of(DatabaseClient client) {
        return new ProjectionDatabase(client, null);
    }

    public static ProjectionDatabase create(Environment env, ProjectionPoolProperties pool, StreamProperties stream) {
        String url = firstNonBlank(pool.url(), env.getProperty("spring.r2dbc.url"));
        if (url == null) {
            throw new IllegalStateException(
                    "query-engine needs spring.r2dbc.url (or query-engine.projection.url) to read projection data");
        }
        String username = firstNonBlank(pool.username(), env.getProperty("spring.r2dbc.username"));
        String password = firstNonBlank(pool.password(), env.getProperty("spring.r2dbc.password"));

        ConnectionFactoryOptions.Builder options = ConnectionFactoryOptions.parse(url).mutate();
        if (username != null) {
            options.option(ConnectionFactoryOptions.USER, username);
        }
        if (password != null) {
            options.option(ConnectionFactoryOptions.PASSWORD, password);
        }
        options.option(Option.valueOf("applicationName"), APPLICATION_NAME);
        options.option(Option.<Map<String, String>>valueOf("options"),
                Map.of("statement_timeout", String.valueOf(stream.statementTimeout().toMillis())));
        ConnectionFactory connectionFactory = ConnectionFactories.get(options.build());

        ConnectionPool connectionPool = new ConnectionPool(ConnectionPoolConfiguration.builder(connectionFactory)
                .name(APPLICATION_NAME)
                .initialSize(pool.initialSize())
                .maxSize(pool.maxSize())
                .maxIdleTime(pool.maxIdleTime())
                .maxAcquireTime(stream.acquireTimeout())
                .build());
        return new ProjectionDatabase(DatabaseClient.create(connectionPool), connectionPool);
    }

    public DatabaseClient client() {
        return client;
    }

    /** Closes the pool when the application context shuts down. */
    public void dispose() {
        if (pool != null) {
            pool.dispose();
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }
}
