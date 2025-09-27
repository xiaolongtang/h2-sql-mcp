package com.example.mcp.util;

import com.example.mcp.model.RuleHit;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RuleEngine {
    public static final String RULE_MINUS_TO_EXCEPT = "MINUS_TO_EXCEPT";
    public static final String RULE_NVL_TO_COALESCE = "NVL_TO_COALESCE";
    public static final String RULE_FROM_DUAL = "REMOVE_FROM_DUAL";
    public static final String RULE_SYSDATE = "SYSDATE_TO_CURRENT_TIMESTAMP";
    public static final String HINT_OLD_JOIN = "LEGACY_OUTER_JOIN";
    public static final String HINT_ROWNUM = "ROWNUM_USAGE";
    public static final String HINT_CONNECT_BY = "CONNECT_BY_USAGE";
    public static final String HINT_DECODE = "DECODE_USAGE";

    private record RuleDefinition(String name, Pattern pattern, String replacement, boolean rewrite) {
    }

    public record RewriteResult(String sql, List<String> appliedRules) {
    }

    private final List<RuleDefinition> rewriteRules = List.of(
            new RuleDefinition(RULE_MINUS_TO_EXCEPT, Pattern.compile("\\bMINUS\\b", Pattern.CASE_INSENSITIVE), "EXCEPT", true),
            new RuleDefinition(RULE_NVL_TO_COALESCE, Pattern.compile("\\bNVL\\s*\\(", Pattern.CASE_INSENSITIVE), "COALESCE(", true),
            new RuleDefinition(RULE_FROM_DUAL, Pattern.compile("(?i)\\s+FROM\\s+DUAL\\b"), "", true),
            new RuleDefinition(RULE_SYSDATE, Pattern.compile("\\bSYSDATE\\b", Pattern.CASE_INSENSITIVE), "CURRENT_TIMESTAMP", true)
    );

    private final List<RuleDefinition> hintRules = List.of(
            new RuleDefinition(HINT_OLD_JOIN, Pattern.compile("\\(\\+\\)"), null, false),
            new RuleDefinition(HINT_ROWNUM, Pattern.compile("\\bROWNUM\\b", Pattern.CASE_INSENSITIVE), null, false),
            new RuleDefinition(HINT_CONNECT_BY, Pattern.compile("\\bCONNECT\\s+BY\\b|\\bSTART\\s+WITH\\b", Pattern.CASE_INSENSITIVE), null, false),
            new RuleDefinition(HINT_DECODE, Pattern.compile("\\bDECODE\\s*\\(", Pattern.CASE_INSENSITIVE), null, false)
    );

    public List<RuleHit> findHits(String sql) {
        List<RuleHit> hits = new ArrayList<>();
        if (sql == null) {
            return hits;
        }
        for (RuleDefinition rule : getAllRules()) {
            Matcher matcher = rule.pattern.matcher(sql);
            while (matcher.find()) {
                String snippet = matcher.group().trim();
                hits.add(new RuleHit(rule.name, snippet));
            }
        }
        return hits;
    }

    public RewriteResult rewrite(String sql) {
        if (sql == null) {
            return new RewriteResult(null, List.of());
        }
        String updated = sql;
        List<String> applied = new ArrayList<>();
        for (RuleDefinition rule : rewriteRules) {
            Matcher matcher = rule.pattern.matcher(updated);
            if (matcher.find()) {
                updated = matcher.replaceAll(rule.replacement);
                applied.add(rule.name);
            }
        }
        return new RewriteResult(updated, applied);
    }

    private List<RuleDefinition> getAllRules() {
        List<RuleDefinition> all = new ArrayList<>();
        all.addAll(rewriteRules);
        all.addAll(hintRules);
        return all;
    }
}
