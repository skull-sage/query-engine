package com.brac.query.engine.execution;

import com.brac.query.engine.error.EngineException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class StreamGuardsTest {

    @Test
    void fastStreamCompletesNormally() {
        StepVerifier.withVirtualTime(() ->
                        StreamGuards.apply(Flux.range(1, 3), "q", Duration.ofSeconds(30), Duration.ofMinutes(10)))
                .expectNext(1, 2, 3)
                .verifyComplete();
    }

    @Test
    void stalledClientFailsTheStreamInsteadOfCompleting() {
        StepVerifier.withVirtualTime(() ->
                        StreamGuards.apply(Flux.range(1, 3).delayElements(Duration.ofSeconds(40)), "q",
                                Duration.ofSeconds(30), Duration.ofMinutes(10)))
                .thenAwait(Duration.ofSeconds(40)).expectNext(1).thenAwait(Duration.ofSeconds(31))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(EngineException.class);
                    assertThat(((EngineException) e).getCode()).isEqualTo(StreamGuards.STALLED);
                })
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void totalDurationFailsTheStream() {
        Sinks.Many<Integer> sink = Sinks.many().unicast().onBackpressureBuffer();
        StepVerifier.withVirtualTime(() ->
                        StreamGuards.apply(sink.asFlux(), "q", Duration.ofMinutes(5), Duration.ofSeconds(60)))
                .thenAwait(Duration.ofSeconds(61))
                .expectErrorSatisfies(e -> assertThat(((EngineException) e).getCode()).isEqualTo(StreamGuards.TOO_LONG))
                .verify(Duration.ofSeconds(5));
    }
}
