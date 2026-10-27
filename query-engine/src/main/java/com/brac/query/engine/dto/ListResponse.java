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

    /**
     * A query that is being worked on ({@code drafted} or {@code simulated}). {@code status} is
     * {@code DRAFT} when its definition has errors and {@code VALIDATED} otherwise;
     * {@code previouslyPublished} marks a query that was live and is offline while it is edited.
     */
    public record DraftSummary(
            String status,
            String state,
            boolean simulated,
            String author,
            Instant updatedAt,
            boolean previouslyPublished
    ) {
    }
}
