package com.example.mcp.util;

import com.example.mcp.model.Placeholder;

import java.util.ArrayList;
import java.util.List;

public final class ParamNormalizer {
    private ParamNormalizer() {
    }

    public record Result(String sql, List<Placeholder> placeholders) {
    }

    public static Result normalize(String sql) {
        if (sql == null) {
            return new Result(null, List.of());
        }
        List<Placeholder> placeholders = new ArrayList<>();
        StringBuilder normalized = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';

            if (inLineComment) {
                normalized.append(c);
                if (c == '\n') {
                    inLineComment = false;
                }
                continue;
            }

            if (inBlockComment) {
                normalized.append(c);
                if (c == '*' && next == '/') {
                    normalized.append(next);
                    i++;
                    inBlockComment = false;
                }
                continue;
            }

            if (inSingleQuote) {
                normalized.append(c);
                if (c == '\'' && next == '\'') {
                    normalized.append(next);
                    i++;
                } else if (c == '\'') {
                    inSingleQuote = false;
                }
                continue;
            }

            if (inDoubleQuote) {
                normalized.append(c);
                if (c == '"' && next == '"') {
                    normalized.append(next);
                    i++;
                } else if (c == '"') {
                    inDoubleQuote = false;
                }
                continue;
            }

            if (c == '-' && next == '-') {
                normalized.append(c).append(next);
                i++;
                inLineComment = true;
                continue;
            }
            if (c == '/' && next == '*') {
                normalized.append(c).append(next);
                i++;
                inBlockComment = true;
                continue;
            }

            if (c == '\'') {
                normalized.append(c);
                inSingleQuote = true;
                continue;
            }
            if (c == '"') {
                normalized.append(c);
                inDoubleQuote = true;
                continue;
            }

            if (c == '?' && Character.isDigit(next)) {
                int start = i;
                i++;
                while (i < sql.length() && Character.isDigit(sql.charAt(i))) {
                    i++;
                }
                String token = sql.substring(start, i);
                placeholders.add(new Placeholder("positional", token));
                appendPlaceholder(sql, normalized, start);
                i--;
                continue;
            }

            if (c == ':' && (Character.isLetter(next) || next == '_')) {
                int start = i;
                i++;
                while (i < sql.length()) {
                    char ch = sql.charAt(i);
                    if (!(Character.isLetterOrDigit(ch) || ch == '_' || ch == '$')) {
                        break;
                    }
                    i++;
                }
                String token = sql.substring(start, i);
                placeholders.add(new Placeholder("named", token));
                appendPlaceholder(sql, normalized, start);
                i--;
                continue;
            }

            normalized.append(c);
        }

        return new Result(normalized.toString(), placeholders);
    }

    private static void appendPlaceholder(String sql, StringBuilder normalized, int placeholderStart) {
        if (needsInClauseWrapping(sql, normalized, placeholderStart)) {
            normalized.append('(').append('?').append(')');
        } else {
            normalized.append('?');
        }
    }

    private static boolean needsInClauseWrapping(String sql, StringBuilder normalized, int placeholderStart) {
        int index = normalized.length() - 1;
        while (index >= 0 && Character.isWhitespace(normalized.charAt(index))) {
            index--;
        }
        if (index >= 0 && normalized.charAt(index) == '(') {
            return false;
        }
        index = placeholderStart - 1;
        while (index >= 0 && Character.isWhitespace(sql.charAt(index))) {
            index--;
        }
        if (index >= 0 && sql.charAt(index) == '(') {
            return false;
        }
        if (index < 0 || !equalsIgnoreCase(sql.charAt(index), 'n')) {
            return false;
        }
        index--;
        while (index >= 0 && Character.isWhitespace(sql.charAt(index))) {
            index--;
        }
        if (index < 0 || !equalsIgnoreCase(sql.charAt(index), 'i')) {
            return false;
        }
        int beforeI = index - 1;
        if (beforeI >= 0) {
            char ch = sql.charAt(beforeI);
            if (!Character.isWhitespace(ch) && (Character.isLetterOrDigit(ch) || ch == '_' || ch == '$')) {
                return false;
            }
        }
        int afterIn = index + 1;
        while (afterIn < placeholderStart && Character.isWhitespace(sql.charAt(afterIn))) {
            afterIn++;
        }
        if (afterIn < placeholderStart && sql.charAt(afterIn) == '(') {
            int afterParen = afterIn + 1;
            while (afterParen < placeholderStart && Character.isWhitespace(sql.charAt(afterParen))) {
                afterParen++;
            }
            if (afterParen < placeholderStart && startsWithIgnoreCase(sql, afterParen, "select")) {
                return false;
            }
        }
        return true;
    }

    private static boolean equalsIgnoreCase(char a, char b) {
        return Character.toUpperCase(a) == Character.toUpperCase(b);
    }

    private static boolean startsWithIgnoreCase(String value, int index, String keyword) {
        if (index < 0 || index + keyword.length() > value.length()) {
            return false;
        }
        for (int i = 0; i < keyword.length(); i++) {
            if (!equalsIgnoreCase(value.charAt(index + i), keyword.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
