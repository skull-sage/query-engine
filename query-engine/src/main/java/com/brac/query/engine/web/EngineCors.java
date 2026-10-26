package com.brac.query.engine.web;

import com.brac.query.engine.config.CorsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.WebFilter;

import java.util.List;

/**
 * Builds the CORS filter for the engine's endpoints from {@link CorsProperties}. Kept separate from
 * the auto-configuration so it can be tested on its own.
 *
 * <p>Only what the Query Builder UI needs is allowed: {@code GET}, {@code POST} and the preflight,
 * the request headers the engine reads ({@code X-Author}, {@code X-Roles}) plus the content
 * headers, and no credentials (the engine uses no cookies). {@code Retry-After} is exposed so a
 * browser client can read it on a {@code 503}.
 */
public final class EngineCors {

    private static final Logger log = LoggerFactory.getLogger(EngineCors.class);

    private EngineCors() {
    }

    /** True when the settings turn CORS on: the switch is on and at least one origin is listed. */
    public static boolean active(CorsProperties props) {
        return props.enabled() && props.allowedOrigins().stream().anyMatch(o -> !o.isBlank());
    }

    /** One line for the startup log saying what the settings amount to. */
    public static String describe(CorsProperties props) {
        if (!props.enabled()) {
            return "CORS is off (query-engine.cors.enabled=false): pages on other origins cannot read this service's responses";
        }
        if (!active(props)) {
            return "CORS is off (query-engine.cors.allowed-origins is empty): pages on other origins cannot read this service's responses";
        }
        return "CORS is on for " + props.allowedOrigins();
    }

    /**
     * The filter to register: the CORS filter when {@link #active}, otherwise a pass-through that
     * leaves every request alone, so turning CORS off needs no other change.
     */
    public static WebFilter create(CorsProperties props) {
        return active(props) ? filter(props) : (exchange, chain) -> chain.filter(exchange);
    }

    public static CorsWebFilter filter(CorsProperties props) {
        List<String> origins = props.allowedOrigins();
        if (origins.contains("*")) {
            log.warn("query-engine.cors.allowed-origins contains '*': any website a user visits can call this service "
                    + "from their browser. List the exact origins instead.");
        }
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of(HttpMethod.GET.name(), HttpMethod.POST.name(), HttpMethod.OPTIONS.name()));
        config.setAllowedHeaders(List.of("Content-Type", "Accept", "X-Author", "X-Roles"));
        config.setExposedHeaders(List.of("Retry-After"));
        config.setAllowCredentials(false);
        config.setMaxAge(props.maxAge());
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}
