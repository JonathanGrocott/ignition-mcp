package com.jg.ignition.mcp.gateway.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inductiveautomation.ignition.common.TypeUtilities;
import com.inductiveautomation.ignition.common.config.BoundPropertySet;
import com.inductiveautomation.ignition.common.config.BoundValue;
import com.inductiveautomation.ignition.common.config.Property;
import com.inductiveautomation.ignition.common.model.values.QualityCode;
import com.inductiveautomation.ignition.common.tags.config.BasicTagConfiguration;
import com.inductiveautomation.ignition.common.tags.config.CollisionPolicy;
import com.inductiveautomation.ignition.common.tags.config.TagConfiguration;
import com.inductiveautomation.ignition.common.tags.config.TagConfigurationModel;
import com.inductiveautomation.ignition.common.tags.config.TagPropertyDirectory;
import com.inductiveautomation.ignition.common.tags.config.types.TagObjectType;
import com.inductiveautomation.ignition.common.tags.model.TagPath;
import com.inductiveautomation.ignition.common.tags.model.TagProvider;
import com.inductiveautomation.ignition.common.tags.paths.parser.TagPathParser;
import com.inductiveautomation.ignition.gateway.tags.model.GatewayTagManager;
import com.jg.ignition.mcp.common.PermissionRequirement;
import com.jg.ignition.mcp.common.ToolDefinition;
import com.jg.ignition.mcp.common.ToolExecutionResult;
import com.jg.ignition.mcp.gateway.ToolCallContext;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class TagDefinitionToolHandler implements ToolHandler {

    private static final long CALL_TIMEOUT_SECONDS = 30L;

    private final String toolName;
    private final ToolDefinition definition;

    public TagDefinitionToolHandler(String toolName) {
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
        if (toolName.endsWith(".read")) {
            return readDefinitions(arguments, context);
        }
        return writeDefinition(arguments, context);
    }

    private ToolExecutionResult readDefinitions(JsonNode arguments, ToolCallContext context) {
        if (arguments == null || !arguments.isObject()) {
            return ToolExecutionResult.error("Tag definition read requires an arguments object");
        }

        String providerArg = arguments.path("provider").asText("").trim();
        boolean recursive = arguments.path("recursive").asBoolean(false);
        boolean localPropsOnly = arguments.path("localPropsOnly").asBoolean(false);

        ArrayNode pathsNode = arguments.has("paths") && arguments.get("paths").isArray()
            ? (ArrayNode) arguments.get("paths")
            : context.objectMapper().createArrayNode();
        if (pathsNode.isEmpty()) {
            return ToolExecutionResult.error("Tag definition read requires at least one path");
        }

        GatewayTagManager tagManager = context.gatewayContext().getTagManager();
        Map<String, List<TagPath>> groupedPaths = new LinkedHashMap<>();
        for (JsonNode pathNode : pathsNode) {
            String rawPath = pathNode.asText("").trim();
            if (rawPath.isBlank()) {
                return ToolExecutionResult.error("Path entries cannot be empty");
            }
            if (!context.safetyPolicy().isTagReadAllowed(context.authContext().tokenName(), rawPath)) {
                return ToolExecutionResult.error(
                    "Definition read path blocked by allowlist: " + rawPath,
                    ProjectResourceService.errorBody("ALLOWLIST_BLOCKED", "Definition read path blocked by allowlist")
                );
            }
            TagPath path = parseTagPath(rawPath, providerArg);
            if (path == null || StringUtils.isBlank(path.getSource())) {
                return ToolExecutionResult.error(
                    "Path must include provider source (or supply provider argument): " + rawPath
                );
            }
            groupedPaths.computeIfAbsent(path.getSource(), ignored -> new ArrayList<>()).add(path);
        }

        ObjectNode result = context.objectMapper().createObjectNode();
        ArrayNode definitions = result.putArray("definitions");
        int matched = 0;
        for (Map.Entry<String, List<TagPath>> entry : groupedPaths.entrySet()) {
            TagProvider provider = tagManager.getTagProvider(entry.getKey());
            if (provider == null) {
                return ToolExecutionResult.error("Tag provider not found: " + entry.getKey());
            }

            List<TagConfigurationModel> configs;
            try {
                configs = provider.getTagConfigsAsync(entry.getValue(), recursive, localPropsOnly)
                    .get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            catch (Exception e) {
                return ToolExecutionResult.error(
                    "Failed reading tag definitions from provider " + entry.getKey() + ": " + e.getMessage()
                );
            }

            for (TagConfigurationModel config : configs) {
                definitions.add(serializeConfig(context, config, null));
                matched++;
            }
        }
        result.put("count", matched);
        result.put("recursive", recursive);
        result.put("localPropsOnly", localPropsOnly);
        return ToolExecutionResult.ok("Tag definition read completed", result);
    }

    private ToolExecutionResult writeDefinition(JsonNode arguments, ToolCallContext context) {
        if (arguments == null || !arguments.isObject()) {
            return ToolExecutionResult.error("Tag definition write requires an arguments object");
        }

        if (arguments.has("definitions") && arguments.get("definitions").isArray()) {
            ArrayNode definitions = (ArrayNode) arguments.get("definitions");
            if (definitions.isEmpty()) {
                return ToolExecutionResult.error("definitions cannot be empty");
            }
            if (definitions.size() > context.safetyPolicy().maxBatchWriteSize()) {
                return ToolExecutionResult.error(
                    "Definition batch exceeds maxBatchWriteSize=" + context.safetyPolicy().maxBatchWriteSize()
                );
            }
            ObjectNode batch = context.objectMapper().createObjectNode();
            ArrayNode results = batch.putArray("results");
            boolean anyError = false;
            for (JsonNode definitionNode : definitions) {
                ObjectNode single = definitionNode.deepCopy();
                if (!single.has("commit") && arguments.has("commit")) {
                    single.set("commit", arguments.get("commit"));
                }
                if (!single.has("provider") && arguments.has("provider")) {
                    single.set("provider", arguments.get("provider"));
                }
                if (!single.has("operation") && arguments.has("operation")) {
                    single.set("operation", arguments.get("operation"));
                }
                ToolExecutionResult result = writeDefinition(single, context);
                anyError |= result.isError();
                ObjectNode row = results.addObject();
                row.put("isError", result.isError());
                row.put("text", result.text());
                if (result.structuredContent() != null) {
                    row.set("structuredContent", result.structuredContent());
                }
            }
            batch.put("count", results.size());
            return anyError
                ? ToolExecutionResult.error("One or more tag definition writes failed", batch)
                : ToolExecutionResult.ok("Tag definition batch write completed", batch);
        }

        String pathText = arguments.path("path").asText("").trim();
        if (pathText.isBlank()) {
            return ToolExecutionResult.error("Tag definition write requires path");
        }
        if (!context.safetyPolicy().isTagWriteAllowed(context.authContext().tokenName(), pathText)) {
            context.auditLogger().logWriteAttempt(context.authContext().tokenName(), toolName, false, false, pathText);
            return ToolExecutionResult.error(
                "Definition write path blocked by allowlist: " + pathText,
                ProjectResourceService.errorBody("ALLOWLIST_BLOCKED", "Definition write path blocked by allowlist")
            );
        }

        String providerArg = arguments.path("provider").asText("").trim();
        TagPath path = parseTagPath(pathText, providerArg);
        if (path == null || StringUtils.isBlank(path.getSource())) {
            return ToolExecutionResult.error("Path must include provider source (or supply provider argument)");
        }

        String operation = arguments.path("operation").asText("upsert").trim().toLowerCase();
        boolean isCreate = "create".equals(operation);
        boolean isEdit = "edit".equals(operation);
        boolean isDelete = "delete".equals(operation);
        if (!isCreate && !isEdit && !isDelete && !"upsert".equals(operation)) {
            return ToolExecutionResult.error("operation must be one of create, edit, upsert, delete");
        }

        TagProvider provider = context.gatewayContext().getTagManager().getTagProvider(path.getSource());
        if (provider == null) {
            return ToolExecutionResult.error("Tag provider not found: " + path.getSource());
        }

        CollisionPolicy collisionPolicy = resolveCollisionPolicy(arguments.path("collisionPolicy").asText(""));
        boolean dryRun = context.safetyPolicy().isDryRun(arguments);
        if (isDelete) {
            if (dryRun) {
                ObjectNode result = context.objectMapper().createObjectNode();
                result.put("dryRun", true);
                result.put("operation", operation);
                result.put("path", path.toStringFull());
                result.put("provider", path.getSource());
                return ToolExecutionResult.ok("Tag definition delete dry-run plan generated", result);
            }
            List<QualityCode> deleteResults;
            try {
                deleteResults = provider.removeTagConfigsAsync(List.of(path))
                    .get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            catch (Exception e) {
                context.auditLogger().logWriteAttempt(context.authContext().tokenName(), toolName, true, false, pathText);
                return ToolExecutionResult.error("Definition delete failed: " + e.getMessage());
            }
            boolean allGood = deleteResults.stream().filter(Objects::nonNull).allMatch(QualityCode::isGood);
            context.auditLogger().logWriteAttempt(context.authContext().tokenName(), toolName, true, allGood, pathText);
            if (!allGood) {
                return ToolExecutionResult.error("Definition delete returned non-good quality result");
            }
            ObjectNode out = context.objectMapper().createObjectNode();
            out.put("deleted", true);
            out.put("operation", operation);
            out.put("path", path.toStringFull());
            out.put("provider", path.getSource());
            return ToolExecutionResult.ok("Tag definition delete completed", out);
        }

        TagConfiguration config = isCreate
            ? BasicTagConfiguration.createNew(path)
            : BasicTagConfiguration.createEdit(path);

        TagObjectType tagObjectType = resolveTagType(arguments.path("tagObjectType").asText(""));
        if (tagObjectType != null) {
            config.setType(tagObjectType);
        }

        try {
            applyProperties(config, arguments.path("properties"));
            applyBindings(config, arguments.path("bindings"));
            applyRemovals(config, arguments.path("removeProperties"));
            applyChildren(config, arguments.path("children"), providerArg);
        }
        catch (IllegalArgumentException e) {
            return ToolExecutionResult.error(e.getMessage());
        }

        if (dryRun) {
            ObjectNode result = context.objectMapper().createObjectNode();
            result.put("dryRun", true);
            result.put("operation", operation);
            result.put("path", path.toStringFull());
            result.put("provider", path.getSource());
            result.put("collisionPolicy", collisionPolicy.name());
            result.set("properties", arguments.path("properties").isObject()
                ? arguments.path("properties").deepCopy()
                : context.objectMapper().createObjectNode());
            result.set("bindings", arguments.path("bindings").isObject()
                ? arguments.path("bindings").deepCopy()
                : context.objectMapper().createObjectNode());
            result.set("children", arguments.path("children").isArray()
                ? arguments.path("children").deepCopy()
                : context.objectMapper().createArrayNode());
            return ToolExecutionResult.ok("Tag definition dry-run plan generated", result);
        }

        List<QualityCode> results;
        try {
            results = provider.saveTagConfigsAsync(List.of(config), collisionPolicy)
                .get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        catch (Exception e) {
            context.auditLogger().logWriteAttempt(context.authContext().tokenName(), toolName, true, false, pathText);
            return ToolExecutionResult.error("Definition write failed: " + e.getMessage());
        }

        boolean allGood = results.stream().filter(Objects::nonNull).allMatch(QualityCode::isGood);
        context.auditLogger().logWriteAttempt(context.authContext().tokenName(), toolName, true, allGood, pathText);
        if (!allGood) {
            return ToolExecutionResult.error("Definition write returned non-good quality result");
        }

        ObjectNode out = context.objectMapper().createObjectNode();
        out.put("updated", true);
        out.put("operation", operation);
        out.put("path", path.toStringFull());
        out.put("provider", path.getSource());
        ArrayNode qualities = out.putArray("qualities");
        for (QualityCode quality : results) {
            qualities.add(quality == null ? "Bad_Failure" : quality.getName());
        }
        return ToolExecutionResult.ok("Tag definition write completed", out);
    }

    private static void applyProperties(TagConfiguration config, JsonNode properties) {
        if (properties == null || !properties.isObject()) {
            return;
        }
        TagPropertyDirectory directory = TagPropertyDirectory.getInstance();
        properties.fields().forEachRemaining(entry -> {
            Property<?> property = directory.locate(entry.getKey());
            if (property == null) {
                throw new IllegalArgumentException("Unknown tag property: " + entry.getKey());
            }
            Object value = jsonToJava(entry.getValue());
            try {
                Object coerced = coerceValue(value, property.getType());
                config.getTagProperties().set((Property<Object>) property, coerced);
            }
            catch (Exception e) {
                throw new IllegalArgumentException("Invalid value for property " + entry.getKey() + ": " + e.getMessage());
            }
        });
    }

    private static void applyBindings(TagConfiguration config, JsonNode bindings) {
        if (bindings == null || !bindings.isObject()) {
            return;
        }
        TagPropertyDirectory directory = TagPropertyDirectory.getInstance();
        BoundPropertySet boundPropertySet = config.getTagProperties();
        bindings.fields().forEachRemaining(entry -> {
            Property<?> property = directory.locate(entry.getKey());
            if (property == null) {
                throw new IllegalArgumentException("Unknown tag property for binding: " + entry.getKey());
            }
            JsonNode bindingNode = entry.getValue();
            if (bindingNode == null || bindingNode.isNull()) {
                boundPropertySet.remove(property);
                return;
            }

            String bindType;
            String binding;
            if (bindingNode.isTextual()) {
                bindType = "expr";
                binding = bindingNode.asText();
            }
            else if (bindingNode.isObject()) {
                bindType = bindingNode.path("bindType").asText("expr");
                binding = bindingNode.path("binding").asText("");
            }
            else {
                throw new IllegalArgumentException("Binding for property " + entry.getKey() + " must be string or object");
            }
            if (binding.isBlank()) {
                throw new IllegalArgumentException("Binding value for property " + entry.getKey() + " cannot be blank");
            }
            boundPropertySet.setBoundValue(property, new BoundValue(bindType, binding));
        });
    }

    private static void applyRemovals(TagConfiguration config, JsonNode removals) {
        if (removals == null || !removals.isArray()) {
            return;
        }
        TagPropertyDirectory directory = TagPropertyDirectory.getInstance();
        for (JsonNode node : removals) {
            String key = node.asText("").trim();
            if (key.isBlank()) {
                continue;
            }
            Property<?> property = directory.locate(key);
            if (property == null) {
                throw new IllegalArgumentException("Unknown tag property in removeProperties: " + key);
            }
            config.getTagProperties().remove(property);
        }
    }

    private static void applyChildren(TagConfiguration parent, JsonNode children, String providerArg) {
        if (children == null || !children.isArray()) {
            return;
        }
        for (JsonNode childNode : children) {
            if (childNode == null || !childNode.isObject()) {
                throw new IllegalArgumentException("Child definitions must be objects");
            }
            String rawPath = childNode.path("path").asText("").trim();
            String name = childNode.path("name").asText("").trim();
            TagPath childPath;
            if (StringUtils.isNotBlank(rawPath)) {
                childPath = parseTagPath(rawPath, providerArg);
            }
            else if (StringUtils.isNotBlank(name)) {
                childPath = parent.getPath().getChildPath(name);
            }
            else {
                throw new IllegalArgumentException("Child definition requires path or name");
            }
            if (childPath == null) {
                throw new IllegalArgumentException("Invalid child tag path: " + rawPath);
            }
            TagConfiguration child = BasicTagConfiguration.createNew(childPath);
            TagObjectType childType = resolveTagType(childNode.path("tagObjectType").asText(""));
            if (childType != null) {
                child.setType(childType);
            }
            applyProperties(child, childNode.path("properties"));
            applyBindings(child, childNode.path("bindings"));
            applyRemovals(child, childNode.path("removeProperties"));
            applyChildren(child, childNode.path("children"), providerArg);
            parent.addChild(child);
        }
    }

    private static ObjectNode serializeConfig(ToolCallContext context, TagConfigurationModel config, TagPath parentPath) {
        ObjectNode node = context.objectMapper().createObjectNode();
        TagPath normalizedPath = normalizedConfigPath(config, parentPath);
        node.put("name", StringUtils.defaultString(config.getName()));
        node.put("path", normalizedPath == null ? "" : normalizedPath.toStringFull());
        node.put("type", config.getType() == null ? "" : config.getType().name());
        node.put("editable", config.isEditable());
        node.put("inherited", config.isInherited());
        node.put("udtMember", config.isUdtMember());

        node.set("properties", serializePropertySet(context, config));
        node.set("localProperties", serializePropertySet(context, config.getLocalConfiguration()));

        ArrayNode children = node.putArray("children");
        for (TagConfigurationModel child : config.getChildren()) {
            children.add(serializeConfig(context, child, normalizedPath));
        }
        return node;
    }

    private static TagPath normalizedConfigPath(TagConfigurationModel config, TagPath parentPath) {
        if (config == null) {
            return null;
        }
        TagPath path = config.getPath();
        if (path != null && StringUtils.isNotBlank(path.getSource())) {
            return path;
        }
        if (parentPath != null && StringUtils.isNotBlank(config.getName())) {
            return parentPath.getChildPath(config.getName());
        }
        return path;
    }

    private static ObjectNode serializePropertySet(ToolCallContext context, TagConfiguration configuration) {
        if (configuration == null) {
            return context.objectMapper().createObjectNode();
        }
        return serializeBoundPropertySet(context, configuration.getTagProperties());
    }

    private static ObjectNode serializeBoundPropertySet(ToolCallContext context, BoundPropertySet propertySet) {
        ObjectNode properties = context.objectMapper().createObjectNode();
        if (propertySet == null) {
            return properties;
        }

        for (Property<?> property : propertySet.getProperties()) {
            Object value = propertySet.get(property);
            BoundValue bound = propertySet.getBoundValue(property);

            if (bound != null) {
                ObjectNode boundNode = properties.putObject(property.getName());
                boundNode.put("bindType", bound.getBindType());
                boundNode.put("binding", bound.getBinding());
                if (value != null) {
                    boundNode.set("value", safeValueTree(context, value));
                }
            }
            else {
                properties.set(property.getName(), safeValueTree(context, value));
            }
        }
        return properties;
    }

    private static JsonNode safeValueTree(ToolCallContext context, Object value) {
        if (value == null) {
            return NullNode.instance;
        }
        if (value instanceof Date date) {
            return context.objectMapper().valueToTree(date.getTime());
        }
        try {
            return context.objectMapper().valueToTree(value);
        }
        catch (Exception ignored) {
            return context.objectMapper().valueToTree(String.valueOf(value));
        }
    }

    private static TagPath parseTagPath(String rawPath, String provider) {
        TagPath parsed = TagPathParser.parseSafe(rawPath);
        if (parsed != null && StringUtils.isNotBlank(parsed.getSource())) {
            return parsed;
        }
        if (StringUtils.isNotBlank(provider)) {
            return TagPathParser.parseSafe(rawPath, provider);
        }
        return parsed;
    }

    private static CollisionPolicy resolveCollisionPolicy(String rawValue) {
        if (StringUtils.isBlank(rawValue)) {
            return CollisionPolicy.MergeOverwrite;
        }
        CollisionPolicy policy = CollisionPolicy.fromString(rawValue.trim());
        return policy == null ? CollisionPolicy.MergeOverwrite : policy;
    }

    private static TagObjectType resolveTagType(String rawValue) {
        if (StringUtils.isBlank(rawValue)) {
            return null;
        }
        return TagObjectType.fromString(rawValue.trim());
    }

    private static Object coerceValue(Object value, Class<?> targetType) {
        if (value == null) {
            return null;
        }
        if (targetType == null || targetType.isInstance(value)) {
            return value;
        }
        if (targetType.isEnum()) {
            return TypeUtilities.toEnum((Class<? extends Enum>) targetType, String.valueOf(value));
        }
        return TypeUtilities.coerce(value, targetType);
    }

    private static Object jsonToJava(JsonNode node) {
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
            case "ignition.tags.definition.read" -> "Read tag configuration definitions for Designer-visible tags";
            case "ignition.tags.definition.write" -> "Create or edit tag definitions (allowlist + dry-run + commit)";
            default -> "Tag definition tool";
        };
    }

    private static JsonNode inputSchema(String toolName) {
        ObjectNode schema = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        if (toolName.endsWith(".read")) {
            ObjectNode paths = properties.putObject("paths");
            paths.put("type", "array");
            paths.putObject("items").put("type", "string");
            properties.putObject("provider").put("type", "string");
            properties.putObject("recursive").put("type", "boolean");
            properties.putObject("localPropsOnly").put("type", "boolean");
            schema.putArray("required").add("paths");
        }
        else {
            properties.putObject("operation").put("type", "string");
            properties.putObject("path").put("type", "string");
            properties.putObject("provider").put("type", "string");
            properties.putObject("tagObjectType").put("type", "string");
            properties.putObject("collisionPolicy").put("type", "string");
            properties.putObject("properties").put("type", "object");
            properties.putObject("bindings").put("type", "object");
            properties.putObject("children").put("type", "array");
            properties.putObject("definitions").put("type", "array");
            ObjectNode removeProperties = properties.putObject("removeProperties");
            removeProperties.put("type", "array");
            removeProperties.putObject("items").put("type", "string");
            properties.putObject("commit").put("type", "boolean");
            schema.putArray("required").add("path");
        }
        return schema;
    }
}
