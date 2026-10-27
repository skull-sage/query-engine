package com.brac.query.engine.store;

import com.brac.query.engine.model.QueryEntry;
import com.brac.query.engine.model.QueryState;
import com.brac.query.engine.model.StoredQuery;
import com.brac.query.engine.model.ValidatedQuery;
import com.brac.query.engine.model.ValidationError;
import io.r2dbc.postgresql.codec.Json;
import io.r2dbc.spi.Readable;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes {@code qe_stored_query}, the engine's one table, through the service's own
 * {@link DatabaseClient}. There is no in-memory state: every state change is a statement here.
 *
 * <p>The writes that change a query's state are single conditional statements, so a decision made
 * on a row that has since changed does nothing instead of overwriting the newer row. Each returns
 * whether it changed a row; {@code false} means "not in the state you expected any more" and the
 * service answers with the error that explains it. The rules about who may do what are in
 * {@code QueryLifecycle}; the conditions here only make sure they still hold at write time.
 */
@Component
public class StoredQueryRepository {

    /** The table's name, which the SQL guard keeps author SQL away from. */
    public static final String TABLE = "qe_stored_query";

    /**
     * Saves a draft. A new name inserts. An existing row is only taken over when it is a draft held by the
     * same author, or when it is published and the caller said so explicitly ({@code :unpublish}); the roles
     * and the {@code published_*} columns of such a row are kept for the next publish.
     */
    private static final String SAVE_DRAFT = """
            INSERT INTO qe_stored_query
                (query_name, state, sql_text, params, fetch_size, validated_query, errors, allowed_roles,
                 author, revision, updated_at)
            VALUES
                (:queryName, 'drafted', :sqlText, :params, :fetchSize, :validatedQuery, :errors, :allowedRoles,
                 :author, 1, :updatedAt)
            ON CONFLICT (query_name) DO UPDATE SET
                state = 'drafted',
                sql_text = EXCLUDED.sql_text,
                params = EXCLUDED.params,
                fetch_size = EXCLUDED.fetch_size,
                validated_query = EXCLUDED.validated_query,
                errors = EXCLUDED.errors,
                author = EXCLUDED.author,
                revision = qe_stored_query.revision + 1,
                updated_at = EXCLUDED.updated_at
            WHERE (qe_stored_query.state <> 'published' AND qe_stored_query.author = EXCLUDED.author)
               OR (qe_stored_query.state = 'published' AND :unpublish)
            """;

    private static final String MARK_SIMULATED = """
            UPDATE qe_stored_query
               SET state = 'simulated', revision = revision + 1, updated_at = :now
             WHERE query_name = :queryName AND state IN ('drafted', 'simulated') AND revision = :revision
            """;

    private static final String PUBLISH = """
            UPDATE qe_stored_query
               SET state = 'published', allowed_roles = :allowedRoles, validated_query = :validatedQuery,
                   revision = revision + 1, updated_at = :now, published_at = :now, published_by = :publishedBy
             WHERE query_name = :queryName AND state = 'simulated' AND revision = :revision
            """;

    private final DatabaseClient databaseClient;
    private final JsonbSupport jsonb;

    public StoredQueryRepository(DatabaseClient databaseClient, JsonbSupport jsonb) {
        this.databaseClient = databaseClient;
        this.jsonb = jsonb;
    }

    public Mono<StoredQuery> find(String queryName) {
        return databaseClient.sql("SELECT * FROM qe_stored_query WHERE query_name = :queryName")
                .bind("queryName", queryName)
                .map((row, meta) -> toStoredQuery(row))
                .one();
    }

    public Flux<StoredQuery> findAll() {
        return databaseClient.sql("SELECT * FROM qe_stored_query ORDER BY query_name")
                .map((row, meta) -> toStoredQuery(row))
                .all();
    }

    /** Names of queries that are or were live, so a bare name still resolves while the query is being edited. */
    public Flux<String> publishedNames() {
        return databaseClient.sql("SELECT query_name FROM qe_stored_query WHERE published_at IS NOT NULL")
                .map((row, meta) -> row.get("query_name", String.class))
                .all();
    }

    /** @return false when the row is held by someone else, or is published and {@code unpublish} was not set */
    public Mono<Boolean> saveDraft(StoredQuery draft, boolean unpublish) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(SAVE_DRAFT)
                .bind("queryName", draft.queryName())
                .bind("sqlText", draft.entry().sql())
                .bind("params", jsonb.toJson(draft.entry().params() == null ? Map.of() : draft.entry().params()))
                .bind("errors", jsonb.toJson(draft.errors()))
                .bind("allowedRoles", jsonb.toJson(draft.entry().allowedRoles() == null ? List.of() : draft.entry().allowedRoles()))
                .bind("author", draft.author())
                .bind("updatedAt", toOffset(draft.updatedAt()))
                .bind("unpublish", unpublish);
        spec = draft.entry().fetchSize() == null
                ? spec.bindNull("fetchSize", Integer.class)
                : spec.bind("fetchSize", draft.entry().fetchSize());
        spec = draft.validatedQuery() == null
                ? spec.bindNull("validatedQuery", Json.class)
                : spec.bind("validatedQuery", jsonb.toJson(draft.validatedQuery()));
        return spec.fetch().rowsUpdated().map(rows -> rows > 0);
    }

    /** @return false when the row changed since {@code revision} was read, or is no longer a draft */
    public Mono<Boolean> markSimulated(String queryName, long revision, Instant now) {
        return databaseClient.sql(MARK_SIMULATED)
                .bind("queryName", queryName)
                .bind("revision", revision)
                .bind("now", toOffset(now))
                .fetch().rowsUpdated().map(rows -> rows > 0);
    }

    /** @return false when the row changed since {@code revision} was read, or is not simulated */
    public Mono<Boolean> publish(String queryName, long revision, ValidatedQuery validated, List<String> allowedRoles,
                                 String publishedBy, Instant now) {
        return databaseClient.sql(PUBLISH)
                .bind("queryName", queryName)
                .bind("revision", revision)
                .bind("allowedRoles", jsonb.toJson(allowedRoles))
                .bind("validatedQuery", jsonb.toJson(validated))
                .bind("publishedBy", publishedBy)
                .bind("now", toOffset(now))
                .fetch().rowsUpdated().map(rows -> rows > 0);
    }

    /** Deletes a draft or simulated query. A published one is never deleted here. */
    public Mono<Boolean> discard(String queryName) {
        return databaseClient.sql("DELETE FROM qe_stored_query WHERE query_name = :queryName AND state IN ('drafted', 'simulated')")
                .bind("queryName", queryName)
                .fetch().rowsUpdated().map(rows -> rows > 0);
    }

    private StoredQuery toStoredQuery(Readable row) {
        QueryEntry entry = new QueryEntry(
                row.get("sql_text", String.class),
                jsonb.fromJson(row.get("params", Json.class), new TypeReference<Map<String, String>>() {
                }),
                jsonb.fromJson(row.get("allowed_roles", Json.class), new TypeReference<List<String>>() {
                }),
                row.get("fetch_size", Integer.class));
        ValidatedQuery validated = jsonb.fromJson(row.get("validated_query", Json.class), ValidatedQuery.class);
        List<ValidationError> errors = jsonb.fromJson(row.get("errors", Json.class), new TypeReference<List<ValidationError>>() {
        });
        OffsetDateTime publishedAt = row.get("published_at", OffsetDateTime.class);
        return new StoredQuery(
                row.get("query_name", String.class),
                QueryState.fromDb(row.get("state", String.class)),
                entry,
                validated,
                errors == null ? List.of() : errors,
                row.get("author", String.class),
                row.get("revision", Long.class),
                row.get("updated_at", OffsetDateTime.class).toInstant(),
                publishedAt == null ? null : publishedAt.toInstant(),
                row.get("published_by", String.class));
    }

    private static OffsetDateTime toOffset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
