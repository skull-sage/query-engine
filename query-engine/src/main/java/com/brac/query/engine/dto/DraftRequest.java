package com.brac.query.engine.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

/**
 * Body for {@code POST /engine/draft} and {@code POST /engine/draft-yml}.
 *
 * @param unpublish must be {@code true} to save a draft over a query that is published. That takes the
 *                  query offline for callers until it is simulated and published again, so it is never
 *                  done by accident: without the flag the request is refused with {@code 409 QUERY_PUBLISHED}.
 */
public record DraftRequest(
        @NotBlank String queryName,
        @NotBlank String sql,
        Map<String, String> params,
        @JsonProperty("allowed_roles") List<String> allowedRoles,
        Integer fetchSize,
        Boolean unpublish
) {
}
