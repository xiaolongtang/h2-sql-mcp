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
                normalized.append('?');
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
                normalized.append('?');
                i--;
                continue;
            }

            normalized.append(c);
        }

        return new Result(normalized.toString(), placeholders);
    }
}
