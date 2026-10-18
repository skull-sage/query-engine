package com.brac.query.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * The dedicated connection pool used to read projection data for simulate and {@code /data-query}.
 * It is separate from the service's main pool ({@code spring.r2dbc.*}), which the engine's own
 * calls (draft, publish, list, the published-query lookup) keep using. A stream holds a
 * connection for as long as its client takes to read, so sharing one pool would let a few slow
 * readers block the authoring endpoints and the lookup that begins every {@code /data-query}.
 *
 * <p>{@code url}, {@code username} and {@code password} default to the service's
 * {@code spring.r2dbc.*} values, so a service needs no extra configuration; set them to read
 * projections from a different database, for example a read replica.
 */
@ConfigurationProperties(prefix = "query-engine.projection")
public record ProjectionPoolProperties(
        String url,
        String username,
        String password,
        @DefaultValue("2") int initialSize,
        @DefaultValue("20") int maxSize,
        @DefaultValue("30m") Duration maxIdleTime
) {
}
