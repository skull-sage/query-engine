package com.brac.query.engine.model;

import java.time.Instant;
import java.util.List;

/**
 * Server-side draft persisted in Postgres (see {@code query_draft}), keyed by queryName. See PRD
 * "How drafting stores the draft". At most one open draft exists per queryName. No versioning:
 * drafting a queryName again simply overwrites the stored draft, and publishing simply overwrites
 * any previously published query of the same name. A draft never expires: it stays, and keeps its
 * author's lock, until it is published or discarded.
 */
public record DraftRecord(
        String queryName,
        QueryEntry entry,
        ValidatedQuery validatedQuery,
        DraftStatus status,
        List<ValidationError> errors,
        boolean simulated,
        String author,
        Instant updatedAt
) {

    public DraftRecord withSimulated(Instant now) {
        return new DraftRecord(queryName, entry, validatedQuery, status, errors, true, author, now);
    }
}
