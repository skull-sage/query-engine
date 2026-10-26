package com.brac.query.engine.execution;

import com.brac.query.engine.error.EngineException;
import org.springframework.http.HttpStatus;

/**
 * A failure reading projection data, classified so each caller can answer in the right way. It is
 * not an {@link EngineException} itself because how much detail may be shown depends on who is
 * asking: an author simulating a query needs the database's message to fix the SQL, while a
 * client app calling {@code /data-query} gets only the SQLSTATE (the detail is logged).
 */
public class ProjectionException extends RuntimeException {

    public enum Kind {
        /** Postgres cancelled the statement because it exceeded {@code statement_timeout}. */
        TIMEOUT,
        /** No connection could be obtained in time, or the database could not be reached. */
        BUSY,
        /** Postgres rejected or failed the statement (bad column, syntax, ...). */
        FAILED
    }

    private final Kind kind;
    private final String sqlState;
    private final String detail;

    public ProjectionException(Kind kind, String sqlState, String detail, Throwable cause) {
        super(kind + (sqlState != null ? " [" + sqlState + "]" : "") + (detail != null ? ": " + detail : ""), cause);
        this.kind = kind;
        this.sqlState = sqlState;
        this.detail = detail;
    }

    public Kind kind() {
        return kind;
    }

    public String sqlState() {
        return sqlState;
    }

    public String detail() {
        return detail;
    }

    /**
     * True when Postgres rejected the SQL text itself: a syntax error, an unknown table, column or
     * function, a type mismatch, or a constant expression that cannot be evaluated (SQLSTATE classes
     * 42, 22 and 0A). The author can fix these, as opposed to a failure of the database or the
     * connection.
     */
    public boolean invalidSql() {
        return kind == Kind.FAILED && sqlState != null
                && (sqlState.startsWith("42") || sqlState.startsWith("22") || sqlState.startsWith("0A"));
    }

    /** The one-line message shown to the author for invalid SQL, with the database's own wording. */
    public String invalidSqlMessage() {
        return "PostgreSQL rejected the SQL: " + (detail != null ? detail : "unknown error") + " (SQLSTATE " + sqlState + ")";
    }

    /**
     * @param exposeDetail include the database's own message (authoring calls) or only the SQLSTATE
     *                     (runtime calls)
     */
    public EngineException toEngineException(String queryName, boolean exposeDetail) {
        return switch (kind) {
            case TIMEOUT -> new EngineException("QUERY_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT, queryName,
                    "The query was cancelled because it ran longer than the database statement timeout.");
            case BUSY -> new EngineException("SERVICE_BUSY", HttpStatus.SERVICE_UNAVAILABLE, queryName,
                    "No database connection was available in time. Try again shortly.");
            case FAILED -> exposeDetail && invalidSql()
                    ? new EngineException("SQL_INVALID", HttpStatus.UNPROCESSABLE_ENTITY, queryName, invalidSqlMessage())
                    : new EngineException("QUERY_EXECUTION_FAILED", HttpStatus.INTERNAL_SERVER_ERROR, queryName,
                    "The query could not be executed"
                            + (sqlState != null ? " (SQLSTATE " + sqlState + ")" : "")
                            + (exposeDetail && detail != null ? ": " + detail : ".")
                            + (exposeDetail ? "" : " See the service log for details."));
        };
    }
}
