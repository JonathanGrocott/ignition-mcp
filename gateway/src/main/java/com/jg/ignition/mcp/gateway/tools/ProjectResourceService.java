package com.jg.ignition.mcp.gateway.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inductiveautomation.ignition.common.ImmutableBytes;
import com.inductiveautomation.ignition.common.model.ApplicationScope;
import com.inductiveautomation.ignition.common.resourcecollection.ChangeOperation;
import com.inductiveautomation.ignition.common.resourcecollection.Resource;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceBuilder;
import com.inductiveautomation.ignition.common.resourcecollection.ResourcePath;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceSignature;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.inductiveautomation.ignition.gateway.project.ProjectManager;
import com.jg.ignition.mcp.common.ToolExecutionResult;
import com.jg.ignition.mcp.gateway.ToolCallContext;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class ProjectResourceService {

    private static final long PUSH_TIMEOUT_SECONDS = 30L;
    private static final int MAX_TEXT_DATA_CHARS = 128_000;

    private ProjectResourceService() {
    }

    static ProjectManager projectManager(ToolCallContext context) {
        return context.gatewayContext().getProjectManager();
    }

    static ResourcePath resourcePath(String moduleId, String typeId, String path) {
        return new ResourcePath(new ResourceType(moduleId, typeId), normalizeResourcePath(path));
    }

    static String normalizeResourcePath(String path) {
        String normalized = StringUtils.defaultString(path).trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    static Optional<Resource> find(ProjectManager projectManager, String project, ResourcePath path) {
        return projectManager.getResource(project, path);
    }

    static ToolExecutionResult requireMutable(ProjectManager projectManager, String project) {
        if (projectManager == null) {
            return ToolExecutionResult.error("Project manager is unavailable", errorBody("SDK_UNAVAILABLE", "Project manager is unavailable"));
        }
        if (StringUtils.isBlank(project)) {
            return ToolExecutionResult.error("Project is required", errorBody("VALIDATION_ERROR", "Project is required"));
        }
        if (!projectManager.getNames().contains(project)) {
            return ToolExecutionResult.error("Project not found: " + project, errorBody("NOT_FOUND", "Project not found"));
        }
        if (!projectManager.isMutable(project)) {
            return ToolExecutionResult.error("Project is immutable: " + project, errorBody("IMMUTABLE_PROJECT", "Project is immutable"));
        }
        return null;
    }

    static Resource buildResource(
        String project,
        ResourcePath resourcePath,
        int version,
        String documentation,
        Consumer<ResourceBuilder> serializer
    ) {
        ResourceBuilder builder = Resource.newBuilder()
            .setResourceCollectionName(project)
            .setResourcePath(resourcePath)
            .setApplicationScope(ApplicationScope.ALL)
            .setVersion(version)
            .setDocumentation(StringUtils.defaultString(documentation));
        serializer.accept(builder);
        return builder.build();
    }

    static ToolExecutionResult pushUpsert(
        ToolCallContext context,
        String project,
        ResourcePath path,
        Resource resource,
        boolean createOnly,
        boolean editOnly
    ) {
        ProjectManager projectManager = projectManager(context);
        Optional<Resource> existing = find(projectManager, project, path);
        if (createOnly && existing.isPresent()) {
            return ToolExecutionResult.error("Resource already exists: " + path, errorBody("CONFLICT", "Resource already exists"));
        }
        if (editOnly && existing.isEmpty()) {
            return ToolExecutionResult.error("Resource not found: " + path, errorBody("NOT_FOUND", "Resource not found"));
        }

        ChangeOperation change = existing.isPresent()
            ? ChangeOperation.newModifyOp(resource, existing.get().getResourceSignature())
            : ChangeOperation.newCreateOp(resource);
        return pushChanges(context, project, List.of(change), "Resource write completed");
    }

    static ToolExecutionResult pushUpserts(
        ToolCallContext context,
        String project,
        List<Resource> resources,
        boolean createOnly,
        boolean editOnly
    ) {
        ProjectManager projectManager = projectManager(context);
        List<ChangeOperation> changes = new ArrayList<>();
        for (Resource resource : resources) {
            ResourcePath path = resource.getResourcePath();
            Optional<Resource> existing = find(projectManager, project, path);
            if (createOnly && existing.isPresent()) {
                return ToolExecutionResult.error("Resource already exists: " + path, errorBody("CONFLICT", "Resource already exists"));
            }
            if (editOnly && existing.isEmpty()) {
                return ToolExecutionResult.error("Resource not found: " + path, errorBody("NOT_FOUND", "Resource not found"));
            }
            changes.add(existing.isPresent()
                ? ChangeOperation.newModifyOp(resource, existing.get().getResourceSignature())
                : ChangeOperation.newCreateOp(resource)
            );
        }
        return pushChanges(context, project, changes, "Resource import completed");
    }

    static ToolExecutionResult pushDelete(ToolCallContext context, String project, ResourcePath path) {
        ProjectManager projectManager = projectManager(context);
        Optional<Resource> existing = find(projectManager, project, path);
        if (existing.isEmpty()) {
            return ToolExecutionResult.error("Resource not found: " + path, errorBody("NOT_FOUND", "Resource not found"));
        }
        ResourceSignature signature = existing.get().getResourceSignature();
        return pushChanges(context, project, List.of(ChangeOperation.newDeleteOp(signature)), "Resource delete completed");
    }

    private static ToolExecutionResult pushChanges(
        ToolCallContext context,
        String project,
        List<ChangeOperation> changes,
        String successText
    ) {
        try {
            projectManager(context).push(changes).get(PUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        catch (Exception e) {
            return ToolExecutionResult.error(
                "Project resource push failed: " + e.getMessage(),
                errorBody("PUSH_FAILED", e.getMessage())
            );
        }
        ObjectNode out = context.objectMapper().createObjectNode();
        out.put("project", project);
        out.put("committed", true);
        out.put("changeCount", changes.size());
        return ToolExecutionResult.ok(successText, out);
    }

    static ObjectNode serializeResource(
        ToolCallContext context,
        Resource resource,
        boolean includeData
    ) {
        ObjectMapper mapper = context.objectMapper();
        ObjectNode row = mapper.createObjectNode();
        ResourcePath path = resource.getResourcePath();
        row.put("project", StringUtils.defaultString(resource.getCollectionName()));
        row.put("definingProject", StringUtils.defaultString(resource.getDefiningCollectionName()));
        row.set("definingProjects", mapper.valueToTree(resource.getDefiningCollectionNames()));
        row.put("moduleId", path == null ? "" : StringUtils.defaultString(path.getModuleId()));
        row.put("resourceType", path == null ? "" : StringUtils.defaultString(path.getType()));
        row.put("path", path == null || path.getPath() == null ? "" : path.getPath().toString());
        row.put("name", path == null ? "" : StringUtils.defaultString(path.getName()));
        row.put("folder", resource.isFolder());
        row.put("moduleFolder", resource.isModuleFolder());
        row.put("resourceTypeFolder", resource.isResourceTypeFolder());
        row.put("unary", resource.isUnary());
        row.put("restricted", resource.isRestricted());
        row.put("overridable", resource.isOverridable());
        row.put("applicationScope", ApplicationScope.toCode(resource.getApplicationScope()));
        row.put("version", resource.getVersion());
        row.put("documentation", StringUtils.defaultString(resource.getDocumentation()));

        ObjectNode attributes = row.putObject("attributes");
        for (Map.Entry<String, com.inductiveautomation.ignition.common.gson.JsonElement> entry : resource.getAttributes().entrySet()) {
            attributes.set(entry.getKey(), parsePossiblyJson(mapper, String.valueOf(entry.getValue())));
        }

        List<String> dataKeys = new ArrayList<>(resource.getDataKeys());
        dataKeys.sort(String.CASE_INSENSITIVE_ORDER);
        row.set("dataKeys", mapper.valueToTree(dataKeys));
        if (includeData) {
            ObjectNode data = row.putObject("data");
            for (String key : dataKeys) {
                resource.getData(key).ifPresent(bytes -> data.set(key, serializeData(mapper, bytes)));
            }
        }
        return row;
    }

    static ArrayNode sortedResources(ToolCallContext context, Set<Resource> resources, boolean includeData) {
        ArrayNode rows = context.objectMapper().createArrayNode();
        resources.stream()
            .sorted(Comparator.comparing(resource -> resource.getResourcePath().toString(), String.CASE_INSENSITIVE_ORDER))
            .forEach(resource -> rows.add(serializeResource(context, resource, includeData)));
        return rows;
    }

    static ObjectNode errorBody(String code, String message) {
        return ToolExecutionResult.errorBody(code, StringUtils.defaultString(message));
    }

    static Resource resourceFromSerialized(ToolCallContext context, String targetProject, JsonNode node) {
        String moduleId = node.path("moduleId").asText("ignition").trim();
        String resourceType = node.path("resourceType").asText("").trim();
        String pathText = normalizeResourcePath(node.path("path").asText(""));
        ResourceBuilder builder = Resource.newBuilder()
            .setResourceCollectionName(targetProject)
            .setResourcePath(resourcePath(moduleId, resourceType, pathText))
            .setApplicationScope(node.path("applicationScope").asInt(ApplicationScope.ALL))
            .setVersion(Math.max(1, node.path("version").asInt(1)))
            .setDocumentation(node.path("documentation").asText(""));
        JsonNode data = node.path("data");
        if (data.isObject()) {
            data.fields().forEachRemaining(entry -> builder.putData(entry.getKey(), dataBytes(context, entry.getValue())));
        }
        return builder.build();
    }

    private static ImmutableBytes dataBytes(ToolCallContext context, JsonNode dataNode) {
        String encoding = dataNode.path("encoding").asText("utf-8");
        JsonNode value = dataNode.path("value");
        if ("base64".equalsIgnoreCase(encoding)) {
            return ImmutableBytes.fromBase64String(Base64.getDecoder(), value.asText(""));
        }
        if (value.isTextual()) {
            return ImmutableBytes.ofString(value.asText());
        }
        try {
            return ImmutableBytes.ofString(context.objectMapper().writeValueAsString(value));
        }
        catch (Exception e) {
            return ImmutableBytes.ofString(value.toString());
        }
    }

    private static JsonNode serializeData(ObjectMapper mapper, ImmutableBytes bytes) {
        ObjectNode node = mapper.createObjectNode();
        node.put("byteLength", bytes.length());
        String asText = bytes.getBytesAsString(StandardCharsets.UTF_8);
        if (isMostlyText(asText) && asText.length() <= MAX_TEXT_DATA_CHARS) {
            node.put("encoding", "utf-8");
            node.set("value", parsePossiblyJson(mapper, asText));
        }
        else {
            node.put("encoding", "base64");
            node.put("value", Base64.getEncoder().encodeToString(bytes.getBytes()));
        }
        return node;
    }

    private static JsonNode parsePossiblyJson(ObjectMapper mapper, String value) {
        if (StringUtils.isBlank(value)) {
            return mapper.valueToTree(StringUtils.defaultString(value));
        }
        try {
            return mapper.readTree(value);
        }
        catch (Exception ignored) {
            return mapper.valueToTree(value);
        }
    }

    private static boolean isMostlyText(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isISOControl(ch) && ch != '\n' && ch != '\r' && ch != '\t') {
                return false;
            }
        }
        return true;
    }
}
