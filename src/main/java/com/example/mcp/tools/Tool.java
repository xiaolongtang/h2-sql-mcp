package com.example.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public interface Tool {
    String getName();

    String getDescription();

    ObjectNode getInputSchema();

    JsonNode call(JsonNode arguments) throws Exception;
}
