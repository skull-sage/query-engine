package com.brac.query.engine.service;

import com.brac.query.engine.error.EngineException;
import com.brac.query.engine.model.QueryState;
import com.brac.query.engine.model.StoredQuery;
import org.springframework.http.HttpStatus;

/**
 * The rules of the query lifecycle, as plain functions with no database in them, so each rule can
 * be tested on its own (see the PRD, "Query lifecycle"). Every method throws the
 * {@link EngineException} that explains a refusal, or returns when the step is allowed.
 *
 * <p>These checks decide from a row that was just read. The repository's conditional writes make
 * sure the row is still the same when the change is applied.
 */
final class QueryLifecycle {

    private QueryLifecycle() {
    }

    /** May {@code author} save a draft over {@code existing} (null when there is no row)? */
    static void checkDraft(String queryName, StoredQuery existing, String author, boolean unpublish) {
        if (existing == null) {
            return;
        }
        if (existing.state() == QueryState.PUBLISHED) {
            if (!unpublish) {
                throw new EngineException("QUERY_PUBLISHED", HttpStatus.CONFLICT, queryName,
                        "'" + queryName + "' is published and in use. Drafting it again takes it offline for callers until it is "
                                + "simulated and published again. Send \"unpublish\": true to confirm.");
            }
            return;
        }
        if (!existing.author().equals(author)) {
            throw new EngineException("DRAFT_LOCKED", HttpStatus.CONFLICT, queryName,
                    "Draft for '" + queryName + "' is held by " + existing.author()
                            + ". It stays locked until they publish or discard it.");
        }
    }

    /** The row to work on for simulate, publish, resume and discard: it must exist and not be published. */
    static StoredQuery requireDraft(String queryName, StoredQuery existing) {
        if (existing == null || existing.state() == QueryState.PUBLISHED) {
            throw new EngineException("DRAFT_NOT_FOUND", HttpStatus.NOT_FOUND, queryName,
                    "No open draft for '" + queryName + "' (never drafted, published, or discarded)");
        }
        return existing;
    }

    /** May the draft be simulated? Only when its definition has no errors. */
    static void checkSimulate(String queryName, StoredQuery draft) {
        if (!draft.valid()) {
            throw new EngineException("DRAFT_INVALID", HttpStatus.UNPROCESSABLE_ENTITY, queryName,
                    "Draft for '" + queryName + "' has validation errors and cannot be simulated");
        }
    }

    /** May the draft be published? Only a simulated draft with a valid definition. */
    static void checkPublish(String queryName, StoredQuery draft) {
        if (!draft.valid()) {
            throw new EngineException("DRAFT_INVALID", HttpStatus.UNPROCESSABLE_ENTITY, queryName,
                    "Draft for '" + queryName + "' has no valid definition and cannot be published");
        }
        if (draft.state() != QueryState.SIMULATED) {
            throw new EngineException("NOT_SIMULATED", HttpStatus.UNPROCESSABLE_ENTITY, queryName,
                    "Draft for '" + queryName + "' has not been simulated; publish requires a fresh simulate first");
        }
    }

    /** May {@code /data-query} run this query? Only when it is published; a query being edited says so. */
    static StoredQuery requirePublished(String queryName, StoredQuery existing) {
        if (existing == null || (existing.state() != QueryState.PUBLISHED && !existing.previouslyPublished())) {
            throw new EngineException("QUERY_NOT_FOUND", HttpStatus.NOT_FOUND, queryName,
                    "No published query named '" + queryName + "'");
        }
        if (existing.state() != QueryState.PUBLISHED) {
            throw new EngineException("QUERY_NOT_PUBLISHED", HttpStatus.CONFLICT, queryName,
                    "'" + queryName + "' is being edited by " + existing.author()
                            + " and is unavailable until it is published again");
        }
        return existing;
    }

    /** The write that applied a decision changed nothing: the row was changed by someone else in between. */
    static EngineException changed(String queryName) {
        return new EngineException("DRAFT_CHANGED", HttpStatus.CONFLICT, queryName,
                "'" + queryName + "' was changed by someone else while this was running. Reload it and try again.");
    }
}
