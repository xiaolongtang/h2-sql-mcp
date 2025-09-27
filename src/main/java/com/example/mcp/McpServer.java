package com.example.mcp;

import com.example.mcp.tools.H2PrepareTool;
import com.example.mcp.tools.JpaListNativeQueriesTool;
import com.example.mcp.tools.SqlRewriteTool;
import com.example.mcp.tools.Tool;
import com.example.mcp.tools.ToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal MCP server implementation that communicates over stdio using JSON-RPC messages
 * framed with Content-Length headers (compatible with LSP style transport).
 */
public class McpServer {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolRegistry registry = new ToolRegistry();

    public McpServer() {
        registry.register(new JpaListNativeQueriesTool(mapper));
        registry.register(new H2PrepareTool(mapper));
        registry.register(new SqlRewriteTool(mapper));
    }

    public static void main(String[] args) throws IOException {
        McpServer server = new McpServer();
        server.run(System.in, System.out);
    }

    public void run(InputStream inStream, OutputStream outStream) throws IOException {
        BufferedInputStream in = new BufferedInputStream(inStream);
        BufferedOutputStream out = new BufferedOutputStream(outStream);

        while (true) {
            Map<String, String> headers = readHeaders(in);
            if (headers == null) {
                break;
            }
            String lengthHeader = headers.get("content-length");
            if (lengthHeader == null) {
                continue;
            }
            int length = Integer.parseInt(lengthHeader.trim());
            byte[] payload = in.readNBytes(length);
            if (payload.length < length) {
                break;
            }
            String json = new String(payload, StandardCharsets.UTF_8);
            JsonNode requestNode;
            try {
                requestNode = mapper.readTree(json);
            } catch (JsonProcessingException e) {
                continue;
            }
            handleRequest(requestNode, out);
            out.flush();
        }
    }

    private Map<String, String> readHeaders(BufferedInputStream in) throws IOException {
        Map<String, String> headers = new HashMap<>();
        StringBuilder current = new StringBuilder();
        int b;
        boolean seenAny = false;
        while ((b = in.read()) != -1) {
            seenAny = true;
            if (b == '\n') {
                String line = stripTrailingCarriageReturn(current);
                if (line.isEmpty()) {
                    return headers;
                }
                int colonIndex = line.indexOf(':');
                if (colonIndex > 0) {
                    String name = line.substring(0, colonIndex).trim().toLowerCase(Locale.ROOT);
                    String value = line.substring(colonIndex + 1).trim();
                    headers.put(name, value);
                }
                current.setLength(0);
            } else {
                current.append((char) b);
            }
        }
        if (!seenAny) {
            return null;
        }
        if (current.length() > 0) {
            String line = stripTrailingCarriageReturn(current);
            int colonIndex = line.indexOf(":");
            if (colonIndex > 0) {
                String name = line.substring(0, colonIndex).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colonIndex + 1).trim();
                headers.put(name, value);
            }
        }
        return headers.isEmpty() && !seenAny ? null : headers;
    }

    private String stripTrailingCarriageReturn(StringBuilder builder) {
        int length = builder.length();
        if (length == 0) {
            return "";
        }
        if (builder.charAt(length - 1) == '\r') {
            return builder.substring(0, length - 1);
        }
        return builder.toString();
    }

    private void handleRequest(JsonNode request, BufferedOutputStream out) throws IOException {
        String method = request.path("method").asText();
        JsonNode idNode = request.get("id");
        JsonNode params = request.get("params");
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (idNode != null) {
            response.set("id", idNode);
        } else {
            response.putNull("id");
        }

        switch (method) {
            case "initialize" -> response.set("result", createInitializeResult());
            case "tools/list" -> response.set("result", createToolsListResult());
            case "tools/call" -> response.set("result", handleToolsCall(params, response));
            default -> {
                response.putNull("result");
                ObjectNode error = mapper.createObjectNode();
                error.put("code", -32601);
                error.put("message", "Method not found: " + method);
                response.set("error", error);
            }
        }
        byte[] payload = mapper.writeValueAsBytes(response);
        String header = "Content-Length: " + payload.length + "\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(payload);
    }

    private JsonNode createInitializeResult() {
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", "0.1.0");
        ObjectNode capabilities = mapper.createObjectNode();
        result.set("capabilities", capabilities);
        return result;
    }

    private JsonNode createToolsListResult() {
        ArrayNode array = mapper.createArrayNode();
        List<Tool> tools = registry.list();
        for (Tool tool : tools) {
            ObjectNode entry = mapper.createObjectNode();
            entry.put("name", tool.getName());
            entry.put("description", tool.getDescription());
            entry.set("inputSchema", tool.getInputSchema());
            array.add(entry);
        }
        ObjectNode result = mapper.createObjectNode();
        result.set("tools", array);
        return result;
    }

    private JsonNode handleToolsCall(JsonNode params, ObjectNode response) {
        if (params == null || !params.hasNonNull("name")) {
            ObjectNode error = mapper.createObjectNode();
            error.put("code", -32602);
            error.put("message", "Missing tool name");
            response.set("error", error);
            return mapper.nullNode();
        }
        String name = params.get("name").asText();
        Tool tool = registry.get(name);
        if (tool == null) {
            ObjectNode error = mapper.createObjectNode();
            error.put("code", -32602);
            error.put("message", "Unknown tool: " + name);
            response.set("error", error);
            return mapper.nullNode();
        }
        JsonNode arguments = params.get("arguments");
        try {
            return tool.call(arguments == null ? mapper.createObjectNode() : arguments);
        } catch (Exception e) {
            ObjectNode error = mapper.createObjectNode();
            error.put("code", -32000);
            error.put("message", e.getMessage());
            response.set("error", error);
            return mapper.nullNode();
        }
    }
}
