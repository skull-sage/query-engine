package com.brac.query.engine.validation;

import com.brac.query.engine.config.SqlGuardProperties;
import com.brac.query.engine.model.ValidationError;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs every case in {@code sql-guard-cases.json}. The UI's lexer runs the same file, so the two
 * cannot drift apart: change a rule in one place and the other's test fails until it follows.
 */
class SqlGuardTest {

    private final SqlGuard guard = new SqlGuard(SqlGuardProperties.defaults());

    @TestFactory
    Stream<DynamicTest> sharedCases() throws IOException {
        JsonNode cases;
        try (InputStream in = getClass().getResourceAsStream("/sql-guard-cases.json")) {
            assertThat(in).as("sql-guard-cases.json on the test classpath").isNotNull();
            cases = JsonMapper.builder().build().readTree(in);
        }
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : cases) {
            String name = c.get("name").asString();
            String sql = c.get("sql").asString();
            List<String> expectedErrors = strings(c.get("errors"));
            JsonNode expectedParams = c.get("params");
            tests.add(DynamicTest.dynamicTest(name, () -> {
                SqlGuard.Analysis analysis = guard.analyze(sql);
                assertThat(analysis.errors().stream().map(ValidationError::code).distinct().sorted().toList())
                        .as("error codes for: %s", sql)
                        .isEqualTo(expectedErrors.stream().sorted().toList());
                if (expectedParams != null) {
                    assertThat(List.copyOf(analysis.params())).as("params for: %s", sql).isEqualTo(strings(expectedParams));
                }
            }));
        }
        assertThat(tests).as("number of shared cases").hasSizeGreaterThan(50);
        return tests.stream();
    }

    @Test
    void theSeededSampleQueriesStillPass() {
        for (String sql : List.of(
                "SELECT orderId, status, total, branchId FROM order_projection WHERE status = :status AND branchId = :branchId",
                "SELECT orderId, status, total, branchId FROM order_projection WHERE orderId = :orderId",
                "SELECT sku, warehouse_id, quantity_on_hand FROM inventory_projection WHERE warehouse_id = :warehouseId AND status = :status",
                "SELECT order_id, status, total FROM order_history_projection WHERE order_date >= :from ORDER BY order_id")) {
            assertThat(guard.analyze(sql).errors()).as(sql).isEmpty();
        }
    }

    @Test
    void errorsSayWhereTheProblemIs() {
        var errors = guard.analyze("SELECT 1\nFROM t\nWHERE pg_sleep(1)").errors();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).message()).contains("pg_sleep").contains("line 3, column 7");
    }

    @Test
    void aDeniedFunctionListCanBeReplaced() {
        SqlGuard custom = new SqlGuard(new SqlGuardProperties(true, List.of("my_func", "other_*")));
        assertThat(custom.analyze("SELECT my_func(1)").errors()).hasSize(1);
        assertThat(custom.analyze("SELECT other_thing(1)").errors()).hasSize(1);
        assertThat(custom.analyze("SELECT pg_sleep(1)").errors()).isEmpty();
    }

    @Test
    void switchedOffItOnlyFindsParams() {
        SqlGuard off = new SqlGuard(new SqlGuardProperties(false, null));
        SqlGuard.Analysis analysis = off.analyze("DELETE FROM t WHERE a = :a");
        assertThat(analysis.errors()).isEmpty();
        assertThat(analysis.params()).containsExactly("a");
    }

    @Test
    void blankSqlIsLeftToTheValidator() {
        assertThat(guard.analyze("  ").errors()).isEmpty();
        assertThat(guard.analyze(null).errors()).isEmpty();
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : array) {
            out.add(n.asString());
        }
        return out;
    }
}
