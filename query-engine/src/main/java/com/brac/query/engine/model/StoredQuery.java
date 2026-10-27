package com.brac.query.engine.model;

import java.time.Instant;
import java.util.List;

/**
 * One row of {@code qe_stored_query}: a query in whichever {@link QueryState} it is in.
 *
 * @param entry          the sql, declared params and fetch size as submitted; {@code allowedRoles} is what was chosen
 *                       at the last publish (empty for a query that was never published)
 * @param validatedQuery the descriptor the executor runs; null while {@code errors} is not empty. Once published
 *                       its {@code allowedRoles} are the role gate
 * @param errors         validation errors of the definition; empty when valid
 * @param author         the holder while drafted or simulated (the lock); the last drafter once published
 * @param revision       increases with every write; simulate and publish only apply to the revision they read
 * @param publishedAt    set when first published and never cleared, so a query that is being edited still knows it
 *                       was live before
 */
public record StoredQuery(
        String queryName,
        QueryState state,
        QueryEntry entry,
        ValidatedQuery validatedQuery,
        List<ValidationError> errors,
        String author,
        long revision,
        Instant updatedAt,
        Instant publishedAt,
        String publishedBy
) {
    /** True when the definition has no errors, so it can be simulated. */
    public boolean valid() {
        return errors.isEmpty() && validatedQuery != null;
    }

    /** True when this query was live at some point (it may be offline now because it is being edited). */
    public boolean previouslyPublished() {
        return publishedAt != null;
    }
}
