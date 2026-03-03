package com.jg.ignition.mcp.gateway.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inductiveautomation.ignition.common.browsing.BrowseFilter;
import com.inductiveautomation.ignition.common.browsing.Results;
import com.inductiveautomation.ignition.common.model.values.QualifiedValue;
import com.inductiveautomation.ignition.common.model.values.QualityCode;
import com.inductiveautomation.ignition.common.tags.browsing.NodeDescription;
import com.inductiveautomation.ignition.common.tags.model.TagProvider;
import com.inductiveautomation.ignition.common.tags.model.TagPath;
import com.inductiveautomation.ignition.common.tags.paths.parser.TagPathParser;
import com.inductiveautomation.ignition.gateway.tags.model.GatewayTagManager;
import com.jg.ignition.mcp.common.PermissionRequirement;
import com.jg.ignition.mcp.common.ToolDefinition;
import com.jg.ignition.mcp.common.ToolExecutionResult;
import com.jg.ignition.mcp.gateway.ToolCallContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class TagToolHandler implements ToolHandler {

    private static final long CALL_TIMEOUT_SECONDS = 30L;

    private final String toolName;
    private final ToolDefinition definition;

    public TagToolHandler(String toolName) {
        this.toolName = toolName;
        this.definition = new ToolDefinition(
            toolName,
            descriptionFor(toolName),
            permissionFor(toolName),
            inputSchema(toolName),
            toolName.endsWith(".write")
        );
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolExecutionResult execute(JsonNode arguments, ToolCallContext context) {
        return switch (toolName) {
            case "ignition.tags.browse" -> browse(arguments, context);
            case "ignition.tags.read" -> read(arguments, context);
            case "ignition.tags.write" -> write(arguments, context);
            default -> ToolExecutionResult.error("Unsupported tag tool: " + toolName);
        };
    }

    private ToolExecutionResult browse(JsonNode arguments, ToolCallContext context) {
        ObjectNode result = context.objectMapper().createObjectNode();
        ArrayNode providersNode = result.putArray("providers");

        try {
            GatewayTagManager tagManager = context.gatewayContext().getTagManager();
            for (TagProvider provider : tagManager.getTagProviders()) {
                providersNode.add(provider.getName());
            }

            String path = arguments == null ? "" : arguments.path("path").asText("").trim();
            result.put("requestedPath", path);
            if (path.isBlank()) {
                result.put("nodeCount", 0);
                return ToolExecutionResult.ok("Browse completed", result);
            }

            if (!context.safetyPolicy().isTagReadAllowed(path)) {
                return ToolExecutionResult.error("Browse path blocked by allowlist: " + path);
            }

            TagPath tagPath = TagPathParser.parseSafe(path);
            if (tagPath == null) {
                return ToolExecutionResult.error("Invalid tag path: " + path);
            }

            Results<NodeDescription> browseResults = tagManager.browseAsync(tagPath, BrowseFilter.NONE)
                .get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            ArrayNode nodes = result.putArray("nodes");
            for (NodeDescription node : browseResults.getResults()) {
                ObjectNode item = nodes.addObject();
                item.put("name", node.getName());
                item.put("fullPath", node.getFullPath() == null ? "" : node.getFullPath().toStringFull());
                item.put("hasChildren", node.hasChildren());
                item.put("objectType", node.getObjectType() == null ? "" : node.getObjectType().name());
                item.put("dataType", node.getDataType() == null ? "" : node.getDataType().name());
            }
            result.put("nodeCount", nodes.size());
            result.put("returnedSize", browseResults.getReturnedSize());
            result.put("totalAvailableSize", browseResults.getTotalAvailableSize());
            result.put("continuationPoint", browseResults.getContinuationPoint());
            return ToolExecutionResult.ok("Browse completed", result);
        }
        catch (Exception e) {
            return ToolExecutionResult.error("Failed to browse tags: " + e.getMessage());
        }
    }

    private ToolExecutionResult read(JsonNode arguments, ToolCallContext context) {
        ArrayNode paths = arguments != null && arguments.has("paths") && arguments.get("paths").isArray()
            ? (ArrayNode) arguments.get("paths")
            : context.objectMapper().createArrayNode();
        if (paths.isEmpty()) {
            return ToolExecutionResult.error("Read requires at least one path");
        }

        List<TagPath> tagPaths = new ArrayList<>(paths.size());
        List<String> inputPaths = new ArrayList<>(paths.size());
        for (JsonNode pathNode : paths) {
            String path = pathNode.asText("").trim();
            if (path.isBlank()) {
                return ToolExecutionResult.error("Read path cannot be empty");
            }
            if (!context.safetyPolicy().isTagReadAllowed(path)) {
                return ToolExecutionResult.error("Read path blocked by allowlist: " + path);
            }
            TagPath tagPath = TagPathParser.parseSafe(path);
            if (tagPath == null) {
                return ToolExecutionResult.error("Invalid read path: " + path);
            }
            tagPaths.add(tagPath);
            inputPaths.add(path);
        }

        try {
            List<QualifiedValue> readValues = context.gatewayContext()
                .getTagManager()
                .readAsync(tagPaths)
                .get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            ObjectNode result = context.objectMapper().createObjectNode();
            ArrayNode values = result.putArray("values");
            for (int i = 0; i < inputPaths.size(); i++) {
                QualifiedValue qualifiedValue = i < readValues.size() ? readValues.get(i) : null;
                ObjectNode item = values.addObject();
                item.put("path", inputPaths.get(i));
                if (qualifiedValue == null) {
                    item.put("quality", QualityCode.Bad_NotFound.getName());
                    item.putNull("value");
                    item.putNull("timestamp");
                }
                else {
                    item.put("quality", qualifiedValue.getQuality().getName());
                    item.set("value", context.objectMapper().valueToTree(qualifiedValue.getValue()));
                    item.put("timestamp", qualifiedValue.getTimestamp().getTime());
                }
            }
            result.put("count", values.size());
            return ToolExecutionResult.ok("Read completed", result);
        }
        catch (Exception e) {
            return ToolExecutionResult.error("Read failed: " + e.getMessage());
        }
    }

    private ToolExecutionResult write(JsonNode arguments, ToolCallContext context) {
        ArrayNode writes = arguments != null && arguments.has("writes") && arguments.get("writes").isArray()
            ? (ArrayNode) arguments.get("writes")
            : context.objectMapper().createArrayNode();

        if (writes.isEmpty()) {
            return ToolExecutionResult.error("Write requires at least one write entry");
        }
        if (writes.size() > context.safetyPolicy().maxBatchWriteSize()) {
            return ToolExecutionResult.error(
                "Write batch exceeds maxBatchWriteSize=" + context.safetyPolicy().maxBatchWriteSize()
            );
        }

        List<String> targets = new ArrayList<>();
        List<TagPath> tagPaths = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (JsonNode writeNode : writes) {
            String path = writeNode.path("path").asText("").trim();
            if (path.isBlank()) {
                return ToolExecutionResult.error("Each write entry must include a non-empty path");
            }
            if (!context.safetyPolicy().isTagWriteAllowed(path)) {
                context.auditLogger().logWriteAttempt(
                    context.authContext().tokenName(),
                    toolName,
                    false,
                    false,
                    path
                );
                return ToolExecutionResult.error("Write path blocked by allowlist: " + path);
            }
            TagPath tagPath = TagPathParser.parseSafe(path);
            if (tagPath == null) {
                return ToolExecutionResult.error("Invalid write path: " + path);
            }
            targets.add(path);
            tagPaths.add(tagPath);
            values.add(jsonNodeToSimple(writeNode.get("value")));
        }

        boolean dryRun = context.safetyPolicy().isDryRun(arguments);
        if (dryRun) {
            ObjectNode result = context.objectMapper().createObjectNode();
            result.put("dryRun", true);
            ArrayNode planned = result.putArray("plannedWrites");
            for (int i = 0; i < targets.size(); i++) {
                ObjectNode row = planned.addObject();
                row.put("path", targets.get(i));
                JsonNode valueNode = writes.get(i).get("value");
                row.set("value", valueNode == null ? NullNode.instance : valueNode);
            }
            return ToolExecutionResult.ok("Dry-run write plan generated", result);
        }

        try {
            List<QualityCode> qualities = context.gatewayContext()
                .getTagManager()
                .writeAsync(tagPaths, values)
                .get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            boolean allGood = true;
            for (int i = 0; i < targets.size(); i++) {
                QualityCode quality = i < qualities.size() ? qualities.get(i) : QualityCode.Bad_Failure;
                boolean good = quality != null && quality.isGood();
                allGood &= good;
                context.auditLogger().logWriteAttempt(
                    context.authContext().tokenName(),
                    toolName,
                    true,
                    good,
                    targets.get(i)
                );
            }

            if (!allGood) {
                return ToolExecutionResult.error("One or more tag writes failed");
            }

            ObjectNode result = context.objectMapper().createObjectNode();
            result.put("committed", true);
            result.put("count", targets.size());
            ArrayNode statuses = result.putArray("statuses");
            for (int i = 0; i < targets.size(); i++) {
                QualityCode quality = i < qualities.size() ? qualities.get(i) : QualityCode.Bad_Failure;
                ObjectNode status = statuses.addObject();
                status.put("path", targets.get(i));
                status.put("quality", quality == null ? "Bad_Failure" : quality.getName());
            }
            return ToolExecutionResult.ok("Committed " + targets.size() + " tag write(s)", result);
        }
        catch (Exception e) {
            for (String target : targets) {
                context.auditLogger().logWriteAttempt(
                    context.authContext().tokenName(),
                    toolName,
                    true,
                    false,
                    target
                );
            }
            return ToolExecutionResult.error("Write execution failed: " + e.getMessage());
        }
    }

    private static Object jsonNodeToSimple(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isFloatingPointNumber()) {
            return node.asDouble();
        }
        if (node.isArray() || node.isObject()) {
            return node.toString();
        }
        return node.asText();
    }

    private static PermissionRequirement permissionFor(String toolName) {
        return toolName.endsWith(".write") ? PermissionRequirement.WRITE : PermissionRequirement.READ;
    }

    private static String descriptionFor(String toolName) {
        return switch (toolName) {
            case "ignition.tags.browse" -> "Browse Ignition tag providers and path roots";
            case "ignition.tags.read" -> "Read one or more tag values";
            case "ignition.tags.write" -> "Write one or more tag values (allowlist + rate limited)";
            default -> "Tag tool";
        };
    }

    private static JsonNode inputSchema(String toolName) {
        ObjectNode schema = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        switch (toolName) {
            case "ignition.tags.browse" -> properties.putObject("path").put("type", "string");
            case "ignition.tags.read" -> {
                ObjectNode paths = properties.putObject("paths");
                paths.put("type", "array");
                paths.putObject("items").put("type", "string");
            }
            case "ignition.tags.write" -> {
                ObjectNode writes = properties.putObject("writes");
                writes.put("type", "array");
                ObjectNode item = writes.putObject("items");
                item.put("type", "object");
                ObjectNode itemProps = item.putObject("properties");
                itemProps.putObject("path").put("type", "string");
                itemProps.putObject("value");
                item.putArray("required").add("path").add("value");

                properties.putObject("commit").put("type", "boolean");
            }
            default -> {
            }
        }

        return schema;
    }
}
