package com.example.mcp.tools;

import com.example.mcp.model.Placeholder;
import com.example.mcp.model.QueryItem;
import com.example.mcp.model.RuleHit;
import com.example.mcp.util.QueryExtractor;
import com.example.mcp.util.RuleEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

public class JpaListNativeQueriesTool implements Tool {
    private static final int MAX_THREADS = 15;
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    private final ObjectMapper mapper;
    private final QueryExtractor extractor;

    public JpaListNativeQueriesTool(ObjectMapper mapper) {
        this.mapper = mapper;
        this.extractor = new QueryExtractor(new RuleEngine());
    }

    @Override
    public String getName() {
        return "jpa.list_native_queries";
    }

    @Override
    public String getDescription() {
        return "Scan JPA repository sources and list native SQL queries.";
    }

    @Override
    public ObjectNode getInputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = mapper.createObjectNode();
        properties.set("rootDirs", arrayOfStrings());
        properties.set("includeGlobs", arrayOfStrings());
        properties.set("excludeGlobs", arrayOfStrings());
        properties.set("limit", paginationField(
                "Maximum number of results to return (default " + DEFAULT_LIMIT + ", max " + MAX_LIMIT + ").",
                DEFAULT_LIMIT, 1));
        properties.set("cursor", paginationField(
                "Zero-based index from which to continue the result set.", null, 0));
        properties.set("offset", paginationField(
                "Alias for cursor.", null, 0));
        properties.set("maxSqlLength", paginationField(
                "Optional maximum length for SQL text fields; longer queries will be truncated.", null, 1));
        schema.set("properties", properties);
        ArrayNode required = mapper.createArrayNode();
        required.add("rootDirs");
        schema.set("required", required);
        return schema;
    }

    private ObjectNode arrayOfStrings() {
        ObjectNode items = mapper.createObjectNode();
        items.put("type", "string");
        ObjectNode array = mapper.createObjectNode();
        array.put("type", "array");
        array.set("items", items);
        return array;
    }

    @Override
    public JsonNode call(JsonNode arguments) throws Exception {
        List<Path> roots = readPathArray(arguments.get("rootDirs"));
        List<String> includeGlobs = readStringArray(arguments.get("includeGlobs"));
        List<String> excludeGlobs = readStringArray(arguments.get("excludeGlobs"));

        int limit = determineLimit(arguments);
        int cursor = determineCursor(arguments);
        Integer maxSqlLength = determineMaxSqlLength(arguments);

        Map<String, QueryItem> deduped = new LinkedHashMap<>();
        ArrayNode errorsNode = mapper.createArrayNode();
        for (Path root : roots) {
            if (!Files.exists(root)) {
                continue;
            }
            List<Path> files;
            try (var stream = Files.walk(root)) {
                files = stream.filter(Files::isRegularFile)
                        .filter(path -> shouldInclude(root, path, includeGlobs, excludeGlobs))
                        .collect(Collectors.toList());
            } catch (IOException e) {
                throw new RuntimeException("Failed to scan root: " + root, e);
            }
            if (files.isEmpty()) {
                continue;
            }
            ExecutorService executor = Executors.newFixedThreadPool(Math.min(MAX_THREADS, files.size()));
            List<Future<FileScanResult>> futures = new ArrayList<>();
            try {
                for (Path path : files) {
                    futures.add(executor.submit(() -> scanFile(root, path)));
                }
                for (Future<FileScanResult> future : futures) {
                    FileScanResult result;
                    try {
                        result = future.get();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Scanning interrupted", e);
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof RuntimeException runtime) {
                            throw runtime;
                        }
                        throw new RuntimeException("Failed to scan file under root: " + root, cause);
                    }
                    for (QueryItem item : result.items()) {
                        String key = item.id() + "@" + item.file();
                        deduped.putIfAbsent(key, item);
                    }
                    for (String error : result.errors()) {
                        errorsNode.add(error);
                    }
                }
            } finally {
                executor.shutdownNow();
            }
        }
        List<QueryItem> allItems = new ArrayList<>(deduped.values());
        allItems.sort(Comparator.comparing(QueryItem::file, Comparator.nullsLast(String::compareTo))
                .thenComparing(QueryItem::id, Comparator.nullsLast(String::compareTo)));

        int totalCount = allItems.size();
        int startIndex = Math.max(Math.min(cursor, totalCount), 0);
        int effectiveLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        int endIndex = Math.min(startIndex + effectiveLimit, totalCount);

        List<QueryItem> page = allItems.subList(startIndex, endIndex);
        ArrayNode queriesNode = mapper.createArrayNode();
        for (QueryItem item : page) {
            queriesNode.add(serialize(item, maxSqlLength));
        }

        ObjectNode result = mapper.createObjectNode();
        result.set("queries", queriesNode);
        result.put("totalCount", totalCount);
        result.put("limit", effectiveLimit);
        result.put("cursor", startIndex);
        if (endIndex < totalCount) {
            result.put("nextCursor", endIndex);
        }
        if (!errorsNode.isEmpty()) {
            result.set("errors", errorsNode);
        }
        return result;
    }

    private int determineLimit(JsonNode arguments) {
        JsonNode limitNode = arguments.get("limit");
        int requested = limitNode != null && limitNode.isNumber()
                ? limitNode.asInt(DEFAULT_LIMIT)
                : DEFAULT_LIMIT;
        if (requested < 1) {
            requested = 1;
        }
        return Math.min(requested, MAX_LIMIT);
    }

    private int determineCursor(JsonNode arguments) {
        JsonNode cursorNode = arguments.has("cursor") ? arguments.get("cursor") : arguments.get("offset");
        if (cursorNode == null || !cursorNode.isNumber()) {
            return 0;
        }
        int cursor = cursorNode.asInt(0);
        return Math.max(cursor, 0);
    }

    private Integer determineMaxSqlLength(JsonNode arguments) {
        JsonNode node = arguments.get("maxSqlLength");
        if (node == null || !node.isNumber()) {
            return null;
        }
        int value = node.asInt();
        if (value < 1) {
            return null;
        }
        return value;
    }

    private boolean shouldInclude(Path root, Path file, List<String> includes, List<String> excludes) {
        Path relative = root.relativize(file);
        String normalized = normalizeToUnixSeparators(relative.toString());
        if (!includes.isEmpty() && includes.stream().noneMatch(glob -> matchesGlob(normalized, glob))) {
            return false;
        }
        if (!excludes.isEmpty() && excludes.stream().anyMatch(glob -> matchesGlob(normalized, glob))) {
            return false;
        }
        return true;
    }

    private boolean matchesGlob(String path, String glob) {
        String globForSystem = normalizeToSystemSeparators(glob);
        Path pathForSystem = Path.of(normalizeToSystemSeparators(path));
        return FileSystems.getDefault().getPathMatcher("glob:" + globForSystem)
                .matches(pathForSystem);
    }

    private List<Path> readPathArray(JsonNode node) {
        List<Path> paths = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return paths;
        }
        for (JsonNode element : node) {
            paths.add(Path.of(normalizeToSystemSeparators(element.asText())));
        }
        return paths;
    }

    private List<String> readStringArray(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return values;
        }
        for (JsonNode element : node) {
            values.add(element.asText());
        }
        return values;
    }

    private FileScanResult scanFile(Path root, Path path) {
        String relative = normalizeToUnixSeparators(root.relativize(path).toString());
        try {
            List<QueryItem> items = extractor.extract(path, relative);
            return new FileScanResult(items, List.of());
        } catch (IOException e) {
            String message = String.format(
                    "Failed to read '%s': %s",
                    relative,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()
            );
            return new FileScanResult(List.of(), List.of(message));
        }
    }

    private ObjectNode serialize(QueryItem item, Integer maxSqlLength) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", item.id());
        node.put("file", item.file());
        node.put("repo", item.repo());
        putNullable(node, "method", item.method());
        node.put("sqlRaw", maybeTruncate(item.sqlRaw(), maxSqlLength));
        node.put("sqlNormalized", maybeTruncate(item.sqlNormalized(), maxSqlLength));
        ArrayNode placeholders = mapper.createArrayNode();
        for (Placeholder placeholder : item.placeholders()) {
            ObjectNode placeholderNode = mapper.createObjectNode();
            placeholderNode.put("kind", placeholder.kind());
            placeholderNode.put("token", placeholder.token());
            placeholders.add(placeholderNode);
        }
        node.set("placeholders", placeholders);
        ArrayNode hits = mapper.createArrayNode();
        for (RuleHit hit : item.ruleHits()) {
            ObjectNode hitNode = mapper.createObjectNode();
            hitNode.put("rule", hit.rule());
            hitNode.put("snippet", hit.snippet());
            hits.add(hitNode);
        }
        node.set("ruleHits", hits);
        return node;
    }

    private String maybeTruncate(String value, Integer maxSqlLength) {
        if (value == null || maxSqlLength == null) {
            return value;
        }
        if (value.length() <= maxSqlLength) {
            return value;
        }
        if (maxSqlLength == 1) {
            return "…";
        }
        if (maxSqlLength == 2) {
            return value.substring(0, 1) + "…";
        }
        return value.substring(0, maxSqlLength - 1) + "…";
    }

    private void putNullable(ObjectNode node, String key, String value) {
        if (value == null) {
            node.putNull(key);
        } else {
            node.put(key, value);
        }
    }

    private String normalizeToUnixSeparators(String path) {
        return path.replace('\\', '/');
    }

    private String normalizeToSystemSeparators(String path) {
        if (path == null) {
            return null;
        }
        String separator = FileSystems.getDefault().getSeparator();
        if ("\\".equals(separator)) {
            return path.replace('/', '\\');
        }
        return path.replace('\\', '/');
    }

    private ObjectNode paginationField(String description, Integer defaultValue, int minimum) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "integer");
        node.put("minimum", minimum);
        node.put("description", description);
        if (defaultValue != null) {
            node.put("default", defaultValue);
        }
        return node;
    }

    private record FileScanResult(List<QueryItem> items, List<String> errors) {
    }
}
