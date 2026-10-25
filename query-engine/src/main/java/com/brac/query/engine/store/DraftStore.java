package com.brac.query.engine.store;

import com.brac.query.engine.model.DraftRecord;
import com.brac.query.engine.model.DraftStatus;
import com.brac.query.engine.model.QueryEntry;
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
 * Draft Store backed by the {@code query_engine.drafted_query} table in Postgres, accessed reactively
 * through {@link DatabaseClient} (R2DBC) on the engine's own pool ({@link EngineDatabase}) -- no
 * blocking JDBC, no in-memory map. At most one open draft
 * exists per queryName; drafting again overwrites the row via upsert.
 */
@Component
public class DraftStore {

    private static final String UPSERT = """
            INSERT INTO query_engine.drafted_query
                (query_name, sql_text, params, allowed_roles, fetch_size, validated_query,
                 status, errors, simulated, author, updated_at)
            VALUES
                (:queryName, :sqlText, :params, :allowedRoles, :fetchSize, :validatedQuery,
                 :status, :errors, :simulated, :author, :updatedAt)
            ON CONFLICT (query_name) DO UPDATE SET
                sql_text = EXCLUDED.sql_text,
                params = EXCLUDED.params,
                allowed_roles = EXCLUDED.allowed_roles,
                fetch_size = EXCLUDED.fetch_size,
                validated_query = EXCLUDED.validated_query,
                status = EXCLUDED.status,
                errors = EXCLUDED.errors,
                simulated = EXCLUDED.simulated,
                author = EXCLUDED.author,
                updated_at = EXCLUDED.updated_at
            """;

    private final DatabaseClient db;
    private final JsonbSupport jsonb;

    public DraftStore(EngineDatabase engine, JsonbSupport jsonb) {
        this.db = engine.client();
        this.jsonb = jsonb;
    }

    public Mono<DraftRecord> find(String queryName) {
        return db.sql("SELECT * FROM query_engine.drafted_query WHERE query_name = :queryName")
                .bind("queryName", queryName)
                .map((row, meta) -> toDraftRecord(row))
                .one();
    }

    public Mono<Void> save(DraftRecord record) {
        DatabaseClient.GenericExecuteSpec spec = db.sql(UPSERT)
                .bind("queryName", record.queryName())
                .bind("sqlText", record.entry().sql())
                .bind("params", jsonb.toJson(record.entry().params()))
                .bind("allowedRoles", jsonb.toJson(record.entry().allowedRoles()))
                .bind("status", record.status().name())
                .bind("errors", jsonb.toJson(record.errors()))
                .bind("simulated", record.simulated())
                .bind("author", record.author())
                .bind("updatedAt", toOffset(record.updatedAt()));

        spec = record.entry().fetchSize() == null
                ? spec.bindNull("fetchSize", Integer.class)
                : spec.bind("fetchSize", record.entry().fetchSize());
        spec = record.validatedQuery() == null
                ? spec.bindNull("validatedQuery", Json.class)
                : spec.bind("validatedQuery", jsonb.toJson(record.validatedQuery()));

        return spec.fetch().rowsUpdated().then();
    }

    public Mono<Void> delete(String queryName) {
        return db.sql("DELETE FROM query_engine.drafted_query WHERE query_name = :queryName")
                .bind("queryName", queryName)
                .fetch().rowsUpdated().then();
    }

    public Flux<DraftRecord> findAll() {
        return db.sql("SELECT * FROM query_engine.drafted_query")
                .map((row, meta) -> toDraftRecord(row))
                .all();
    }

    private DraftRecord toDraftRecord(Readable row) {
        QueryEntry entry = new QueryEntry(
                row.get("sql_text", String.class),
                jsonb.fromJson(row.get("params", Json.class), new TypeReference<Map<String, String>>() {
                }),
                jsonb.fromJson(row.get("allowed_roles", Json.class), new TypeReference<List<String>>() {
                }),
                row.get("fetch_size", Integer.class)
        );
        ValidatedQuery validatedQuery = jsonb.fromJson(row.get("validated_query", Json.class), ValidatedQuery.class);
        List<ValidationError> errors = jsonb.fromJson(row.get("errors", Json.class), new TypeReference<List<ValidationError>>() {
        });
        return new DraftRecord(
                row.get("query_name", String.class),
                entry,
                validatedQuery,
                DraftStatus.valueOf(row.get("status", String.class)),
                errors == null ? List.of() : errors,
                Boolean.TRUE.equals(row.get("simulated", Boolean.class)),
                row.get("author", String.class),
                row.get("updated_at", OffsetDateTime.class).toInstant()
        );
    }

    private static OffsetDateTime toOffset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
