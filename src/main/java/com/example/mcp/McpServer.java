package com.example.mcp;

import com.example.mcp.tools.H2PrepareTool;
import com.example.mcp.tools.JpaListNativeQueriesTool;
import com.example.mcp.tools.PrepareReportTool;
import com.example.mcp.tools.SqlRewriteTool;
import com.example.mcp.tools.Tool;
import com.example.mcp.tools.ToolRegistry;
import com.example.mcp.util.JarLocationResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MCP server bootstrap that exposes SQL migration utilities over the Anthropics MCP Java SDK.
 */
public class McpServer {

    private static final String LOG_FILE_PATH = configureSimpleLogger();
    private static final Logger LOGGER = LoggerFactory.getLogger(McpServer.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final McpJsonMapper mcpJsonMapper = McpJsonMapper.getDefault();
    private final ToolRegistry registry = new ToolRegistry();

    public McpServer() {
        registry.register(new JpaListNativeQueriesTool(mapper));
        registry.register(new H2PrepareTool(mapper));
        registry.register(new PrepareReportTool(mapper));
        registry.register(new SqlRewriteTool(mapper));
    }

    public static void main(String[] args) {
        new McpServer().start();
    }

    public void start() {
        List<McpServerFeatures.SyncToolSpecification> tools = registry.list().stream()
                .map(this::toToolSpecification)
                .toList();

        if (LOG_FILE_PATH != null) {
            LOGGER.info("Logging MCP server output to {}", LOG_FILE_PATH);
        }

        StdioServerTransportProvider transportProvider = new StdioServerTransportProvider(mcpJsonMapper);

        McpSyncServer server = io.modelcontextprotocol.server.McpServer
                .sync(transportProvider)
                .serverInfo(new McpSchema.Implementation("h2-sql-mcp", "0.1.0"))
                .jsonMapper(mcpJsonMapper)
                .tools(tools)
                .build();

        keepServerAlive(server, tools.size());
    }

    private static String configureSimpleLogger() {
        String existing = System.getProperty("org.slf4j.simpleLogger.logFile");
        if (existing != null && !existing.isBlank()) {
            return existing;
        }

        try {
            Path logFile = resolveLogFilePath();
            if (logFile == null) {
                return null;
            }
            Path parent = logFile.getParent();
            if (parent != null && Files.notExists(parent)) {
                Files.createDirectories(parent);
            }
            String absolutePath = logFile.toAbsolutePath().toString();
            System.setProperty("org.slf4j.simpleLogger.logFile", absolutePath);
            return absolutePath;
        } catch (Exception ex) {
            System.err.println("Failed to configure simple logger file output: " + ex.getMessage());
            return null;
        }
    }

    private static Path resolveLogFilePath() {
        Path jarDirectory = JarLocationResolver.resolveJarDirectory(McpServer.class);
        if (jarDirectory == null) {
            return null;
        }
        return jarDirectory.resolve("mcp-server.log");
    }

    private void keepServerAlive(McpSyncServer server, int toolCount) {
        CountDownLatch shutdown = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean(false);

        Runnable shutdownHook = () -> {
            if (closed.compareAndSet(false, true)) {
                try {
                    LOGGER.info("Shutting down MCP server");
                    server.closeGracefully();
                } catch (Exception e) {
                    LOGGER.warn("Error while shutting down MCP server", e);
                } finally {
                    shutdown.countDown();
                }
            }
        };

        Runtime.getRuntime().addShutdownHook(new Thread(shutdownHook, "mcp-server-shutdown"));

        LOGGER.info("MCP server started with {} tool(s); awaiting requests...", toolCount);
        try {
            shutdown.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("MCP server interrupted; shutting down");
            shutdownHook.run();
        }
    }

    private McpServerFeatures.SyncToolSpecification toToolSpecification(Tool tool) {
        McpSchema.Tool descriptor = buildToolDescriptor(tool);
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(descriptor)
                .callHandler((exchange, request) -> executeTool(tool, request))
                .build();
    }

    private McpSchema.Tool buildToolDescriptor(Tool tool) {
        String schemaJson = serializeSchema(tool);
        return McpSchema.Tool.builder()
                .name(tool.getName())
                .description(tool.getDescription())
                .inputSchema(mcpJsonMapper, schemaJson)
                .build();
    }

    private String serializeSchema(Tool tool) {
        JsonNode schema = tool.getInputSchema();
        if (schema == null) {
            throw new IllegalStateException("Tool " + tool.getName() + " must provide an input schema");
        }
        return schema.toString();
    }

    private McpSchema.CallToolResult executeTool(Tool tool, McpSchema.CallToolRequest request) {
        JsonNode arguments = toArgumentsNode(request);
        try {
            JsonNode result = tool.call(arguments);
            McpSchema.CallToolResult.Builder builder = McpSchema.CallToolResult.builder().isError(false);
            if (result == null || result.isNull()) {
                builder.addTextContent("null");
            } else {
                Object structured = mapper.convertValue(result, Object.class);
                builder.structuredContent(structured);
                builder.addTextContent(renderResultText(result));
            }
            return builder.build();
        } catch (Exception ex) {
            LOGGER.error("Tool '{}' execution failed", tool.getName(), ex);
            String message = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            return McpSchema.CallToolResult.builder()
                    .isError(true)
                    .addTextContent("Tool execution failed: " + message)
                    .build();
        }
    }

    private JsonNode toArgumentsNode(McpSchema.CallToolRequest request) {
        if (request.arguments() == null) {
            return mapper.createObjectNode();
        }
        return mapper.valueToTree(request.arguments());
    }

    private String renderResultText(JsonNode result) {
        if (result == null || result.isNull()) {
            return "null";
        }
        if (result.isTextual()) {
            return result.asText();
        }
        if (result.isNumber() || result.isBoolean()) {
            return result.toString();
        }
        try {
            return mapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            return result.toString();
        }
    }
}
