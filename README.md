# H2 SQL MCP Server

This project implements a single-process Java MCP server that helps migrate native JPA queries from Oracle to H2. The server exposes three tools over the MCP JSON-RPC protocol (`tools/list`, `tools/call`) and bundles into one shaded JAR for easy distribution.

## Features

- **`jpa.list_native_queries`** – scans repository source directories for `@Query(nativeQuery = true)` declarations, normalises parameter placeholders and reports Oracle-incompatible constructs.
- **`h2.prepare`** – prepares SQL statements against H2 (without executing them) using an optional initialisation script list.
- **`sql.rewrite`** – rewrites common Oracle syntax (`MINUS`, `NVL`, `FROM DUAL`, `SYSDATE`) into H2-compatible statements and reports the applied rules.

## Build

```bash
mvn clean package
```

The shaded artefact is produced at `target/h2-sql-mcp-0.1.0-shaded.jar`.

## Running the server

The server communicates over stdio using JSON-RPC framed by `Content-Length` headers. Run it directly:

```bash
java -jar target/h2-sql-mcp-0.1.0-shaded.jar
```

Clients should send `initialize`, `tools/list` and `tools/call` messages according to the MCP specification.

## Example session

```bash
# request tools
printf 'Content-Length: 55\r\n\r\n{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | \
  java -jar target/h2-sql-mcp-0.1.0-shaded.jar
```

### Example `tools/call` payloads

#### List native queries

```json
{
  "name": "jpa.list_native_queries",
  "arguments": {
    "rootDirs": ["./demo-repo"],
    "includeGlobs": ["**/*Repository.java"],
    "excludeGlobs": []
  }
}
```

#### Prepare SQL in H2

```json
{
  "name": "h2.prepare",
  "arguments": {
    "sql": "SELECT * FROM USERS WHERE STATUS = :status",
    "jdbcUrl": "jdbc:h2:mem:compat;MODE=Oracle;DATABASE_TO_UPPER=false;DEFAULT_NULL_ORDERING=HIGH",
    "initSqlPaths": ["./schema-h2.sql"]
  }
}
```

#### Rewrite SQL

```json
{
  "name": "sql.rewrite",
  "arguments": {
    "sql": "SELECT * FROM A MINUS SELECT * FROM B"
  }
}
```

Each payload should be wrapped in a JSON-RPC envelope when calling the MCP server.

## Development notes

- Java 17 / Maven build.
- Only literal-string queries are extracted (no string concatenation parsing).
- `h2.prepare` never executes statements; it only validates syntax through `PreparedStatement` parsing.
- Rewriting currently covers the most common Oracle-to-H2 cases. Additional rules can be added via `RuleEngine`.
