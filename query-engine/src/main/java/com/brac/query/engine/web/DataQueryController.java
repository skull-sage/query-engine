package com.brac.query.engine.web;

import com.brac.query.engine.dto.DataQueryRequest;
import com.brac.query.engine.service.DataQueryService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.NotAcceptableStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Phase 2 runtime invocation endpoints, reactive end to end. This PoC stands in the
 * caller's roles with the X-Roles header rather than a real JWT, since no auth stack exists yet.
 *
 * <ul>
 *   <li>{@code /data-query}: executes queries reactively with R2DBC directly, excluding database
 *       cursors and streaming chunking. Returns a JSON array of rows.</li>
 *   <li>{@code /data-query-streaming}: serves runtime invocation in a streaming setting utilizing
 *       reactive API + R2DBC + database cursor. Supports {@code application/x-ndjson} for streaming
 *       as rows are consumed, or {@code application/json} bounded by {@code query-engine.stream.json-max-rows}.</li>
 * </ul>
 */
@RestController
public class DataQueryController {

    private final DataQueryService service;

    public DataQueryController(DataQueryService service) {
        this.service = service;
    }

    /**
     * Executes a published query reactively with R2DBC without a database cursor or streaming.
     * Returns all rows as a JSON list.
     */
    @PostMapping(path = "/data-query", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<List<Map<String, Object>>>> invoke(@Valid @RequestBody DataQueryRequest request,
                                                                  @RequestHeader(value = "X-Roles", defaultValue = "senior_DBA") String rolesHeader) {
        List<String> roles = parseRoles(rolesHeader);
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();

        return service.prepare(request.name(), params, roles)
                .flatMap(invocation -> service.executeDirect(invocation, roles))
                .map(ResponseEntity::ok);
    }

    /**
     * Streaming runtime invocation utilizing reactive API + R2DBC + database cursor.
     */
    @PostMapping("/data-query-streaming")
    public Mono<ResponseEntity<?>> invokeStreaming(@Valid @RequestBody DataQueryRequest request,
                                                   @RequestHeader(value = "X-Roles", defaultValue = "senior_DBA") String rolesHeader,
                                                   ServerWebExchange exchange) {
        boolean ndjson = wantsNdjson(exchange.getRequest().getHeaders());
        List<String> roles = parseRoles(rolesHeader);
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();

        return service.prepare(request.name(), params, roles).flatMap(invocation -> {
            if (ndjson) {
                return Mono.<ResponseEntity<?>>just(ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_NDJSON)
                        // A gateway that buffers the response would hide the pacing and hold memory.
                        .cacheControl(CacheControl.noStore())
                        .header("X-Accel-Buffering", "no")
                        .body(service.stream(invocation, roles)));
            }
            return service.bounded(invocation, roles).map(rows -> ResponseEntity.ok(rows));
        });
    }

    private static List<String> parseRoles(String rolesHeader) {
        if (rolesHeader == null || rolesHeader.isBlank()) {
            return List.of();
        }
        return Arrays.stream(rolesHeader.split(","))
                .map(String::trim)
                .filter(r -> !r.isEmpty())
                .toList();
    }

    /**
     * Picks NDJSON when it is the best-ranked acceptable type, JSON otherwise (including a missing
     * header and wildcards), and rejects requests that accept neither.
     */
    static boolean wantsNdjson(HttpHeaders headers) {
        List<MediaType> accepts = headers.getAccept();
        if (accepts.isEmpty()) {
            return false;
        }
        double bestQuality = 0;
        Boolean ndjson = null;
        for (MediaType type : accepts) {
            double quality = type.getQualityValue();
            if (quality <= bestQuality) {
                continue; // q=0 is "not acceptable"; ties go to whichever was listed first
            }
            if (type.equalsTypeAndSubtype(MediaType.APPLICATION_NDJSON)) {
                bestQuality = quality;
                ndjson = true;
            } else if (type.isCompatibleWith(MediaType.APPLICATION_JSON)) {
                bestQuality = quality;
                ndjson = false;
            }
        }
        if (ndjson == null) {
            throw new NotAcceptableStatusException(List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_NDJSON));
        }
        return ndjson;
    }
}
