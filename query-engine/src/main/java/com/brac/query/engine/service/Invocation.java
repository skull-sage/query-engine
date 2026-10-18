package com.brac.query.engine.service;

import com.brac.query.engine.model.ValidatedQuery;

import java.util.Map;

/**
 * A {@code /data-query} call that passed every check done before reading data: the query exists,
 * the caller's roles are allowed, and the params are present and correctly typed. What remains is
 * reading the rows, either streamed or bounded (see {@link DataQueryService}).
 */
public record Invocation(String queryName, ValidatedQuery query, Map<String, Object> params) {
}
