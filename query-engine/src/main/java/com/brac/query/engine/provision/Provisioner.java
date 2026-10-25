package com.brac.query.engine.provision;

import com.brac.query.engine.store.EngineDatabase;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.Result;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sets up the database logins and permissions that docs/security_protocol.md requires, from the
 * service's own {@code application.yml}. Run it once per database, as an administrator, before the
 * service first starts, and again whenever a password in the configuration changes:
 * {@code ./gradlew provision} in a service, or this class's {@code main} on the service's classpath.
 *
 * <p>It starts a small Spring context that loads the service's configuration exactly as the service
 * does (yml, profiles, environment variables, command line) but none of the service's beans, reads
 * the three logins and their passwords, fills in {@code query-engine/provision.sql} and runs it in
 * one transaction. The administrator login comes from {@link ProvisionProperties}; the running
 * service never needs it.
 *
 * <p>With {@code --query-engine.provision.dry-run=true} it prints the SQL (passwords hidden) and
 * connects to nothing.
 */
public final class Provisioner {

    static final String TEMPLATE = "query-engine/provision.sql";

    private Provisioner() {
    }

    public static void main(String[] args) {
        int exitCode = 0;
        try {
            SpringApplication app = new SpringApplication(Config.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            app.setBannerMode(Banner.Mode.OFF);
            app.setLogStartupInfo(false);
            try (ConfigurableApplicationContext context = app.run(args)) {
                run(context.getEnvironment(), context.getBean(ProvisionProperties.class));
            }
        } catch (Throwable e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            System.err.println("Provisioning failed: " + (root.getMessage() != null ? root.getMessage() : root));
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    /** Only loads the configuration and binds {@link ProvisionProperties}; none of the service's beans start. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ProvisionProperties.class)
    static class Config {
    }

    static void run(Environment environment, ProvisionProperties properties) throws IOException {
        Map<String, Object> values = values(environment, properties);
        String template = StreamUtils.copyToString(new ClassPathResource(TEMPLATE).getInputStream(), StandardCharsets.UTF_8);
        for (String name : ProvisionSql.placeholderNames(template)) {
            if (!values.containsKey(name)) {
                throw new IllegalStateException("No value for '" + name + "' in the provisioning template");
            }
        }

        if (properties.dryRun()) {
            System.out.println(ProvisionSql.render(template, values, true));
            return;
        }

        List<String> statements = ProvisionSql.statements(ProvisionSql.render(template, values, false));
        ConnectionFactory factory = adminConnectionFactory(environment, properties);
        System.out.println("Provisioning database '" + values.get("database") + "': logins '" + values.get("service_login")
                + "' (service), '" + values.get("owner_login") + "' (engine tables), '" + values.get("reader_login")
                + "' (author queries); projection schemas " + values.get("projection_schemas") + ".");
        execute(factory, statements);
        System.out.println("Done: " + statements.size() + " statements applied.");
    }

    /** Everything the template needs, from the service's configuration. Fails with a message naming the missing setting. */
    static Map<String, Object> values(Environment env, ProvisionProperties properties) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("service_login", required(env, "spring.r2dbc.username", "the service's own database login"));
        values.put("service_password", required(env, "spring.r2dbc.password", "the service's own database password"));
        values.put("owner_login", required(env, "query-engine.store.username", "the login that owns the engine's tables"));
        values.put("owner_password", required(env, "query-engine.store.password", "the password of the login that owns the engine's tables"));
        values.put("reader_login", required(env, "query-engine.projection.username", "the read-only login that runs authors' queries"));
        values.put("reader_password", required(env, "query-engine.projection.password", "the password of the read-only login"));
        values.put("database", database(env, properties));
        values.put("engine_schema", EngineDatabase.SCHEMA);
        List<String> schemas = properties.projectionSchemas().stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        if (schemas.isEmpty()) {
            throw new IllegalStateException("query-engine.provision.projection-schemas is empty");
        }
        if (schemas.contains(EngineDatabase.SCHEMA)) {
            throw new IllegalStateException("query-engine.provision.projection-schemas cannot include '" + EngineDatabase.SCHEMA + "'");
        }
        values.put("projection_schemas", schemas);

        String service = (String) values.get("service_login");
        String owner = (String) values.get("owner_login");
        String reader = (String) values.get("reader_login");
        if (service.equals(owner) || service.equals(reader) || owner.equals(reader)) {
            throw new IllegalStateException("The service login (spring.r2dbc.username), the engine login (query-engine.store.username) "
                    + "and the projection login (query-engine.projection.username) must be three different users, but they are '"
                    + service + "', '" + owner + "' and '" + reader + "'");
        }
        return values;
    }

    private static String database(Environment env, ProvisionProperties properties) {
        if (properties.database() != null && !properties.database().isBlank()) {
            return properties.database().trim();
        }
        String url = required(env, "spring.r2dbc.url", "the service's database URL");
        Object name = ConnectionFactoryOptions.parse(url).getValue(ConnectionFactoryOptions.DATABASE);
        if (name == null || name.toString().isBlank()) {
            throw new IllegalStateException("spring.r2dbc.url has no database name; set query-engine.provision.database");
        }
        return name.toString();
    }

    private static String required(Environment env, String property, String what) {
        String value = env.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " is not set (" + what + ")");
        }
        return value;
    }

    private static ConnectionFactory adminConnectionFactory(Environment env, ProvisionProperties properties) {
        ProvisionProperties.Admin admin = properties.admin();
        if (admin.username() == null || admin.username().isBlank()) {
            throw new IllegalStateException("No administrator login: set query-engine.provision.admin.username and .password "
                    + "(environment variables QUERYENGINE_PROVISION_ADMIN_USERNAME and QUERYENGINE_PROVISION_ADMIN_PASSWORD)");
        }
        String url = admin.url() != null && !admin.url().isBlank() ? admin.url()
                : required(env, "spring.r2dbc.url", "the service's database URL");
        ConnectionFactoryOptions.Builder options = ConnectionFactoryOptions.parse(url).mutate()
                .option(ConnectionFactoryOptions.USER, admin.username());
        if (admin.password() != null) {
            options.option(ConnectionFactoryOptions.PASSWORD, admin.password());
        }
        return ConnectionFactories.get(options.build());
    }

    /** Runs the statements in order, in one transaction. */
    static void execute(ConnectionFactory factory, List<String> statements) {
        Connection connection = Mono.from(factory.create()).block();
        try {
            Mono.from(connection.beginTransaction()).block();
            List<String> done = new ArrayList<>();
            for (String sql : statements) {
                try {
                    Flux.from(connection.createStatement(sql).execute())
                            .concatMap(Result::getRowsUpdated)
                            .then()
                            .block();
                } catch (RuntimeException e) {
                    throw new IllegalStateException("Statement " + (done.size() + 1) + " of " + statements.size() + " failed ("
                            + ProvisionSql.label(sql) + "): " + e.getMessage() + ". Nothing was changed.", e);
                }
                done.add(sql);
            }
            Mono.from(connection.commitTransaction()).block();
        } catch (RuntimeException e) {
            try {
                Mono.from(connection.rollbackTransaction()).block();
            } catch (RuntimeException ignored) {
                // The connection is going away; the server rolls an open transaction back anyway.
            }
            throw e;
        } finally {
            Mono.from(connection.close()).block();
        }
    }
}
