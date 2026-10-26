package com.brac.query.engine;

import com.brac.query.engine.config.CorsProperties;
import com.brac.query.engine.config.EngineStoreProperties;
import com.brac.query.engine.config.ProjectionPoolProperties;
import com.brac.query.engine.config.SecurityProperties;
import com.brac.query.engine.config.SimulateProperties;
import com.brac.query.engine.config.SqlGuardProperties;
import com.brac.query.engine.config.StreamProperties;
import com.brac.query.engine.execution.ProjectionDatabase;
import com.brac.query.engine.store.EngineDatabase;
import com.brac.query.engine.web.EngineCors;
import io.r2dbc.spi.Connection;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.r2dbc.connection.init.ConnectionFactoryInitializer;
import org.springframework.r2dbc.connection.init.DatabasePopulator;
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator;
import reactor.core.publisher.Flux;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

/**
 * Plugs query-engine into any Spring Boot service that has it on the classpath: registers the
 * engine's controllers ({@code /engine/*}, {@code /data-query}, {@code /query-roles}), service,
 * stores and error handling, and creates the engine's own tables in the {@code query_engine}
 * schema of the service's database.
 *
 * <p>The consuming service supplies only its domain specifics: its R2DBC connection
 * ({@code spring.r2dbc.*}) and its projection tables. The engine opens two more pools of its
 * own, each of which can log in as a different database user (see docs/security_protocol.md):
 * {@code query-engine.store.*} for the engine's tables and {@code query-engine.projection.*} for
 * running authors' queries. Registered through
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}, so
 * the service's own component scan doesn't need to know the engine's package.
 */
@AutoConfiguration
@ComponentScan(basePackageClasses = QueryEngineAutoConfiguration.class)
@EnableConfigurationProperties({StreamProperties.class, ProjectionPoolProperties.class, SimulateProperties.class,
        EngineStoreProperties.class, SqlGuardProperties.class, SecurityProperties.class, CorsProperties.class})
public class QueryEngineAutoConfiguration {

    /** Classpath location of the engine's DDL + identity catalog seed. */
    public static final String SCHEMA_LOCATION = "query-engine/schema.sql";

    /**
     * Lets the listed browser origins call the engine directly (the Query Builder UI calls it
     * without a proxy). On only when {@code query-engine.cors.enabled} is true (the default) and
     * {@code query-engine.cors.allowed-origins} lists at least one origin; otherwise this is a
     * pass-through and the service does no CORS handling at all. First in the filter chain, so
     * preflights are answered before anything else and error responses carry the CORS headers too.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public WebFilter queryEngineCorsFilter(CorsProperties cors) {
        return EngineCors.create(cors);
    }

    /** Says in the startup log whether browser pages on other origins can use this service. */
    @Bean
    public ApplicationListener<ApplicationReadyEvent> queryEngineCorsStatus(CorsProperties cors) {
        return event -> LoggerFactory.getLogger(EngineCors.class).info(EngineCors.describe(cors));
    }

    /**
     * The pool for the engine's own tables, separate from the service's pool. See
     * {@link EngineDatabase}.
     */
    @Bean(destroyMethod = "dispose")
    public EngineDatabase engineDatabase(Environment environment, EngineStoreProperties store) {
        return EngineDatabase.create(environment, store);
    }

    /**
     * The pool used to read projection data (simulate and {@code /data-query}), separate from the
     * service's main pool so slow streaming clients cannot starve the engine's own calls. See
     * {@link ProjectionDatabase}.
     */
    @Bean(destroyMethod = "dispose")
    public ProjectionDatabase projectionDatabase(Environment environment, ProjectionPoolProperties pool, StreamProperties stream) {
        return ProjectionDatabase.create(environment, pool, stream);
    }

    /**
     * Runs {@link #SCHEMA_LOCATION} on startup, as the engine's own login. Every statement in it
     * is idempotent. If the {@code query_engine} schema is missing it is created first, which only
     * works for a login that may create schemas (a dev database); a provisioned database already
     * has it. Set {@code query-engine.schema.initialize=false} when the schema is managed
     * elsewhere (e.g. by a migration tool).
     */
    @Bean
    @ConditionalOnProperty(prefix = "query-engine.schema", name = "initialize", havingValue = "true", matchIfMissing = true)
    public ConnectionFactoryInitializer queryEngineSchemaInitializer(EngineDatabase engine) {
        DatabasePopulator tables = new ResourceDatabasePopulator(new ClassPathResource(SCHEMA_LOCATION));
        ConnectionFactoryInitializer initializer = new ConnectionFactoryInitializer();
        initializer.setConnectionFactory(engine.connectionFactory());
        initializer.setDatabasePopulator(connection -> ensureSchema(connection).then(tables.populate(connection)));
        return initializer;
    }

    private static Mono<Void> ensureSchema(Connection connection) {
        return Flux.from(connection.createStatement(
                                "SELECT 1 FROM pg_namespace WHERE nspname = '" + EngineDatabase.SCHEMA + "'").execute())
                .flatMap(result -> result.map((row, meta) -> 1))
                .hasElements()
                .flatMap(exists -> exists ? Mono.<Void>empty()
                        : Flux.from(connection.createStatement("CREATE SCHEMA " + EngineDatabase.SCHEMA).execute())
                        .flatMap(result -> result.getRowsUpdated())
                        .onErrorMap(e -> new IllegalStateException("The '" + EngineDatabase.SCHEMA + "' schema does not exist and "
                                + "this database login may not create it. Run the provisioning step as an administrator "
                                + "(./gradlew provision in the service, see docs/security_protocol.md).", e))
                        .then());
    }
}
