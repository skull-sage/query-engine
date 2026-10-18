package com.brac.query.engine.model;

import java.util.Map;

/**
 * The query entry an author writes, equivalent to one YAML list item in a {@code {module}_sql.yml}
 * file (see PRD "Query definition format").
 *
 * <p>{@code fetchSize} is optional: rows per database round trip when this query is read through a
 * cursor. Null means the service default ({@code query-engine.stream.fetch-size}).
 */
public record QueryEntry(
        String sql,
        Map<String, String> params,
        java.util.List<String> allowedRoles,
        Integer fetchSize
) {
}
