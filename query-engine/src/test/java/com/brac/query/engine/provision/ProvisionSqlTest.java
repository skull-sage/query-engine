package com.brac.query.engine.provision;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProvisionSqlTest {

    private static final ProvisionProperties DEFAULTS =
            new ProvisionProperties(new ProvisionProperties.Admin(null, null, null), null, List.of("public"), false);

    private static String template() throws IOException {
        return StreamUtils.copyToString(new ClassPathResource(Provisioner.TEMPLATE).getInputStream(), StandardCharsets.UTF_8);
    }

    private static MockEnvironment configured() {
        return new MockEnvironment()
                .withProperty("spring.r2dbc.url", "r2dbc:postgresql://localhost:5433/order_query")
                .withProperty("spring.r2dbc.username", "order_query")
                .withProperty("spring.r2dbc.password", "svc-secret")
                .withProperty("query-engine.store.username", "query_engine_owner")
                .withProperty("query-engine.store.password", "owner-secret")
                .withProperty("query-engine.projection.username", "query_engine_ro")
                .withProperty("query-engine.projection.password", "reader-secret");
    }

    @Test
    void namesAndValuesAreQuotedSoTheyCannotBreakOut() {
        String sql = ProvisionSql.render("CREATE ROLE {{ident:login}} PASSWORD {{literal:pw}}",
                Map.of("login", "we\"ird", "pw", "it's a \\ test"), false);
        assertThat(sql).isEqualTo("CREATE ROLE \"we\"\"ird\" PASSWORD E'it''s a \\\\ test'");
    }

    @Test
    void listsAreCommaSeparated() {
        String sql = ProvisionSql.render("ARRAY[{{literals:s}}] / {{idents:s}}", Map.of("s", List.of("public", "orders")), false);
        assertThat(sql).isEqualTo("ARRAY[E'public', E'orders'] / \"public\", \"orders\"");
    }

    @Test
    void passwordsCanBeHiddenForPrinting() {
        String sql = ProvisionSql.render("PASSWORD {{literal:owner_password}} FOR {{literal:owner_login}}",
                Map.of("owner_password", "hunter2", "owner_login", "o"), true);
        assertThat(sql).isEqualTo("PASSWORD '********' FOR E'o'").doesNotContain("hunter2");
    }

    @Test
    void aMissingOrUnsafeValueIsAnErrorNotAnEmptyString() {
        assertThatThrownBy(() -> ProvisionSql.render("{{ident:nope}}", Map.of(), false)).hasMessageContaining("nope");
        assertThatThrownBy(() -> ProvisionSql.render("{{literal:x}}", Map.of("x", "a$provision$b"), false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProvisionSql.render("{{literal:x}}", Map.of("x", "a\0b"), false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProvisionSql.render("{{ident:x}}", Map.of("x", ""), false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProvisionSql.render("{{idents:x}}", Map.of("x", "single"), false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void statementsSplitOnlyAtTheSemicolonsThatEndOne() {
        List<String> statements = ProvisionSql.statements("""
                -- first
                SELECT 'a;b';          -- trailing ; comment
                /* ; */ DO $provision$ BEGIN PERFORM 1; PERFORM 2; END $provision$;
                ;
                -- only a comment;
                SELECT 3
                """);
        assertThat(statements).hasSize(3);
        assertThat(statements.get(0)).contains("SELECT 'a;b'");
        assertThat(statements.get(1)).contains("PERFORM 1; PERFORM 2;");
        assertThat(statements.get(2)).isEqualTo("-- only a comment;\nSELECT 3");
    }

    @Test
    void unterminatedTextIsRefused() {
        assertThatThrownBy(() -> ProvisionSql.statements("SELECT 'abc")).hasMessageContaining("Unterminated");
    }

    @Test
    void theShippedTemplateRendersToTheExpectedStatements() throws IOException {
        Map<String, Object> values = Provisioner.values(configured(), DEFAULTS);
        String rendered = ProvisionSql.render(template(), values, false);
        List<String> statements = ProvisionSql.statements(rendered);

        assertThat(rendered).doesNotContain("{{");
        assertThat(statements).hasSize(13);
        assertThat(rendered).contains("CREATE ROLE \"order_query\" LOGIN PASSWORD E'svc-secret'");
        assertThat(rendered).contains("ALTER ROLE \"query_engine_owner\" WITH LOGIN PASSWORD E'owner-secret'");
        assertThat(rendered).contains("ALTER ROLE \"query_engine_ro\" WITH LOGIN PASSWORD E'reader-secret'");
        assertThat(rendered).contains("GRANT pg_read_all_data TO \"query_engine_ro\"");
        assertThat(rendered).contains("ALTER ROLE \"query_engine_ro\" SET search_path = \"public\"");
        assertThat(rendered).contains("REVOKE CONNECT ON DATABASE \"order_query\" FROM PUBLIC");
        assertThat(rendered).contains("GRANT CONNECT ON DATABASE \"order_query\" TO \"order_query\", \"query_engine_owner\", \"query_engine_ro\"");
        assertThat(rendered).contains("CREATE SCHEMA IF NOT EXISTS \"query_engine\" AUTHORIZATION \"query_engine_owner\"");

        String printed = ProvisionSql.render(template(), values, true);
        assertThat(printed).doesNotContain("svc-secret").doesNotContain("owner-secret").doesNotContain("reader-secret");
    }

    @Test
    void theTemplateOnlyAsksForValuesTheProvisionerSupplies() throws IOException {
        Map<String, Object> values = Provisioner.values(configured(), DEFAULTS);
        assertThat(values.keySet()).containsAll(ProvisionSql.placeholderNames(template()));
    }

    @Test
    void theDatabaseNameComesFromTheUrlUnlessSet() {
        assertThat(Provisioner.values(configured(), DEFAULTS)).containsEntry("database", "order_query");
        ProvisionProperties named = new ProvisionProperties(DEFAULTS.admin(), "other_db", List.of("public"), false);
        assertThat(Provisioner.values(configured(), named)).containsEntry("database", "other_db");
    }

    @Test
    void aMissingSettingIsNamedInTheError() {
        MockEnvironment env = configured();
        env.setProperty("query-engine.store.password", "");
        assertThatThrownBy(() -> Provisioner.values(env, DEFAULTS)).hasMessageContaining("query-engine.store.password");
        assertThatThrownBy(() -> Provisioner.values(new MockEnvironment(), DEFAULTS)).hasMessageContaining("spring.r2dbc.username");
    }

    @Test
    void theThreeLoginsMustBeDifferentUsers() {
        MockEnvironment env = configured().withProperty("query-engine.projection.username", "query_engine_owner");
        assertThatThrownBy(() -> Provisioner.values(env, DEFAULTS)).hasMessageContaining("three different users");
    }

    @Test
    void projectionSchemasMustNotIncludeTheEngineSchema() {
        ProvisionProperties bad = new ProvisionProperties(DEFAULTS.admin(), null, List.of("public", "query_engine"), false);
        assertThatThrownBy(() -> Provisioner.values(configured(), bad)).hasMessageContaining("query_engine");
        ProvisionProperties empty = new ProvisionProperties(DEFAULTS.admin(), null, List.of(" "), false);
        assertThatThrownBy(() -> Provisioner.values(configured(), empty)).hasMessageContaining("empty");
    }
}

