package com.example.mcp.util;

import com.example.mcp.model.Placeholder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParamNormalizerTest {

    @Test
    void wrapsInClausePlaceholdersWithParentheses() {
        ParamNormalizer.Result result = ParamNormalizer.normalize("select * from dummy where id in ?1");

        assertEquals("select * from dummy where id in (?)", result.sql());
        assertEquals(List.of("?1"), result.placeholders().stream().map(Placeholder::token).toList());
    }

    @Test
    void keepsExistingParenthesesAroundInClausePlaceholders() {
        ParamNormalizer.Result result = ParamNormalizer.normalize("select * from dummy where id in (?1)");

        assertEquals("select * from dummy where id in (?)", result.sql());
        assertEquals(List.of("?1"), result.placeholders().stream().map(Placeholder::token).toList());
    }

    @Test
    void wrapsNamedParametersInInClause() {
        ParamNormalizer.Result result = ParamNormalizer.normalize("select * from dummy where id in :ids");

        assertEquals("select * from dummy where id in (?)", result.sql());
        assertEquals(List.of(":ids"), result.placeholders().stream().map(Placeholder::token).toList());
    }
}
