package com.brac.query.engine.model;

import java.util.Locale;

/**
 * Where a stored query is in its life. A query is one row, so it is in exactly one of these states
 * (see the PRD, "Query lifecycle"):
 * <ul>
 *   <li>{@link #DRAFTED}: saved, not yet simulated successfully; may carry errors;</li>
 *   <li>{@link #SIMULATED}: the current definition ran against real data; the only state that can be published;</li>
 *   <li>{@link #PUBLISHED}: live, callable through {@code /data-query}.</li>
 * </ul>
 */
public enum QueryState {
    DRAFTED,
    SIMULATED,
    PUBLISHED;

    /** The value stored in the {@code state} column. */
    public String db() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static QueryState fromDb(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }

    /** True for the two states in which the query is being worked on and is held by an author. */
    public boolean isDraft() {
        return this != PUBLISHED;
    }
}
