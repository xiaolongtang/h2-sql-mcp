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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class JpaListNativeQueriesTool implements Tool {
    private static final int MAX_THREADS = 15;
    private static final List<String> DEFAULT_INCLUDE_GLOBS = List.of("**/*.java", "**/*.kt");
    private static final Set<String> DEFAULT_SKIPPED_DIRECTORY_NAMES = Set.of(
            ".git",
            ".svn",
            ".hg",
            ".idea",
            ".vscode",
            ".gradle",
            ".mvn",
            "node_modules",
            "target",
            "build",
            "out",
            "bin",
            "dist",
            "tmp",
            "logs",
            "__pycache__"
    );

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
        boolean usingDefaultIncludes = includeGlobs.isEmpty() || includeGlobs.equals(DEFAULT_INCLUDE_GLOBS);
        if (usingDefaultIncludes) {
            includeGlobs = DEFAULT_INCLUDE_GLOBS;
        }
        List<String> excludeGlobs = readStringArray(arguments.get("excludeGlobs"));

        ArrayNode queriesNode = mapper.createArrayNode();
        ArrayNode errorsNode = mapper.createArrayNode();
        Set<String> seen = new HashSet<>();
        for (Path root : roots) {
            if (!Files.exists(root)) {
                continue;
            }
            List<Path> files;
            try {
                files = collectCandidateFiles(root, includeGlobs, excludeGlobs, usingDefaultIncludes);
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
                        if (seen.add(key)) {
                            queriesNode.add(serialize(item));
                        }
                    }
                    for (String error : result.errors()) {
                        errorsNode.add(error);
                    }
                }
            } finally {
                executor.shutdownNow();
            }
        }
        ObjectNode result = mapper.createObjectNode();
        result.set("queries", queriesNode);
        if (!errorsNode.isEmpty()) {
            result.set("errors", errorsNode);
        }
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
        String globForSystem = normalizeGlobForSystem(glob);
        Path pathForSystem = Path.of(normalizeToSystemSeparators(path));
        return FileSystems.getDefault().getPathMatcher("glob:" + globForSystem)
                .matches(pathForSystem);
    }

    private List<Path> collectCandidateFiles(Path root, List<String> includes, List<String> excludes, boolean usingDefaultIncludes) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (shouldSkipDirectory(root, dir, usingDefaultIncludes)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && shouldInclude(root, file, includes, excludes)) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    private boolean shouldSkipDirectory(Path root, Path dir, boolean usingDefaultIncludes) {
        if (!usingDefaultIncludes || dir.equals(root)) {
            return false;
        }
        Path name = dir.getFileName();
        if (name == null) {
            return false;
        }
        String directoryName = name.toString();
        String normalized = directoryName.toLowerCase(Locale.ROOT);
        return DEFAULT_SKIPPED_DIRECTORY_NAMES.contains(normalized);
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

    private String normalizeGlobForSystem(String glob) {
        if (glob == null) {
            return null;
        }
        String normalized = glob.replace('\\', '/');
        String separator = FileSystems.getDefault().getSeparator();
        if ("\\".equals(separator)) {
            return normalized.replace("/", "\\\\");
        }
        return normalized;
    }

    private record FileScanResult(List<QueryItem> items, List<String> errors) {
    }
}
