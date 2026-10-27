package com.brac.query.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Sizing of the dedicated connection pool that runs author SQL (simulate, {@code /data-query} and the
 * draft-time check). It connects to the same database, with the same login, as the service
 * ({@code spring.r2dbc.*}); it is a separate pool so that a stream holding a connection for as long as its
 * client takes to read cannot starve the service's own pool, which the engine uses for storing and
 * listing queries and the service uses for its event consumers.
 */
@ConfigurationProperties(prefix = "query-engine.projection")
public record DBProjectionPoolProperties(
        @DefaultValue("2") int initialSize,
        @DefaultValue("20") int maxSize,
        @DefaultValue("30m") Duration maxIdleTime
) {
}
