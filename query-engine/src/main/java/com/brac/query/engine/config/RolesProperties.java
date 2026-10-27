package com.brac.query.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * The roles and groups the UI offers when choosing who may call a query (at publish time) and who to
 * call it as (when running it). Empty by default, which means the engine's built-in demo catalog is
 * used (see {@code QueryRolesCatalog}).
 *
 * <p>In a real deployment this mirrors the enterprise identity provider (for example Keycloak realm
 * roles and groups); list them in the service's {@code application.yml} under {@code query-engine.roles}.
 * The catalog is only a list of names to pick from: it grants nothing. What a caller may do is decided
 * by the {@code allowed_roles} stored with each published query, checked against the caller's roles.
 */
@ConfigurationProperties(prefix = "query-engine.roles")
public record RolesProperties(List<RealmRole> realmRoles, List<Group> groups) {

    public record RealmRole(String name, String description) {
    }

    public record Group(String name, String path, String description) {
    }
}
