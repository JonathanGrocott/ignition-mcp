package com.jg.ignition.mcp.gateway.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inductiveautomation.ignition.common.Dataset;
import com.inductiveautomation.ignition.common.db.namedquery.NamedQuery;
import com.inductiveautomation.ignition.common.db.namedquery.NamedQueryManager;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceBuilder;
import com.inductiveautomation.ignition.common.resourcecollection.Resource;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceCollectionManifest;
import com.inductiveautomation.ignition.common.resourcecollection.ResourcePath;
import com.inductiveautomation.ignition.common.resourcecollection.RuntimeResourceCollection;
import com.inductiveautomation.ignition.common.sqltags.model.types.DataType;
import com.inductiveautomation.ignition.common.util.TimeUnits;
import com.inductiveautomation.ignition.gateway.project.ProjectManager;
import com.jg.ignition.mcp.common.PermissionRequirement;
import com.jg.ignition.mcp.common.ToolDefinition;
import com.jg.ignition.mcp.common.ToolExecutionResult;
import com.jg.ignition.mcp.gateway.ToolCallContext;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

public class ProjectToolHandler implements ToolHandler {

    private final String toolName;
    private final ToolDefinition definition;

    public ProjectToolHandler(String toolName) {
        this.toolName = toolName;
        this.definition = new ToolDefinition(
            toolName,
            description(toolName),
            permissionFor(toolName),
            inputSchema(toolName),
            isMutatingTool(toolName)
        );
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolExecutionResult execute(JsonNode arguments, ToolCallContext context) {
        return switch (toolName) {
            case "ignition.projects.list" -> listProjects(arguments, context);
            case "ignition.namedqueries.list" -> listNamedQueries(arguments, context);
            case "ignition.namedqueries.read" -> readNamedQuery(arguments, context);
            case "ignition.namedqueries.execute" -> executeNamedQuery(arguments, context);
            case "ignition.namedqueries.write" -> writeNamedQuery(arguments, context);
            case "ignition.namedqueries.delete" -> deleteNamedQuery(arguments, context);
            case "ignition.namedqueries.import" -> importNamedQueries(arguments, context);
            default -> ToolExecutionResult.error("Unsupported project tool: " + toolName);
        };
    }

    private ToolExecutionResult listProjects(JsonNode arguments, ToolCallContext context) {
        ProjectManager projectManager = context.gatewayContext().getProjectManager();
        if (projectManager == null) {
            return ToolExecutionResult.error("Project manager is unavailable");
        }

        boolean includeNamedQueryCounts = arguments == null || arguments.path("includeNamedQueryCounts").asBoolean(true);

        try {
            List<String> projectNames = new ArrayList<>(projectManager.getNames());
            projectNames.sort(String::compareToIgnoreCase);
            Map<String, ResourceCollectionManifest> manifests = projectManager.getManifests();

            ObjectNode result = context.objectMapper().createObjectNode();
            ArrayNode projects = result.putArray("projects");
            int enabledCount = 0;
            int namedQueryTotal = 0;

            for (String projectName : projectNames) {
                ResourceCollectionManifest manifest = manifests.get(projectName);
                boolean enabled = manifest == null || manifest.enabled();
                boolean inheritable = manifest != null && manifest.inheritable();
                boolean mutable = projectManager.isMutable(projectName);
                if (enabled) {
                    enabledCount++;
                }

                ObjectNode row = projects.addObject();
                row.put("name", projectName);
                row.put("enabled", enabled);
                row.put("inheritable", inheritable);
                row.put("mutable", mutable);

                if (includeNamedQueryCounts) {
                    int namedQueryCount = countNamedQueries(projectManager, projectName);
                    row.put("namedQueryCount", namedQueryCount);
                    namedQueryTotal += namedQueryCount;
                }
            }

            result.put("count", projects.size());
            result.put("enabledCount", enabledCount);
            if (includeNamedQueryCounts) {
                result.put("namedQueryCount", namedQueryTotal);
            }
            return ToolExecutionResult.ok("Listed " + projects.size() + " project(s)", result);
        }
        catch (Exception e) {
            return ToolExecutionResult.error("Failed to list projects: " + e.getMessage());
        }
    }

    private ToolExecutionResult listNamedQueries(JsonNode arguments, ToolCallContext context) {
        ProjectManager projectManager = context.gatewayContext().getProjectManager();
        if (projectManager == null) {
            return ToolExecutionResult.error("Project manager is unavailable");
        }

        String projectFilter = arguments == null ? "" : arguments.path("project").asText("").trim();
        String pathPrefix = arguments == null ? "" : arguments.path("pathPrefix").asText("").trim();

        try {
            List<String> projectNames = new ArrayList<>(projectManager.getNames());
            projectNames.sort(String::compareToIgnoreCase);
            if (StringUtils.isNotBlank(projectFilter) && projectNames.stream().noneMatch(projectFilter::equals)) {
                return ToolExecutionResult.error("Project not found: " + projectFilter);
            }

            Map<String, ResourceCollectionManifest> manifests = projectManager.getManifests();
            List<NamedQueryDescriptor> descriptors = new ArrayList<>();

            for (String projectName : projectNames) {
                if (StringUtils.isNotBlank(projectFilter) && !projectFilter.equals(projectName)) {
                    continue;
                }

                ResourceCollectionManifest manifest = manifests.get(projectName);
                boolean enabled = manifest == null || manifest.enabled();
                boolean mutable = projectManager.isMutable(projectName);
                Optional<RuntimeResourceCollection> collection = projectManager.find(projectName);
                if (collection.isEmpty()) {
                    continue;
                }

                for (Resource resource : collection.get().getAllResources().values()) {
                    if (!isNamedQueryResource(resource)) {
                        continue;
                    }
                    ResourcePath resourcePath = resource.getResourcePath();
                    String queryPath = queryPath(resourcePath);
                    if (StringUtils.isNotBlank(pathPrefix) && !queryPath.startsWith(pathPrefix)) {
                        continue;
                    }

                    descriptors.add(
                        new NamedQueryDescriptor(
                            projectName,
                            queryPath,
                            StringUtils.defaultString(resourcePath.getFolderPath()),
                            StringUtils.defaultString(resourcePath.getName()),
                            enabled,
                            mutable
                        )
                    );
                }
            }

            descriptors.sort(
                Comparator.comparing(NamedQueryDescriptor::projectName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(NamedQueryDescriptor::queryPath, String.CASE_INSENSITIVE_ORDER)
            );

            ObjectNode result = context.objectMapper().createObjectNode();
            ArrayNode queries = result.putArray("queries");
            Set<String> projectNamesWithQuery = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (NamedQueryDescriptor descriptor : descriptors) {
                ObjectNode row = queries.addObject();
                row.put("project", descriptor.projectName());
                row.put("path", descriptor.queryPath());
                row.put("folder", descriptor.folderPath());
                row.put("name", descriptor.resourceName());
                row.put("projectEnabled", descriptor.projectEnabled());
                row.put("projectMutable", descriptor.projectMutable());
                projectNamesWithQuery.add(descriptor.projectName());
            }

            result.put("count", queries.size());
            result.put("projectCount", projectNamesWithQuery.size());
            if (StringUtils.isNotBlank(projectFilter)) {
                result.put("project", projectFilter);
            }
            if (StringUtils.isNotBlank(pathPrefix)) {
                result.put("pathPrefix", pathPrefix);
            }
            return ToolExecutionResult.ok("Listed " + queries.size() + " named querie(s)", result);
        }
        catch (Exception e) {
            return ToolExecutionResult.error("Failed to list named queries: " + e.getMessage());
        }
    }

    private ToolExecutionResult readNamedQuery(JsonNode arguments, ToolCallContext context) {
        if (arguments == null || !arguments.isObject()) {
            return ToolExecutionResult.error("Named query read requires an arguments object");
        }

        String projectName = arguments.path("project").asText("").trim();
        String queryPath = arguments.path("path").asText("").trim();
        boolean includeQuery = arguments.path("includeQuery").asBoolean(true);
        if (projectName.isBlank()) {
            return ToolExecutionResult.error("Named query read requires project");
        }
        if (queryPath.isBlank()) {
            return ToolExecutionResult.error("Named query read requires path");
        }

        NamedQueryLookup lookup = lookupNamedQuery(context, projectName, queryPath);
        if (lookup.error() != null) {
            return ToolExecutionResult.error(lookup.error());
        }
        NamedQuery namedQuery = lookup.namedQuery();

        ObjectNode result = context.objectMapper().createObjectNode();
        result.put("project", projectName);
        result.put("path", queryPath);
        result.put("type", namedQuery.getType() == null ? "" : namedQuery.getType().name());
        result.put("database", StringUtils.defaultString(namedQuery.getDatabase()));
        result.put("description", StringUtils.defaultString(namedQuery.getDescription()));
        result.put("enabled", namedQuery.isEnabled());
        result.put("readOnly", namedQuery.isReadOnly());
        result.put("cachingEnabled", namedQuery.isCachingEnabled());
        result.put("cacheAmount", namedQuery.getCacheAmount());
        result.put("cacheUnit", namedQuery.getCacheUnit() == null ? "" : namedQuery.getCacheUnit().name());
        result.put("autoBatchEnabled", namedQuery.isAutoBatchEnabled());
        result.put("useMaxReturnSize", namedQuery.isUseMaxReturnSize());
        result.put("maxReturnSize", namedQuery.getMaxReturnSize());
        result.put("fallbackEnabled", namedQuery.isFallbackEnabled());
        result.put("fallbackValue", StringUtils.defaultString(namedQuery.getFallbackValue()));
        result.put("permissionCount", namedQuery.getPermissions() == null ? 0 : namedQuery.getPermissions().size());
        if (includeQuery) {
            result.put("query", StringUtils.defaultString(namedQuery.getQuery()));
        }

        ArrayNode parameters = result.putArray("parameters");
        List<NamedQuery.Parameter> queryParameters = namedQuery.getParameters();
        if (queryParameters != null) {
            for (NamedQuery.Parameter parameter : queryParameters) {
                if (parameter == null) {
                    continue;
                }
                ObjectNode row = parameters.addObject();
                row.put("name", StringUtils.defaultString(parameter.getIdentifier()));
                row.put("type", parameter.getType() == null ? "" : parameter.getType().name());
                row.put("sqlType", parameter.getSqlType() == null ? "" : parameter.getSqlType().name());
            }
        }
        result.put("parameterCount", parameters.size());
        return ToolExecutionResult.ok("Read named query " + queryPath, result);
    }

    private ToolExecutionResult executeNamedQuery(JsonNode arguments, ToolCallContext context) {
        if (arguments == null || !arguments.isObject()) {
            return ToolExecutionResult.error("Named query execute requires an arguments object");
        }

        String projectName = arguments.path("project").asText("").trim();
        String queryPath = arguments.path("path").asText("").trim();
        if (projectName.isBlank()) {
            return ToolExecutionResult.error("Named query execute requires project");
        }
        if (queryPath.isBlank()) {
            return ToolExecutionResult.error("Named query execute requires path");
        }
        if (!context.safetyPolicy().isNamedQueryExecuteAllowed(
            context.authContext().tokenName(),
            projectName,
            queryPath
        )) {
            context.auditLogger().logWriteAttempt(
                context.authContext().tokenName(),
                toolName,
                false,
                false,
                projectName + "/" + queryPath
            );
            return ToolExecutionResult.error(
                "Named query execute blocked by allowlist: " + projectName + "/" + queryPath,
                ProjectResourceService.errorBody("ALLOWLIST_BLOCKED", "Named query execute blocked by allowlist")
            );
        }

        NamedQueryLookup lookup = lookupNamedQuery(context, projectName, queryPath);
        if (lookup.error() != null) {
            return ToolExecutionResult.error(lookup.error());
        }
        NamedQuery namedQuery = lookup.namedQuery();
        NamedQueryManager namedQueryManager = lookup.namedQueryManager();
        boolean dryRun = context.safetyPolicy().isDryRun(arguments);
        boolean includeResultData = arguments.path("includeResultData").asBoolean(true);

        Map<String, Object> parameters;
        try {
            parameters = parseParameters(arguments.path("parameters"), context);
        }
        catch (IllegalArgumentException e) {
            return ToolExecutionResult.error("Invalid execute parameters: " + e.getMessage());
        }
        boolean mutatingQuery = isMutatingQuery(namedQuery);

        if (dryRun) {
            ObjectNode result = context.objectMapper().createObjectNode();
            result.put("dryRun", true);
            result.put("project", projectName);
            result.put("path", queryPath);
            result.put("type", namedQuery.getType() == null ? "" : namedQuery.getType().name());
            result.put("mutatingQuery", mutatingQuery);
            result.put("parameterCount", parameters.size());
            result.set("parameterNames", context.objectMapper().valueToTree(parameters.keySet()));
            result.put("note", "Execution skipped. Pass commit=true to execute.");
            return ToolExecutionResult.ok("Named query dry-run plan generated", result);
        }

        final Object executionResult;
        try {
            executionResult = namedQueryManager.execute(
                projectName,
                queryPath,
                parameters,
                false,
                false,
                "",
                false
            );
        }
        catch (Exception e) {
            context.auditLogger().logWriteAttempt(
                context.authContext().tokenName(),
                toolName,
                true,
                false,
                projectName + "/" + queryPath
            );
            return ToolExecutionResult.error("Named query execute failed: " + e.getMessage());
        }

        context.auditLogger().logWriteAttempt(
            context.authContext().tokenName(),
            toolName,
            true,
            true,
            projectName + "/" + queryPath
        );

        ObjectNode result = context.objectMapper().createObjectNode();
        result.put("executed", true);
        result.put("project", projectName);
        result.put("path", queryPath);
        result.put("type", namedQuery.getType() == null ? "" : namedQuery.getType().name());
        result.put("mutatingQuery", mutatingQuery);
        result.put("parameterCount", parameters.size());

        if (!includeResultData) {
            result.put("resultOmitted", true);
            return ToolExecutionResult.ok("Named query executed (result omitted)", result);
        }

        if (executionResult instanceof Dataset dataset) {
            int limit = Math.max(1, Math.min(arguments.path("maxRows").asInt(context.safetyPolicy().namedQueryMaxRows()),
                context.safetyPolicy().namedQueryMaxRows()));
            result.set("result", datasetToJson(dataset, limit, context));
        }
        else {
            result.set("result", context.objectMapper().valueToTree(simplifyValue(executionResult)));
        }
        return ToolExecutionResult.ok("Named query executed", result);
    }

    private ToolExecutionResult writeNamedQuery(JsonNode arguments, ToolCallContext context) {
        if (arguments == null || !arguments.isObject()) {
            return ToolExecutionResult.error("Named query write requires an arguments object");
        }

        String projectName = arguments.path("project").asText("").trim();
        String queryPath = arguments.path("path").asText("").trim();
        String operation = arguments.path("operation").asText("upsert").trim().toLowerCase();
        boolean createOnly = "create".equals(operation);
        boolean editOnly = "edit".equals(operation);
        if (projectName.isBlank()) {
            return ToolExecutionResult.error("Named query write requires project");
        }
        if (queryPath.isBlank()) {
            return ToolExecutionResult.error("Named query write requires path");
        }
        if (!createOnly && !editOnly && !"upsert".equals(operation)) {
            return ToolExecutionResult.error("operation must be one of create, edit, upsert");
        }
        if (!context.safetyPolicy().isNamedQueryWriteAllowed(
            context.authContext().tokenName(),
            projectName,
            queryPath
        )) {
            context.auditLogger().logWriteAttempt(context.authContext().tokenName(), toolName, false, false, projectName + "/" + queryPath);
            return ToolExecutionResult.error(
                "Named query write blocked by allowlist: " + projectName + "/" + queryPath,
                ProjectResourceService.errorBody("ALLOWLIST_BLOCKED", "Named query write blocked by allowlist")
            );
        }

        ProjectManager projectManager = ProjectResourceService.projectManager(context);
        ToolExecutionResult mutableError = ProjectResourceService.requireMutable(projectManager, projectName);
        if (mutableError != null) {
            return mutableError;
        }

        NamedQuery namedQuery;
        try {
            namedQuery = parseNamedQuery(arguments);
        }
        catch (IllegalArgumentException e) {
            return ToolExecutionResult.error("Invalid named query: " + e.getMessage());
        }

        ResourcePath resourcePath = NamedQuery.RESOURCE_TYPE.subPath(queryPath);
        if (context.safetyPolicy().isDryRun(arguments)) {
            ObjectNode result = context.objectMapper().createObjectNode();
            result.put("dryRun", true);
            result.put("operation", operation);
            result.put("project", projectName);
            result.put("path", queryPath);
            result.put("resourcePath", resourcePath.toString());
            result.put("type", namedQuery.getType() == null ? "" : namedQuery.getType().name());
            result.put("parameterCount", namedQuery.getParameters() == null ? 0 : namedQuery.getParameters().size());
            return ToolExecutionResult.ok("Named query write dry-run plan generated", result);
        }

        Consumer<ResourceBuilder> serializer = NamedQuery.toResource(namedQuery);
        Resource resource = ProjectResourceService.buildResource(
            projectName,
            resourcePath,
            NamedQuery.CURRENT_RESOURCE_VERSION,
            namedQuery.getDescription(),
            serializer
        );
        ToolExecutionResult pushed = ProjectResourceService.pushUpsert(
            context,
            projectName,
            resourcePath,
            resource,
            createOnly,
            editOnly
        );
        context.auditLogger().logWriteAttempt(context.authContext().tokenName(), toolName, true, !pushed.isError(), projectName + "/" + queryPath);
        return pushed;
    }

    private ToolExecutionResult deleteNamedQuery(JsonNode arguments, ToolCallContext context) {
        if (arguments == null || !arguments.isObject()) {
            return ToolExecutionResult.error("Named query delete requires an arguments object");
        }
        String projectName = arguments.path("project").asText("").trim();
        String queryPath = arguments.path("path").asText("").trim();
        if (projectName.isBlank()) {
            return ToolExecutionResult.error("Named query delete requires project");
        }
        if (queryPath.isBlank()) {
            return ToolExecutionResult.error("Named query delete requires path");
        }
        if (!context.safetyPolicy().isNamedQueryWriteAllowed(
            context.authContext().tokenName(),
            projectName,
            queryPath
        )) {
            context.auditLogger().logWriteAttempt(context.authContext().tokenName(), toolName, false, false, projectName + "/" + queryPath);
            return ToolExecutionResult.error(
                "Named query delete blocked by allowlist: " + projectName + "/" + queryPath,
                ProjectResourceService.errorBody("ALLOWLIST_BLOCKED", "Named query delete blocked by allowlist")
            );
        }
        ProjectManager projectManager = ProjectResourceService.projectManager(context);
        ToolExecutionResult mutableError = ProjectResourceService.requireMutable(projectManager, projectName);
        if (mutableError != null) {
            return mutableError;
        }
        ResourcePath resourcePath = NamedQuery.RESOURCE_TYPE.subPath(queryPath);
        if (context.safetyPolicy().isDryRun(arguments)) {
            ObjectNode result = context.objectMapper().createObjectNode();
            result.put("dryRun", true);
            result.put("operation", "delete");
            result.put("project", projectName);
            result.put("path", queryPath);
            result.put("resourcePath", resourcePath.toString());
            return ToolExecutionResult.ok("Named query delete dry-run plan generated", result);
        }
        ToolExecutionResult pushed = ProjectResourceService.pushDelete(context, projectName, resourcePath);
        context.auditLogger().logWriteAttempt(context.authContext().tokenName(), toolName, true, !pushed.isError(), projectName + "/" + queryPath);
        return pushed;
    }

    private ToolExecutionResult importNamedQueries(JsonNode arguments, ToolCallContext context) {
        if (arguments == null || !arguments.isObject()) {
            return ToolExecutionResult.error("Named query import requires an arguments object");
        }
        JsonNode resourcesNode = importResourcesNode(arguments);
        if (!resourcesNode.isArray()) {
            return ToolExecutionResult.error("Named query import requires bundle.resources or resources array");
        }

        String projectName = arguments.path("targetProject").asText(arguments.path("project").asText(arguments.path("bundle").path("project").asText(""))).trim();
        String operation = arguments.path("operation").asText("upsert").trim().toLowerCase();
        boolean createOnly = "create".equals(operation);
        boolean editOnly = "edit".equals(operation);
        String pathPrefix = ProjectResourceService.normalizeResourcePath(arguments.path("pathPrefix").asText(""));
        if (projectName.isBlank()) {
            return ToolExecutionResult.error("Named query import requires targetProject or project");
        }
        if (!createOnly && !editOnly && !"upsert".equals(operation)) {
            return ToolExecutionResult.error("operation must be one of create, edit, upsert");
        }

        ProjectManager projectManager = ProjectResourceService.projectManager(context);
        ToolExecutionResult mutableError = ProjectResourceService.requireMutable(projectManager, projectName);
        if (mutableError != null) {
            return mutableError;
        }

        List<Resource> imports = new ArrayList<>();
        ObjectNode out = context.objectMapper().createObjectNode();
        ArrayNode rows = out.putArray("resources");
        int skipped = 0;
        for (JsonNode resourceNode : resourcesNode) {
            if (!isNamedQueryResourceNode(resourceNode)) {
                skipped++;
                continue;
            }
            String queryPath = ProjectResourceService.normalizeResourcePath(resourceNode.path("path").asText(""));
            if (StringUtils.isNotBlank(pathPrefix) && !queryPath.startsWith(pathPrefix)) {
                skipped++;
                continue;
            }
            if (!context.safetyPolicy().isNamedQueryWriteAllowed(context.authContext().tokenName(), projectName, queryPath)) {
                context.auditLogger().logWriteAttempt(context.authContext().tokenName(), toolName, false, false, projectName + "/" + queryPath);
                return ToolExecutionResult.error(
                    "Named query import blocked by allowlist: " + projectName + "/" + queryPath,
                    ProjectResourceService.errorBody("ALLOWLIST_BLOCKED", "Named query import blocked by allowlist")
                );
            }
            Resource resource = ProjectResourceService.resourceFromSerialized(context, projectName, resourceNode);
            imports.add(resource);
            ObjectNode row = rows.addObject();
            row.put("project", projectName);
            row.put("path", queryPath);
            row.put("operation", operation);
            row.put("resourcePath", resource.getResourcePath().toString());
        }

        boolean dryRun = context.safetyPolicy().isDryRun(arguments);
        out.put("dryRun", dryRun);
        out.put("project", projectName);
        out.put("operation", operation);
        out.put("importCount", imports.size());
        out.put("skippedCount", skipped);
        if (dryRun) {
            return ToolExecutionResult.ok("Named query import dry-run plan generated", out);
        }

        ToolExecutionResult pushed = ProjectResourceService.pushUpserts(context, projectName, imports, createOnly, editOnly);
        context.auditLogger().logWriteAttempt(context.authContext().tokenName(), toolName, true, !pushed.isError(), projectName + "/" + imports.size() + " named querie(s)");
        if (pushed.isError()) {
            return pushed;
        }
        ((ObjectNode) pushed.structuredContent()).set("resources", rows);
        ((ObjectNode) pushed.structuredContent()).put("importCount", imports.size());
        ((ObjectNode) pushed.structuredContent()).put("skippedCount", skipped);
        return pushed;
    }

    private static int countNamedQueries(ProjectManager projectManager, String projectName) {
        return projectManager.find(projectName)
            .map(RuntimeResourceCollection::getAllResources)
            .map(resources -> (int) resources.values().stream().filter(ProjectToolHandler::isNamedQueryResource).count())
            .orElse(0);
    }

    private static boolean isNamedQueryResource(Resource resource) {
        if (resource == null || resource.isFolder()) {
            return false;
        }
        ResourcePath resourcePath = resource.getResourcePath();
        if (resourcePath == null) {
            return false;
        }
        return "ignition".equalsIgnoreCase(resourcePath.getModuleId())
            && "named-query".equalsIgnoreCase(resourcePath.getType());
    }

    private static boolean isNamedQueryResourceNode(JsonNode resourceNode) {
        return "ignition".equalsIgnoreCase(resourceNode.path("moduleId").asText(""))
            && "named-query".equalsIgnoreCase(resourceNode.path("resourceType").asText(""));
    }

    private static JsonNode importResourcesNode(JsonNode arguments) {
        JsonNode bundleResources = arguments.path("bundle").path("resources");
        return bundleResources.isArray() ? bundleResources : arguments.path("resources");
    }

    private static String queryPath(ResourcePath resourcePath) {
        if (resourcePath == null || resourcePath.getPath() == null) {
            return "";
        }
        return StringUtils.removeStart(resourcePath.getPath().toString(), "/");
    }

    private static PermissionRequirement permissionFor(String name) {
        return isMutatingTool(name) ? PermissionRequirement.WRITE : PermissionRequirement.READ;
    }

    private static boolean isMutatingTool(String name) {
        return "ignition.namedqueries.execute".equals(name)
            || "ignition.namedqueries.write".equals(name)
            || "ignition.namedqueries.delete".equals(name)
            || "ignition.namedqueries.import".equals(name);
    }

    private static boolean isMutatingQuery(NamedQuery namedQuery) {
        if (namedQuery == null) {
            return true;
        }
        NamedQuery.Type type = namedQuery.getType();
        if (type == NamedQuery.Type.UpdateQuery) {
            return true;
        }
        return !namedQuery.isReadOnly();
    }

    private static Map<String, Object> parseParameters(JsonNode parametersNode, ToolCallContext context) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (parametersNode == null || parametersNode.isNull() || parametersNode.isMissingNode()) {
            return parameters;
        }
        if (!parametersNode.isObject()) {
            throw new IllegalArgumentException("'parameters' must be an object");
        }
        parametersNode.fields().forEachRemaining(entry ->
            parameters.put(entry.getKey(), context.objectMapper().convertValue(entry.getValue(), Object.class))
        );
        return parameters;
    }

    private static NamedQuery parseNamedQuery(JsonNode arguments) {
        NamedQuery query = new NamedQuery();
        query.setType(parseEnum(arguments.path("type").asText("Query"), NamedQuery.Type.class, "type"));
        query.setQuery(arguments.path("query").asText(""));
        query.setDatabase(arguments.path("database").asText(""));
        query.setDescription(arguments.path("description").asText(""));
        query.setEnabled(arguments.path("enabled").asBoolean(true));
        query.setReadOnly(arguments.path("readOnly").asBoolean(query.getType() != NamedQuery.Type.UpdateQuery));
        query.setCachingEnabled(arguments.path("cachingEnabled").asBoolean(false));
        query.setCacheAmount(arguments.path("cacheAmount").asInt(0));
        if (arguments.hasNonNull("cacheUnit")) {
            query.setCacheUnit(parseEnum(arguments.path("cacheUnit").asText(), TimeUnits.class, "cacheUnit"));
        }
        query.setAutoBatchEnabled(arguments.path("autoBatchEnabled").asBoolean(false));
        query.setUseMaxReturnSize(arguments.path("useMaxReturnSize").asBoolean(false));
        query.setMaxReturnSize(arguments.path("maxReturnSize").asLong(0L));
        query.setFallbackEnabled(arguments.path("fallbackEnabled").asBoolean(false));
        query.setFallbackValue(arguments.path("fallbackValue").asText(""));
        query.setSyntaxProvider(arguments.path("syntaxProvider").asText(""));

        List<NamedQuery.Parameter> parameters = new ArrayList<>();
        JsonNode parameterNodes = arguments.path("parameters");
        if (parameterNodes.isArray()) {
            for (JsonNode parameterNode : parameterNodes) {
                String identifier = parameterNode.path("name").asText(parameterNode.path("identifier").asText("")).trim();
                if (identifier.isBlank()) {
                    throw new IllegalArgumentException("parameter name cannot be blank");
                }
                if (!NamedQuery.isValidParamName(identifier)) {
                    throw new IllegalArgumentException("invalid parameter name: " + identifier);
                }
                NamedQuery.ParameterType parameterType = parseEnum(
                    parameterNode.path("type").asText("Parameter"),
                    NamedQuery.ParameterType.class,
                    "parameter.type"
                );
                DataType dataType = parseEnum(
                    parameterNode.path("sqlType").asText("String"),
                    DataType.class,
                    "parameter.sqlType"
                );
                parameters.add(new NamedQuery.Parameter(parameterType, identifier, dataType));
            }
        }
        query.setParameters(parameters);
        return query;
    }

    private static <T extends Enum<T>> T parseEnum(String rawValue, Class<T> enumType, String fieldName) {
        try {
            return Enum.valueOf(enumType, rawValue);
        }
        catch (Exception e) {
            throw new IllegalArgumentException("invalid " + fieldName + ": " + rawValue);
        }
    }

    private static ObjectNode datasetToJson(Dataset dataset, int maxRows, ToolCallContext context) {
        ObjectNode out = context.objectMapper().createObjectNode();
        List<String> columns = dataset.getColumnNames();
        int rowCount = dataset.getRowCount();
        int returnedRows = Math.min(rowCount, maxRows);
        out.put("type", "dataset");
        out.put("rowCount", rowCount);
        out.put("returnedRows", returnedRows);
        out.put("truncated", rowCount > returnedRows);
        out.set("columns", context.objectMapper().valueToTree(columns));

        ArrayNode rows = out.putArray("rows");
        for (int row = 0; row < returnedRows; row++) {
            ObjectNode rowNode = rows.addObject();
            for (int col = 0; col < columns.size(); col++) {
                String column = columns.get(col);
                rowNode.set(column, context.objectMapper().valueToTree(simplifyValue(dataset.getValueAt(row, col))));
            }
        }
        return out;
    }

    private static Object simplifyValue(Object value) {
        if (value instanceof Date date) {
            return date.getTime();
        }
        return value;
    }

    private static NamedQueryLookup lookupNamedQuery(ToolCallContext context, String projectName, String queryPath) {
        ProjectManager projectManager = context.gatewayContext().getProjectManager();
        if (projectManager == null) {
            return new NamedQueryLookup(null, null, "Project manager is unavailable");
        }
        if (!projectManager.getNames().contains(projectName)) {
            return new NamedQueryLookup(null, null, "Project not found: " + projectName);
        }

        NamedQueryManager namedQueryManager = context.gatewayContext().getNamedQueryManager();
        if (namedQueryManager == null) {
            return new NamedQueryLookup(null, null, "Named query manager is unavailable");
        }

        try {
            NamedQuery namedQuery = namedQueryManager.getQueryFromPath(projectName, queryPath);
            if (namedQuery == null) {
                return new NamedQueryLookup(null, namedQueryManager, "Named query not found: " + queryPath);
            }
            return new NamedQueryLookup(namedQuery, namedQueryManager, null);
        }
        catch (Exception e) {
            return new NamedQueryLookup(null, namedQueryManager, "Failed to read named query " + queryPath + ": " + e.getMessage());
        }
    }

    private static String description(String name) {
        return switch (name) {
            case "ignition.projects.list" -> "List configured designer projects";
            case "ignition.namedqueries.list" -> "List named queries by project";
            case "ignition.namedqueries.read" -> "Read a named query definition (SQL, type, and parameters)";
            case "ignition.namedqueries.execute" -> "Execute a named query (allowlisted, dry-run by default)";
            case "ignition.namedqueries.write" -> "Create or edit a named query resource";
            case "ignition.namedqueries.delete" -> "Delete a named query resource";
            case "ignition.namedqueries.import" -> "Import named query resources from a reviewed project resource bundle";
            default -> "Project tool";
        };
    }

    private static JsonNode inputSchema(String name) {
        ObjectNode schema = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        switch (name) {
            case "ignition.projects.list" -> props.putObject("includeNamedQueryCounts").put("type", "boolean");
            case "ignition.namedqueries.list" -> {
                props.putObject("project").put("type", "string");
                props.putObject("pathPrefix").put("type", "string");
            }
            case "ignition.namedqueries.read" -> {
                props.putObject("project").put("type", "string");
                props.putObject("path").put("type", "string");
                props.putObject("includeQuery").put("type", "boolean");
                schema.putArray("required").add("project").add("path");
            }
            case "ignition.namedqueries.execute" -> {
                props.putObject("project").put("type", "string");
                props.putObject("path").put("type", "string");
                props.putObject("parameters").put("type", "object");
                props.putObject("maxRows").put("type", "integer");
                props.putObject("includeResultData").put("type", "boolean");
                props.putObject("commit").put("type", "boolean");
                schema.putArray("required").add("project").add("path");
            }
            case "ignition.namedqueries.write" -> {
                props.putObject("operation").put("type", "string");
                props.putObject("project").put("type", "string");
                props.putObject("path").put("type", "string");
                props.putObject("type").put("type", "string");
                props.putObject("query").put("type", "string");
                props.putObject("database").put("type", "string");
                props.putObject("description").put("type", "string");
                props.putObject("enabled").put("type", "boolean");
                props.putObject("readOnly").put("type", "boolean");
                props.putObject("parameters").put("type", "array");
                props.putObject("commit").put("type", "boolean");
                schema.putArray("required").add("project").add("path").add("query");
            }
            case "ignition.namedqueries.delete" -> {
                props.putObject("project").put("type", "string");
                props.putObject("path").put("type", "string");
                props.putObject("commit").put("type", "boolean");
                schema.putArray("required").add("project").add("path");
            }
            case "ignition.namedqueries.import" -> {
                props.putObject("targetProject").put("type", "string");
                props.putObject("project").put("type", "string");
                props.putObject("bundle").put("type", "object");
                props.putObject("resources").put("type", "array");
                props.putObject("pathPrefix").put("type", "string");
                props.putObject("operation").put("type", "string");
                props.putObject("commit").put("type", "boolean");
            }
            default -> {
                // no-op
            }
        }
        return schema;
    }

    private record NamedQueryDescriptor(
        String projectName,
        String queryPath,
        String folderPath,
        String resourceName,
        boolean projectEnabled,
        boolean projectMutable
    ) {
    }

    private record NamedQueryLookup(
        NamedQuery namedQuery,
        NamedQueryManager namedQueryManager,
        String error
    ) {
    }
}
