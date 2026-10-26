package com.jg.ignition.mcp.gateway.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inductiveautomation.ignition.common.ImmutableBytes;
import com.inductiveautomation.ignition.common.resourcecollection.Resource;
import com.inductiveautomation.ignition.common.resourcecollection.ResourcePath;
import com.inductiveautomation.ignition.gateway.project.ProjectManager;
import com.jg.ignition.mcp.common.ToolExecutionResult;
import com.jg.ignition.mcp.gateway.ToolCallContext;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/** Shared bounds and policy checks for read-only discovery. Never reads Gateway files directly. */
final class InspectionSupport {
    static final int MAX_FILE_BYTES = 1_048_576;
    static final int MAX_RESULT_BYTES = 2_097_152;

    private InspectionSupport() {}

    static int limit(JsonNode args, String key, int fallback, int maximum) {
        JsonNode value = args.path(key);
        if (value.isMissingNode()) {
            return fallback;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1 || value.intValue() > maximum) {
            throw new IllegalArgumentException(key + " must be an integer from 1 to " + maximum);
        }
        return value.intValue();
    }

    static String required(JsonNode args, String key) {
        JsonNode value = args.path(key);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value.asText().trim();
    }

    static boolean allowed(ToolCallContext context, String project, Resource resource) {
        ResourcePath path = resource.getResourcePath();
        return path != null && context.safetyPolicy().isProjectResourceReadAllowed(
            context.authContext().tokenName(), project, path.getModuleId(), path.getType(), path.getPath().toString());
    }

    static Resource readResource(ToolCallContext context, String project, String module, String type, String path) {
        if (!context.safetyPolicy().isProjectResourceReadAllowed(context.authContext().tokenName(), project, module, type, path)) {
            throw new InspectionException("ALLOWLIST_BLOCKED", "Project resource blocked by allowlist");
        }
        ProjectManager manager = manager(context);
        return manager.getResource(project, ProjectResourceService.resourcePath(module, type, path))
            .orElseThrow(() -> new InspectionException("NOT_FOUND", "Project resource not found"));
    }

    static ProjectManager manager(ToolCallContext context) {
        ProjectManager manager = context.gatewayContext().getProjectManager();
        if (manager == null) {
            throw new InspectionException("SDK_UNAVAILABLE", "Project manager is unavailable");
        }
        return manager;
    }

    static Inventory inventory(ToolCallContext context, JsonNode args, Predicate<ResourcePath> filter) {
        ProjectManager manager = manager(context);
        String project = args.path("project").asText("").trim();
        String prefix = args.path("pathPrefix").asText("");
        List<String> projects = new ArrayList<>(manager.getNames());
        projects.sort(String::compareTo);
        if (!project.isEmpty()) {
            if (!projects.contains(project)) {
                throw new InspectionException("NOT_FOUND", "Project not found");
            }
            projects = List.of(project);
        }
        int maximum = limit(args, "maxResourcesExamined", 5000, 10000);
        int examined = 0;
        List<Entry> entries = new ArrayList<>();
        for (String name : projects) {
            var collection = manager.find(name);
            if (collection.isEmpty()) {
                continue;
            }
            for (Resource resource : collection.get().getAllResources().values()) {
                if (examined == maximum) {
                    entries.sort(Comparator.comparing(Entry::key));
                    return new Inventory(entries, examined, true);
                }
                examined++;
                ResourcePath path = resource.getResourcePath();
                if (path != null && !resource.isFolder() && filter.test(path)
                    && path.getPath().toString().startsWith(prefix) && allowed(context, name, resource)) {
                    entries.add(new Entry(name, resource));
                }
            }
        }
        entries.sort(Comparator.comparing(Entry::key));
        return new Inventory(entries, examined, false);
    }

    static ObjectNode identity(ToolCallContext context, String project, Resource resource) {
        ObjectNode row = context.objectMapper().createObjectNode();
        ResourcePath path = resource.getResourcePath();
        row.put("project", project);
        row.put("definingProject", resource.getDefiningCollectionName());
        row.put("moduleId", path.getModuleId());
        row.put("resourceType", path.getType());
        row.put("path", path.getPath().toString());
        row.put("documentation", resource.getDocumentation());
        return row;
    }

    static Optional<String> text(ImmutableBytes bytes) {
        if (bytes.length() > MAX_FILE_BYTES) {
            return Optional.empty();
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.getBytes())).toString();
            if (text.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
                return Optional.empty();
            }
            return Optional.of(text);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    static JsonNode json(ToolCallContext context, Resource resource, String key) {
        var bytes = resource.getData(key).orElseThrow(() -> new InspectionException("UNSUPPORTED_FORMAT", "Resource has no " + key));
        String text = text(bytes).orElseThrow(() -> new InspectionException("UNSUPPORTED_FORMAT", "Resource data is binary or exceeds the 1 MiB file limit"));
        try {
            JsonNode result = context.objectMapper().readTree(text);
            if (result == null || !result.isObject()) {
                throw new IllegalArgumentException("Expected a JSON object");
            }
            return result;
        } catch (Exception e) {
            throw new InspectionException("UNSUPPORTED_FORMAT", "Resource data is not a supported JSON object");
        }
    }

    static ToolExecutionResult result(String message, ObjectNode body) {
        if (body.toString().getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_BYTES) {
            return ToolExecutionResult.error("RESULT_TOO_LARGE", "Inspection result exceeds 2 MiB; narrow the request or reduce limits");
        }
        return ToolExecutionResult.ok(message, body);
    }

    static ToolExecutionResult failure(Exception e) {
        if (e instanceof InspectionException failure) {
            return ToolExecutionResult.error(failure.code, failure.getMessage());
        }
        if (e instanceof IllegalArgumentException) {
            return ToolExecutionResult.error("VALIDATION_ERROR", e.getMessage());
        }
        // SDK exceptions can contain SQL, connection strings or implementation details.
        return ToolExecutionResult.error("SDK_FAILURE", "Inspection failed while reading Gateway state");
    }

    record Entry(String project, Resource resource) {
        String key() { return project + "/" + resource.getResourcePath(); }
    }
    record Inventory(List<Entry> entries, int examined, boolean truncated) {}

    static final class InspectionException extends RuntimeException {
        final String code;
        InspectionException(String code, String message) { super(message); this.code = code; }
    }
}
