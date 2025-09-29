package com.example.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class JpaListNativeQueriesToolTest {

    @TempDir
    Path tempDir;

    @Test
    void detectsNativeQueriesIncludingTextBlocks() throws Exception {
        writeRepositorySource();

        ObjectMapper mapper = new ObjectMapper();
        JpaListNativeQueriesTool tool = new JpaListNativeQueriesTool(mapper);
        ObjectNode args = defaultArguments(mapper);

        JsonNode result = tool.call(args);
        ArrayNode queries = (ArrayNode) result.get("queries");

        assertNotNull(queries, "queries node should be present");
        assertEquals(3, queries.size(), "should detect all native queries");
        assertEquals(3, result.get("totalCount").asInt());
        assertEquals(0, result.get("cursor").asInt());
        assertEquals(50, result.get("limit").asInt());
        assertContainsQuery(queries, "DemoRepository#countAllNative", "SELECT COUNT(*) FROM demo_records where numer=?1");
        assertContainsQuery(queries, "DemoRepository#findAllNamesNative", "SELECT name FROM demo_records where name= :name ORDER BY name");
        assertContainsQuery(queries, "DemoRepository#countAllNative1", "WITH employee_data AS (");
    }

    @Test
    void paginatesResultsUsingCursor() throws Exception {
        writeRepositorySource();

        ObjectMapper mapper = new ObjectMapper();
        JpaListNativeQueriesTool tool = new JpaListNativeQueriesTool(mapper);

        ObjectNode firstArgs = defaultArguments(mapper);
        firstArgs.put("limit", 2);

        JsonNode firstResult = tool.call(firstArgs);
        ArrayNode firstPage = (ArrayNode) firstResult.get("queries");
        assertEquals(2, firstPage.size(), "first page should honor limit");
        assertEquals(3, firstResult.get("totalCount").asInt());
        assertEquals(0, firstResult.get("cursor").asInt());
        assertEquals(2, firstResult.get("limit").asInt());
        assertEquals(2, firstResult.get("nextCursor").asInt());

        ObjectNode secondArgs = defaultArguments(mapper);
        secondArgs.put("cursor", firstResult.get("nextCursor").asInt());
        secondArgs.put("limit", 2);

        JsonNode secondResult = tool.call(secondArgs);
        ArrayNode secondPage = (ArrayNode) secondResult.get("queries");
        assertEquals(1, secondPage.size(), "second page should contain remaining item");
        assertEquals(3, secondResult.get("totalCount").asInt());
        assertEquals(2, secondResult.get("cursor").asInt());
        assertNull(secondResult.get("nextCursor"), "nextCursor should be absent when no more data");

        Set<String> collectedIds = new HashSet<>();
        firstPage.forEach(node -> collectedIds.add(node.get("id").asText()));
        secondPage.forEach(node -> collectedIds.add(node.get("id").asText()));
        assertEquals(3, collectedIds.size(), "pagination should expose every query across pages");
    }

    @Test
    void truncatesSqlWhenMaxSqlLengthProvided() throws Exception {
        writeRepositorySource();

        ObjectMapper mapper = new ObjectMapper();
        JpaListNativeQueriesTool tool = new JpaListNativeQueriesTool(mapper);

        ObjectNode args = defaultArguments(mapper);
        args.put("maxSqlLength", 20);

        JsonNode result = tool.call(args);
        ArrayNode queries = (ArrayNode) result.get("queries");
        for (JsonNode query : queries) {
            JsonNode sqlRaw = query.get("sqlRaw");
            JsonNode sqlNormalized = query.get("sqlNormalized");
            assertTrue(sqlRaw.isNull() || sqlRaw.asText().length() <= 20, "sqlRaw should be truncated when requested");
            assertTrue(sqlNormalized.isNull() || sqlNormalized.asText().length() <= 20, "sqlNormalized should be truncated when requested");
        }
    }

    private String repositorySource() {
        return "package com.example.demo;\n" +
                "\n" +
                "import org.springframework.data.jpa.repository.JpaRepository;\n" +
                "import org.springframework.data.jpa.repository.Query;\n" +
                "import org.springframework.stereotype.Repository;\n" +
                "\n" +
                "import java.util.List;\n" +
                "\n" +
                "@Repository\n" +
                "public interface DemoRepository extends JpaRepository<DemoEntity, Long> {\n" +
                "\n" +
                "    @Query(value = \"SELECT COUNT(*) FROM demo_records where numer=?1\", nativeQuery = true)\n" +
                "    Long countAllNative(Long number);\n" +
                "\n" +
                "    @org.springframework.data.jpa.repository.Query(value = \"SELECT name FROM demo_records where name= :name ORDER BY name\", nativeQuery = true)\n" +
                "    List<Object> findAllNamesNative(String name);\n" +
                "\n" +
                "    @Query(value = \"\"\"\n" +
                "WITH employee_data AS (\n" +
                "    SELECT 100 AS employee_id, 'Steven King' AS employee_name, NULL AS manager_id FROM DUAL UNION ALL\n" +
                "    SELECT 101 AS employee_id, 'Neena Kochhar' AS employee_name, 100 AS manager_id FROM DUAL UNION ALL\n" +
                "    SELECT 102 AS employee_id, 'Lex De Haan' AS employee_name, 100 AS manager_id FROM DUAL UNION ALL\n" +
                "    SELECT 103 AS employee_id, 'Alexander Hunold' AS employee_name, 102 AS manager_id FROM DUAL UNION ALL\n" +
                "    SELECT 104 AS employee_id, 'Bruce Ernst' AS employee_name, 103 AS manager_id FROM DUAL\n" +
                ")\n" +
                "SELECT\n" +
                "    employee_id,\n" +
                "    LPAD(' ', (LEVEL - 1) * 2) || employee_name AS indented_name, \n" +
                "    LEVEL, \n" +
                "    SYS_CONNECT_BY_PATH(employee_name, ' / ') AS path \n" +
                "FROM\n" +
                "    employee_data\n" +
                "START WITH\n" +
                "    manager_id IS NULL \n" +
                "CONNECT BY\n" +
                "    PRIOR employee_id = manager_id\n" +
                "\"\"\", nativeQuery = true)\n" +
                "    Long countAllNative1();\n" +
                "}\n";
    }
    private void assertContainsQuery(ArrayNode queries, String id, String expectedSqlFragment) {
        for (JsonNode query : queries) {
            if (id.equals(query.get("id").asText())) {
                String sql = query.get("sqlRaw").asText();
                assertNotNull(sql, "sql should be present for " + id);
                if (!sql.contains(expectedSqlFragment)) {
                    fail("SQL for " + id + " did not contain expected fragment");
                }
                return;
            }
        }
        fail("Expected query with id " + id + " not found");
    }

    private ObjectNode defaultArguments(ObjectMapper mapper) {
        ObjectNode args = mapper.createObjectNode();
        ArrayNode rootDirs = mapper.createArrayNode();
        rootDirs.add(tempDir.toString());
        args.set("rootDirs", rootDirs);
        return args;
    }

    private void writeRepositorySource() throws Exception {
        Path sourceDir = tempDir.resolve("src/main/java/com/example/demo");
        Files.createDirectories(sourceDir);
        Path repositoryFile = sourceDir.resolve("DemoRepository.java");
        Files.writeString(repositoryFile, repositorySource());
    }
}
