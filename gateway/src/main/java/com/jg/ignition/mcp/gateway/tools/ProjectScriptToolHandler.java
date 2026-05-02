package com.jg.ignition.mcp.gateway.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inductiveautomation.ignition.common.gateway.script.UpdateScriptConfig;
import com.inductiveautomation.ignition.common.resourcecollection.Resource;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceBuilder;
import com.inductiveautomation.ignition.common.resourcecollection.ResourcePath;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.inductiveautomation.ignition.common.script.ScheduledScript;
import com.inductiveautomation.ignition.common.script.ScriptConfig;
import com.inductiveautomation.ignition.common.script.ScriptLibrary;
import com.inductiveautomation.ignition.common.script.TagChangeScript;
import com.inductiveautomation.ignition.common.script.TimerKey;
import com.inductiveautomation.ignition.common.script.message.MessageHandlerKey;
import com.inductiveautomation.ignition.common.util.ResourceUtil;
import com.inductiveautomation.ignition.gateway.project.ProjectManager;
import com.jg.ignition.mcp.common.PermissionRequirement;
import com.jg.ignition.mcp.common.ToolDefinition;
import com.jg.ignition.mcp.common.ToolExecutionResult;
import com.jg.ignition.mcp.gateway.ToolCallContext;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

public class ProjectScriptToolHandler implements ToolHandler {

    private static final int DEFAULT_RESOURCE_VERSION = 1;

    private final String toolName;
    private final ToolDefinition definition;

    public ProjectScriptToolHandler(String toolName) {
        this.toolName = toolName;
        this.definition = new ToolDefinition(
            toolName,
            description(toolName),
            isMutating(toolName)
                ? PermissionRequirement.WRITE
                : PermissionRequirement.READ,
            inputSchema(toolName),
            isMutating(toolName)
        );
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolExecutionResult execute(JsonNode arguments, ToolCallContext context) {
        return switch (toolName) {
            case "ignition.scripts.project.list" -> listScripts(arguments, context);
            case "ignition.scripts.project.read" -> readScript(arguments, context);
            case "ignition.scripts.project.write" -> writeScript(arguments, context);
            case "ignition.scripts.project.delete" -> deleteScript(arguments, context);
            case "ignition.scripts.project.import" -> importScripts(arguments, context);
            default -> ToolExecutionResult.error("Unsupported project script tool: " + toolName);
        };
    }

    private ToolExecutionResult listScripts(JsonNode arguments, ToolCallContext context) {
        ProjectManager projectManager = ProjectResourceService.projectManager(context);
        if (projectManager == null) {
            return ToolExecutionResult.error("Project manager is unavailable");
        }
        String projectFilter = arguments == null ? "" : arguments.path("project").asText("").trim();
        String scriptTypeFilter = arguments == null ? "" : arguments.path("scriptType").asText("").trim();
        boolean includeCode = arguments != null && arguments.path("includeCode").asBoolean(false);

        try {
            List<String> projects = new ArrayList<>(projectManager.getNames());
            projects.sort(String.CASE_INSENSITIVE_ORDER);
            if (StringUtils.isNotBlank(projectFilter) && !projects.contains(projectFilter)) {
                return ToolExecutionResult.error("Project not found: " + projectFilter);
            }

            List<Resource> resources = new ArrayList<>();
            for (String project : projects) {
                if (StringUtils.isNotBlank(projectFilter) && !projectFilter.equals(project)) {
                    continue;
                }
                projectManager.find(project).ifPresent(collection ->
                    collection.getAllResources().values().stream()
                        .filter(resource -> isScriptResource(resource, scriptTypeFilter))
                        .forEach(resources::add)
                );
            }

            resources.sort(Comparator.comparing(resource -> resource.getCollectionName() + "/" + resource.getResourcePath()));
            ObjectNode out = context.objectMapper().createObjectNode();
            ArrayNode rows = out.putArray("scripts");
            for (Resource resource : resources) {
                rows.add(serializeScript(context, resource, includeCode));
            }
            out.put("count", rows.size());
            return ToolExecutionResult.ok("Listed " + rows.size() + " project script resource(s)", out);
        }
        catch (Exception e) {
            return ToolExecutionResult.error("Failed to list project scripts: " + e.getMessage());
        }
    }

    private ToolExecutionResult readScript(JsonNode arguments, ToolCallContext context) {
        if (arguments == null || !arguments.isObject()) {
            return ToolExecutionResult.error("Project script read requires an arguments object");
        }
        String project = arguments.path("project").asText("").trim();
        String scriptType = arguments.path("scriptType").asText("").trim();
        String pathText = scriptPath(arguments);
        if (project.isBlank()) {
            return ToolExecutionResult.error("Project script read requires project");
        }
        ResourcePath path = resolveScriptResourcePath(scriptType, pathText);
        if (path == null) {
            return ToolExecutionResult.error("Unsupported scriptType: " + scriptType);
        }

        Optional<Resource> resource = ProjectResourceService.find(ProjectResourceService.projectManager(context), project, path);
        if (resource.isEmpty()) {
            return ToolExecutionResult.error("Project script not found: " + path);
        }

        ObjectNode out = context.objectMapper().createObjectNode();
        out.set("script", serializeScript(context, resource.get(), true));
        return ToolExecutionResult.ok("Project script read completed", out);
    }

    private ToolExecutionResult writeScript(JsonNode arguments, ToolCallContext context) {
        if (arguments == null || !arguments.isObject()) {
            return ToolExecutionResult.error("Project script write requires an arguments object");
        }
        String project = arguments.path("project").asText("").trim();
        String scriptType = arguments.path("scriptType").asText("").trim();
        String pathText = scriptPath(arguments);
        String code = arguments.path("code").asText("");
        String operation = arguments.path("operation").asText("upsert").trim().toLowerCase();
        boolean createOnly = "create".equals(operation);
        boolean editOnly = "edit".equals(operation);
        if (!createOnly && !editOnly && !"upsert".equals(operation)) {
            return ToolExecutionResult.error("operation must be one of create, edit, upsert");
        }

        ProjectManager projectManager = ProjectResourceService.projectManager(context);
        ToolExecutionResult mutableError = ProjectResourceService.requireMutable(projectManager, project);
        if (mutableError != null) {
            return mutableError;
        }
        if (!context.safetyPolicy().isProjectScriptWriteAllowed(
            context.authContext().tokenName(),
            project,
            scriptType + "/" + pathText
        )) {
            context.auditLogger().logWriteAttempt(context.authContext().tokenName(), toolName, false, false, project + "/" + scriptType + "/" + pathText);
            return ToolExecutionResult.error(
                "Project script write blocked by allowlist",
                ProjectResourceService.errorBody("ALLOWLIST_BLOCKED", "Project script write blocked by allowlist")
            );
        }

        ResourcePath path = resolveScriptResourcePath(scriptType, pathText);
        Consumer<ResourceBuilder> serializer = serializerFor(scriptType, pathText, code, arguments);
        if (path == null || serializer == null) {
            return ToolExecutionResult.error("Unsupported scriptType: " + scriptType);
        }

        if (context.safetyPolicy().isDryRun(arguments)) {
            ObjectNode out = context.objectMapper().createObjectNode();
            out.put("dryRun", true);
            out.put("operation", operation);
            out.put("project", project);
            out.put("scriptType", scriptType);
            out.put("path", path.toString());
            out.put("codeLength", code.length());
            return ToolExecutionResult.ok("Project script dry-run plan generated", out);
        }

        Resource resource = ProjectResourceService.buildResource(
            project,
            path,
            DEFAULT_RESOURCE_VERSION,
            arguments.path("documentation").asText(""),
            serializer
        );
        ToolExecutionResult pushed = ProjectResourceService.pushUpsert(context, project, path, resource, createOnly, editOnly);
        context.auditLogger().logWriteAttempt(context.authContext().tokenName(), toolName, true, !pushed.isError(), project + "/" + scriptType + "/" + pathText);
        return pushed;
    }

    private ToolExecutionResult deleteScript(JsonNode arguments, ToolCallContext context) {
        if (arguments == null || !arguments.isObject()) {
            return ToolExecutionResult.error("Project script delete requires an arguments object");
        }
        String project = arguments.path("project").asText("").trim();
        String scriptType = arguments.path("scriptType").asText("").trim();
        String pathText = scriptPath(arguments);
        ProjectManager projectManager = ProjectResourceService.projectManager(context);
        ToolExecutionResult mutableError = ProjectResourceService.requireMutable(projectManager, project);
        if (mutableError != null) {
            return mutableError;
        }
        if (!context.safetyPolicy().isProjectScriptWriteAllowed(
            context.authContext().tokenName(),
            project,
            scriptType + "/" + pathText
        )) {
            context.auditLogger().logWriteAttempt(context.authContext().tokenName(), toolName, false, false, project + "/" + scriptType + "/" + pathText);
            return ToolExecutionResult.error(
                "Project script delete blocked by allowlist",
                ProjectResourceService.errorBody("ALLOWLIST_BLOCKED", "Project script delete blocked by allowlist")
            );
        }
        ResourcePath path = resolveScriptResourcePath(scriptType, pathText);
        if (path == null) {
            return ToolExecutionResult.error("Unsupported scriptType: " + scriptType);
        }
        if (context.safetyPolicy().isDryRun(arguments)) {
            ObjectNode out = context.objectMapper().createObjectNode();
            out.put("dryRun", true);
            out.put("operation", "delete");
            out.put("project", project);
            out.put("scriptType", scriptType);
            out.put("path", path.toString());
            return ToolExecutionResult.ok("Project script delete dry-run plan generated", out);
        }
        ToolExecutionResult pushed = ProjectResourceService.pushDelete(context, project, path);
        context.auditLogger().logWriteAttempt(context.authContext().tokenName(), toolName, true, !pushed.isError(), project + "/" + scriptType + "/" + pathText);
        return pushed;
    }

    private ToolExecutionResult importScripts(JsonNode arguments, ToolCallContext context) {
        if (arguments == null || !arguments.isObject()) {
            return ToolExecutionResult.error("Project script import requires an arguments object");
        }
        JsonNode resourcesNode = importResourcesNode(arguments);
        if (!resourcesNode.isArray()) {
            return ToolExecutionResult.error("Project script import requires bundle.resources or resources array");
        }
        String project = arguments.path("targetProject").asText(arguments.path("project").asText(arguments.path("bundle").path("project").asText(""))).trim();
        String scriptTypeFilter = arguments.path("scriptType").asText("").trim();
        String operation = arguments.path("operation").asText("upsert").trim().toLowerCase();
        boolean createOnly = "create".equals(operation);
        boolean editOnly = "edit".equals(operation);
        if (project.isBlank()) {
            return ToolExecutionResult.error("Project script import requires targetProject or project");
        }
        if (!createOnly && !editOnly && !"upsert".equals(operation)) {
            return ToolExecutionResult.error("operation must be one of create, edit, upsert");
        }

        ProjectManager projectManager = ProjectResourceService.projectManager(context);
        ToolExecutionResult mutableError = ProjectResourceService.requireMutable(projectManager, project);
        if (mutableError != null) {
            return mutableError;
        }

        List<Resource> imports = new ArrayList<>();
        ObjectNode out = context.objectMapper().createObjectNode();
        ArrayNode rows = out.putArray("resources");
        int skipped = 0;
        for (JsonNode resourceNode : resourcesNode) {
            ResourceType resourceType = new ResourceType(
                resourceNode.path("moduleId").asText(""),
                resourceNode.path("resourceType").asText("")
            );
            String scriptType = scriptTypeFor(resourceType);
            if (StringUtils.isBlank(scriptType) || "legacyEventConfig".equals(scriptType)) {
                skipped++;
                continue;
            }
            if (StringUtils.isNotBlank(scriptTypeFilter) && !scriptTypeFilter.equals(scriptType)) {
                skipped++;
                continue;
            }
            String pathText = ProjectResourceService.normalizeResourcePath(resourceNode.path("path").asText(""));
            if (!context.safetyPolicy().isProjectScriptWriteAllowed(
                context.authContext().tokenName(),
                project,
                scriptType + "/" + pathText
            )) {
                context.auditLogger().logWriteAttempt(context.authContext().tokenName(), toolName, false, false, project + "/" + scriptType + "/" + pathText);
                return ToolExecutionResult.error(
                    "Project script import blocked by allowlist",
                    ProjectResourceService.errorBody("ALLOWLIST_BLOCKED", "Project script import blocked by allowlist")
                );
            }
            Resource resource = ProjectResourceService.resourceFromSerialized(context, project, resourceNode);
            imports.add(resource);
            ObjectNode row = rows.addObject();
            row.put("project", project);
            row.put("scriptType", scriptType);
            row.put("path", pathText);
            row.put("operation", operation);
            row.put("resourcePath", resource.getResourcePath().toString());
        }

        boolean dryRun = context.safetyPolicy().isDryRun(arguments);
        out.put("dryRun", dryRun);
        out.put("project", project);
        out.put("operation", operation);
        out.put("importCount", imports.size());
        out.put("skippedCount", skipped);
        if (dryRun) {
            return ToolExecutionResult.ok("Project script import dry-run plan generated", out);
        }

        ToolExecutionResult pushed = ProjectResourceService.pushUpserts(context, project, imports, createOnly, editOnly);
        context.auditLogger().logWriteAttempt(context.authContext().tokenName(), toolName, true, !pushed.isError(), project + "/" + imports.size() + " project script(s)");
        if (pushed.isError()) {
            return pushed;
        }
        ((ObjectNode) pushed.structuredContent()).set("resources", rows);
        ((ObjectNode) pushed.structuredContent()).put("importCount", imports.size());
        ((ObjectNode) pushed.structuredContent()).put("skippedCount", skipped);
        return pushed;
    }

    private static ObjectNode serializeScript(ToolCallContext context, Resource resource, boolean includeCode) {
        ObjectNode row = ProjectResourceService.serializeResource(context, resource, false);
        ResourcePath path = resource.getResourcePath();
        String scriptType = scriptTypeFor(path == null ? null : path.getResourceType());
        row.put("scriptType", scriptType);
        if (includeCode) {
            try {
                row.set("decoded", decodeScript(context, scriptType, resource));
            }
            catch (Exception e) {
                row.put("decodeError", e.getMessage());
                row.set("rawResource", ProjectResourceService.serializeResource(context, resource, true));
            }
        }
        return row;
    }

    private static ObjectNode decodeScript(ToolCallContext context, String scriptType, Resource resource) {
        ObjectNode out = context.objectMapper().createObjectNode();
        switch (scriptType) {
            case "library" -> {
                resource.getData(Resource.DEFAULT_JSON_KEY).ifPresent(bytes ->
                    out.set("library", parseLibraryPayload(context, bytes.getBytesAsString()))
                );
            }
            case "startup" -> {
                ScriptConfig.StartupScript script = ScriptConfig.StartupScript.ofResource(resource);
                out.put("code", script.script());
                out.put("enabled", script.enabled());
            }
            case "shutdown" -> {
                ScriptConfig.ShutdownScript script = ScriptConfig.ShutdownScript.ofResource(resource);
                out.put("code", script.script());
                out.put("enabled", script.enabled());
            }
            case "projectUpdate" -> {
                UpdateScriptConfig script = UpdateScriptConfig.ofResource(resource);
                out.put("code", script.script());
                out.put("enabled", script.enabled());
            }
            case "timer" -> {
                ScriptConfig.TimerScript script = ScriptConfig.TimerScript.ofResource(resource);
                out.put("code", script.script());
                out.set("timer", context.objectMapper().valueToTree(Map.of(
                    "name", script.timerKey().getName(),
                    "delay", script.timerKey().getDelay(),
                    "fixedDelay", script.timerKey().isFixedDelay(),
                    "sharedThread", script.timerKey().isSharedThread(),
                    "enabled", script.timerKey().isEnabled()
                )));
            }
            case "message" -> {
                ScriptConfig.MessageHandlerScript script = ScriptConfig.MessageHandlerScript.ofResource(resource);
                out.put("code", script.script());
                out.set("messageHandler", context.objectMapper().valueToTree(Map.of(
                    "name", script.messageHandlerKey().getName(),
                    "threadType", script.messageHandlerKey().getThreadType(),
                    "enabled", script.messageHandlerKey().isEnabled()
                )));
            }
            case "tagChange" -> {
                ScriptConfig.TagChangeScriptEvent script = ScriptConfig.TagChangeScriptEvent.ofResource(resource);
                out.put("code", script.script().getScript());
                out.put("name", script.script().getName());
                out.put("enabled", script.script().isEnabled());
                out.set("paths", context.objectMapper().valueToTree(script.script().getPaths()));
            }
            case "scheduled" -> {
                ScheduledScript script = ScheduledScript.decodeFromResource(resource);
                out.put("code", script.getScript());
                out.put("name", script.getName());
                out.put("cronExpression", script.getCronExpression());
                out.put("enabled", script.getEnabled());
            }
            default -> out.set("rawResource", ProjectResourceService.serializeResource(context, resource, true));
        }
        return out;
    }

    private static JsonNode parseLibraryPayload(ToolCallContext context, String payload) {
        try {
            return context.objectMapper().readTree(payload);
        }
        catch (Exception e) {
            ObjectNode out = context.objectMapper().createObjectNode();
            out.put("code", payload);
            return out;
        }
    }

    private static JsonNode importResourcesNode(JsonNode arguments) {
        JsonNode bundleResources = arguments.path("bundle").path("resources");
        return bundleResources.isArray() ? bundleResources : arguments.path("resources");
    }

    private static boolean isScriptResource(Resource resource, String scriptTypeFilter) {
        if (resource == null || resource.isFolder() || resource.getResourcePath() == null) {
            return false;
        }
        String scriptType = scriptTypeFor(resource.getResourcePath().getResourceType());
        return StringUtils.isNotBlank(scriptType)
            && (StringUtils.isBlank(scriptTypeFilter) || scriptTypeFilter.equals(scriptType));
    }

    private static ResourcePath resolveScriptResourcePath(String scriptType, String pathText) {
        return switch (StringUtils.defaultString(scriptType)) {
            case "library" -> new ResourcePath(ScriptLibrary.RESOURCE_TYPE, requiredPath(pathText, "library"));
            case "startup" -> ScriptConfig.StartupScript.RESOURCE_TYPE.rootPath();
            case "shutdown" -> ScriptConfig.ShutdownScript.RESOURCE_TYPE.rootPath();
            case "projectUpdate" -> UpdateScriptConfig.RESOURCE_TYPE.rootPath();
            case "timer" -> ScriptConfig.TimerScript.RESOURCE_TYPE.childPath(requiredName(pathText, "timer"));
            case "message" -> ScriptConfig.MessageHandlerScript.RESOURCE_TYPE.childPath(requiredName(pathText, "message"));
            case "tagChange" -> ScriptConfig.TagChangeScriptEvent.RESOURCE_TYPE.childPath(requiredName(pathText, "tagChange"));
            case "scheduled" -> ScheduledScript.RESOURCE_TYPE.childPath(requiredName(pathText, "scheduled"));
            default -> null;
        };
    }

    private static Consumer<ResourceBuilder> serializerFor(String scriptType, String pathText, String code, JsonNode arguments) {
        boolean enabled = arguments.path("enabled").asBoolean(true);
        return switch (StringUtils.defaultString(scriptType)) {
            case "library" -> builder -> {
                ObjectNode payload = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
                payload.putObject("scripts").put(requiredPath(pathText, "library"), code);
                builder.putData(Resource.DEFAULT_JSON_KEY, payload.toString());
            };
            case "startup" -> ScriptConfig.StartupScript.serialize(code, enabled, ScriptConfig.StartupScript.DESCRIPTOR);
            case "shutdown" -> ScriptConfig.ShutdownScript.serialize(code, enabled, ScriptConfig.ShutdownScript.DESCRIPTOR);
            case "projectUpdate" -> UpdateScriptConfig.serialize(code, enabled);
            case "timer" -> ScriptConfig.TimerScript.serialize(timerKey(pathText, arguments), code, ScriptConfig.TimerScript.DESCRIPTOR);
            case "message" -> ScriptConfig.MessageHandlerScript.serialize(messageHandlerKey(pathText, arguments), code, ScriptConfig.MessageHandlerScript.DESCRIPTOR);
            case "tagChange" -> ScriptConfig.TagChangeScriptEvent.serialize(tagChangeScript(pathText, code, arguments), ScriptConfig.TagChangeScriptEvent.DESCRIPTOR);
            case "scheduled" -> ScheduledScript.encodeToResource(scheduledScript(pathText, code, arguments));
            default -> null;
        };
    }

    private static TimerKey timerKey(String pathText, JsonNode arguments) {
        TimerKey key = new TimerKey();
        key.setName(requiredName(pathText, "timer"));
        key.setDelay(arguments.path("delay").asLong(1000L));
        key.setFixedDelay(arguments.path("fixedDelay").asBoolean(true));
        key.setSharedThread(arguments.path("sharedThread").asBoolean(true));
        key.setEnabled(arguments.path("enabled").asBoolean(true));
        return key;
    }

    private static MessageHandlerKey messageHandlerKey(String pathText, JsonNode arguments) {
        MessageHandlerKey key = new MessageHandlerKey();
        key.setName(requiredName(pathText, "message"));
        key.setThreadType(arguments.path("threadType").asText(MessageHandlerKey.THREAD_SHARED));
        key.setEnabled(arguments.path("enabled").asBoolean(true));
        return key;
    }

    private static TagChangeScript tagChangeScript(String pathText, String code, JsonNode arguments) {
        TagChangeScript script = new TagChangeScript(requiredName(pathText, "tagChange"));
        script.setScript(code);
        script.setEnabled(arguments.path("enabled").asBoolean(true));
        if (arguments.has("paths") && arguments.get("paths").isArray()) {
            List<String> paths = new ArrayList<>();
            for (JsonNode node : arguments.get("paths")) {
                paths.add(node.asText());
            }
            script.setPaths(paths);
        }
        return script;
    }

    private static ScheduledScript scheduledScript(String pathText, String code, JsonNode arguments) {
        return new ScheduledScript(
            requiredName(pathText, "scheduled"),
            arguments.path("cronExpression").asText("* * * * *"),
            code,
            arguments.path("enabled").asBoolean(true)
        );
    }

    private static String scriptTypeFor(ResourceType resourceType) {
        if (resourceType == null || !"ignition".equals(resourceType.moduleId())) {
            return "";
        }
        return switch (StringUtils.defaultString(resourceType.typeId())) {
            case "script-app-library" -> "library";
            case "startup" -> "startup";
            case "shutdown" -> "shutdown";
            case "update" -> "projectUpdate";
            case "timer" -> "timer";
            case "message" -> "message";
            case "tag-change" -> "tagChange";
            case "scheduled" -> "scheduled";
            case "event-scripts" -> "legacyEventConfig";
            default -> "";
        };
    }

    private static String scriptPath(JsonNode arguments) {
        return ProjectResourceService.normalizeResourcePath(arguments.path("path").asText(arguments.path("name").asText("")));
    }

    private static String requiredName(String pathText, String fallback) {
        String name = StringUtils.defaultIfBlank(ProjectResourceService.normalizeResourcePath(pathText), fallback);
        return ResourceUtil.escapeIllegalCharacters(name);
    }

    private static String requiredPath(String pathText, String fallback) {
        return StringUtils.defaultIfBlank(ProjectResourceService.normalizeResourcePath(pathText), fallback);
    }

    private static String description(String name) {
        return switch (name) {
            case "ignition.scripts.project.list" -> "List project script resources";
            case "ignition.scripts.project.read" -> "Read one project script resource";
            case "ignition.scripts.project.write" -> "Create or edit one project script resource";
            case "ignition.scripts.project.delete" -> "Delete one project script resource";
            case "ignition.scripts.project.import" -> "Import project script resources from a reviewed project resource bundle";
            default -> "Project script tool";
        };
    }

    private static JsonNode inputSchema(String name) {
        ObjectNode schema = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("project").put("type", "string");
        props.putObject("scriptType").put("type", "string");
        props.putObject("path").put("type", "string");
        props.putObject("name").put("type", "string");
        props.putObject("code").put("type", "string");
        props.putObject("operation").put("type", "string");
        props.putObject("enabled").put("type", "boolean");
        props.putObject("delay").put("type", "integer");
        props.putObject("fixedDelay").put("type", "boolean");
        props.putObject("sharedThread").put("type", "boolean");
        props.putObject("threadType").put("type", "string");
        props.putObject("cronExpression").put("type", "string");
        props.putObject("includeCode").put("type", "boolean");
        props.putObject("bundle").put("type", "object");
        props.putObject("resources").put("type", "array");
        props.putObject("targetProject").put("type", "string");
        props.putObject("commit").put("type", "boolean");
        if (!name.endsWith(".list") && !name.endsWith(".import")) {
            ArrayNode required = schema.putArray("required");
            required.add("project").add("scriptType");
            if (name.endsWith(".write")) {
                required.add("code");
            }
        }
        return schema;
    }

    private static boolean isMutating(String name) {
        return name.endsWith(".write") || name.endsWith(".delete") || name.endsWith(".import");
    }
}
