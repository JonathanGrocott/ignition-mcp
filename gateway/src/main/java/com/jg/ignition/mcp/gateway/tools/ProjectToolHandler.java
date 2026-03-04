package com.jg.ignition.mcp.gateway.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inductiveautomation.ignition.common.Dataset;
import com.inductiveautomation.ignition.common.db.namedquery.NamedQuery;
import com.inductiveautomation.ignition.common.db.namedquery.NamedQueryManager;
import com.inductiveautomation.ignition.common.resourcecollection.Resource;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceCollectionManifest;
import com.inductiveautomation.ignition.common.resourcecollection.ResourcePath;
import com.inductiveautomation.ignition.common.resourcecollection.RuntimeResourceCollection;
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
        if (!context.safetyPolicy().isNamedQueryExecuteAllowed(projectName, queryPath)) {
            context.auditLogger().logWriteAttempt(
                context.authContext().tokenName(),
                toolName,
                false,
                false,
                projectName + "/" + queryPath
            );
            return ToolExecutionResult.error("Named query execute blocked by allowlist: " + projectName + "/" + queryPath);
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
        return "ignition.namedqueries.execute".equals(name);
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
