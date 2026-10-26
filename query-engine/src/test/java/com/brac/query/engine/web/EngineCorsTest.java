package com.brac.query.engine.web;

import com.brac.query.engine.config.CorsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.reactive.CorsWebFilter;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class EngineCorsTest {

    private static final String UI = "http://localhost:5173";

    private final CorsWebFilter filter = EngineCors.filter(new CorsProperties(true, List.of(UI), Duration.ofMinutes(30)));

    private MockServerWebExchange preflight(String origin, String method, String headers) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.options("http://localhost:8081/data-query")
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method);
        if (headers != null) {
            request.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, headers);
        }
        return MockServerWebExchange.from(request);
    }

    @Test
    void preflightFromAllowedOriginIsAnsweredWithoutReachingTheEndpoint() {
        MockServerWebExchange exchange = preflight(UI, "POST", "content-type,x-roles,accept");
        AtomicBoolean reached = new AtomicBoolean();
        filter.filter(exchange, ex -> {
            reached.set(true);
            return Mono.empty();
        }).block();

        HttpHeaders headers = exchange.getResponse().getHeaders();
        assertThat(reached).isFalse();
        assertThat(headers.getAccessControlAllowOrigin()).isEqualTo(UI);
        assertThat(headers.getAccessControlAllowMethods()).contains(HttpMethod.POST);
        assertThat(headers.getAccessControlAllowHeaders()).map(String::toLowerCase).containsExactlyInAnyOrder("content-type", "x-roles", "accept");
        assertThat(headers.getAccessControlMaxAge()).isEqualTo(1800);
        assertThat(headers.getAccessControlAllowCredentials()).isFalse();
    }

    @Test
    void preflightFromOtherOriginIsRefused() {
        MockServerWebExchange exchange = preflight("http://evil.example", "POST", "content-type");
        filter.filter(exchange, ex -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange.getResponse().getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    @Test
    void preflightAskingForAnUnlistedHeaderIsRefused() {
        MockServerWebExchange exchange = preflight(UI, "POST", "authorization");
        filter.filter(exchange, ex -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void actualRequestFromAllowedOriginGetsTheHeadersAndReachesTheEndpoint() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("http://localhost:8081/data-query")
                .header(HttpHeaders.ORIGIN, UI));
        AtomicBoolean reached = new AtomicBoolean();
        filter.filter(exchange, ex -> {
            reached.set(true);
            return Mono.empty();
        }).block();

        assertThat(reached).isTrue();
        assertThat(exchange.getResponse().getHeaders().getAccessControlAllowOrigin()).isEqualTo(UI);
        assertThat(exchange.getResponse().getHeaders().getAccessControlExposeHeaders()).contains("Retry-After");
    }

    @Test
    void requestWithoutAnOriginIsUntouched() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("http://localhost:8081/data-query"));
        AtomicBoolean reached = new AtomicBoolean();
        filter.filter(exchange, ex -> {
            reached.set(true);
            return Mono.empty();
        }).block();

        assertThat(reached).isTrue();
        assertThat(exchange.getResponse().getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    @Test
    void switchedOffDoesNoCorsHandlingEvenWithOriginsListed() {
        CorsProperties off = new CorsProperties(false, List.of(UI), Duration.ofMinutes(30));
        assertThat(EngineCors.active(off)).isFalse();

        MockServerWebExchange exchange = preflight(UI, "POST", "content-type");
        AtomicBoolean reached = new AtomicBoolean();
        EngineCors.create(off).filter(exchange, ex -> {
            reached.set(true);
            return Mono.empty();
        }).block();

        assertThat(reached).isTrue();
        assertThat(exchange.getResponse().getHeaders().getAccessControlAllowOrigin()).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void noOriginsListedIsOffToo() {
        CorsProperties empty = new CorsProperties(true, List.of(), Duration.ofMinutes(30));
        assertThat(EngineCors.active(empty)).isFalse();
        assertThat(EngineCors.active(new CorsProperties(true, List.of(" "), Duration.ofMinutes(30)))).isFalse();
        assertThat(EngineCors.describe(empty)).contains("allowed-origins is empty");
        assertThat(EngineCors.describe(new CorsProperties(false, List.of(UI), Duration.ofMinutes(30)))).contains("enabled=false");
        assertThat(EngineCors.describe(new CorsProperties(true, List.of(UI), Duration.ofMinutes(30)))).contains("CORS is on for [" + UI + "]");
    }
}
