package com.jg.ignition.mcp.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jg.ignition.mcp.common.JsonRpcErrorPayload;
import com.jg.ignition.mcp.common.McpConstants;
import com.jg.ignition.mcp.common.ToolDefinition;
import com.jg.ignition.mcp.common.ToolExecutionResult;
import com.jg.ignition.mcp.gateway.tools.ToolHandler;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.Objects;

public class McpDispatcher {

    public static final int JSONRPC_RATE_LIMIT = -32029;
    public static final int JSONRPC_PERMISSION_DENIED = -32001;
    public static final int JSONRPC_TOOL_NOT_FOUND = -32004;

    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final McpPermissionEvaluator permissionEvaluator;

    public McpDispatcher(
        ObjectMapper objectMapper,
        ToolRegistry toolRegistry,
        McpPermissionEvaluator permissionEvaluator
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.permissionEvaluator = Objects.requireNonNull(permissionEvaluator, "permissionEvaluator");
    }

    public JsonNode dispatch(JsonNode payload, ToolCallContext context) {
        if (!context.safetyPolicy().allowRequest(context.authContext().tokenName())) {
            return errorResponse(
                NullNode.instance,
                JSONRPC_RATE_LIMIT,
                "Rate limit exceeded for token",
                new JsonRpcErrorPayload(JSONRPC_RATE_LIMIT, "Try again later", null)
            );
        }

        if (payload == null || payload.isNull()) {
            return errorResponse(NullNode.instance, McpConstants.JSONRPC_INVALID_REQUEST, "Invalid request", null);
        }

        if (payload.isArray()) {
            ArrayNode requests = (ArrayNode) payload;
            if (requests.isEmpty()) {
                return errorResponse(NullNode.instance, McpConstants.JSONRPC_INVALID_REQUEST, "Invalid request", null);
            }

            ArrayNode responses = objectMapper.createArrayNode();
            for (JsonNode request : requests) {
                JsonNode response = dispatchSingle(request, context);
                if (response != null) {
                    responses.add(response);
                }
            }
            return responses.isEmpty() ? null : responses;
        }

        return dispatchSingle(payload, context);
    }

    public ObjectNode errorResponse(JsonNode id, int code, String message, Object data) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? NullNode.instance : id);

        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        if (data != null) {
            error.set("data", objectMapper.valueToTree(data));
        }
        return response;
    }

    private JsonNode dispatchSingle(JsonNode request, ToolCallContext context) {
        if (!request.isObject()) {
            return errorResponse(NullNode.instance, McpConstants.JSONRPC_INVALID_REQUEST, "Invalid request", null);
        }

        JsonNode id = request.has("id") ? request.get("id") : null;
        boolean notification = !request.has("id");

        JsonNode methodNode = request.get("method");
        if (methodNode == null || !methodNode.isTextual()) {
            return notification
                ? null
                : errorResponse(id, McpConstants.JSONRPC_INVALID_REQUEST, "method must be a string", null);
        }
        String method = methodNode.asText();
        JsonNode params = request.path("params");

        try {
            JsonNode result = switch (method) {
                case McpConstants.METHOD_INITIALIZE -> handleInitialize();
                case McpConstants.METHOD_PING -> handlePing();
                case McpConstants.METHOD_TOOLS_LIST -> handleToolsList();
                case McpConstants.METHOD_TOOLS_CALL -> handleToolsCall(params, context);
                case McpConstants.METHOD_NOTIFICATIONS_INITIALIZED -> null;
                default -> throw new RpcException(
                    McpConstants.JSONRPC_METHOD_NOT_FOUND,
                    "Method not found: " + method,
                    null
                );
            };

            if (notification) {
                return null;
            }
            return successResponse(id, result);
        }
        catch (RpcException e) {
            return notification ? null : errorResponse(id, e.code, e.getMessage(), e.data);
        }
        catch (Exception e) {
            return notification
                ? null
                : errorResponse(id, McpConstants.JSONRPC_INTERNAL_ERROR, "Internal error", e.getMessage());
        }
    }

    private ObjectNode handleInitialize() {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", McpConstants.PROTOCOL_VERSION);

        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools").put("listChanged", false);

        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", "ignition-mcp");
        serverInfo.put("version", "0.1.0-SNAPSHOT");
        return result;
    }

    private ObjectNode handlePing() {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("ok", true);
        result.put("time", Instant.now().toString());
        return result;
    }

    private ObjectNode handleToolsList() {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        for (ToolDefinition definition : toolRegistry.definitions()) {
            ObjectNode tool = tools.addObject();
            tool.put("name", definition.name());
            tool.put("description", definition.description());
            if (definition.inputSchema() != null) {
                tool.set("inputSchema", definition.inputSchema().deepCopy());
            }
        }
        return result;
    }

    private ObjectNode handleToolsCall(JsonNode params, ToolCallContext context) {
        if (!params.isObject()) {
            throw new RpcException(McpConstants.JSONRPC_INVALID_PARAMS, "params must be an object", null);
        }

        String name = params.path("name").asText("");
        if (StringUtils.isBlank(name)) {
            throw new RpcException(McpConstants.JSONRPC_INVALID_PARAMS, "params.name is required", null);
        }

        ToolHandler handler = toolRegistry.find(name)
            .orElseThrow(() -> new RpcException(JSONRPC_TOOL_NOT_FOUND, "Unknown tool: " + name, null));
        ToolDefinition definition = handler.definition();

        McpPermissionEvaluator.PermissionDecision permission = permissionEvaluator.evaluate(
            definition.permission(),
            context.requestContext()
        );
        if (!permission.granted()) {
            throw new RpcException(
                JSONRPC_PERMISSION_DENIED,
                permission.message(),
                objectMapper.createObjectNode()
                    .put("statusCode", permission.statusCode())
                    .put("permission", definition.permission().name())
            );
        }

        if (definition.mutating() && !context.safetyPolicy().allowWrite(context.authContext().tokenName())) {
            throw new RpcException(JSONRPC_RATE_LIMIT, "Write rate limit exceeded for token", null);
        }

        JsonNode arguments = params.has("arguments") ? params.get("arguments") : objectMapper.createObjectNode();
        if (arguments == null || arguments.isNull()) {
            arguments = objectMapper.createObjectNode();
        }
        if (!arguments.isObject()) {
            throw new RpcException(McpConstants.JSONRPC_INVALID_PARAMS, "params.arguments must be an object", null);
        }

        long started = System.currentTimeMillis();
        ToolExecutionResult executionResult = handler.execute(arguments, context);
        long duration = System.currentTimeMillis() - started;
        context.auditLogger().logToolCall(
            context.authContext().tokenName(),
            definition.name(),
            duration,
            !executionResult.isError(),
            executionResult.text()
        );

        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        content.addObject()
            .put("type", "text")
            .put("text", executionResult.text());
        if (executionResult.structuredContent() != null) {
            result.set("structuredContent", executionResult.structuredContent());
        }
        result.put("isError", executionResult.isError());
        return result;
    }

    private ObjectNode successResponse(JsonNode id, JsonNode result) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? NullNode.instance : id);
        response.set("result", result == null ? objectMapper.createObjectNode() : result);
        return response;
    }

    private static final class RpcException extends RuntimeException {
        private final int code;
        private final Object data;

        private RpcException(int code, String message, Object data) {
            super(message);
            this.code = code;
            this.data = data;
        }
    }
}
