package com.brac.query.engine.execution;

import com.brac.query.engine.error.EngineException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static com.brac.query.engine.execution.ProjectionException.Kind.BUSY;
import static com.brac.query.engine.execution.ProjectionException.Kind.FAILED;
import static org.assertj.core.api.Assertions.assertThat;

class ProjectionExceptionTest {

    private static ProjectionException failed(String sqlState) {
        return new ProjectionException(FAILED, sqlState, "syntax error at or near \"FORM\"", null);
    }

    @Test
    void sqlTheDatabaseRejectsIsInvalidSqlForAuthors() {
        for (String state : new String[] {"42601", "42703", "42P01", "42883", "22012", "0A000"}) {
            assertThat(failed(state).invalidSql()).as(state).isTrue();
        }
        EngineException error = failed("42601").toEngineException("orders.q", true);
        assertThat(error.getCode()).isEqualTo("SQL_INVALID");
        assertThat(error.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(error.getMessage()).contains("syntax error at or near \"FORM\"").contains("SQLSTATE 42601");
    }

    @Test
    void otherFailuresAreNotBlamedOnTheSql() {
        assertThat(failed("53200").invalidSql()).isFalse();
        assertThat(failed(null).invalidSql()).isFalse();
        assertThat(new ProjectionException(BUSY, "08006", "x", null).invalidSql()).isFalse();
        assertThat(failed("53200").toEngineException("orders.q", true).getCode()).isEqualTo("QUERY_EXECUTION_FAILED");
    }

    @Test
    void callersOfPublishedQueriesGetNoDatabaseWording() {
        EngineException error = failed("42601").toEngineException("orders.q", false);
        assertThat(error.getCode()).isEqualTo("QUERY_EXECUTION_FAILED");
        assertThat(error.getMessage()).doesNotContain("FORM");
    }
}
