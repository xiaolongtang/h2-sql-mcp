package com.example.mcp.util;

import com.example.mcp.model.QueryItem;
import com.example.mcp.model.RuleHit;
import com.example.mcp.util.ParamNormalizer;
import com.example.mcp.util.ParamNormalizer.Result;
import org.apache.commons.text.StringEscapeUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
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
    private static final Pattern STRING_PATTERN = Pattern.compile("\"(\\\\.|[^\\\\\"])*\"");
    private static final Pattern METHOD_PATTERN = Pattern.compile("(?:public|protected|private|default)?\\s*(?:static\\s+)?[\\w<>,\\s\\[\\]]+\\s+(\\w+)\\s*\\(");

    private final RuleEngine ruleEngine;

    public QueryExtractor(RuleEngine ruleEngine) {
        this.ruleEngine = ruleEngine;
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
            if (!normalizedBody.contains("nativequery=true")) {
                searchStart = matcher.end();
                continue;
            }
            Matcher stringMatcher = STRING_PATTERN.matcher(body);
            if (!stringMatcher.find()) {
                searchStart = matcher.end();
                continue;
            }
            String rawLiteral = stringMatcher.group();
            String sqlRaw = StringEscapeUtils.unescapeJava(rawLiteral.substring(1, rawLiteral.length() - 1));

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
        CharsetDecoder decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(file));
        return decoder.decode(buffer).toString();
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
