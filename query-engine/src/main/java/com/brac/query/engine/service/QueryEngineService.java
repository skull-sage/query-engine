package com.brac.query.engine.service;

import com.brac.query.engine.config.SimulateProperties;
import com.brac.query.engine.config.StreamProperties;
import com.brac.query.engine.execution.ProjectionException;
import com.brac.query.engine.validation.QueryValidator;
import com.brac.query.engine.execution.ProjectionStoreExecutor;
import com.brac.query.engine.dto.DraftRequest;
import com.brac.query.engine.dto.DraftResponse;
import com.brac.query.engine.dto.DiscardRequest;
import com.brac.query.engine.dto.DiscardResponse;
import com.brac.query.engine.dto.DraftedLookupRequest;
import com.brac.query.engine.dto.PublishRequest;
import com.brac.query.engine.dto.PublishResponse;
import com.brac.query.engine.dto.ListRequest;
import com.brac.query.engine.dto.ListResponse;
import com.brac.query.engine.dto.SimulateRequest;
import com.brac.query.engine.dto.SimulateResponse;
import com.brac.query.engine.error.EngineException;
import com.brac.query.engine.model.DraftRecord;
import com.brac.query.engine.model.DraftStatus;
import com.brac.query.engine.model.PublishedQuery;
import com.brac.query.engine.model.ParamType;
import com.brac.query.engine.model.QueryEntry;
import com.brac.query.engine.model.ValidatedQuery;
import com.brac.query.engine.store.DraftStore;
import com.brac.query.engine.store.PublishedQueryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Orchestrates the query lifecycle described in the PRD: draft -> simulate -> publish/discard,
 * all addressed by {@code queryName} in the request body rather than the URL, and all fully
 * reactive (returning {@link Mono}) so the pipeline is non-blocking from the WebFlux controller
 * down through R2DBC. The Draft Store and Query Registry are both persisted in Postgres (see
 * {@link DraftStore} and {@link PublishedQueryRepository}) -- no in-memory map holds lifecycle
 * state. There is no versioning: drafting or publishing a queryName again simply overwrites
 * whatever was there before.
 */
@Service
public class QueryEngineService {
    private final DraftStore draftStore;
    private final PublishedQueryRepository publishedQueries;
    private final QueryValidator validator;
    private final ProjectionStoreExecutor executor;
    private final StreamProperties streamProperties;
    private final SimulateProperties simulateProperties;

    public QueryEngineService(DraftStore draftStore, PublishedQueryRepository publishedQueries, QueryValidator validator,
                               ProjectionStoreExecutor executor, StreamProperties streamProperties,
                               SimulateProperties simulateProperties) {
        this.draftStore = draftStore;
        this.publishedQueries = publishedQueries;
        this.validator = validator;
        this.executor = executor;
        this.streamProperties = streamProperties;
        this.simulateProperties = simulateProperties;
    }

    public Mono<DraftResponse> draft(DraftRequest request, String author) {
        return Mono.defer(() -> {
            String queryName = requireQueryName(request.queryName());
            Instant now = Instant.now();

            Mono<Void> lockCheck = draftStore.find(queryName)
                    .flatMap(existing -> {
                        if (!existing.author().equals(author)) {
                            return Mono.<Void>error(new EngineException("DRAFT_LOCKED", HttpStatus.CONFLICT, queryName,
                                    "Draft for '" + queryName + "' is held by " + existing.author()
                                            + ". It stays locked until they publish or discard it."));
                        }
                        return Mono.empty();
                    });

            return lockCheck.then(Mono.defer(() -> {
                QueryEntry entry = new QueryEntry(request.sql(), request.params(), request.allowedRoles(), request.fetchSize());
                var validationResult = validator.validate(entry);
                DraftStatus status = validationResult.hasErrors() ? DraftStatus.DRAFT : DraftStatus.VALIDATED;

                DraftRecord record = new DraftRecord(
                        queryName, entry, validationResult.validatedQuery(), status,
                        validationResult.errors(), false, author, now
                );
                return draftStore.save(record).thenReturn(toDraftResponse(record));
            }));
        });
    }

    public Mono<DraftResponse> getDrafted(DraftedLookupRequest request) {
        return Mono.defer(() -> {
            String queryName = requireQueryName(request.queryName());
            return loadDraftOrThrow(queryName).map(QueryEngineService::toDraftResponse);
        });
    }

    public Mono<SimulateResponse> simulate(SimulateRequest request) {
        return Mono.defer(() -> {
            String queryName = requireQueryName(request.queryName());
            Map<String, Object> params = request.params() == null ? Map.of() : request.params();

            return loadDraftOrThrow(queryName).flatMap(record -> {
                if (record.status() == DraftStatus.DRAFT) {
                    return Mono.error(new EngineException("DRAFT_INVALID", HttpStatus.UNPROCESSABLE_ENTITY, queryName,
                            "Draft for '" + queryName + "' has validation errors and cannot be simulated"));
                }
                EngineException paramError = validateParams(queryName, record, params);
                if (paramError != null) {
                    return Mono.error(paramError);
                }

                // Stand-in for: OPA policy evaluation (row/field redaction), then execution against
                // the real Projection Store (Postgres) under the row-cap cost limiter.
                int fetchSize = streamProperties.effectiveFetchSize(record.validatedQuery().fetchSize());
                return executor.execute(queryName, record.validatedQuery(), params, simulateProperties.maxRows(), fetchSize)
                        .onErrorMap(ProjectionException.class, e -> e.toEngineException(queryName, true))
                        .flatMap(execResult -> {
                    Instant now = Instant.now();
                    var metrics = new SimulateResponse.Metrics(
                            execResult.latencyMs(),
                            execResult.rows().size(),
                            Math.round(ThreadLocalRandom.current().nextDouble(1, 50) * 100) / 100.0
                    );
                    SimulateResponse response = new SimulateResponse(queryName, execResult.rows(),
                            execResult.rows().size(), execResult.truncated(), metrics, List.of());
                    return draftStore.save(record.withSimulated(now)).thenReturn(response);
                });
            });
        });
    }

    @Transactional
    public Mono<PublishResponse> publish(PublishRequest request, String publishedBy) {
        return Mono.defer(() -> {
            String queryName = requireQueryName(request.queryName());

            return loadDraftOrThrow(queryName).flatMap(record -> {
                if (!record.simulated()) {
                    return Mono.error(new EngineException("NOT_SIMULATED", HttpStatus.UNPROCESSABLE_ENTITY, queryName,
                            "Draft for '" + queryName + "' has not been simulated; publish requires a fresh simulate first"));
                }
                if (record.validatedQuery() == null) {
                    return Mono.error(new EngineException("DRAFT_INVALID", HttpStatus.UNPROCESSABLE_ENTITY, queryName,
                            "Draft for '" + queryName + "' has no validated descriptor"));
                }

                // allowed_roles are chosen at publish time (realm roles and/or groups from the
                // publish dialog), not during drafting. They override whatever the draft carried
                // and become the RBAC gate enforced in invoke().
                List<String> allowedRoles = request.allowedRoles() == null ? List.of() : request.allowedRoles().stream()
                        .map(r -> r == null ? null : r.trim())
                        .filter(r -> r != null && !r.isEmpty())
                        .distinct()
                        .toList();
                if (allowedRoles.isEmpty()) {
                    return Mono.error(new EngineException("NO_ALLOWED_ROLES", HttpStatus.UNPROCESSABLE_ENTITY, queryName,
                            "At least one allowed role or group must be selected to publish '" + queryName + "'"));
                }

                QueryEntry publishedEntry = new QueryEntry(
                        record.entry().sql(), record.entry().params(), allowedRoles, record.entry().fetchSize());
                ValidatedQuery publishedValidated = new ValidatedQuery(
                        record.validatedQuery().sql(), record.validatedQuery().params(), allowedRoles,
                        record.validatedQuery().fetchSize());

                Instant now = Instant.now();
                PublishedQuery published = new PublishedQuery(queryName, publishedEntry, publishedValidated, now, publishedBy);

                return publishedQueries.publish(published)
                        .then(draftStore.delete(queryName))
                        .thenReturn(new PublishResponse(queryName, "PUBLISHED", now, publishedBy));
            });
        });
    }

    public Mono<DiscardResponse> discard(DiscardRequest request) {
        return Mono.defer(() -> {
            String queryName = requireQueryName(request.queryName());
            return loadDraftOrThrow(queryName)
                    .flatMap(record -> draftStore.delete(queryName)
                            .thenReturn(new DiscardResponse(queryName, "DISCARDED")));
        });
    }

    public Mono<ListResponse> list(ListRequest request) {
        return Mono.defer(() -> {
            ListRequest.Filter filter = request == null ? null : request.filter();
            Instant now = Instant.now();

            Mono<Map<String, PublishedQuery>> publishedMono =
                    publishedQueries.findAll().collectMap(PublishedQuery::queryName);
            Mono<Map<String, DraftRecord>> draftMono =
                    draftStore.findAll().collectMap(DraftRecord::queryName);

            return Mono.zip(publishedMono, draftMono).map(tuple -> {
                Map<String, PublishedQuery> publishedByName = tuple.getT1();
                Map<String, DraftRecord> draftByName = tuple.getT2();

                java.util.Set<String> allNames = new java.util.TreeSet<>();
                allNames.addAll(publishedByName.keySet());
                allNames.addAll(draftByName.keySet());

                List<ListResponse.Item> items = new ArrayList<>();
                for (String name : allNames) {
                    PublishedQuery published = publishedByName.get(name);
                    DraftRecord draft = draftByName.get(name);

                    if (!matchesFilter(name, published, draft, filter)) {
                        continue;
                    }

                    ListResponse.PublishedSummary publishedSummary = published == null ? null : new ListResponse.PublishedSummary(
                            published.validatedQuery().sql(), published.validatedQuery().params(),
                            published.validatedQuery().fetchSize(),
                            published.validatedQuery().allowedRoles(), published.publishedAt(), published.publishedBy()
                    );
                    ListResponse.DraftSummary draftSummary = draft == null ? null : new ListResponse.DraftSummary(
                            draft.status().name(), draft.simulated(), draft.author(), draft.updatedAt()
                    );
                    items.add(new ListResponse.Item(name, publishedSummary, draftSummary));
                }

                int pageSize = (request != null && request.page() != null && request.page().size() != null)
                        ? Math.min(request.page().size(), 200) : 50;
                List<ListResponse.Item> page = items.size() > pageSize ? items.subList(0, pageSize) : items;
                String nextCursor = items.size() > pageSize ? page.get(page.size() - 1).queryName() : null;

                return new ListResponse(page, nextCursor);
            });
        });
    }

    /**
     * Everything a {@code /data-query} call must satisfy before any data is read: the query exists,
     * the caller's roles are allowed, and the params are present and correctly typed. Reading the
     * rows is {@link DataQueryService}'s job.
     */
    public Mono<Invocation> prepareInvocation(String name, Map<String, Object> params, List<String> callerRoles) {
        return resolveQueryName(name).flatMap(queryName -> publishedQueries.find(queryName)
                .switchIfEmpty(Mono.error(new EngineException("QUERY_NOT_FOUND", HttpStatus.NOT_FOUND, queryName,
                        "No published query named '" + queryName + "'")))
                .flatMap(published -> {
                    List<String> allowedRoles = published.validatedQuery().allowedRoles();
                    boolean permitted = callerRoles.stream().anyMatch(allowedRoles::contains);
                    if (!permitted) {
                        return Mono.<Invocation>error(new EngineException("FORBIDDEN", HttpStatus.FORBIDDEN, queryName,
                                "Role(s) " + callerRoles + " not permitted to invoke '" + queryName + "'"));
                    }

                    EngineException paramError = validateInvokeParams(queryName, published.validatedQuery(), params);
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
        return publishedQueries.allQueryNames()
                .filter(qn -> qn.endsWith("." + name))
                .collectList()
                .flatMap(matches -> {
                    if (matches.size() == 1) {
                        return Mono.just(matches.get(0));
                    }
                    return Mono.error(new EngineException("QUERY_NOT_FOUND", HttpStatus.NOT_FOUND, name,
                            matches.isEmpty() ? "No published query named '" + name + "'"
                                    : "Ambiguous name '" + name + "' matches multiple queries: " + matches));
                });
    }

    // --- helpers -----------------------------------------------------------------------------

    private static DraftResponse toDraftResponse(DraftRecord record) {
        return new DraftResponse(
                record.queryName(), record.status().name(), record.validatedQuery(),
                record.errors(),
                new DraftResponse.Entry(record.entry().sql(), record.entry().params(), record.entry().fetchSize()),
                record.simulated());
    }

    private boolean matchesFilter(String queryName, PublishedQuery published, DraftRecord draft, ListRequest.Filter filter) {
        if (filter == null) {
            return true;
        }
        if (filter.module() != null && !queryName.startsWith(filter.module() + ".")) {
            return false;
        }
        if (filter.queryNamePrefix() != null && !queryName.startsWith(filter.queryNamePrefix())) {
            return false;
        }
        if (filter.author() != null) {
            boolean authorMatches = draft != null && draft.author().equals(filter.author());
            if (!authorMatches) {
                return false;
            }
        }
        if (filter.status() != null && !filter.status().isEmpty()) {
            boolean statusMatches = (published != null && filter.status().contains("PUBLISHED"))
                    || (draft != null && filter.status().contains(draft.status().name()));
            if (!statusMatches) {
                return false;
            }
        }
        return true;
    }

    /** Returns an EngineException describing the first param problem, or null if params are valid. */
    private EngineException validateParams(String queryName, DraftRecord record, Map<String, Object> params) {
        if (record.validatedQuery() == null) {
            return null;
        }
        return checkParams(queryName, record.validatedQuery(), params);
    }

    private EngineException validateInvokeParams(String queryName, ValidatedQuery validatedQuery, Map<String, Object> params) {
        return checkParams(queryName, validatedQuery, params);
    }

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

    private Mono<DraftRecord> loadDraftOrThrow(String queryName) {
        return draftStore.find(queryName)
                .switchIfEmpty(Mono.error(new EngineException("DRAFT_NOT_FOUND", HttpStatus.NOT_FOUND, queryName,
                        "No open draft for '" + queryName + "' (never drafted, published, or discarded)")));
    }

    private String requireQueryName(String queryName) {
        if (queryName == null || queryName.isBlank() || !queryName.matches("^[a-zA-Z0-9_]+\\.[a-zA-Z0-9_]+$")) {
            throw new EngineException("INVALID_QUERY_NAME", HttpStatus.BAD_REQUEST, queryName,
                    "queryName must be in '{module}.{name}' form");
        }
        return queryName;
    }

}
