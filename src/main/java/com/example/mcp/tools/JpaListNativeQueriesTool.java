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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class JpaListNativeQueriesTool implements Tool {
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

        ArrayNode queriesNode = mapper.createArrayNode();
        Set<String> seen = new HashSet<>();
        for (Path root : roots) {
            if (!Files.exists(root)) {
                continue;
            }
            try (var stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> shouldInclude(root, path, includeGlobs, excludeGlobs))
                        .forEach(path -> {
                            try {
                                String relative = normalizeToUnixSeparators(root.relativize(path).toString());
                                List<QueryItem> items = extractor.extract(path, relative);
                                for (QueryItem item : items) {
                                    String key = item.id() + "@" + relative;
                                    if (seen.add(key)) {
                                        queriesNode.add(serialize(item));
                                    }
                                }
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
            } catch (IOException e) {
                throw new RuntimeException("Failed to scan root: " + root, e);
            }
        }
        ObjectNode result = mapper.createObjectNode();
        result.set("queries", queriesNode);
        return result;
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

    private ObjectNode serialize(QueryItem item) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", item.id());
        node.put("file", item.file());
        node.put("repo", item.repo());
        putNullable(node, "method", item.method());
        node.put("sqlRaw", item.sqlRaw());
        node.put("sqlNormalized", item.sqlNormalized());
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
}
