package com.example.mcp.tools;

import com.example.mcp.model.PrepareDiagnostics;
import com.example.mcp.model.PrepareResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.h2.tools.RunScript;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class H2PrepareTool implements Tool {
    private static final Pattern LINE_COLUMN_PATTERN = Pattern.compile("line (\\d+), column (\\d+)");

    private final ObjectMapper mapper;

    public H2PrepareTool(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String getName() {
        return "h2.prepare";
    }

    @Override
    public String getDescription() {
        return "Prepare SQL against an H2 database without executing it.";
    }

    @Override
    public ObjectNode getInputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = mapper.createObjectNode();
        properties.putObject("sql").put("type", "string");
        properties.putObject("jdbcUrl").put("type", "string");
        properties.putObject("username").put("type", "string");
        properties.putObject("password").put("type", "string");
        ObjectNode initArray = mapper.createObjectNode();
        initArray.put("type", "array");
        initArray.putObject("items").put("type", "string");
        properties.set("initSqlPaths", initArray);
        schema.set("properties", properties);
        var required = mapper.createArrayNode();
        required.add("sql");
        schema.set("required", required);
        return schema;
    }

    @Override
    public JsonNode call(JsonNode arguments) throws Exception {
        String sql = arguments.path("sql").asText(null);
        if (sql == null) {
            throw new IllegalArgumentException("'sql' is required");
        }
        String jdbcUrl = arguments.path("jdbcUrl").asText("jdbc:h2:mem:compat;MODE=Oracle;DATABASE_TO_UPPER=false;DEFAULT_NULL_ORDERING=HIGH");
        String username = arguments.path("username").asText("sa");
        String password = arguments.path("password").asText("");
        List<Path> initPaths = readPaths(arguments.get("initSqlPaths"));

        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            for (Path path : initPaths) {
                if (Files.exists(path)) {
                    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                        RunScript.execute(connection, reader);
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                // Only prepare, do not execute
            }
            return serialize(new PrepareResult(true, null));
        } catch (SQLException | IOException e) {
            if (e instanceof SQLException sqlException) {
                PrepareDiagnostics diagnostics = toDiagnostics(sqlException);
                return serialize(new PrepareResult(false, diagnostics));
            }
            throw e;
        }
    }

    private PrepareDiagnostics toDiagnostics(SQLException exception) {
        String message = exception.getMessage();
        Matcher matcher = LINE_COLUMN_PATTERN.matcher(message == null ? "" : message);
        Integer line = null;
        Integer column = null;
        if (matcher.find()) {
            line = Integer.parseInt(matcher.group(1));
            column = Integer.parseInt(matcher.group(2));
        }
        return new PrepareDiagnostics(
                message,
                exception.getSQLState(),
                exception.getErrorCode(),
                line,
                column
        );
    }

    private ObjectNode serialize(PrepareResult result) {
        ObjectNode node = mapper.createObjectNode();
        node.put("ok", result.ok());
        if (result.diagnostics() == null) {
            node.putNull("diagnostics");
        } else {
            ObjectNode diag = mapper.createObjectNode();
            diag.put("message", result.diagnostics().message());
            diag.put("sqlState", result.diagnostics().sqlState());
            diag.put("errorCode", result.diagnostics().errorCode());
            if (result.diagnostics().line() != null) {
                diag.put("line", result.diagnostics().line());
            } else {
                diag.putNull("line");
            }
            if (result.diagnostics().column() != null) {
                diag.put("column", result.diagnostics().column());
            } else {
                diag.putNull("column");
            }
            node.set("diagnostics", diag);
        }
        return node;
    }

    private List<Path> readPaths(JsonNode node) {
        List<Path> paths = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return paths;
        }
        for (JsonNode element : node) {
            paths.add(Path.of(element.asText()));
        }
        return paths;
    }
}
