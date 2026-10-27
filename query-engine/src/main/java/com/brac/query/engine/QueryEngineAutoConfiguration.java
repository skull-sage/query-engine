package com.brac.query.engine;

import com.brac.query.engine.config.CorsProperties;
import com.brac.query.engine.config.DBProjectionPoolProperties;
import com.brac.query.engine.config.RolesProperties;
import com.brac.query.engine.config.SecurityProperties;
import com.brac.query.engine.config.SimulateProperties;
import com.brac.query.engine.config.SqlGuardProperties;
import com.brac.query.engine.config.StreamProperties;
import com.brac.query.engine.execution.ProjectionDatabaseClient;
import com.brac.query.engine.web.EngineCors;
import io.r2dbc.spi.ConnectionFactory;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.r2dbc.connection.init.ConnectionFactoryInitializer;
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator;
import org.springframework.web.server.WebFilter;

/**
 * Plugs query-engine into any Spring Boot service that has it on the classpath: registers the
 * engine's controllers ({@code /engine/*}, {@code /data-query}, {@code /query-roles}), service,
 * store and error handling, and creates the engine's one table, {@code qe_stored_query}, in the
 * service's own database on boot.
 *
 * <p>The consuming service supplies only its domain specifics: its R2DBC datasource
 * ({@code spring.r2dbc.*}) and its projection tables. The engine uses that datasource for everything.
 * Author SQL runs on a second pool of the same datasource, inside a read-only transaction (see
 * {@link ProjectionDatabaseClient} and docs/security_protocol.md). Registered through
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}, so the
 * service's own component scan doesn't need to know the engine's package.
 */
@AutoConfiguration
@ComponentScan(basePackageClasses = QueryEngineAutoConfiguration.class)
@EnableConfigurationProperties({StreamProperties.class, DBProjectionPoolProperties.class, SimulateProperties.class,
        SqlGuardProperties.class, SecurityProperties.class, CorsProperties.class, RolesProperties.class})
public class QueryEngineAutoConfiguration {

    /** Classpath location of the DDL for the engine's table. */
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
     * The pool that runs author SQL (simulate, {@code /data-query} and the draft-time check), separate from the
     * service's main pool so slow streaming clients cannot starve the service's own calls. Same datasource,
     * same login. See {@link ProjectionDatabaseClient}.
     */
    @Bean(destroyMethod = "dispose")
    public ProjectionDatabaseClient projectionDatabaseClient(Environment environment, DBProjectionPoolProperties pool, StreamProperties stream) {
        return ProjectionDatabaseClient.create(environment, pool, stream);
    }

    /**
     * Runs {@link #SCHEMA_LOCATION} on startup with the service's datasource: a single
     * {@code CREATE TABLE IF NOT EXISTS}, so it is safe on every start and never changes an existing table. Set
     * {@code query-engine.schema.initialize=false} when a DBA creates the table ahead of time (the service login
     * then needs no right to create tables).
     */
    @Bean
    @ConditionalOnProperty(prefix = "query-engine.schema", name = "initialize", havingValue = "true", matchIfMissing = true)
    public ConnectionFactoryInitializer queryEngineSchemaInitializer(ConnectionFactory connectionFactory) {
        ResourceDatabasePopulator tables = new ResourceDatabasePopulator(new ClassPathResource(SCHEMA_LOCATION));
        ConnectionFactoryInitializer initializer = new ConnectionFactoryInitializer();
        initializer.setConnectionFactory(connectionFactory);
        initializer.setDatabasePopulator(connection -> tables.populate(connection).onErrorMap(e -> new IllegalStateException(
                "query-engine could not create its table qe_stored_query with the service's database login. Either let that "
                        + "login create tables in its schema, or have a DBA run " + SCHEMA_LOCATION + " (a single CREATE TABLE IF NOT "
                        + "EXISTS) and set query-engine.schema.initialize=false.", e)));
        return initializer;
    }
}
