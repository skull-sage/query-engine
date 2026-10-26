package com.brac.query.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Which browser origins may call the engine's endpoints directly (CORS). A browser app served from
 * another origin, such as the Query Builder UI on {@code http://localhost:5173}, can only call a
 * domain service if the service answers the browser's preflight and sends
 * {@code Access-Control-Allow-Origin} for that origin.
 *
 * <p>Off unless {@code enabled} is true (the default) <em>and</em> at least one origin is listed.
 * Off means the service does no CORS handling at all: it sends no CORS headers, so a browser page
 * on another origin cannot read its responses (same-origin pages, a dev-server proxy and
 * non-browser callers are unaffected). List origins exactly (scheme, host and port); {@code *} is
 * accepted but logged as a warning, since the engine has no authentication of its own yet.
 *
 * @param enabled        the switch: {@code false} turns CORS off whatever origins are listed, so the
 *                       list can stay in the file for when it is needed
 * @param allowedOrigins origins allowed to call the service, for example {@code http://localhost:5173}
 * @param maxAge         how long a browser may cache a preflight answer, so repeated calls do not
 *                       each cost an extra {@code OPTIONS} request
 */
@ConfigurationProperties(prefix = "query-engine.cors")
public record CorsProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue List<String> allowedOrigins,
        @DefaultValue("30m") Duration maxAge
) {
}
