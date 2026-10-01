// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.core.sql;

import com.aws.aqp.core.errors.InvalidQueryException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Splits a statement into tokens, understanding exactly as much syntax as is needed to find
 * clause boundaries safely: string literals, quoted identifiers and parentheses.
 * <p>
 * This is what the regex-based rewriting lacked. A keyword or comma inside {@code 'a,WHERE b'},
 * inside {@code "my-table"}, or inside a function call is not a clause boundary, and only a
 * tokenizer that tracks quoting and nesting can tell the difference.
 * <p>
 * Comments ({@code --}, {@code /* *}{@code /}, and CQL's {@code //}) are rejected rather
 * than skipped. Clause text is passed through verbatim to the data
 * store, and a line comment inside a clause would swallow whatever the proxy appends after it.
 */
public final class SqlLexer {

    public enum Type {
        WORD, QUOTED_IDENTIFIER, STRING, ION_LITERAL, NUMBER,
        LEFT_PAREN, RIGHT_PAREN, COMMA, DOT, SEMICOLON, OTHER
    }

    public static final class Token {
        public final Type type;
        public final String text;
        /** Offset of the first character in the statement. */
        public final int start;
        /** Offset one past the last character in the statement. */
        public final int end;
        /** Parenthesis nesting depth; 0 means top level. Parentheses themselves are outside. */
        public final int depth;

        Token(Type type, String text, int start, int end, int depth) {
            this.type = type;
            this.text = text;
            this.start = start;
            this.end = end;
            this.depth = depth;
        }

        public boolean isWord(String word) {
            return type == Type.WORD && text.equalsIgnoreCase(word);
        }

        @Override
        public String toString() {
            return type + "(" + text + ")@" + start;
        }
    }

    private SqlLexer() {
    }

    public static List<Token> tokenize(String sql) {
        List<Token> tokens = new ArrayList<>();
        int depth = 0;
        int i = 0;
        int n = sql.length();

        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : '\0';

            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            // All three forms: SQL's -- and /* */, and CQL's //. A // used to get through, and
            // since clause text is passed through verbatim it swallowed what the proxy appends
            // after the WHERE clause, such as ALLOW FILTERING.
            if ((c == '-' && next == '-') || (c == '/' && next == '*') || (c == '/' && next == '/')) {
                throw new InvalidQueryException(String.format(
                        "SQL comments are not supported (found at position %d).", i));
            }

            int start = i;
            // CQL dollar-quoted string: $$ ... $$, no escapes, may contain quotes. Read as one
            // literal so the quotes inside do not start a string the data store never sees.
            if (c == '$' && next == '$') {
                int close = sql.indexOf("$$", i + 2);
                if (close < 0) {
                    throw new InvalidQueryException(String.format(
                            "Unterminated string literal starting at position %d.", start));
                }
                i = close + 2;
                tokens.add(new Token(Type.STRING, sql.substring(start, i), start, i, depth));
                continue;
            }
            switch (c) {
                case '\'':
                    i = endOfQuoted(sql, i, '\'', "string literal");
                    tokens.add(new Token(Type.STRING, sql.substring(start, i), start, i, depth));
                    break;
                case '"':
                    i = endOfQuoted(sql, i, '"', "quoted identifier");
                    tokens.add(new Token(Type.QUOTED_IDENTIFIER, sql.substring(start, i), start, i, depth));
                    break;
                case '`':
                    int close = sql.indexOf('`', i + 1);
                    if (close < 0) {
                        throw new InvalidQueryException(String.format(
                                "Unterminated Ion literal starting at position %d.", start));
                    }
                    i = close + 1;
                    tokens.add(new Token(Type.ION_LITERAL, sql.substring(start, i), start, i, depth));
                    break;
                case '(':
                    tokens.add(new Token(Type.LEFT_PAREN, "(", start, ++i, depth));
                    depth++;
                    break;
                case ')':
                    depth--;
                    if (depth < 0) {
                        throw new InvalidQueryException(String.format(
                                "Unbalanced ')' at position %d.", start));
                    }
                    tokens.add(new Token(Type.RIGHT_PAREN, ")", start, ++i, depth));
                    break;
                case ',':
                    tokens.add(new Token(Type.COMMA, ",", start, ++i, depth));
                    break;
                case ';':
                    tokens.add(new Token(Type.SEMICOLON, ";", start, ++i, depth));
                    break;
                case '.':
                    if (Character.isDigit(next)) {
                        i = endOfNumber(sql, i);
                        tokens.add(new Token(Type.NUMBER, sql.substring(start, i), start, i, depth));
                    } else {
                        tokens.add(new Token(Type.DOT, ".", start, ++i, depth));
                    }
                    break;
                default:
                    if (isIdentifierStart(c)) {
                        while (i < n && isIdentifierPart(sql.charAt(i))) {
                            i++;
                        }
                        tokens.add(new Token(Type.WORD, sql.substring(start, i), start, i, depth));
                    } else if (Character.isDigit(c)) {
                        i = endOfNumber(sql, i);
                        tokens.add(new Token(Type.NUMBER, sql.substring(start, i), start, i, depth));
                    } else {
                        tokens.add(new Token(Type.OTHER, String.valueOf(c), start, ++i, depth));
                    }
            }
        }

        if (depth != 0) {
            throw new InvalidQueryException("Unbalanced parentheses: missing ')'.");
        }
        return Collections.unmodifiableList(tokens);
    }

    /**
     * Returns the offset just past the closing quote. Both CQL and PartiQL escape a quote inside
     * a quoted run by doubling it ({@code 'O''Brien'}, {@code "a""b"}).
     */
    private static int endOfQuoted(String sql, int open, char quote, String what) {
        int i = open + 1;
        while (i < sql.length()) {
            if (sql.charAt(i) == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        throw new InvalidQueryException(String.format(
                "Unterminated %s starting at position %d.", what, open));
    }

    private static int endOfNumber(String sql, int i) {
        int n = sql.length();
        while (i < n && (Character.isDigit(sql.charAt(i)) || sql.charAt(i) == '.')) {
            i++;
        }
        if (i < n && (sql.charAt(i) == 'e' || sql.charAt(i) == 'E')) {
            int j = i + 1;
            if (j < n && (sql.charAt(j) == '+' || sql.charAt(j) == '-')) {
                j++;
            }
            if (j < n && Character.isDigit(sql.charAt(j))) {
                i = j;
                while (i < n && Character.isDigit(sql.charAt(i))) {
                    i++;
                }
            }
        }
        return i;
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
