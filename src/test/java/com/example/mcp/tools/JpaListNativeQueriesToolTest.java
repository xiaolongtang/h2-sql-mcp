package com.example.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

class JpaListNativeQueriesToolTest {

    @TempDir
    Path tempDir;

    @Test
    void detectsNativeQueriesIncludingTextBlocks() throws Exception {
        prepareRepository();

        ObjectMapper mapper = new ObjectMapper();
        JpaListNativeQueriesTool tool = new JpaListNativeQueriesTool(mapper);
        ObjectNode args = mapper.createObjectNode();
        ArrayNode rootDirs = mapper.createArrayNode();
        rootDirs.add(tempDir.toString());
        args.set("rootDirs", rootDirs);

        JsonNode result = tool.call(args);
        ArrayNode queries = (ArrayNode) result.get("queries");

        assertNotNull(queries, "queries node should be present");
        assertEquals(4, queries.size(), "should detect all native queries");
        assertContainsQuery(queries, "DemoRepository#countAllNative", "SELECT COUNT(*) FROM demo_records where numer=?1");
        assertContainsQuery(queries, "DemoRepository#findAllNamesNative", "SELECT name FROM demo_records where name= :name ORDER BY name");
        assertContainsQuery(queries, "DemoRepository#countAllNative1", "WITH employee_data AS (");
        JsonNode subselectQuery = assertContainsQuery(
                queries,
                "DemoRepository#findWithSubselect",
                "SELECT * FROM aa WHERE aa.id IN (SELECT id FROM bb)"
        );
        assertEquals(
                "SELECT * FROM aa WHERE aa.id IN (SELECT id FROM bb)",
                subselectQuery.get("sqlNormalized").asText(),
                "sqlNormalized should preserve subselect contents"
        );
    }

    @Test
    void collapsesWhitespaceWhenRequested() throws Exception {
        prepareRepository();

        ObjectMapper mapper = new ObjectMapper();
        JpaListNativeQueriesTool tool = new JpaListNativeQueriesTool(mapper);
        ObjectNode args = mapper.createObjectNode();
        ArrayNode rootDirs = mapper.createArrayNode();
        rootDirs.add(tempDir.toString());
        args.set("rootDirs", rootDirs);
        args.put("collapseWhitespace", true);

        JsonNode result = tool.call(args);
        ArrayNode queries = (ArrayNode) result.get("queries");

        assertNotNull(queries, "queries node should be present");
        JsonNode textBlockQuery = null;
        for (JsonNode query : queries) {
            if ("DemoRepository#countAllNative1".equals(query.get("id").asText())) {
                textBlockQuery = query;
                break;
            }
        }
        if (textBlockQuery == null) {
            fail("Text block query should be detected");
        }
        String sql = textBlockQuery.get("sqlRaw").asText();
        assertNotNull(sql, "sql should be present");
        if (sql.contains("\n") || sql.contains("\r")) {
            fail("Collapsed SQL should not contain newline characters");
        }
        if (!sql.contains("LPAD(' ', (LEVEL - 1) * 2) || employee_name AS indented_name,")) {
            fail("Collapsed SQL should preserve embedded single-space literals");
        }
    }

    private void prepareRepository() throws Exception {
        Path sourceDir = tempDir.resolve("src/main/java/com/example/demo");
        Files.createDirectories(sourceDir);
        Path repositoryFile = sourceDir.resolve("DemoRepository.java");
        Files.writeString(repositoryFile, repositorySource());
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
                "    @Query(value = \"SELECT * FROM aa WHERE aa.id IN (SELECT id FROM bb)\", nativeQuery = true)\n" +
                "    List<Object> findWithSubselect();\n" +
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
    private JsonNode assertContainsQuery(ArrayNode queries, String id, String expectedSqlFragment) {
        for (JsonNode query : queries) {
            if (id.equals(query.get("id").asText())) {
                String sql = query.get("sqlRaw").asText();
                assertNotNull(sql, "sql should be present for " + id);
                if (!sql.contains(expectedSqlFragment)) {
                    fail("SQL for " + id + " did not contain expected fragment");
                }
                return query;
            }
        }
        fail("Expected query with id " + id + " not found");
        return null;
    }
}
