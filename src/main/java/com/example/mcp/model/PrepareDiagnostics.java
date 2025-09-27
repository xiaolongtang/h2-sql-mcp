package com.example.mcp.model;

public record PrepareDiagnostics(String message, String sqlState, int errorCode, Integer line, Integer column) {
}
