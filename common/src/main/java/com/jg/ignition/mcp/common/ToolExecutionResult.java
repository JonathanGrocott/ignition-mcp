package com.jg.ignition.mcp.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

public record ToolExecutionResult(
    boolean isError,
    String text,
    JsonNode structuredContent
) {
    public static ToolExecutionResult ok(String text, JsonNode structuredContent) {
        return new ToolExecutionResult(false, text, structuredContent);
    }

    public static ToolExecutionResult error(String text) {
        return error(classifyError(text), text);
    }

    public static ToolExecutionResult error(String code, String text) {
        return new ToolExecutionResult(true, text, errorBody(code, text));
    }

    public static ToolExecutionResult error(String text, JsonNode structuredContent) {
        return new ToolExecutionResult(true, text, structuredContent);
    }

    public static ObjectNode errorBody(String code, String message) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("code", code == null || code.isBlank() ? "TOOL_ERROR" : code);
        node.put("message", message == null ? "" : message);
        return node;
    }

    private static String classifyError(String text) {
        String value = text == null ? "" : text.toLowerCase();
        if (value.contains("allowlist") || value.contains("blocked")) {
            return "ALLOWLIST_BLOCKED";
        }
        if (value.contains("not found") || value.contains("missing")) {
            return "NOT_FOUND";
        }
        if (value.contains("unavailable")) {
            return "SDK_UNAVAILABLE";
        }
        if (value.contains("dry-run") || value.contains("dry run")) {
            return "DRY_RUN_REQUIRED";
        }
        if (value.contains("requires")
            || value.contains("invalid")
            || value.contains("unsupported")
            || value.contains("must ")
            || value.contains("cannot")
            || value.contains("empty")
            || value.contains("exceeds")) {
            return "VALIDATION_ERROR";
        }
        if (value.contains("failed") || value.contains("failure")) {
            return "SDK_FAILURE";
        }
        return "TOOL_ERROR";
    }
}
