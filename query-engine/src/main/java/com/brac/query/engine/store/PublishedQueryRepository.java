package com.brac.query.engine.store;

import com.brac.query.engine.model.PublishedQuery;
import com.brac.query.engine.model.QueryEntry;
import com.brac.query.engine.model.ValidatedQuery;
import io.r2dbc.postgresql.codec.Json;
import io.r2dbc.spi.Readable;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Query Registry backed by the {@code published_query} table in Postgres, accessed reactively via
 * {@link DatabaseClient} (R2DBC). At most one published entry per queryName; publishing again
 * overwrites the row via upsert. No versioning.
 */
@Component
public class PublishedQueryRepository {

    private static final String UPSERT = """
            INSERT INTO published_query
                (query_name, sql_text, params, allowed_roles, fetch_size, validated_query,
                 published_at, published_by)
            VALUES
                (:queryName, :sqlText, :params, :allowedRoles, :fetchSize, :validatedQuery,
                 :publishedAt, :publishedBy)
            ON CONFLICT (query_name) DO UPDATE SET
                sql_text = EXCLUDED.sql_text,
                params = EXCLUDED.params,
                allowed_roles = EXCLUDED.allowed_roles,
                fetch_size = EXCLUDED.fetch_size,
                validated_query = EXCLUDED.validated_query,
                published_at = EXCLUDED.published_at,
                published_by = EXCLUDED.published_by
            """;

    private final DatabaseClient db;
    private final JsonbSupport jsonb;

    public PublishedQueryRepository(DatabaseClient db, JsonbSupport jsonb) {
        this.db = db;
        this.jsonb = jsonb;
    }

    public Mono<PublishedQuery> find(String queryName) {
        return db.sql("SELECT * FROM published_query WHERE query_name = :queryName")
                .bind("queryName", queryName)
                .map((row, meta) -> toPublishedQuery(row))
                .one();
    }

    public Mono<Void> publish(PublishedQuery publishedQuery) {
        DatabaseClient.GenericExecuteSpec spec = db.sql(UPSERT)
                .bind("queryName", publishedQuery.queryName())
                .bind("sqlText", publishedQuery.entry().sql())
                .bind("params", jsonb.toJson(publishedQuery.entry().params()))
                .bind("allowedRoles", jsonb.toJson(publishedQuery.entry().allowedRoles()))
                .bind("validatedQuery", jsonb.toJson(publishedQuery.validatedQuery()))
                .bind("publishedAt", publishedQuery.publishedAt().atOffset(ZoneOffset.UTC))
                .bind("publishedBy", publishedQuery.publishedBy());

        spec = publishedQuery.entry().fetchSize() == null
                ? spec.bindNull("fetchSize", Integer.class)
                : spec.bind("fetchSize", publishedQuery.entry().fetchSize());

        return spec.fetch().rowsUpdated().then();
    }

    public Flux<PublishedQuery> findAll() {
        return db.sql("SELECT * FROM published_query")
                .map((row, meta) -> toPublishedQuery(row))
                .all();
    }

    public Flux<String> allQueryNames() {
        return db.sql("SELECT query_name FROM published_query")
                .map((row, meta) -> row.get("query_name", String.class))
                .all();
    }

    private PublishedQuery toPublishedQuery(Readable row) {
        QueryEntry entry = new QueryEntry(
                row.get("sql_text", String.class),
                jsonb.fromJson(row.get("params", Json.class), new TypeReference<Map<String, String>>() {
                }),
                jsonb.fromJson(row.get("allowed_roles", Json.class), new TypeReference<List<String>>() {
                }),
                row.get("fetch_size", Integer.class)
        );
        ValidatedQuery validatedQuery = jsonb.fromJson(row.get("validated_query", Json.class), ValidatedQuery.class);
        return new PublishedQuery(
                row.get("query_name", String.class),
                entry,
                validatedQuery,
                row.get("published_at", OffsetDateTime.class).toInstant(),
                row.get("published_by", String.class)
        );
    }
}
