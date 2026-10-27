package com.brac.query.engine.dto;

import com.brac.query.engine.model.ValidatedQuery;
import com.brac.query.engine.model.ValidationError;

import java.util.List;
import java.util.Map;

/**
 * Response for {@code POST /engine/draft}, {@code POST /engine/draft-yml}, and {@code POST /engine/drafted}.
 *
 * @param status      {@code DRAFT} when the definition has errors, {@code VALIDATED} when it has none
 * @param state       the stored query's lifecycle state, {@code drafted} or {@code simulated}
 * @param entry       the sql and declared params exactly as submitted. Present even when validation
 *                    failed ({@code validatedQuery} is then null), so an editor can resume the draft.
 * @param simulated   true while the current definition has been simulated successfully; every edit clears it.
 *                    Publish requires it.
 * @param previouslyPublished true when this query was live before and is offline while it is being edited
 * @param allowedRoles the roles chosen at the last publish, so the publish step can start from them
 * @param warnings    things the author should know that do not make the draft invalid (empty most of the time)
 */
public record DraftResponse(
        String queryName,
        String status,
        String state,
        ValidatedQuery validatedQuery,
        List<ValidationError> errors,
        Entry entry,
        boolean simulated,
        boolean previouslyPublished,
        List<String> allowedRoles,
        List<ValidationError> warnings
) {
    /** Notes that do not make the draft invalid, for example that the database could not check the SQL. */
    public DraftResponse withWarnings(List<ValidationError> notes) {
        return new DraftResponse(queryName, status, state, validatedQuery, errors, entry, simulated,
                previouslyPublished, allowedRoles, notes);
    }

    public record Entry(String sql, Map<String, String> params, Integer fetchSize) {
    }
}
