package com.example.mcp.model;

import java.util.List;

public record QueryItem(
        String id,
        String file,
        String repo,
        String method,
        String sqlRaw,
        String sqlNormalized,
        List<Placeholder> placeholders,
        List<RuleHit> ruleHits
) {
}
