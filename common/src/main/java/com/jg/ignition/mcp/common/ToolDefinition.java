package com.jg.ignition.mcp.common;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolDefinition(
    String name,
    String description,
    PermissionRequirement permission,
    JsonNode inputSchema,
    boolean mutating
) {
}
