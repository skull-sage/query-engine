package com.brac.query.engine.provision;

import com.brac.query.engine.validation.SqlLexer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the provisioning template ({@code query-engine/provision.sql}) into statements: fills in
 * the {@code {{kind:name}}} placeholders with safely quoted values and splits the text at the
 * semicolons that end a statement. Pure text work, so it is tested without a database.
 *
 * <p>Placeholder kinds: {@code ident} (a name, double-quoted), {@code literal} (a text value, as an
 * {@code E'...'} string), {@code idents} and {@code literals} (a list of either, comma separated).
 * A value cannot break out of its quotes, and a name that is missing from the values is an error
 * rather than an empty string.
 */
public final class ProvisionSql {

    /** Tag of the dollar-quoted blocks in the template; a value containing it could end one early. */
    private static final String BLOCK_TAG = "$provision$";
    private static final String MASK = "'********'";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(ident|literal|idents|literals):([a-z_]+)}}");

    private ProvisionSql() {
    }

    /**
     * @param values      a {@code String} for {@code ident} / {@code literal}, a {@code List<String>} for the list kinds
     * @param maskSecrets show {@code '********'} instead of every literal whose name ends in {@code _password}
     */
    public static String render(String template, Map<String, Object> values, boolean maskSecrets) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String kind = matcher.group(1);
            String name = matcher.group(2);
            Object value = values.get(name);
            if (value == null) {
                throw new IllegalArgumentException("The provisioning template uses {{" + kind + ":" + name + "}} but no value was given for '" + name + "'");
            }
            boolean secret = maskSecrets && name.endsWith("_password");
            String rendered = switch (kind) {
                case "ident" -> ident(scalar(name, value));
                case "literal" -> secret ? MASK : literal(scalar(name, value));
                case "idents" -> String.join(", ", list(name, value).stream().map(ProvisionSql::ident).toList());
                default -> String.join(", ", list(name, value).stream().map(v -> secret ? MASK : literal(v)).toList());
            };
            matcher.appendReplacement(out, Matcher.quoteReplacement(rendered));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** The names the template asks for, so a caller can check it has a value for each. */
    public static Set<String> placeholderNames(String template) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            names.add(matcher.group(2));
        }
        return names;
    }

    /**
     * Splits rendered SQL into statements at the semicolons that end one. A semicolon inside a
     * string, a comment or a {@code $tag$ ... $tag$} block (a {@code DO} body) does not end it.
     * Text with only comments between two semicolons is dropped.
     */
    public static List<String> statements(String sql) {
        SqlLexer.Result lexed = SqlLexer.lex(sql);
        if (lexed.unterminated() != null) {
            throw new IllegalArgumentException("Unterminated " + lexed.unterminated().what() + " starting at "
                    + SqlLexer.location(sql, lexed.unterminated().offset()));
        }
        List<String> out = new ArrayList<>();
        int start = 0;
        boolean hasCode = false;
        for (SqlLexer.Token token : lexed.tokens()) {
            if (token.isSymbol(';')) {
                if (hasCode) {
                    out.add(sql.substring(start, token.offset()).trim());
                }
                start = token.offset() + 1;
                hasCode = false;
            } else {
                hasCode = true;
            }
        }
        if (hasCode) {
            out.add(sql.substring(start).trim());
        }
        return out;
    }

    /** First line of a statement that is not a comment, shortened, for progress messages. */
    public static String label(String statement) {
        for (String line : statement.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
                return trimmed.length() > 70 ? trimmed.substring(0, 67) + "..." : trimmed;
            }
        }
        return statement;
    }

    static String ident(String value) {
        checkSafe("name", value);
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    static String literal(String value) {
        checkSafe("value", value);
        return "E'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    private static void checkSafe(String what, String value) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException("A provisioning " + what + " is empty");
        }
        if (value.indexOf('\0') >= 0 || value.contains(BLOCK_TAG)) {
            throw new IllegalArgumentException("A provisioning " + what + " contains a character sequence that cannot be quoted safely");
        }
    }

    private static String scalar(String name, Object value) {
        if (value instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException("'" + name + "' should be a single value");
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(String name, Object value) {
        if (value instanceof List<?> l && !l.isEmpty() && l.stream().allMatch(String.class::isInstance)) {
            return (List<String>) l;
        }
        throw new IllegalArgumentException("'" + name + "' should be a non-empty list");
    }
}
