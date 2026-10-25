package com.brac.query.engine.validation;

import com.brac.query.engine.config.SqlGuardProperties;
import com.brac.query.engine.model.ValidationError;
import com.brac.query.engine.validation.SqlLexer.Kind;
import com.brac.query.engine.validation.SqlLexer.Token;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks that a query's SQL is a single read-only {@code SELECT}, and finds its {@code :param}
 * placeholders. It runs when a query is drafted, again when it is simulated, published and called,
 * and over every stored query at startup.
 *
 * <p>This is a guard rail, not the protection itself. It reads the text, so it cannot see what a
 * function does when called: a read-only database login and a {@code BEGIN READ ONLY} transaction
 * are what actually stop writes. The guard gives authors a clear, early error and closes gaps
 * those two cannot, such as several statements in one call.
 *
 * <p>Rules (error codes in brackets):
 * <ul>
 *   <li>text must end inside no string, quoted name or comment [SQL_UNTERMINATED];</li>
 *   <li>the statement starts with SELECT or WITH [SQL_NOT_SELECT];</li>
 *   <li>only one statement; a single trailing {@code ;} is fine [SQL_MULTIPLE_STATEMENTS];</li>
 *   <li>no INSERT, UPDATE, DELETE or MERGE anywhere, so none inside WITH either [SQL_DATA_MODIFYING];</li>
 *   <li>no {@code INTO} [SQL_SELECT_INTO];</li>
 *   <li>no {@code FOR UPDATE / SHARE / NO KEY UPDATE / KEY SHARE} [SQL_LOCKING_CLAUSE];</li>
 *   <li>no use of the {@code query_engine} schema [SQL_FORBIDDEN_SCHEMA];</li>
 *   <li>no call to a denied function [SQL_FORBIDDEN_FUNCTION];</li>
 *   <li>no {@code U&"..."} names [SQL_UNSUPPORTED_SYNTAX].</li>
 * </ul>
 * A column called {@code status}, {@code comment} or {@code end} is fine, and so is {@code
 * updated_at}: words are compared whole. To use a column literally called {@code update}, quote
 * it: {@code "update"}.
 *
 * <p>Ported to JavaScript in {@code query-builder-ui/src/sql/sqlGuard.js}; both run the cases in
 * {@code src/test/resources/sql-guard-cases.json}.
 */
@Component
public class SqlGuard {

    public static final String UNTERMINATED = "SQL_UNTERMINATED";
    public static final String NOT_SELECT = "SQL_NOT_SELECT";
    public static final String MULTIPLE_STATEMENTS = "SQL_MULTIPLE_STATEMENTS";
    public static final String DATA_MODIFYING = "SQL_DATA_MODIFYING";
    public static final String SELECT_INTO = "SQL_SELECT_INTO";
    public static final String LOCKING_CLAUSE = "SQL_LOCKING_CLAUSE";
    public static final String FORBIDDEN_SCHEMA = "SQL_FORBIDDEN_SCHEMA";
    public static final String FORBIDDEN_FUNCTION = "SQL_FORBIDDEN_FUNCTION";
    public static final String UNSUPPORTED_SYNTAX = "SQL_UNSUPPORTED_SYNTAX";

    /** Schema that holds the engine's own tables; author SQL may not touch it. */
    public static final String ENGINE_SCHEMA = "query_engine";

    private static final int MAX_ERRORS = 20;
    private static final Pattern PARAM_TOKEN = Pattern.compile(":([a-zA-Z_][a-zA-Z0-9_]*)");

    private final boolean enabled;
    private final Set<String> deniedExact = new LinkedHashSet<>();
    private final List<String> deniedPrefixes = new ArrayList<>();

    public SqlGuard(SqlGuardProperties properties) {
        this.enabled = properties.enabled();
        for (String entry : properties.deniedFunctions()) {
            String name = entry.trim().toLowerCase(Locale.ROOT);
            if (name.endsWith("*")) {
                deniedPrefixes.add(name.substring(0, name.length() - 1));
            } else if (!name.isEmpty()) {
                deniedExact.add(name);
            }
        }
    }

    /**
     * @param errors    rule violations, empty when the SQL is acceptable
     * @param params    placeholder names in order of first use
     * @param lexFailed the text could not be split into tokens, so {@code params} may be incomplete
     */
    public record Analysis(List<ValidationError> errors, Set<String> params, boolean lexFailed) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    public Analysis analyze(String sql) {
        if (sql == null || sql.isBlank()) {
            return new Analysis(List.of(), new LinkedHashSet<>(), false);
        }
        if (!enabled) {
            return new Analysis(List.of(), regexParams(sql), false);
        }

        SqlLexer.Result lexed = SqlLexer.lex(sql);
        List<Token> tokens = lexed.tokens();
        Set<String> params = new LinkedHashSet<>();
        for (Token token : tokens) {
            if (token.kind() == Kind.PARAM) {
                params.add(token.text());
            }
        }

        List<ValidationError> errors = new ArrayList<>();
        if (lexed.unterminated() != null) {
            SqlLexer.Unterminated open = lexed.unterminated();
            errors.add(error(UNTERMINATED, "Unterminated " + open.what() + " starting at "
                    + SqlLexer.location(sql, open.offset()) + "; it is never closed"));
            return new Analysis(errors, params, true);
        }

        // Statement shape: one SELECT or WITH.
        int n = tokens.size();
        int head = 0;
        while (head < n && tokens.get(head).isSymbol('(')) {
            head++;
        }
        boolean selectLike = head < n && (tokens.get(head).isWord("select") || tokens.get(head).isWord("with"));
        if (!selectLike) {
            String found = head < n ? "'" + display(sql, tokens.get(head)) + "' at " + SqlLexer.location(sql, tokens.get(head).offset())
                    : "nothing";
            errors.add(error(NOT_SELECT, "Only a SELECT query is allowed: the statement must start with SELECT or WITH (found "
                    + found + ")"));
        }
        for (int i = 0; i < n - 1; i++) {
            if (tokens.get(i).isSymbol(';')) {
                errors.add(error(MULTIPLE_STATEMENTS, "Only one statement is allowed, but there is more text after the ';' at "
                        + SqlLexer.location(sql, tokens.get(i).offset())));
                break;
            }
        }
        if (!selectLike) {
            return new Analysis(errors, params, false);
        }

        for (int i = 0; i < n && errors.size() < MAX_ERRORS; i++) {
            Token token = tokens.get(i);
            Token prev = i > 0 ? tokens.get(i - 1) : null;
            Token next = i + 1 < n ? tokens.get(i + 1) : null;
            boolean afterDot = prev != null && prev.isSymbol('.');
            String where = " (" + SqlLexer.location(sql, token.offset()) + ")";

            if (token.kind() == Kind.UNICODE_IDENT) {
                errors.add(error(UNSUPPORTED_SYNTAX,
                        "Names written as U&\"...\" are not supported; write the name directly" + where));
                continue;
            }
            if (token.kind() == Kind.WORD && !afterDot) {
                switch (token.text()) {
                    case "insert", "delete", "merge" -> errors.add(error(DATA_MODIFYING,
                            token.text().toUpperCase(Locale.ROOT) + " is not allowed, not even inside WITH: queries may only read"
                                    + where));
                    case "update" -> {
                        if (!isLockingUpdate(tokens, i)) {
                            errors.add(error(DATA_MODIFYING,
                                    "UPDATE is not allowed, not even inside WITH: queries may only read" + where));
                        }
                    }
                    case "into" -> {
                        boolean insertInto = prev != null && (prev.isWord("insert") || prev.isWord("merge"));
                        if (!insertInto) {
                            errors.add(error(SELECT_INTO, "SELECT ... INTO creates a table and is not allowed" + where));
                        }
                    }
                    case "for" -> {
                        if (next != null && (next.isWord("update") || next.isWord("share")
                                || next.isWord("no") || next.isWord("key"))) {
                            errors.add(error(LOCKING_CLAUSE,
                                    "Row locking (FOR UPDATE / FOR SHARE) is not allowed: queries may only read" + where));
                        }
                    }
                    default -> {
                    }
                }
            }
            if (token.isName() && next != null && next.isSymbol('.') && ENGINE_SCHEMA.equals(token.text())) {
                errors.add(error(FORBIDDEN_SCHEMA,
                        "The '" + ENGINE_SCHEMA + "' schema belongs to the query engine and can't be queried" + where));
            }
            if (token.isName() && next != null && next.isSymbol('(') && isDenied(token.text())) {
                errors.add(error(FORBIDDEN_FUNCTION, "Function '" + token.text() + "' is not allowed" + where));
            }
        }
        return new Analysis(errors, params, false);
    }

    private boolean isDenied(String name) {
        if (deniedExact.contains(name)) {
            return true;
        }
        for (String prefix : deniedPrefixes) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** {@code FOR UPDATE} and {@code FOR NO KEY UPDATE}: the UPDATE belongs to a locking clause, reported at FOR. */
    private static boolean isLockingUpdate(List<Token> tokens, int index) {
        if (index == 0) {
            return false;
        }
        Token prev = tokens.get(index - 1);
        if (prev.isWord("for")) {
            return true;
        }
        return prev.isWord("key") && index >= 2 && tokens.get(index - 2).isWord("no");
    }

    private static String display(String sql, Token token) {
        return token.kind() == Kind.WORD ? token.text().toUpperCase(Locale.ROOT)
                : token.kind() == Kind.QUOTED_IDENT ? "\"" + token.text() + "\""
                : String.valueOf(sql.charAt(token.offset()));
    }

    private static ValidationError error(String code, String message) {
        return new ValidationError(code, null, message);
    }

    /** The scan used when the guard is switched off: every {@code :identifier}, as before the guard existed. */
    private static Set<String> regexParams(String sql) {
        Set<String> params = new LinkedHashSet<>();
        Matcher matcher = PARAM_TOKEN.matcher(sql);
        while (matcher.find()) {
            params.add(matcher.group(1));
        }
        return params;
    }
}
