package com.jg.ignition.mcp.gateway.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inductiveautomation.ignition.common.resourcecollection.Resource;
import com.inductiveautomation.ignition.common.resourcecollection.ResourcePath;
import com.inductiveautomation.ignition.common.resourcecollection.RuntimeResourceCollection;
import com.inductiveautomation.ignition.gateway.project.ProjectManager;
import com.jg.ignition.mcp.common.PermissionRequirement;
import com.jg.ignition.mcp.common.ToolDefinition;
import com.jg.ignition.mcp.common.ToolExecutionResult;
import com.jg.ignition.mcp.gateway.ToolCallContext;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class ProjectResourceToolHandler implements ToolHandler {

    private final String toolName;
    private final ToolDefinition definition;

    public ProjectResourceToolHandler(String toolName) {
        this.toolName = toolName;
        this.definition = new ToolDefinition(
            toolName,
            description(toolName),
            PermissionRequirement.READ,
            inputSchema(toolName),
            false
        );
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolExecutionResult execute(JsonNode arguments, ToolCallContext context) {
        return switch (toolName) {
            case "ignition.projects.resource.list" -> listResources(arguments, context);
            case "ignition.projects.resource.read" -> readResource(arguments, context);
            case "ignition.projects.resource.export" -> exportResources(arguments, context);
            default -> ToolExecutionResult.error("Unsupported project resource tool: " + toolName);
        };
    }

    private ToolExecutionResult listResources(JsonNode arguments, ToolCallContext context) {
        ProjectManager projectManager = ProjectResourceService.projectManager(context);
        if (projectManager == null) {
            return ToolExecutionResult.error("Project manager is unavailable");
        }

        String projectFilter = arguments == null ? "" : arguments.path("project").asText("").trim();
        String moduleFilter = arguments == null ? "" : arguments.path("moduleId").asText("").trim();
        String typeFilter = arguments == null ? "" : arguments.path("resourceType").asText("").trim();
        String pathPrefix = arguments == null ? "" : ProjectResourceService.normalizeResourcePath(arguments.path("pathPrefix").asText(""));
        boolean includeFolders = arguments != null && arguments.path("includeFolders").asBoolean(false);
        boolean includeData = arguments != null && arguments.path("includeData").asBoolean(false);

        try {
            List<String> projectNames = new ArrayList<>(projectManager.getNames());
            projectNames.sort(String.CASE_INSENSITIVE_ORDER);
            if (StringUtils.isNotBlank(projectFilter) && !projectNames.contains(projectFilter)) {
                return ToolExecutionResult.error("Project not found: " + projectFilter);
            }

            List<Resource> resources = new ArrayList<>();
            for (String project : projectNames) {
                if (StringUtils.isNotBlank(projectFilter) && !projectFilter.equals(project)) {
                    continue;
                }
                Optional<RuntimeResourceCollection> collection = projectManager.find(project);
                if (collection.isEmpty()) {
                    continue;
                }
                for (Resource resource : collection.get().getAllResources().values()) {
                    if (!includeFolders && resource.isFolder()) {
                        continue;
                    }
                    if (!matchesFilters(resource, moduleFilter, typeFilter, pathPrefix)) {
                        continue;
                    }
                    ResourcePath path = resource.getResourcePath();
                    if (!context.safetyPolicy().isProjectResourceReadAllowed(
                        context.authContext().tokenName(),
                        project,
                        path == null ? "" : path.getModuleId(),
                        path == null ? "" : path.getType(),
                        path == null || path.getPath() == null ? "" : path.getPath().toString()
                    )) {
                        continue;
                    }
                    resources.add(resource);
                }
            }

            resources.sort(Comparator.comparing(ProjectResourceToolHandler::sortKey, String.CASE_INSENSITIVE_ORDER));
            ObjectNode out = context.objectMapper().createObjectNode();
            ArrayNode rows = out.putArray("resources");
            for (Resource resource : resources) {
                rows.add(ProjectResourceService.serializeResource(context, resource, includeData));
            }
            out.put("count", rows.size());
            if (StringUtils.isNotBlank(projectFilter)) {
                out.put("project", projectFilter);
            }
            return ToolExecutionResult.ok("Listed " + rows.size() + " project resource(s)", out);
        }
        catch (Exception e) {
            return ToolExecutionResult.error("Failed to list project resources: " + e.getMessage());
        }
    }

    private ToolExecutionResult readResource(JsonNode arguments, ToolCallContext context) {
        if (arguments == null || !arguments.isObject()) {
            return ToolExecutionResult.error("Project resource read requires an arguments object");
        }
        String project = arguments.path("project").asText("").trim();
        String moduleId = arguments.path("moduleId").asText("ignition").trim();
        String resourceType = arguments.path("resourceType").asText("").trim();
        String pathText = ProjectResourceService.normalizeResourcePath(arguments.path("path").asText(""));
        boolean includeData = arguments.path("includeData").asBoolean(true);
        if (project.isBlank()) {
            return ToolExecutionResult.error("Project resource read requires project");
        }
        if (resourceType.isBlank()) {
            return ToolExecutionResult.error("Project resource read requires resourceType");
        }
        if (!context.safetyPolicy().isProjectResourceReadAllowed(
            context.authContext().tokenName(),
            project,
            moduleId,
            resourceType,
            pathText
        )) {
            return ToolExecutionResult.error(
                "Project resource read blocked by allowlist: " + project + "/" + moduleId + "/" + resourceType + "/" + pathText,
                ProjectResourceService.errorBody("ALLOWLIST_BLOCKED", "Project resource read blocked by allowlist")
            );
        }

        ProjectManager projectManager = ProjectResourceService.projectManager(context);
        if (projectManager == null) {
            return ToolExecutionResult.error("Project manager is unavailable");
        }
        ResourcePath resourcePath = ProjectResourceService.resourcePath(moduleId, resourceType, pathText);
        Optional<Resource> resource = ProjectResourceService.find(projectManager, project, resourcePath);
        if (resource.isEmpty()) {
            return ToolExecutionResult.error("Project resource not found: " + resourcePath);
        }

        ObjectNode out = context.objectMapper().createObjectNode();
        out.set("resource", ProjectResourceService.serializeResource(context, resource.get(), includeData));
        return ToolExecutionResult.ok("Project resource read completed", out);
    }

    private ToolExecutionResult exportResources(JsonNode arguments, ToolCallContext context) {
        if (arguments == null || !arguments.isObject()) {
            return ToolExecutionResult.error("Project resource export requires an arguments object");
        }
        ProjectManager projectManager = ProjectResourceService.projectManager(context);
        if (projectManager == null) {
            return ToolExecutionResult.error("Project manager is unavailable");
        }

        String project = arguments.path("project").asText("").trim();
        String moduleFilter = arguments.path("moduleId").asText("").trim();
        String typeFilter = arguments.path("resourceType").asText("").trim();
        String pathPrefix = ProjectResourceService.normalizeResourcePath(arguments.path("pathPrefix").asText(""));
        boolean includeFolders = arguments.path("includeFolders").asBoolean(false);
        int maxResources = Math.max(1, Math.min(arguments.path("maxResources").asInt(500), 5000));
        if (project.isBlank()) {
            return ToolExecutionResult.error("Project resource export requires project");
        }
        if (!projectManager.getNames().contains(project)) {
            return ToolExecutionResult.error("Project not found: " + project);
        }

        Optional<RuntimeResourceCollection> collection = projectManager.find(project);
        if (collection.isEmpty()) {
            return ToolExecutionResult.error("Project not found: " + project);
        }

        List<Resource> resources = new ArrayList<>();
        int matchedBeforeLimit = 0;
        for (Resource resource : collection.get().getAllResources().values()) {
            if (!includeFolders && resource.isFolder()) {
                continue;
            }
            if (!matchesFilters(resource, moduleFilter, typeFilter, pathPrefix)) {
                continue;
            }
            ResourcePath path = resource.getResourcePath();
            if (!context.safetyPolicy().isProjectResourceReadAllowed(
                context.authContext().tokenName(),
                project,
                path == null ? "" : path.getModuleId(),
                path == null ? "" : path.getType(),
                path == null || path.getPath() == null ? "" : path.getPath().toString()
            )) {
                continue;
            }
            matchedBeforeLimit++;
            if (resources.size() < maxResources) {
                resources.add(resource);
            }
        }
        resources.sort(Comparator.comparing(ProjectResourceToolHandler::sortKey, String.CASE_INSENSITIVE_ORDER));

        ObjectNode bundle = context.objectMapper().createObjectNode();
        bundle.put("format", "ignition-mcp.project-resource-bundle.v1");
        bundle.put("exportedAt", Instant.now().toString());
        bundle.put("project", project);
        bundle.put("resourceCount", resources.size());
        bundle.put("matchedBeforeLimit", matchedBeforeLimit);
        bundle.put("truncated", matchedBeforeLimit > resources.size());
        ObjectNode filters = bundle.putObject("filters");
        filters.put("moduleId", moduleFilter);
        filters.put("resourceType", typeFilter);
        filters.put("pathPrefix", pathPrefix);
        filters.put("includeFolders", includeFolders);
        ArrayNode exported = bundle.putArray("resources");
        for (Resource resource : resources) {
            exported.add(ProjectResourceService.serializeResource(context, resource, true));
        }

        ObjectNode out = context.objectMapper().createObjectNode();
        out.set("bundle", bundle);
        return ToolExecutionResult.ok("Exported " + resources.size() + " project resource(s)", out);
    }

    private static boolean matchesFilters(Resource resource, String moduleFilter, String typeFilter, String pathPrefix) {
        ResourcePath path = resource.getResourcePath();
        if (path == null) {
            return false;
        }
        if (StringUtils.isNotBlank(moduleFilter) && !moduleFilter.equals(path.getModuleId())) {
            return false;
        }
        if (StringUtils.isNotBlank(typeFilter) && !typeFilter.equals(path.getType())) {
            return false;
        }
        String resourcePath = path.getPath() == null ? "" : path.getPath().toString();
        return StringUtils.isBlank(pathPrefix) || resourcePath.startsWith(pathPrefix);
    }

    private static String sortKey(Resource resource) {
        ResourcePath path = resource.getResourcePath();
        return resource.getCollectionName() + "/" + (path == null ? "" : path.toString());
    }

    private static String description(String name) {
        return switch (name) {
            case "ignition.projects.resource.list" -> "List project resources by project/module/type/path";
            case "ignition.projects.resource.read" -> "Read one project resource and optionally include data files";
            case "ignition.projects.resource.export" -> "Export an allowlisted project resource bundle for review or backup";
            default -> "Project resource tool";
        };
    }

    private static JsonNode inputSchema(String name) {
        ObjectNode schema = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("project").put("type", "string");
        props.putObject("moduleId").put("type", "string");
        props.putObject("resourceType").put("type", "string");
        props.putObject("path").put("type", "string");
        props.putObject("pathPrefix").put("type", "string");
        props.putObject("includeFolders").put("type", "boolean");
        props.putObject("includeData").put("type", "boolean");
        props.putObject("maxResources").put("type", "integer");
        if (name.endsWith(".read")) {
            schema.putArray("required").add("project").add("resourceType").add("path");
        }
        else if (name.endsWith(".export")) {
            schema.putArray("required").add("project");
        }
        return schema;
    }
}
