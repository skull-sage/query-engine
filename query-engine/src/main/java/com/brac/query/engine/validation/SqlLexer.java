package com.brac.query.engine.validation;

import java.util.ArrayList;
import java.util.List;

/**
 * A small hand-written tokenizer for PostgreSQL text. It is not a parser: it only splits the text
 * into pieces so {@link SqlGuard} can look at the words that really are code, ignoring the ones
 * inside string literals, quoted names and comments.
 *
 * <p>What it understands (assuming {@code standard_conforming_strings = on}, the Postgres
 * default, which the projection connections also pin):
 * <ul>
 *   <li>{@code --} comments and {@code /* *}{@code /} comments, which may nest;</li>
 *   <li>{@code 'strings'} with {@code ''} inside, {@code E'strings'} where a backslash escapes
 *       the next character, and {@code $tag$ ... $tag$} dollar quoting;</li>
 *   <li>{@code "quoted names"} with {@code ""} inside, and {@code U&"..."} names, which are only
 *       flagged because their escapes would hide the real name;</li>
 *   <li>{@code :param} placeholders and {@code ::} casts;</li>
 *   <li>words, numbers and single-character symbols.</li>
 * </ul>
 * Unquoted words are lower-cased the way Postgres folds names (ASCII only). Everything outside
 * strings and comments becomes a token, so nothing can hide between tokens.
 *
 * <p>The same rules are ported to JavaScript in {@code query-builder-ui/src/sql/sqlLexer.js}.
 */
public final class SqlLexer {

    public enum Kind {
        /** An unquoted word: keyword or name, lower-cased. */
        WORD,
        /** A {@code "quoted name"}; the text is the name as Postgres sees it. */
        QUOTED_IDENT,
        /** A {@code U&"..."} name; not decoded. */
        UNICODE_IDENT,
        /** Any string literal; the content is dropped. */
        STRING,
        /** A {@code :name} placeholder; the text is the name. */
        PARAM,
        /** The {@code ::} cast operator. */
        CAST,
        NUMBER,
        /** One punctuation character, e.g. {@code ;} {@code (} {@code .} */
        SYMBOL
    }

    public record Token(Kind kind, String text, int offset) {
        public boolean isWord(String word) {
            return kind == Kind.WORD && text.equals(word);
        }

        public boolean isSymbol(char symbol) {
            return kind == Kind.SYMBOL && text.length() == 1 && text.charAt(0) == symbol;
        }

        /** A word or a quoted name: something that can name a table, schema or function. */
        public boolean isName() {
            return kind == Kind.WORD || kind == Kind.QUOTED_IDENT;
        }
    }

    /** Something opened but never closed: {@code what} is "string", "quoted name", ... */
    public record Unterminated(String what, int offset) {
    }

    /** The tokens found, and, when the text ended inside a literal or comment, where that began. */
    public record Result(List<Token> tokens, Unterminated unterminated) {
    }

    private SqlLexer() {
    }

    public static Result lex(String sql) {
        List<Token> tokens = new ArrayList<>();
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : '\0';

            if (isSpace(c)) {
                i++;
            } else if (c == '-' && next == '-') {
                i += 2;
                while (i < n && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
                    i++;
                }
            } else if (c == '/' && next == '*') {
                int start = i;
                int depth = 1;
                i += 2;
                while (i < n && depth > 0) {
                    if (sql.startsWith("/*", i)) {
                        depth++;
                        i += 2;
                    } else if (sql.startsWith("*/", i)) {
                        depth--;
                        i += 2;
                    } else {
                        i++;
                    }
                }
                if (depth > 0) {
                    return new Result(tokens, new Unterminated("comment", start));
                }
            } else if (c == '\'') {
                int end = endOfQuoted(sql, i, '\'', false);
                if (end < 0) {
                    return new Result(tokens, new Unterminated("string", i));
                }
                tokens.add(new Token(Kind.STRING, "", i));
                i = end;
            } else if (c == '"') {
                int end = endOfQuoted(sql, i, '"', false);
                if (end < 0) {
                    return new Result(tokens, new Unterminated("quoted name", i));
                }
                tokens.add(new Token(Kind.QUOTED_IDENT, sql.substring(i + 1, end - 1).replace("\"\"", "\""), i));
                i = end;
            } else if (c == '$') {
                int tagEnd = dollarTagEnd(sql, i);
                if (tagEnd < 0) {
                    tokens.add(new Token(Kind.SYMBOL, "$", i));
                    i++;
                } else {
                    String tag = sql.substring(i, tagEnd + 1);
                    int close = sql.indexOf(tag, tagEnd + 1);
                    if (close < 0) {
                        return new Result(tokens, new Unterminated("dollar-quoted string", i));
                    }
                    tokens.add(new Token(Kind.STRING, "", i));
                    i = close + tag.length();
                }
            } else if (c == ':') {
                if (next == ':') {
                    tokens.add(new Token(Kind.CAST, "::", i));
                    i += 2;
                } else if (isParamStart(next)) {
                    int j = i + 2;
                    while (j < n && isParamPart(sql.charAt(j))) {
                        j++;
                    }
                    tokens.add(new Token(Kind.PARAM, sql.substring(i + 1, j), i));
                    i = j;
                } else {
                    tokens.add(new Token(Kind.SYMBOL, ":", i));
                    i++;
                }
            } else if (isDigit(c) || (c == '.' && isDigit(next))) {
                int j = i;
                while (j < n && isDigit(sql.charAt(j))) {
                    j++;
                }
                if (j < n && sql.charAt(j) == '.' && !(j + 1 < n && sql.charAt(j + 1) == '.')) {
                    j++;
                    while (j < n && isDigit(sql.charAt(j))) {
                        j++;
                    }
                }
                if (j < n && (sql.charAt(j) == 'e' || sql.charAt(j) == 'E')) {
                    int k = j + 1;
                    if (k < n && (sql.charAt(k) == '+' || sql.charAt(k) == '-')) {
                        k++;
                    }
                    if (k < n && isDigit(sql.charAt(k))) {
                        while (k < n && isDigit(sql.charAt(k))) {
                            k++;
                        }
                        j = k;
                    }
                }
                tokens.add(new Token(Kind.NUMBER, sql.substring(i, j), i));
                i = j;
            } else if (isIdentStart(c)) {
                int j = i + 1;
                while (j < n && isIdentPart(sql.charAt(j))) {
                    j++;
                }
                String lower = asciiLower(sql.substring(i, j));
                char after = j < n ? sql.charAt(j) : '\0';
                char afterNext = j + 1 < n ? sql.charAt(j + 1) : '\0';

                if (after == '\'' && (lower.equals("e") || lower.equals("b") || lower.equals("x") || lower.equals("n"))) {
                    // E'..' lets a backslash escape the quote; B'..', X'..', N'..' are plain strings.
                    int end = endOfQuoted(sql, j, '\'', lower.equals("e"));
                    if (end < 0) {
                        return new Result(tokens, new Unterminated("string", i));
                    }
                    tokens.add(new Token(Kind.STRING, "", i));
                    i = end;
                } else if (lower.equals("u") && after == '&' && afterNext == '\'') {
                    int end = endOfQuoted(sql, j + 1, '\'', false);
                    if (end < 0) {
                        return new Result(tokens, new Unterminated("string", i));
                    }
                    tokens.add(new Token(Kind.STRING, "", i));
                    i = end;
                } else if (lower.equals("u") && after == '&' && afterNext == '"') {
                    int end = endOfQuoted(sql, j + 1, '"', false);
                    if (end < 0) {
                        return new Result(tokens, new Unterminated("quoted name", i));
                    }
                    tokens.add(new Token(Kind.UNICODE_IDENT, sql.substring(j + 2, end - 1), i));
                    i = end;
                } else {
                    tokens.add(new Token(Kind.WORD, lower, i));
                    i = j;
                }
            } else {
                tokens.add(new Token(Kind.SYMBOL, String.valueOf(c), i));
                i++;
            }
        }
        return new Result(tokens, null);
    }

    /** "line 2, column 7" for a character offset in {@code sql}. */
    public static String location(String sql, int offset) {
        int line = 1;
        int column = 1;
        for (int k = 0; k < offset && k < sql.length(); k++) {
            char ch = sql.charAt(k);
            if (ch == '\n') {
                line++;
                column = 1;
            } else if (ch != '\r') {
                column++;
            }
        }
        return "line " + line + ", column " + column;
    }

    /** Lower-cases ASCII letters only, which is how Postgres folds unquoted names. */
    public static String asciiLower(String text) {
        StringBuilder out = null;
        for (int k = 0; k < text.length(); k++) {
            char ch = text.charAt(k);
            if (ch >= 'A' && ch <= 'Z') {
                if (out == null) {
                    out = new StringBuilder(text);
                }
                out.setCharAt(k, (char) (ch + 32));
            }
        }
        return out == null ? text : out.toString();
    }

    /** Index just past the closing quote of the literal opened at {@code open}, or -1 if it never closes. */
    private static int endOfQuoted(String sql, int open, char quote, boolean backslashEscapes) {
        int n = sql.length();
        int j = open + 1;
        while (j < n) {
            char d = sql.charAt(j);
            if (backslashEscapes && d == '\\') {
                j += 2;
            } else if (d == quote) {
                if (j + 1 < n && sql.charAt(j + 1) == quote) {
                    j += 2;
                } else {
                    return j + 1;
                }
            } else {
                j++;
            }
        }
        return -1;
    }

    /** Index of the closing {@code $} of a dollar-quote opening tag ({@code $$} or {@code $name$}) at {@code at}, or -1. */
    private static int dollarTagEnd(String sql, int at) {
        int n = sql.length();
        int j = at + 1;
        if (j < n && sql.charAt(j) == '$') {
            return j;
        }
        if (j < n && isDollarTagStart(sql.charAt(j))) {
            j++;
            while (j < n && (isDollarTagStart(sql.charAt(j)) || isDigit(sql.charAt(j)))) {
                j++;
            }
            if (j < n && sql.charAt(j) == '$') {
                return j;
            }
        }
        return -1;
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\u000B';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isDollarTagStart(char c) {
        return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c >= 0x80;
    }

    private static boolean isIdentStart(char c) {
        return isDollarTagStart(c);
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || isDigit(c) || c == '$';
    }

    /** Placeholders stay ASCII, exactly as the earlier {@code :([a-zA-Z_][a-zA-Z0-9_]*)} scan had it. */
    private static boolean isParamStart(char c) {
        return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isParamPart(char c) {
        return isParamStart(c) || isDigit(c);
    }
}
