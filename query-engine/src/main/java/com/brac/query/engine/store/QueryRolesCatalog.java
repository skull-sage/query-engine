package com.brac.query.engine.store;

import com.brac.query.engine.config.RolesProperties;
import com.brac.query.engine.dto.QueryRolesResponse;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * The identity catalog behind {@code GET /query-roles}: realm roles and groups from configuration
 * ({@code query-engine.roles}), or the built-in demo catalog when none is configured. Nothing is
 * stored in the database; the catalog is only a list of names to choose from.
 */
@Component
public class QueryRolesCatalog {

    private static final List<QueryRolesResponse.RealmRole> DEFAULT_ROLES = List.of(
            new QueryRolesResponse.RealmRole("senior_DBA", "Senior database administrator with broad read access to projections"),
            new QueryRolesResponse.RealmRole("chief_DBA", "Lead DBA, may invoke any published query"),
            new QueryRolesResponse.RealmRole("data_analyst", "Business/data analyst running reporting queries"),
            new QueryRolesResponse.RealmRole("branch_manager", "Branch manager, scoped to their own branch data"),
            new QueryRolesResponse.RealmRole("warehouse_manager", "Warehouse manager, scoped to their own warehouse stock"),
            new QueryRolesResponse.RealmRole("auditor", "Read-only auditor for compliance reviews"),
            new QueryRolesResponse.RealmRole("ops_engineer", "Operations/SRE engineer monitoring live data"),
            new QueryRolesResponse.RealmRole("read_only", "Generic least-privilege read-only role"));

    private static final List<QueryRolesResponse.Group> DEFAULT_GROUPS = List.of(
            new QueryRolesResponse.Group("data-platform", "/data-platform", "Data platform engineering team"),
            new QueryRolesResponse.Group("finance", "/finance", "Finance department"),
            new QueryRolesResponse.Group("operations", "/operations", "Operations and support"),
            new QueryRolesResponse.Group("supply-chain", "/supply-chain", "Procurement and supply chain planning"),
            new QueryRolesResponse.Group("audit", "/audit", "Internal audit and compliance"),
            new QueryRolesResponse.Group("branch-managers", "/branch-managers", "All branch managers"),
            new QueryRolesResponse.Group("executives", "/executives", "Executive leadership"));

    private final QueryRolesResponse catalog;

    public QueryRolesCatalog(RolesProperties properties) {
        List<QueryRolesResponse.RealmRole> roles = properties.realmRoles() == null || properties.realmRoles().isEmpty()
                ? DEFAULT_ROLES
                : properties.realmRoles().stream()
                        .map(r -> new QueryRolesResponse.RealmRole(r.name(), r.description())).toList();
        List<QueryRolesResponse.Group> groups = properties.groups() == null || properties.groups().isEmpty()
                ? DEFAULT_GROUPS
                : properties.groups().stream()
                        .map(g -> new QueryRolesResponse.Group(g.name(), g.path() != null ? g.path() : "/" + g.name(), g.description()))
                        .toList();
        this.catalog = new QueryRolesResponse(
                roles.stream().sorted(Comparator.comparing(QueryRolesResponse.RealmRole::name)).toList(),
                groups.stream().sorted(Comparator.comparing(QueryRolesResponse.Group::name)).toList());
    }

    public QueryRolesResponse catalog() {
        return catalog;
    }
}
