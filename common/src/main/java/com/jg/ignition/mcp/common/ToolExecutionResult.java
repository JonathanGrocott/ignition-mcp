package com.jg.ignition.mcp.common;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolExecutionResult(
    boolean isError,
    String text,
    JsonNode structuredContent
) {
    public static ToolExecutionResult ok(String text, JsonNode structuredContent) {
        return new ToolExecutionResult(false, text, structuredContent);
    }

    public static ToolExecutionResult error(String text) {
        return new ToolExecutionResult(true, text, null);
    }
}
