package com.brac.query.engine.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

/** Body for {@code POST /engine/draft} and {@code POST /engine/draft-yml}. */
public record DraftRequest(
        @NotBlank String queryName,
        @NotBlank String sql,
        Map<String, String> params,
        @JsonProperty("allowed_roles") List<String> allowedRoles,
        Integer fetchSize
) {
}
