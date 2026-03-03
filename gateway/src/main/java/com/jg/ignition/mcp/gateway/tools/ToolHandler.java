package com.jg.ignition.mcp.gateway.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.jg.ignition.mcp.common.ToolDefinition;
import com.jg.ignition.mcp.common.ToolExecutionResult;
import com.jg.ignition.mcp.gateway.ToolCallContext;

public interface ToolHandler {

    ToolDefinition definition();

    ToolExecutionResult execute(JsonNode arguments, ToolCallContext context);
}
