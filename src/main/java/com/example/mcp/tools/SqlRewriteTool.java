package com.example.mcp.tools;

import com.example.mcp.util.RuleEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class SqlRewriteTool implements Tool {
    private final ObjectMapper mapper;
    private final RuleEngine ruleEngine;

    public SqlRewriteTool(ObjectMapper mapper) {
        this.mapper = mapper;
        this.ruleEngine = new RuleEngine();
    }

    @Override
    public String getName() {
        return "sql.rewrite";
    }

    @Override
    public String getDescription() {
        return "Rewrite common Oracle SQL syntax to H2 compatible equivalents.";
    }

    @Override
    public ObjectNode getInputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = mapper.createObjectNode();
        properties.putObject("sql").put("type", "string");
        properties.putObject("mode").put("type", "string");
        schema.set("properties", properties);
        ArrayNode required = mapper.createArrayNode();
        required.add("sql");
        schema.set("required", required);
        return schema;
    }

    @Override
    public JsonNode call(JsonNode arguments) {
        String sql = arguments.path("sql").asText(null);
        if (sql == null) {
            throw new IllegalArgumentException("'sql' is required");
        }
        RuleEngine.RewriteResult result = ruleEngine.rewrite(sql);
        ObjectNode node = mapper.createObjectNode();
        node.put("sql", result.sql());
        ArrayNode applied = mapper.createArrayNode();
        for (String rule : result.appliedRules()) {
            applied.add(rule);
        }
        node.set("appliedRules", applied);
        return node;
    }
}
