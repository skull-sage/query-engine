package com.brac.query.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Tuning for streaming {@code /data-query} results from Postgres (see
 * docs/reactive_R2DBC_Cursor-impl.md). All values can be overridden in the consuming service's
 * {@code application.yml} under {@code query-engine.stream.*}.
 *
 * @param fetchSize        rows per database round trip when a query does not set its own
 * @param maxFetchSize     largest {@code fetchSize} an author may set on a query
 * @param jsonMaxRows      most rows returned in JSON mode; more is {@code 413 RESULT_TOO_LARGE}
 * @param stallTimeout     a stream is cut when no row has been delivered for this long (client stopped reading)
 * @param maxDuration      longest a single stream may run, which also bounds how long a connection and
 *                         database snapshot are held
 * @param statementTimeout Postgres {@code statement_timeout} on projection connections
 * @param acquireTimeout   how long a request waits for a projection connection before {@code 503}
 */
@ConfigurationProperties(prefix = "query-engine.stream")
public record StreamProperties(
        @DefaultValue("50") int fetchSize,
        @DefaultValue("10000") int maxFetchSize,
        @DefaultValue("1000") int jsonMaxRows,
        @DefaultValue("30s") Duration stallTimeout,
        @DefaultValue("10m") Duration maxDuration,
        @DefaultValue("60s") Duration statementTimeout,
        @DefaultValue("3s") Duration acquireTimeout
) {

    /** The query's own fetch size when it set one, otherwise the service default. */
    public int effectiveFetchSize(Integer queryFetchSize) {
        return queryFetchSize != null ? queryFetchSize : fetchSize;
    }
}
