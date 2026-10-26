package com.brac.query.engine.dto;

import com.brac.query.engine.model.ValidationError;
import com.brac.query.engine.model.ValidatedQuery;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Response for {@code POST /engine/draft}, {@code POST /engine/draft-yml}, and {@code POST /engine/drafted}.
 *
 * @param entry     the sql and declared params exactly as submitted. Present even when validation
 *                  failed ({@code validatedQuery} is then null), so an editor can resume the draft.
 * @param warnings  things the author should know that do not make the draft invalid (empty most of the time)
 * @param simulated true once a simulate has succeeded against this draft; reset by every re-draft.
 *                  Publish requires it.
 */
public record DraftResponse(
        String queryName,
        String status,
        ValidatedQuery validatedQuery,
        List<ValidationError> errors,
        Entry entry,
        boolean simulated,
        List<ValidationError> warnings
) {
    /** Notes that do not make the draft invalid, for example that the database could not check the SQL. */
    public DraftResponse withWarnings(List<ValidationError> notes) {
        return new DraftResponse(queryName, status, validatedQuery, errors, entry, simulated, notes);
    }


    public record Entry(String sql, Map<String, String> params, Integer fetchSize) {
    }
}
