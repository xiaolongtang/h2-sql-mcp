package com.example.mcp.tools;

import com.example.mcp.util.JarLocationResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

public class PrepareReportTool implements Tool {

    private static final String REPORT_FILE_NAME = "prepare-report.csv";

    private final ObjectMapper mapper;

    public PrepareReportTool(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String getName() {
        return "h2.prepare.report";
    }

    @Override
    public String getDescription() {
        return "Append SQL and LLM diagnostic feedback to the shared preparation report.";
    }

    @Override
    public ObjectNode getInputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = mapper.createObjectNode();
        properties.putObject("sql").put("type", "string");
        properties.putObject("diagnosis").put("type", "string");
        schema.set("properties", properties);
        var required = mapper.createArrayNode();
        required.add("sql");
        required.add("diagnosis");
        schema.set("required", required);
        return schema;
    }

    @Override
    public JsonNode call(JsonNode arguments) throws IOException {
        String sql = arguments.path("sql").asText(null);
        String diagnosis = arguments.path("diagnosis").asText(null);
        if (sql == null || diagnosis == null) {
            throw new IllegalArgumentException("'sql' and 'diagnosis' are required");
        }

        Path reportPath = resolveReportPath();
        boolean newFile = Files.notExists(reportPath);
        if (newFile) {
            Path parent = reportPath.getParent();
            if (parent != null && Files.notExists(parent)) {
                Files.createDirectories(parent);
            }
        }

        try (var writer = Files.newBufferedWriter(reportPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            if (newFile) {
                writer.write("timestamp,sql,diagnosis\n");
            }
            writer.write(formatCsvValue(Instant.now().toString()));
            writer.write(',');
            writer.write(formatCsvValue(sql));
            writer.write(',');
            writer.write(formatCsvValue(diagnosis));
            writer.write('\n');
        }

        ObjectNode result = mapper.createObjectNode();
        result.put("reportPath", reportPath.toAbsolutePath().toString());
        result.put("appended", true);
        return result;
    }

    private Path resolveReportPath() {
        Path jarDirectory = JarLocationResolver.resolveJarDirectory(PrepareReportTool.class);
        if (jarDirectory != null) {
            return jarDirectory.resolve(REPORT_FILE_NAME);
        }
        return Path.of(REPORT_FILE_NAME);
    }

    private String formatCsvValue(String value) {
        String escaped = value.replace("\"", "\"\"");
        return '"' + escaped + '"';
    }
}
