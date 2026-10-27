package com.brac.query.engine.service;

import com.brac.query.engine.error.EngineException;
import com.brac.query.engine.model.QueryEntry;
import com.brac.query.engine.model.QueryState;
import com.brac.query.engine.model.StoredQuery;
import com.brac.query.engine.model.ValidatedQuery;
import com.brac.query.engine.model.ValidationError;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The lifecycle rules from the PRD ("Query lifecycle"), one rule per test, with no database. */
class QueryLifecycleTest {

    private static final String NAME = "orders.byStatus";
    private static final Instant T = Instant.parse("2026-10-26T10:00:00Z");

    private static StoredQuery row(QueryState state, String author, boolean valid, boolean everPublished) {
        ValidatedQuery validated = valid ? new ValidatedQuery("SELECT 1", List.of(), List.of(), null) : null;
        List<ValidationError> errors = valid ? List.of() : List.of(new ValidationError("SQL_INVALID", null, "bad"));
        return new StoredQuery(NAME, state, new QueryEntry("SELECT 1", Map.of(), List.of(), null), validated, errors,
                author, 3, T, everPublished ? T : null, everPublished ? "someone" : null);
    }

    private static void assertRefused(Runnable step, String code, HttpStatus status) {
        assertThatThrownBy(step::run).isInstanceOfSatisfying(EngineException.class, e -> {
            assertThat(e.getCode()).isEqualTo(code);
            assertThat(e.getStatus()).isEqualTo(status);
        });
    }

    // --- draft ---

    @Test
    void aNewNameCanBeDrafted() {
        assertThatCode(() -> QueryLifecycle.checkDraft(NAME, null, "a@x.com", false)).doesNotThrowAnyException();
    }

    @Test
    void theHolderCanDraftAgainInEitherDraftState() {
        for (QueryState state : new QueryState[] {QueryState.DRAFTED, QueryState.SIMULATED}) {
            assertThatCode(() -> QueryLifecycle.checkDraft(NAME, row(state, "a@x.com", true, false), "a@x.com", false))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void anotherAuthorIsLockedOut() {
        for (QueryState state : new QueryState[] {QueryState.DRAFTED, QueryState.SIMULATED}) {
            assertRefused(() -> QueryLifecycle.checkDraft(NAME, row(state, "a@x.com", true, false), "b@x.com", false),
                    "DRAFT_LOCKED", HttpStatus.CONFLICT);
            // asking to unpublish does not get around the lock of a query that is being worked on
            assertRefused(() -> QueryLifecycle.checkDraft(NAME, row(state, "a@x.com", true, false), "b@x.com", true),
                    "DRAFT_LOCKED", HttpStatus.CONFLICT);
        }
    }

    @Test
    void aPublishedQueryIsNeverTakenOfflineWithoutSayingSo() {
        StoredQuery published = row(QueryState.PUBLISHED, "a@x.com", true, true);
        assertRefused(() -> QueryLifecycle.checkDraft(NAME, published, "a@x.com", false), "QUERY_PUBLISHED", HttpStatus.CONFLICT);
        assertRefused(() -> QueryLifecycle.checkDraft(NAME, published, "b@x.com", false), "QUERY_PUBLISHED", HttpStatus.CONFLICT);
    }

    @Test
    void aPublishedQueryCanBeTakenOfflineOnPurposeByAnyAuthor() {
        StoredQuery published = row(QueryState.PUBLISHED, "a@x.com", true, true);
        assertThatCode(() -> QueryLifecycle.checkDraft(NAME, published, "a@x.com", true)).doesNotThrowAnyException();
        assertThatCode(() -> QueryLifecycle.checkDraft(NAME, published, "b@x.com", true)).doesNotThrowAnyException();
    }

    // --- simulate / publish / discard need a draft ---

    @Test
    void thereIsNothingToWorkOnWhenTheNameIsUnknownOrPublished() {
        assertRefused(() -> QueryLifecycle.requireDraft(NAME, null), "DRAFT_NOT_FOUND", HttpStatus.NOT_FOUND);
        assertRefused(() -> QueryLifecycle.requireDraft(NAME, row(QueryState.PUBLISHED, "a@x.com", true, true)),
                "DRAFT_NOT_FOUND", HttpStatus.NOT_FOUND);
        assertThat(QueryLifecycle.requireDraft(NAME, row(QueryState.DRAFTED, "a@x.com", true, false))).isNotNull();
    }

    @Test
    void aDraftWithErrorsCannotBeSimulatedOrPublished() {
        StoredQuery broken = row(QueryState.DRAFTED, "a@x.com", false, false);
        assertRefused(() -> QueryLifecycle.checkSimulate(NAME, broken), "DRAFT_INVALID", HttpStatus.UNPROCESSABLE_ENTITY);
        assertRefused(() -> QueryLifecycle.checkPublish(NAME, broken), "DRAFT_INVALID", HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void aValidDraftCanBeSimulatedAndSimulatedAgain() {
        assertThatCode(() -> QueryLifecycle.checkSimulate(NAME, row(QueryState.DRAFTED, "a@x.com", true, false))).doesNotThrowAnyException();
        assertThatCode(() -> QueryLifecycle.checkSimulate(NAME, row(QueryState.SIMULATED, "a@x.com", true, false))).doesNotThrowAnyException();
    }

    @Test
    void onlyASimulatedDraftCanBePublished() {
        assertRefused(() -> QueryLifecycle.checkPublish(NAME, row(QueryState.DRAFTED, "a@x.com", true, false)),
                "NOT_SIMULATED", HttpStatus.UNPROCESSABLE_ENTITY);
        assertThatCode(() -> QueryLifecycle.checkPublish(NAME, row(QueryState.SIMULATED, "a@x.com", true, false))).doesNotThrowAnyException();
    }

    @Test
    void aQueryThatWasPublishedMustBeSimulatedAgainAfterAnEdit() {
        // An edit moves it back to drafted, so even though it was live before, publish is refused until simulate succeeds.
        assertRefused(() -> QueryLifecycle.checkPublish(NAME, row(QueryState.DRAFTED, "a@x.com", true, true)),
                "NOT_SIMULATED", HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // --- invoke ---

    @Test
    void onlyAPublishedQueryCanBeCalled() {
        StoredQuery published = row(QueryState.PUBLISHED, "a@x.com", true, true);
        assertThat(QueryLifecycle.requirePublished(NAME, published)).isSameAs(published);
    }

    @Test
    void anUnknownOrNeverPublishedQueryIsNotFound() {
        assertRefused(() -> QueryLifecycle.requirePublished(NAME, null), "QUERY_NOT_FOUND", HttpStatus.NOT_FOUND);
        for (QueryState state : new QueryState[] {QueryState.DRAFTED, QueryState.SIMULATED}) {
            assertRefused(() -> QueryLifecycle.requirePublished(NAME, row(state, "a@x.com", true, false)),
                    "QUERY_NOT_FOUND", HttpStatus.NOT_FOUND);
        }
    }

    @Test
    void aQueryBeingEditedSaysSoInsteadOfLookingMissing() {
        for (QueryState state : new QueryState[] {QueryState.DRAFTED, QueryState.SIMULATED}) {
            assertThatThrownBy(() -> QueryLifecycle.requirePublished(NAME, row(state, "a@x.com", true, true)))
                    .isInstanceOfSatisfying(EngineException.class, e -> {
                        assertThat(e.getCode()).isEqualTo("QUERY_NOT_PUBLISHED");
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.getMessage()).contains("a@x.com").contains("published again");
                    });
        }
    }

    @Test
    void aRowChangedUnderneathIsReportedAsAConflict() {
        assertRefused(() -> {
            throw QueryLifecycle.changed(NAME);
        }, "DRAFT_CHANGED", HttpStatus.CONFLICT);
    }
}
