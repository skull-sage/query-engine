package com.brac.query.engine.execution;

import com.brac.query.engine.error.EngineException;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Time limits on a stream, so a client that stops reading, or reads forever, cannot hold a
 * database connection indefinitely.
 *
 * <p>Both limits fail the stream with an error. They must never complete it: a stream that ends
 * cleanly looks like a successful, complete result to the client. (That is why this does not use
 * {@code take(Duration)}.) After the first row has been sent the response status is fixed, so the
 * error aborts the connection and the client sees an incomplete transfer; before the first row it
 * is still an ordinary {@code 504} JSON error.
 *
 * <p>Neither limit buffers or changes demand: the guards only watch the gap between rows.
 */
public final class StreamGuards {

    public static final String STALLED = "STREAM_STALLED";
    public static final String TOO_LONG = "STREAM_TOO_LONG";

    private StreamGuards() {
    }

    /**
     * @param stall       maximum gap between two rows delivered downstream. Rows are only delivered
     *                    when the client has read enough to ask for more, so a gap this long means
     *                    the client stopped reading
     * @param maxDuration maximum total time from subscription (also covers the wait for the first row)
     */
    public static <T> Flux<T> apply(Flux<T> source, String queryName, Duration stall, Duration maxDuration) {
        return Flux.defer(() -> {
            long deadline = nowNanos() + maxDuration.toNanos();
            return source.timeout(
                    Mono.delay(maxDuration),
                    row -> Mono.delay(smaller(stall, Duration.ofNanos(Math.max(0, deadline - nowNanos())))),
                    Flux.defer(() -> Flux.error(nowNanos() >= deadline
                            ? new EngineException(TOO_LONG, HttpStatus.GATEWAY_TIMEOUT, queryName,
                                    "The stream exceeded the maximum duration of " + maxDuration.toSeconds() + "s.")
                            : new EngineException(STALLED, HttpStatus.GATEWAY_TIMEOUT, queryName,
                                    "The client stopped reading for " + stall.toSeconds() + "s."))));
        });
    }

    /** Uses the scheduler's clock so tests with virtual time see the same time the timers do. */
    private static long nowNanos() {
        return Schedulers.parallel().now(TimeUnit.NANOSECONDS);
    }

    private static Duration smaller(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
