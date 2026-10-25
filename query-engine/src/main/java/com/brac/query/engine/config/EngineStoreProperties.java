package com.brac.query.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * The connection the engine uses for its own tables (drafts, published queries, role catalog) in
 * the {@code query_engine} schema. Separate from the service's pool ({@code spring.r2dbc.*}) and
 * from the projection pool ({@code query-engine.projection.*}), so each can log in as a different
 * database user: this one as {@code query_engine_owner}, which owns the schema and can touch
 * nothing else. See docs/security_protocol.md.
 *
 * <p>{@code url}, {@code username} and {@code password} default to the service's
 * {@code spring.r2dbc.*} values. That keeps a service starting without any extra setup, but then
 * one login does everything, which the startup check refuses (see {@link SecurityProperties}).
 */
@ConfigurationProperties(prefix = "query-engine.store")
public record EngineStoreProperties(
        String url,
        String username,
        String password,
        @DefaultValue("1") int initialSize,
        @DefaultValue("5") int maxSize,
        @DefaultValue("30m") Duration maxIdleTime
) {
}
