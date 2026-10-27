package com.brac.query.engine.security;

import com.brac.query.engine.config.SecurityProperties;
import com.brac.query.engine.execution.ProjectionDatabaseClient;
import com.brac.query.engine.model.StoredQuery;
import com.brac.query.engine.model.QueryState;
import com.brac.query.engine.model.ValidationError;
import com.brac.query.engine.store.StoredQueryRepository;
import com.brac.query.engine.validation.SqlGuard;
import io.r2dbc.spi.R2dbcException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Runs once at startup and checks the setup that docs/security_protocol.md depends on.
 *
 * <p><b>Read-only self-test (always).</b> Author SQL must not be able to change anything. The engine
 * sends a harmless write (a temporary table) through the very path author SQL takes and requires
 * Postgres to refuse it as a read-only transaction (SQLSTATE 25006), and requires the session to report
 * {@code transaction_read_only = on}. If either is wrong the service does not start, in every mode: it
 * means the protection that holds author SQL back is not working.
 *
 * <p><b>The login.</b> The engine uses the service's own login, which writes the service's projections, so
 * the transaction is what protects them, not the login. A superuser ignores every permission and can read
 * server files, and a login that bypasses row-level security ignores that too. In {@code enforce} mode
 * (the default) the service refuses to start with either; {@code query-engine.security.mode=warn} logs and
 * starts anyway, for a development machine whose database uses the image's default superuser.
 *
 * <p><b>Stored queries.</b> Every published query is run through {@link SqlGuard}. One that no longer
 * passes (published before the rules existed, or after they were tightened) is logged, and refused when
 * called either way.
 */
@Component
public class SecurityStartupCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SecurityStartupCheck.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String READ_ONLY_VIOLATION = "25006";

    private static final String LOGIN_SQL = """
            SELECT current_user::text AS login,
                   current_setting('transaction_read_only') AS read_only,
                   r.rolsuper AS superuser,
                   r.rolbypassrls AS bypass_rls
              FROM pg_roles r
             WHERE r.rolname = current_user
            """;

    private final ProjectionDatabaseClient projectionDatabaseClient;
    private final StoredQueryRepository store;
    private final SqlGuard sqlGuard;
    private final SecurityProperties properties;

    public SecurityStartupCheck(ProjectionDatabaseClient projectionDatabaseClient, StoredQueryRepository store, SqlGuard sqlGuard,
                                SecurityProperties properties) {
        this.projectionDatabaseClient = projectionDatabaseClient;
        this.store = store;
        this.sqlGuard = sqlGuard;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, Object> login = projectionDatabaseClient.readOnlyQuery(LOGIN_SQL).blockFirst(TIMEOUT);
        requireReadOnly(login);
        if (properties.mode() != SecurityProperties.Mode.OFF) {
            checkLogin(login);
        }
        auditStoredQueries();
    }

    private void requireReadOnly(Map<String, Object> login) {
        if (login == null || !"on".equals(login.get("read_only"))) {
            throw new IllegalStateException("Author SQL is not running in a read-only transaction (transaction_read_only is '"
                    + (login == null ? "unknown" : login.get("read_only")) + "'). Refusing to start: this is what keeps author "
                    + "SQL from changing data. See docs/security_protocol.md.");
        }
        try {
            projectionDatabaseClient.readOnlyQuery("CREATE TEMPORARY TABLE qe_startup_probe (x int)").collectList().block(TIMEOUT);
        } catch (RuntimeException e) {
            String state = sqlState(e);
            if (READ_ONLY_VIOLATION.equals(state)) {
                return; // refused as a read-only transaction, which is the point
            }
            throw new IllegalStateException("Could not confirm that author SQL is read-only: the probe write failed with "
                    + (state == null ? e.getClass().getSimpleName() : "SQLSTATE " + state) + " instead of 25006 (read-only transaction). "
                    + "Refusing to start. " + e.getMessage(), e);
        }
        throw new IllegalStateException("Author SQL could create a table: the read-only transaction is not in effect. "
                + "Refusing to start. See docs/security_protocol.md.");
    }

    private void checkLogin(Map<String, Object> login) {
        String name = String.valueOf(login.get("login"));
        List<String> problems = new java.util.ArrayList<>();
        if (Boolean.TRUE.equals(login.get("superuser"))) {
            problems.add("the database login '" + name + "' is a superuser, so it ignores every permission and can read server files");
        }
        if (Boolean.TRUE.equals(login.get("bypass_rls"))) {
            problems.add("the database login '" + name + "' bypasses row-level security");
        }
        if (problems.isEmpty()) {
            log.info("Security check passed: author SQL runs read-only as login '{}'.", name);
            return;
        }
        String message = "The service's database login is more powerful than the query engine should run with:\n  - "
                + String.join("\n  - ", problems);
        if (properties.mode() == SecurityProperties.Mode.ENFORCE) {
            throw new IllegalStateException(message + "\nUse a plain login (NOSUPERUSER, NOBYPASSRLS) that can read and write the "
                    + "service's projection tables, or set query-engine.security.mode=warn to start anyway on a development machine.");
        }
        log.warn("{}\nStarting anyway because query-engine.security.mode=warn.", message);
    }

    private void auditStoredQueries() {
        List<StoredQuery> published = store.findAll().filter(q -> q.state() == QueryState.PUBLISHED)
                .collectList().block(TIMEOUT);
        if (published == null) {
            return;
        }
        int rejected = 0;
        for (StoredQuery query : published) {
            List<ValidationError> errors = sqlGuard.analyze(query.validatedQuery().sql()).errors();
            if (!errors.isEmpty()) {
                rejected++;
                log.warn("Published query '{}' no longer passes the SQL rules and will be refused when called: {}",
                        query.queryName(), errors.stream().map(e -> e.code() + " " + e.message()).toList());
            }
        }
        log.info("Checked {} published queries against the SQL rules: {} rejected.", published.size(), rejected);
    }

    private static String sqlState(Throwable error) {
        int depth = 0;
        for (Throwable t = error; t != null && depth < 12; t = t.getCause(), depth++) {
            if (t instanceof R2dbcException r2dbc && r2dbc.getSqlState() != null) {
                return r2dbc.getSqlState();
            }
        }
        return null;
    }
}
