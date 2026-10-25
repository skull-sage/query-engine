package com.brac.query.engine.validation;

import com.brac.query.engine.config.StreamProperties;
import com.brac.query.engine.model.ParamSpec;
import com.brac.query.engine.model.ParamType;
import com.brac.query.engine.model.QueryEntry;
import com.brac.query.engine.model.ValidatedQuery;
import com.brac.query.engine.model.ValidationError;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates a drafted {@link QueryEntry} and builds its {@link ValidatedQuery} descriptor.
 * {@link SqlGuard} first checks the SQL is a single read-only SELECT and finds its
 * {@code :identifier} placeholders; those are then cross-checked against {@code params}.
 */
@Component
public class QueryValidator {

    private final StreamProperties streamProperties;
    private final SqlGuard sqlGuard;

    public QueryValidator(StreamProperties streamProperties, SqlGuard sqlGuard) {
        this.streamProperties = streamProperties;
        this.sqlGuard = sqlGuard;
    }

    public ValidationResult validate(QueryEntry entry) {
        List<ValidationError> errors = new ArrayList<>();

        SqlGuard.Analysis analysis = sqlGuard.analyze(entry.sql());
        errors.addAll(analysis.errors());
        Set<String> usedTokens = analysis.params();
        Map<String, String> declared = entry.params() == null ? Map.of() : entry.params();

        // When the text could not even be tokenized the placeholders are unreliable, and
        // complaining about them would only bury the real problem.
        if (!analysis.lexFailed()) {
            for (String token : usedTokens) {
                if (!declared.containsKey(token)) {
                    errors.add(new ValidationError("UNDECLARED_PARAM", token,
                            ":" + token + " is used in sql but missing from params"));
                }
            }
            for (String declaredName : declared.keySet()) {
                if (!usedTokens.contains(declaredName)) {
                    errors.add(new ValidationError("UNUSED_PARAM", declaredName,
                            declaredName + " is declared in params but never used in sql"));
                }
            }
        }

        List<ParamSpec> params = new ArrayList<>();
        for (Map.Entry<String, String> declEntry : declared.entrySet()) {
            String name = declEntry.getKey();
            if (!usedTokens.contains(name)) {
                continue;
            }
            var resolved = ParamType.fromDeclared(declEntry.getValue());
            if (resolved.isEmpty()) {
                errors.add(new ValidationError("UNKNOWN_PARAM_TYPE", name,
                        "params declares an unknown type '" + declEntry.getValue() + "' for " + name));
                continue;
            }
            params.add(new ParamSpec(name, resolved.get()));
        }

        if (entry.sql() == null || entry.sql().isBlank()) {
            errors.add(new ValidationError("MISSING_SQL", null, "sql is required"));
        }

        Integer fetchSize = entry.fetchSize();
        if (fetchSize != null && (fetchSize < 1 || fetchSize > streamProperties.maxFetchSize())) {
            errors.add(new ValidationError("INVALID_FETCH_SIZE", null,
                    "fetchSize must be between 1 and " + streamProperties.maxFetchSize() + " (got " + fetchSize + ")"));
        }

        if (!errors.isEmpty()) {
            return new ValidationResult(null, params, errors);
        }

        ValidatedQuery validated = new ValidatedQuery(
                entry.sql(),
                params,
                entry.allowedRoles() == null ? List.of() : entry.allowedRoles(),
                fetchSize
        );
        return new ValidationResult(validated, params, errors);
    }

    public record ValidationResult(ValidatedQuery validatedQuery, List<ParamSpec> params, List<ValidationError> errors) {
        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }
}
