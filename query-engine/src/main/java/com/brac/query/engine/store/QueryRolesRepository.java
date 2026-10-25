package com.brac.query.engine.store;

import com.brac.query.engine.dto.QueryRolesResponse;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Reads the seeded identity catalog ({@code query_engine.realm_role} and
 * {@code query_engine.realm_group}) that backs {@code GET /query-roles}, reactively via
 * {@link DatabaseClient} on the engine's own pool ({@link EngineDatabase}). Read-only -- the
 * catalog is managed by the identity provider in a real deployment and is seeded here (see
 * query-engine/schema.sql).
 */
@Component
public class QueryRolesRepository {

    private final DatabaseClient db;

    public QueryRolesRepository(EngineDatabase engine) {
        this.db = engine.client();
    }

    public Flux<QueryRolesResponse.RealmRole> findRealmRoles() {
        return db.sql("SELECT name, description FROM query_engine.realm_role ORDER BY name")
                .map((row, meta) -> new QueryRolesResponse.RealmRole(
                        row.get("name", String.class),
                        row.get("description", String.class)))
                .all();
    }

    public Flux<QueryRolesResponse.Group> findGroups() {
        return db.sql("SELECT name, path, description FROM query_engine.realm_group ORDER BY name")
                .map((row, meta) -> new QueryRolesResponse.Group(
                        row.get("name", String.class),
                        row.get("path", String.class),
                        row.get("description", String.class)))
                .all();
    }
}
