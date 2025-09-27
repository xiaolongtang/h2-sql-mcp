package com.example.mcp.util;

import com.example.mcp.model.QueryItem;
import com.example.mcp.model.RuleHit;
import com.example.mcp.util.ParamNormalizer;
import com.example.mcp.util.ParamNormalizer.Result;
import org.apache.commons.text.StringEscapeUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class QueryExtractor {
    private static final Pattern CLASS_PATTERN = Pattern.compile("(public\\s+)?(class|interface)\\s+(\\w+)");
    private static final Pattern QUERY_PATTERN = Pattern.compile("@Query\\s*\\((.*?)\\)", Pattern.DOTALL);
    private static final Pattern STRING_PATTERN = Pattern.compile(
            "\"\"\"([\\s\\S]*?)\"\"\"|\"(\\\\.|[^\\\\\"])*\""
    );
    private static final Pattern METHOD_PATTERN = Pattern.compile("(?:public|protected|private|default)?\\s*(?:static\\s+)?[\\w<>,\\s\\[\\]]+\\s+(\\w+)\\s*\\(");
    private static final Pattern NATIVE_QUERY_FLAG_PATTERN = Pattern.compile("nativequery\\s*=\\s*true");

    private final RuleEngine ruleEngine;

    public QueryExtractor(RuleEngine ruleEngine) {
        this.ruleEngine = ruleEngine;
    }

    private String decodeLiteral(String rawLiteral) {
        if (rawLiteral.startsWith("\"\"\"")) {
            return decodeTextBlock(rawLiteral);
        }
        return StringEscapeUtils.unescapeJava(rawLiteral.substring(1, rawLiteral.length() - 1));
    }

    private String decodeTextBlock(String rawLiteral) {
        String content = rawLiteral.substring(3, rawLiteral.length() - 3);
        content = content.replace("\r\n", "\n");
        if (content.startsWith("\n")) {
            content = content.substring(1);
        }
        String[] lines = content.split("\n", -1);
        int indent = findCommonIndent(lines);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (indent > 0) {
                line = removeIndent(line, indent);
            }
            builder.append(line);
            if (i < lines.length - 1) {
                builder.append('\n');
            }
        }
        String result = builder.toString();
        return StringEscapeUtils.unescapeJava(result);
    }

    private int findCommonIndent(String[] lines) {
        int indent = Integer.MAX_VALUE;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            int count = 0;
            while (count < line.length()) {
                char ch = line.charAt(count);
                if (ch == ' ' || ch == '\t') {
                    count++;
                } else {
                    break;
                }
            }
            indent = Math.min(indent, count);
        }
        return indent == Integer.MAX_VALUE ? 0 : indent;
    }

    private String removeIndent(String line, int indent) {
        int remove = Math.min(indent, line.length());
        int index = 0;
        while (index < remove) {
            char ch = line.charAt(index);
            if (ch == ' ' || ch == '\t') {
                index++;
            } else {
                break;
            }
        }
        return line.substring(index);
    }

    public List<QueryItem> extract(Path file, String relativePath) throws IOException {
        String content = readContent(file);
        String repoName = detectRepoName(content, file.getFileName().toString());
        List<QueryItem> result = new ArrayList<>();
        Matcher matcher = QUERY_PATTERN.matcher(content);
        int searchStart = 0;
        while (matcher.find(searchStart)) {
            String body = matcher.group(1);
            if (body == null) {
                searchStart = matcher.end();
                continue;
            }
            String normalizedBody = body.toLowerCase(Locale.ROOT);
            if (!NATIVE_QUERY_FLAG_PATTERN.matcher(normalizedBody).find()) {
                searchStart = matcher.end();
                continue;
            }
            Matcher stringMatcher = STRING_PATTERN.matcher(body);
            if (!stringMatcher.find()) {
                searchStart = matcher.end();
                continue;
            }
            String rawLiteral = stringMatcher.group();
            String sqlRaw = decodeLiteral(rawLiteral);

            String methodName = detectMethodName(content, matcher.end());
            ParamNormalizer.Result normalized = ParamNormalizer.normalize(sqlRaw);
            List<RuleHit> hits = ruleEngine.findHits(sqlRaw);
            String id = repoName + (methodName == null ? "" : "#" + methodName);
            result.add(new QueryItem(
                    id,
                    relativePath,
                    repoName,
                    methodName,
                    sqlRaw,
                    normalized.sql(),
                    normalized.placeholders(),
                    hits
            ));
            searchStart = matcher.end();
        }
        return result;
    }

    private String readContent(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        CharsetDecoder decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    private String detectRepoName(String content, String fallback) {
        Matcher matcher = CLASS_PATTERN.matcher(content);
        if (matcher.find()) {
            return matcher.group(3);
        }
        int dot = fallback.indexOf('.');
        return dot > 0 ? fallback.substring(0, dot) : fallback;
    }

    private String detectMethodName(String content, int fromIndex) {
        Matcher matcher = METHOD_PATTERN.matcher(content.substring(fromIndex));
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
