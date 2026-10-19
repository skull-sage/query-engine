package com.brac.query.engine.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record SimulateResponse(
        String queryName,
        List<Map<String, Object>> rows,
        int rowCount,
        boolean truncated,
        Metrics metrics,
        List<String> redactedFields
) {
    public record Metrics(long latencyMs, long rowsScanned, double planCost) {
    }
}
