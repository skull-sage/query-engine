package com.brac.query.engine.dto;

import com.brac.query.engine.model.ParamSpec;

import java.time.Instant;
import java.util.List;

public record ListResponse(List<Item> items, String nextCursor) {

    public record Item(String queryName, PublishedSummary published, DraftSummary draft) {
    }

    public record PublishedSummary(
            String sql,
            List<ParamSpec> params,
            Integer fetchSize,
            List<String> allowedRoles,
            Instant publishedAt,
            String publishedBy
    ) {
    }

    public record DraftSummary(
            String status,
            boolean simulated,
            String author,
            Instant updatedAt
    ) {
    }
}
