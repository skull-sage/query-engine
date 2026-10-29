package com.brac.query.engine.web;

import com.brac.query.engine.dto.DataQueryRequest;
import com.brac.query.engine.model.ValidatedQuery;
import com.brac.query.engine.service.DataQueryService;
import com.brac.query.engine.service.Invocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataQueryControllerTest {

    private DataQueryService service;
    private WebTestClient client;

    private final Invocation dummyInvocation = new Invocation(
            "orders.byStatus",
            new ValidatedQuery("SELECT * FROM order_projection", List.of(), List.of("senior_DBA"), null),
            Map.of("status", "SHIPPED")
    );

    @BeforeEach
    void setUp() {
        service = Mockito.mock(DataQueryService.class);
        DataQueryController controller = new DataQueryController(service);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        client = WebTestClient.bindToController(controller)
                .validator(validator)
                .controllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void dataQueryExecutesDirectlyWithoutCursorOrStreaming() {
        when(service.prepare(eq("orders.byStatus"), any(), eq(List.of("senior_DBA"))))
                .thenReturn(Mono.just(dummyInvocation));
        when(service.executeDirect(eq(dummyInvocation), eq(List.of("senior_DBA"))))
                .thenReturn(Mono.just(List.of(
                        Map.of("id", 1, "status", "SHIPPED"),
                        Map.of("id", 2, "status", "SHIPPED")
                )));

        client.post()
                .uri("/data-query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new DataQueryRequest("orders.byStatus", Map.of("status", "SHIPPED")))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[0].id").isEqualTo(1)
                .jsonPath("$[0].status").isEqualTo("SHIPPED");

        verify(service).executeDirect(dummyInvocation, List.of("senior_DBA"));
    }

    @Test
    void dataQueryPassesCustomRoles() {
        when(service.prepare(eq("orders.byStatus"), any(), eq(List.of("finance", "analyst"))))
                .thenReturn(Mono.just(dummyInvocation));
        when(service.executeDirect(eq(dummyInvocation), eq(List.of("finance", "analyst"))))
                .thenReturn(Mono.just(List.of(Map.of("id", 10))));

        client.post()
                .uri("/data-query")
                .header("X-Roles", "finance, analyst")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new DataQueryRequest("orders.byStatus", Map.of()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].id").isEqualTo(10);

        verify(service).executeDirect(dummyInvocation, List.of("finance", "analyst"));
    }

    @Test
    void dataQueryStreamingNdjsonStreamsRows() {
        when(service.prepare(eq("orders.byStatus"), any(), eq(List.of("senior_DBA"))))
                .thenReturn(Mono.just(dummyInvocation));
        when(service.stream(eq(dummyInvocation), eq(List.of("senior_DBA"))))
                .thenReturn(Flux.just(
                        Map.of("id", 1),
                        Map.of("id", 2)
                ));

        client.post()
                .uri("/data-query-streaming")
                .accept(MediaType.APPLICATION_NDJSON)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new DataQueryRequest("orders.byStatus", Map.of()))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_NDJSON)
                .expectBody(String.class)
                .value(body -> {
                    org.assertj.core.api.Assertions.assertThat(body).contains("{\"id\":1}");
                    org.assertj.core.api.Assertions.assertThat(body).contains("{\"id\":2}");
                });

        verify(service).stream(dummyInvocation, List.of("senior_DBA"));
    }

    @Test
    void dataQueryStreamingJsonReturnsBoundedList() {
        when(service.prepare(eq("orders.byStatus"), any(), eq(List.of("senior_DBA"))))
                .thenReturn(Mono.just(dummyInvocation));
        when(service.bounded(eq(dummyInvocation), eq(List.of("senior_DBA"))))
                .thenReturn(Mono.just(List.of(Map.of("id", 1))));

        client.post()
                .uri("/data-query-streaming")
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new DataQueryRequest("orders.byStatus", Map.of()))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].id").isEqualTo(1);

        verify(service).bounded(dummyInvocation, List.of("senior_DBA"));
    }

    @Test
    void dataQueryRejectsBlankQueryName() {
        client.post()
                .uri("/data-query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("INVALID_REQUEST")
                .jsonPath("$.message").isNotEmpty();
    }
}
