package com.brac.query.engine.service;

import com.brac.query.engine.config.SimulateProperties;
import com.brac.query.engine.config.StreamProperties;
import com.brac.query.engine.dto.DiscardRequest;
import com.brac.query.engine.dto.DiscardResponse;
import com.brac.query.engine.dto.DraftRequest;
import com.brac.query.engine.dto.DraftResponse;
import com.brac.query.engine.dto.DraftedLookupRequest;
import com.brac.query.engine.dto.ListRequest;
import com.brac.query.engine.dto.ListResponse;
import com.brac.query.engine.dto.PublishRequest;
import com.brac.query.engine.dto.PublishResponse;
import com.brac.query.engine.dto.SimulateRequest;
import com.brac.query.engine.dto.SimulateResponse;
import com.brac.query.engine.error.EngineException;
import com.brac.query.engine.execution.ProjectionException;
import com.brac.query.engine.execution.ProjectionStoreExecutor;
import com.brac.query.engine.model.ParamType;
import com.brac.query.engine.model.QueryEntry;
import com.brac.query.engine.model.QueryState;
import com.brac.query.engine.model.StoredQuery;
import com.brac.query.engine.model.ValidatedQuery;
import com.brac.query.engine.model.ValidationError;
import com.brac.query.engine.store.StoredQueryRepository;
import com.brac.query.engine.validation.QueryValidator;
import com.brac.query.engine.validation.SqlGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Orchestrates the query lifecycle described in the PRD ("Query lifecycle"): a query is one row of
 * {@code qe_stored_query} that moves drafted -> simulated -> published. Every request names the
 * query by {@code queryName} in the body, and everything is reactive from the WebFlux controller
 * down through R2DBC. No lifecycle state is held in memory.
 *
 * <p>The rules about which step is allowed live in {@link QueryLifecycle}; the repository applies
 * each state change as one conditional statement, so a decision based on a row that has changed in
 * the meantime is refused ({@code 409 DRAFT_CHANGED}) instead of overwriting the newer row.
 */
@Service
public class QueryEngineService {

    private static final Logger log = LoggerFactory.getLogger(QueryEngineService.class);

    private final StoredQueryRepository store;
    private final QueryValidator validator;
    private final SqlGuard sqlGuard;
    private final ProjectionStoreExecutor executor;
    private final StreamProperties streamProperties;
    private final SimulateProperties simulateProperties;

    public QueryEngineService(StoredQueryRepository store, QueryValidator validator, SqlGuard sqlGuard,
                              ProjectionStoreExecutor executor, StreamProperties streamProperties,
                              SimulateProperties simulateProperties) {
        this.store = store;
        this.validator = validator;
        this.sqlGuard = sqlGuard;
        this.executor = executor;
        this.streamProperties = streamProperties;
        this.simulateProperties = simulateProperties;
    }

    // --- draft ---------------------------------------------------------------------------------

    public Mono<DraftResponse> draft(DraftRequest request, String author) {
        return Mono.defer(() -> {
            String queryName = requireQueryName(request.queryName());
            boolean unpublish = Boolean.TRUE.equals(request.unpublish());
            Instant now = Instant.now();

            return find(queryName).flatMap(existing -> {
                QueryLifecycle.checkDraft(queryName, existing.orElse(null), author, unpublish);
                QueryEntry entry = new QueryEntry(request.sql(), request.params(), request.allowedRoles(), request.fetchSize());
                return withDatabaseCheck(queryName, validator.validate(entry)).flatMap(checked -> {
                    var result = checked.result();
                    StoredQuery draft = new StoredQuery(queryName, QueryState.DRAFTED, entry, result.validatedQuery(),
                            result.errors(), author, 1, now, null, null);
                    return store.saveDraft(draft, unpublish).flatMap(saved -> saved
                            ? store.find(queryName).map(row -> toDraftResponse(row).withWarnings(checked.warnings()))
                            : refuseLostRace(queryName, author, unpublish));
                });
            });
        });
    }

    /**
     * The conditional save changed nothing, so the row was taken or published between the check and the write.
     * Look again and answer with the reason.
     */
    private Mono<DraftResponse> refuseLostRace(String queryName, String author, boolean unpublish) {
        return find(queryName).flatMap(now -> {
            QueryLifecycle.checkDraft(queryName, now.orElse(null), author, unpublish);
            return Mono.error(QueryLifecycle.changed(queryName));
        });
    }

    /** The validation result, plus notes about checks that could not be made. */
    private record Checked(QueryValidator.ValidationResult result, List<ValidationError> warnings) {
    }

    /**
     * When the checks that need no database pass, asks Postgres whether it accepts the SQL (see
     * {@link ProjectionStoreExecutor#check}). A rejection becomes a {@code SQL_INVALID} error, so
     * the draft is stored with errors and cannot be simulated or published until it is fixed.
     * If the database could not be asked, the draft is stored as it is and the response carries an
     * {@code SQL_NOT_CHECKED} warning, so the author is not told the SQL was checked when it was not.
     */
    private Mono<Checked> withDatabaseCheck(String queryName, QueryValidator.ValidationResult result) {
        if (result.hasErrors()) {
            return Mono.just(new Checked(result, List.of()));
        }
        return executor.check(queryName, result.validatedQuery()).map(check -> switch (check.outcome()) {
            case ACCEPTED -> new Checked(result, List.of());
            case REJECTED -> new Checked(new QueryValidator.ValidationResult(null, result.params(),
                    List.of(new ValidationError("SQL_INVALID", null, check.problem().invalidSqlMessage()))), List.of());
            case UNAVAILABLE -> new Checked(result, List.of(new ValidationError("SQL_NOT_CHECKED", null,
                    "The database could not check this SQL just now, so it was saved without that check. A syntax error or an "
                            + "unknown table or column will only show when you simulate.")));
        });
    }

    public Mono<DraftResponse> getDrafted(DraftedLookupRequest request) {
        return Mono.defer(() -> {
            String queryName = requireQueryName(request.queryName());
            return loadDraft(queryName).map(QueryEngineService::toDraftResponse);
        });
    }

    // --- simulate --------------------------------------------------------------------------------

    public Mono<SimulateResponse> simulate(SimulateRequest request) {
        return Mono.defer(() -> {
            String queryName = requireQueryName(request.queryName());
            Map<String, Object> params = request.params() == null ? Map.of() : request.params();

            return loadDraft(queryName).flatMap(draft -> {
                QueryLifecycle.checkSimulate(queryName, draft);
                EngineException sqlError = rejectedSql(queryName, draft.validatedQuery().sql(), true);
                if (sqlError != null) {
                    return Mono.error(sqlError);
                }
                EngineException paramError = checkParams(queryName, draft.validatedQuery(), params);
                if (paramError != null) {
                    return Mono.error(paramError);
                }

                // Stand-in for: OPA policy evaluation (row/field redaction), then execution against
                // the real Projection Store (Postgres) under the row-cap cost limiter. The SQL runs in
                // BEGIN READ ONLY, so nothing it says can change data.
                int fetchSize = streamProperties.effectiveFetchSize(draft.validatedQuery().fetchSize());
                return executor.execute(queryName, draft.validatedQuery(), params, simulateProperties.maxRows(), fetchSize)
                        .onErrorMap(ProjectionException.class, e -> e.toEngineException(queryName, true))
                        .flatMap(execResult -> {
                            var metrics = new SimulateResponse.Metrics(
                                    execResult.latencyMs(),
                                    execResult.rows().size(),
                                    Math.round(ThreadLocalRandom.current().nextDouble(1, 50) * 100) / 100.0);
                            SimulateResponse response = new SimulateResponse(queryName, execResult.rows(),
                                    execResult.rows().size(), execResult.truncated(), metrics, List.of());
                            // Only the definition that was run may become "simulated".
                            return store.markSimulated(queryName, draft.revision(), Instant.now()).flatMap(moved -> moved
                                    ? Mono.just(response)
                                    : Mono.<SimulateResponse>error(QueryLifecycle.changed(queryName)));
                        });
            });
        });
    }

    // --- publish / discard -----------------------------------------------------------------------

    public Mono<PublishResponse> publish(PublishRequest request, String publishedBy) {
        return Mono.defer(() -> {
            String queryName = requireQueryName(request.queryName());

            return loadDraft(queryName).flatMap(draft -> {
                QueryLifecycle.checkPublish(queryName, draft);
                EngineException sqlError = rejectedSql(queryName, draft.validatedQuery().sql(), true);
                if (sqlError != null) {
                    return Mono.error(sqlError);
                }

                // allowed_roles are chosen at publish time (realm roles and/or groups from the publish
                // dialog) and become the RBAC gate enforced when the query is called.
                List<String> allowedRoles = request.allowedRoles() == null ? List.of() : request.allowedRoles().stream()
                        .map(r -> r == null ? null : r.trim())
                        .filter(r -> r != null && !r.isEmpty())
                        .distinct()
                        .toList();
                if (allowedRoles.isEmpty()) {
                    return Mono.error(new EngineException("NO_ALLOWED_ROLES", HttpStatus.UNPROCESSABLE_ENTITY, queryName,
                            "At least one allowed role or group must be selected to publish '" + queryName + "'"));
                }

                ValidatedQuery validated = new ValidatedQuery(
                        draft.validatedQuery().sql(), draft.validatedQuery().params(), allowedRoles,
                        draft.validatedQuery().fetchSize());
                Instant now = Instant.now();
                return store.publish(queryName, draft.revision(), validated, allowedRoles, publishedBy, now)
                        .flatMap(published -> published
                                ? Mono.just(new PublishResponse(queryName, "PUBLISHED", now, publishedBy))
                                : Mono.<PublishResponse>error(QueryLifecycle.changed(queryName)));
            });
        });
    }

    /**
     * Deletes a drafted or simulated query. For one that was published before and is being edited this deletes
     * the query entirely: the row is the only copy.
     */
    public Mono<DiscardResponse> discard(DiscardRequest request) {
        return Mono.defer(() -> {
            String queryName = requireQueryName(request.queryName());
            return loadDraft(queryName)
                    .flatMap(draft -> store.discard(queryName))
                    .flatMap(deleted -> deleted
                            ? Mono.just(new DiscardResponse(queryName, "DISCARDED"))
                            : Mono.<DiscardResponse>error(QueryLifecycle.changed(queryName)));
        });
    }

    // --- list ------------------------------------------------------------------------------------

    public Mono<ListResponse> list(ListRequest request) {
        return Mono.defer(() -> {
            ListRequest.Filter filter = request == null ? null : request.filter();

            return store.findAll().collectList().map(rows -> {
                List<ListResponse.Item> items = new ArrayList<>();
                for (StoredQuery row : rows) {
                    if (!matchesFilter(row, filter)) {
                        continue;
                    }
                    if (row.state() == QueryState.PUBLISHED) {
                        items.add(new ListResponse.Item(row.queryName(), new ListResponse.PublishedSummary(
                                row.validatedQuery().sql(), row.validatedQuery().params(), row.validatedQuery().fetchSize(),
                                row.validatedQuery().allowedRoles(), row.publishedAt(), row.publishedBy()), null));
                    } else {
                        items.add(new ListResponse.Item(row.queryName(), null, new ListResponse.DraftSummary(
                                draftStatus(row), row.state().db(), row.state() == QueryState.SIMULATED, row.author(),
                                row.updatedAt(), row.previouslyPublished())));
                    }
                }

                int pageSize = (request != null && request.page() != null && request.page().size() != null)
                        ? Math.min(request.page().size(), 200) : 50;
                List<ListResponse.Item> page = items.size() > pageSize ? items.subList(0, pageSize) : items;
                String nextCursor = items.size() > pageSize ? page.get(page.size() - 1).queryName() : null;
                return new ListResponse(page, nextCursor);
            });
        });
    }

    // --- invoke ----------------------------------------------------------------------------------

    /**
     * Everything a {@code /data-query} call must satisfy before any data is read: the query is published,
     * the caller's roles are allowed, the stored SQL still passes the guard, and the params are present and
     * correctly typed. Reading the rows is {@link DataQueryService}'s job.
     */
    public Mono<Invocation> prepareInvocation(String name, Map<String, Object> params, List<String> callerRoles) {
        return resolveQueryName(name).flatMap(queryName -> find(queryName).flatMap(found -> {
            StoredQuery published = QueryLifecycle.requirePublished(queryName, found.orElse(null));
            List<String> allowedRoles = published.validatedQuery().allowedRoles();
            boolean permitted = callerRoles.stream().anyMatch(allowedRoles::contains);
            if (!permitted) {
                return Mono.<Invocation>error(new EngineException("FORBIDDEN", HttpStatus.FORBIDDEN, queryName,
                        "Role(s) " + callerRoles + " not permitted to invoke '" + queryName + "'"));
            }

            EngineException sqlError = rejectedSql(queryName, published.validatedQuery().sql(), false);
            if (sqlError != null) {
                return Mono.<Invocation>error(sqlError);
            }

            EngineException paramError = checkParams(queryName, published.validatedQuery(), params);
            if (paramError != null) {
                return Mono.<Invocation>error(paramError);
            }
            return Mono.just(new Invocation(queryName, published.validatedQuery(), params));
        }));
    }

    /** Accepts either a bare name ("orderById") or a fully namespaced one ("orders.orderById"). */
    private Mono<String> resolveQueryName(String name) {
        if (name == null || name.isBlank()) {
            return Mono.error(new EngineException("QUERY_NOT_FOUND", HttpStatus.NOT_FOUND, name, "name is required"));
        }
        if (name.contains(".")) {
            return Mono.just(name);
        }
        return store.publishedNames()
                .filter(qn -> qn.endsWith("." + name))
                .collectList()
                .flatMap(matches -> {
                    if (matches.size() == 1) {
                        return Mono.just(matches.get(0));
                    }
                    return Mono.error(new EngineException("QUERY_NOT_FOUND", HttpStatus.NOT_FOUND, name,
                            matches.isEmpty() ? "No published query named '" + name + "'"
                                    : "Ambiguous name '" + name + "' matches multiple queries: " + new TreeSet<>(matches)));
                });
    }

    // --- helpers ---------------------------------------------------------------------------------

    private Mono<Optional<StoredQuery>> find(String queryName) {
        return store.find(queryName).map(Optional::of).defaultIfEmpty(Optional.empty());
    }

    /** The row for simulate / publish / resume / discard: it must exist and must not be published. */
    private Mono<StoredQuery> loadDraft(String queryName) {
        return find(queryName).map(found -> QueryLifecycle.requireDraft(queryName, found.orElse(null)));
    }

    /**
     * {@code SQL_REJECTED} when the SQL is not a single read-only SELECT, otherwise null. Drafting
     * already refuses such SQL, so this catches a query stored before the rules existed, or after
     * they were tightened. An author sees what is wrong; a calling client app only sees the codes
     * (the detail is logged).
     */
    private EngineException rejectedSql(String queryName, String sql, boolean exposeDetail) {
        List<ValidationError> problems = sqlGuard.analyze(sql).errors();
        if (problems.isEmpty()) {
            return null;
        }
        String codes = String.join(", ", problems.stream().map(ValidationError::code).distinct().toList());
        if (!exposeDetail) {
            log.warn("Refusing to run '{}': its SQL is no longer accepted ({}). Detail: {}", queryName, codes, problems);
            return new EngineException("SQL_REJECTED", HttpStatus.UNPROCESSABLE_ENTITY, queryName,
                    "'" + queryName + "' can't run because its SQL is not a read-only SELECT (" + codes
                            + "). Ask its author to fix and publish it again.");
        }
        return new EngineException("SQL_REJECTED", HttpStatus.UNPROCESSABLE_ENTITY, queryName,
                "The SQL of '" + queryName + "' is not a read-only SELECT: " + problems.get(0).message()
                        + (problems.size() > 1 ? " (and " + (problems.size() - 1) + " more: " + codes + ")" : "")
                        + ". Edit the draft and save it again.");
    }

    /** {@code DRAFT} when the definition has errors, {@code VALIDATED} when it has none. */
    private static String draftStatus(StoredQuery row) {
        return row.errors().isEmpty() ? "VALIDATED" : "DRAFT";
    }

    private static DraftResponse toDraftResponse(StoredQuery row) {
        return new DraftResponse(
                row.queryName(), draftStatus(row), row.state().db(), row.validatedQuery(), row.errors(),
                new DraftResponse.Entry(row.entry().sql(), row.entry().params(), row.entry().fetchSize()),
                row.state() == QueryState.SIMULATED, row.previouslyPublished(),
                row.entry().allowedRoles() == null ? List.of() : row.entry().allowedRoles(), List.of());
    }

    private boolean matchesFilter(StoredQuery row, ListRequest.Filter filter) {
        if (filter == null) {
            return true;
        }
        String queryName = row.queryName();
        if (filter.module() != null && !queryName.startsWith(filter.module() + ".")) {
            return false;
        }
        if (filter.queryNamePrefix() != null && !queryName.startsWith(filter.queryNamePrefix())) {
            return false;
        }
        if (filter.author() != null && !(row.state().isDraft() && row.author().equals(filter.author()))) {
            return false;
        }
        if (filter.status() != null && !filter.status().isEmpty()) {
            boolean statusMatches = row.state() == QueryState.PUBLISHED
                    ? filter.status().contains("PUBLISHED")
                    : filter.status().contains(draftStatus(row));
            if (!statusMatches) {
                return false;
            }
        }
        return true;
    }

    /** Returns an EngineException describing the first param problem, or null if params are valid. */
    private EngineException checkParams(String queryName, ValidatedQuery validatedQuery, Map<String, Object> params) {
        for (var paramSpec : validatedQuery.params()) {
            if (!params.containsKey(paramSpec.name())) {
                return new EngineException("INVALID_ARGUMENT", HttpStatus.BAD_REQUEST, queryName,
                        "Missing required param '" + paramSpec.name() + "'");
            }
            Object value = params.get(paramSpec.name());
            if (!typeMatches(paramSpec.type(), value)) {
                return new EngineException("INVALID_ARGUMENT", HttpStatus.BAD_REQUEST, queryName,
                        paramSpec.name() + " expected " + paramSpec.type() + ", received "
                                + (value == null ? "null" : value.getClass().getSimpleName()));
            }
        }
        return null;
    }

    private boolean typeMatches(ParamType type, Object value) {
        if (value == null) {
            return false;
        }
        return switch (type) {
            case STRING -> value instanceof String;
            case INT -> value instanceof Integer;
            case LONG -> value instanceof Integer || value instanceof Long;
            case DECIMAL -> value instanceof Number;
            case BOOLEAN -> value instanceof Boolean;
            case DATE, DATETIME -> value instanceof String;
        };
    }

    private String requireQueryName(String queryName) {
        if (queryName == null || queryName.isBlank() || !queryName.matches("^[a-zA-Z0-9_]+\\.[a-zA-Z0-9_]+$")) {
            throw new EngineException("INVALID_QUERY_NAME", HttpStatus.BAD_REQUEST, queryName,
                    "queryName must be in '{module}.{name}' form");
        }
        return queryName;
    }
}
