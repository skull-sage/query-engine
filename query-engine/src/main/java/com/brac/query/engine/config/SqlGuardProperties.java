package com.brac.query.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Settings for {@link com.brac.query.engine.validation.SqlGuard}, the check that a query's SQL is
 * a single read-only {@code SELECT}. See docs/security_protocol.md.
 *
 * @param enabled         {@code false} switches the rules off, for testing the database's own
 *                        protections in a dev environment. Never turn it off in a shared one
 * @param deniedFunctions functions that may not be called, lower-case; an entry ending in
 *                        {@code *} matches every name starting with it. Leave unset to use
 *                        {@link #DEFAULT_DENIED_FUNCTIONS}; setting it replaces that list
 */
@ConfigurationProperties(prefix = "query-engine.sql-guard")
public record SqlGuardProperties(
        @DefaultValue("true") boolean enabled,
        List<String> deniedFunctions
) {

    public static final List<String> DEFAULT_DENIED_FUNCTIONS = List.of(
            // sessions and settings
            "set_config", "pg_terminate_backend", "pg_cancel_backend", "pg_reload_conf",
            // waiting and locks
            "pg_sleep", "pg_sleep_for", "pg_sleep_until", "pg_advisory_*", "pg_try_advisory_*",
            // side effects
            "nextval", "setval", "pg_notify", "lo_*", "dblink*",
            // server files
            "pg_read_file", "pg_read_binary_file", "pg_ls_dir", "pg_stat_file",
            // run other SQL text, or read a table by its name given as a string
            "query_to_xml", "query_to_xml_and_xmlschema", "query_to_xmlschema",
            "cursor_to_xml", "cursor_to_xmlschema",
            "table_to_xml", "table_to_xml_and_xmlschema", "table_to_xmlschema",
            "schema_to_xml", "schema_to_xml_and_xmlschema", "schema_to_xmlschema",
            "database_to_xml", "database_to_xml_and_xmlschema", "database_to_xmlschema");

    public SqlGuardProperties {
        deniedFunctions = deniedFunctions == null ? DEFAULT_DENIED_FUNCTIONS : List.copyOf(deniedFunctions);
    }

    /** The built-in rules, for code that builds the guard without Spring (tests). */
    public static SqlGuardProperties defaults() {
        return new SqlGuardProperties(true, null);
    }
}
