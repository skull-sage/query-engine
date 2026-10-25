package com.brac.query.engine.security;

import com.brac.query.engine.config.SecurityProperties;
import com.brac.query.engine.execution.ProjectionDatabase;
import com.brac.query.engine.model.PublishedQuery;
import com.brac.query.engine.model.ValidationError;
import com.brac.query.engine.store.EngineDatabase;
import com.brac.query.engine.store.PublishedQueryRepository;
import com.brac.query.engine.validation.SqlGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs once at startup, after the engine's tables exist, and checks the setup that
 * docs/security_protocol.md depends on.
 *
 * <p><b>Logins.</b> Author SQL must not run as a user that could change the engine's tables or
 * anything else. This reads what the database says about the two logins and, in
 * {@code enforce} mode (the default), refuses to start unless:
 * <ul>
 *   <li>the engine login and the projection login are different users;</li>
 *   <li>neither is a superuser, and the projection login does not bypass row security;</li>
 *   <li>the projection login does not own the {@code query_engine} schema;</li>
 *   <li>the projection login has no INSERT, UPDATE, DELETE or TRUNCATE on any table, the engine's\n *       or the domain's (it may only read).</li>
 * </ul>
 * {@code query-engine.security.mode=warn} logs the same findings and starts anyway, for a dev
 * machine that has not been provisioned yet.
 *
 * <p><b>Stored queries.</b> Every published query is run through {@link SqlGuard}. One that no
 * longer passes (published before the rules existed, or after they were tightened) is logged; it
 * is refused when called either way.
 */
@Component
public class SecurityStartupCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SecurityStartupCheck.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final String LOGIN_SQL = """
            SELECT current_user::text AS login,
                   r.rolsuper AS superuser,
                   r.rolbypassrls AS bypass_rls,
                   COALESCE((SELECT n.nspowner = r.oid FROM pg_namespace n WHERE n.nspname = 'query_engine'), false)
                       AS owns_engine_schema,
                   (SELECT string_agg(name, ', ') FROM (
                        SELECT n.nspname || '.' || c.relname AS name
                          FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                         WHERE c.relkind IN ('r', 'p')
                           AND n.nspname NOT IN ('pg_catalog', 'information_schema') AND n.nspname !~ '^pg_toast'
                           AND (has_table_privilege(c.oid, 'INSERT') OR has_table_privilege(c.oid, 'UPDATE')
                                OR has_table_privilege(c.oid, 'DELETE') OR has_table_privilege(c.oid, 'TRUNCATE'))
                         ORDER BY 1 LIMIT 5) w)
                       AS writable_tables
              FROM pg_roles r
             WHERE r.rolname = current_user
            """;

    private static final String LEGACY_SQL = """
            SELECT (to_regclass('public.query_draft') IS NOT NULL
                    OR to_regclass('public.published_query') IS NOT NULL) AS legacy
            """;

    private final EngineDatabase engine;
    private final ProjectionDatabase projection;
    private final PublishedQueryRepository publishedQueries;
    private final SqlGuard sqlGuard;
    private final SecurityProperties properties;

    public SecurityStartupCheck(EngineDatabase engine, ProjectionDatabase projection,
                                PublishedQueryRepository publishedQueries, SqlGuard sqlGuard, SecurityProperties properties) {
        this.engine = engine;
        this.projection = projection;
        this.publishedQueries = publishedQueries;
        this.sqlGuard = sqlGuard;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        warnAboutLegacyTables();
        if (properties.mode() != SecurityProperties.Mode.OFF) {
            checkLogins();
        }
        auditStoredQueries();
    }

    private void checkLogins() {
        Login store = inspect(engine.client());
        Login reader = inspect(projection.client());

        List<String> problems = new ArrayList<>();
        if (store == null || reader == null) {
            problems.add("could not read the database logins' properties");
        } else {
            if (store.login().equals(reader.login())) {
                problems.add("the engine (query-engine.store.*) and the projection pool (query-engine.projection.*) both log in as '"
                        + store.login() + "'; author queries must run as a different user that owns nothing");
            }
            if (store.superuser()) {
                problems.add("the engine login '" + store.login() + "' is a superuser");
            }
            if (reader.superuser()) {
                problems.add("the projection login '" + reader.login() + "' is a superuser, so it ignores every permission");
            }
            if (reader.bypassRls()) {
                problems.add("the projection login '" + reader.login() + "' bypasses row-level security");
            }
            if (reader.ownsEngineSchema()) {
                problems.add("the projection login '" + reader.login() + "' owns the query_engine schema");
            }
            if (reader.writableTables() != null) {
                problems.add("the projection login '" + reader.login() + "' can write to tables (e.g. " + reader.writableTables()
                        + "); it must only be able to read");
            }
        }

        if (problems.isEmpty()) {
            log.info("Security check passed: engine login '{}', projection login '{}'.", store.login(), reader.login());
            return;
        }
        String message = "Database logins are not set up as docs/security_protocol.md requires:\n  - "
                + String.join("\n  - ", problems);
        if (properties.mode() == SecurityProperties.Mode.ENFORCE) {
            throw new IllegalStateException(message + "\nProvision the logins (`./gradlew provision` in the service, see docs/security_protocol.md), or set "
                    + "query-engine.security.mode=warn to start anyway on a development machine.");
        }
        log.warn("{}\nStarting anyway because query-engine.security.mode=warn.", message);
    }

    private Login inspect(DatabaseClient client) {
        return client.sql(LOGIN_SQL)
                .map((row, meta) -> new Login(
                        row.get("login", String.class),
                        Boolean.TRUE.equals(row.get("superuser", Boolean.class)),
                        Boolean.TRUE.equals(row.get("bypass_rls", Boolean.class)),
                        Boolean.TRUE.equals(row.get("owns_engine_schema", Boolean.class)),
                        row.get("writable_tables", String.class)))
                .one()
                .block(TIMEOUT);
    }

    private void warnAboutLegacyTables() {
        Boolean legacy = engine.client().sql(LEGACY_SQL)
                .map((row, meta) -> row.get("legacy", Boolean.class))
                .one()
                .onErrorResume(e -> Mono.empty())
                .block(TIMEOUT);
        if (Boolean.TRUE.equals(legacy)) {
            log.warn("Found query_draft / published_query in the public schema. Older drafts and published queries live "
                    + "there, not in query_engine. Run query-engine/db/migrate_public_to_query_engine.sql as an "
                    + "administrator to move them.");
        }
    }

    private void auditStoredQueries() {
        List<PublishedQuery> all = publishedQueries.findAll().collectList().block(TIMEOUT);
        if (all == null) {
            return;
        }
        int rejected = 0;
        for (PublishedQuery query : all) {
            List<ValidationError> errors = sqlGuard.analyze(query.validatedQuery().sql()).errors();
            if (!errors.isEmpty()) {
                rejected++;
                log.warn("Published query '{}' no longer passes the SQL rules and will be refused when called: {}",
                        query.queryName(), errors.stream().map(e -> e.code() + " " + e.message()).toList());
            }
        }
        log.info("Checked {} published queries against the SQL rules: {} rejected.", all.size(), rejected);
    }

    private record Login(String login, boolean superuser, boolean bypassRls, boolean ownsEngineSchema,
                         String writableTables) {
    }
}
