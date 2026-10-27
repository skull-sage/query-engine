package com.brac.query.engine.web;

import com.brac.query.engine.dto.QueryRolesResponse;
import com.brac.query.engine.store.QueryRolesCatalog;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Exposes the identity catalog (realm roles + groups) the UI needs when deciding who may invoke a
 * query. Kept separate from {@code /engine} because it is reference data, not part of the
 * draft/publish lifecycle. The catalog comes from configuration, so this touches no database.
 */
@RestController
public class QueryRolesController {

    private final QueryRolesCatalog catalog;

    public QueryRolesController(QueryRolesCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/query-roles")
    public Mono<ResponseEntity<QueryRolesResponse>> queryRoles() {
        return Mono.just(ResponseEntity.ok(catalog.catalog()));
    }
}
