package com.jg.ignition.mcp.gateway.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inductiveautomation.ignition.common.resourcecollection.Resource;
import com.inductiveautomation.ignition.common.resourcecollection.ResourcePath;
import com.jg.ignition.mcp.common.PermissionRequirement;
import com.jg.ignition.mcp.common.ToolDefinition;
import com.jg.ignition.mcp.common.ToolExecutionResult;
import com.jg.ignition.mcp.gateway.ToolCallContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Typed summaries and literal reference search over authorized, effective project resources. */
public final class ProjectInspectionToolHandler implements ToolHandler {
    private static final String PERSPECTIVE = "com.inductiveautomation.perspective";
    private static final String NOTIFICATION = "com.inductiveautomation.alarm-notification";
    private final String name;

    public ProjectInspectionToolHandler(String name) { this.name = name; }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(name, description(), PermissionRequirement.READ, schema(), false);
    }

    @Override
    public ToolExecutionResult execute(JsonNode arguments, ToolCallContext context) {
        JsonNode args = arguments == null ? context.objectMapper().createObjectNode() : arguments;
        try {
            if (!args.isObject()) { throw new IllegalArgumentException("arguments must be an object"); }
            return switch (name) {
                case "ignition.perspective.views.list", "ignition.alarms.pipelines.list" -> list(args, context);
                case "ignition.perspective.views.read" -> view(args, context);
                case "ignition.alarms.pipelines.read" -> pipeline(args, context);
                case "ignition.projects.search", "ignition.projects.references" -> search(args, context);
                default -> ToolExecutionResult.error("Unknown project inspection tool");
            };
        } catch (Exception e) { return InspectionSupport.failure(e); }
    }

    private boolean matches(ResourcePath path) {
        return name.contains("perspective") ? PERSPECTIVE.equals(path.getModuleId()) && "views".equals(path.getType())
            : NOTIFICATION.equals(path.getModuleId()) && path.getType().toLowerCase(Locale.ROOT).contains("pipeline");
    }

    private ToolExecutionResult list(JsonNode args, ToolCallContext context) {
        int limit = InspectionSupport.limit(args, "maxResults", 200, 1000);
        var inventory = InspectionSupport.inventory(context, args, this::matches);
        ObjectNode out = context.objectMapper().createObjectNode();
        ArrayNode rows = out.putArray(name.contains("perspective") ? "views" : "pipelines");
        for (var entry : inventory.entries()) {
            if (rows.size() == limit) { break; }
            rows.add(InspectionSupport.identity(context, entry.project(), entry.resource()));
        }
        out.put("count", rows.size());
        out.put("resourcesExamined", inventory.examined());
        out.put("truncated", inventory.truncated() || inventory.entries().size() > rows.size());
        return InspectionSupport.result("Project inspection inventory completed", out);
    }

    private ToolExecutionResult view(JsonNode args, ToolCallContext context) {
        String project = InspectionSupport.required(args, "project");
        String path = InspectionSupport.required(args, "path");
        int maxComponents = InspectionSupport.limit(args, "maxComponents", 300, 1000);
        int maxDepth = InspectionSupport.limit(args, "maxDepth", 20, 64);
        Resource resource = InspectionSupport.readResource(context, project, PERSPECTIVE, "views", path);
        JsonNode data = InspectionSupport.json(context, resource, "view.json");
        if (!data.path("root").isObject()) {
            return ToolExecutionResult.error("UNSUPPORTED_FORMAT", "View has no root component object");
        }
        ObjectNode out = InspectionSupport.identity(context, project, resource);
        boolean scripts = args.path("includeScripts").asBoolean(false);
        boolean properties = args.path("includeProperties").asBoolean(false);
        for (String key : List.of("params", "props", "custom", "propConfig", "events")) {
            if (data.has(key)) { out.set(key, detail(data.get(key), scripts)); }
        }
        ViewBudget budget = new ViewBudget(maxComponents, maxDepth);
        out.set("root", component(data.get("root"), "/root", 0, properties, scripts, budget, context));
        out.put("componentCount", budget.count);
        out.put("truncated", budget.truncated);
        out.put("scriptsIncluded", scripts);
        return InspectionSupport.result("Perspective view inspected", out);
    }

    private ObjectNode component(JsonNode node, String pointer, int depth, boolean properties, boolean scripts,
                                 ViewBudget budget, ToolCallContext context) {
        ObjectNode row = context.objectMapper().createObjectNode();
        row.put("jsonPointer", pointer);
        row.put("name", node.path("meta").path("name").asText(""));
        row.put("type", node.path("type").asText("unknown"));
        budget.count++;
        for (String key : List.of("version", "meta", "position", "propConfig", "events", "customMethods", "messageHandlers")) {
            if (node.has(key)) { row.set(key, detail(node.get(key), scripts)); }
        }
        if (properties) {
            for (String key : List.of("props", "custom")) {
                if (node.has(key)) { row.set(key, detail(node.get(key), scripts)); }
            }
        }
        // Keep embedding targets visible even in compact mode.
        String type = node.path("type").asText();
        if ((type.equals("ia.display.view") || type.contains("embedded")) && node.path("props").has("path")) {
            row.set("embeddedView", node.path("props").get("path").deepCopy());
        }
        ArrayNode bindings = row.putArray("bindings");
        node.path("propConfig").fields().forEachRemaining(entry -> {
            if (entry.getValue().has("binding")) {
                ObjectNode binding = bindings.addObject();
                binding.put("property", entry.getKey());
                binding.set("definition", detail(entry.getValue().get("binding"), scripts));
            }
        });
        ArrayNode children = row.putArray("children");
        JsonNode source = node.path("children");
        if (!source.isMissingNode() && !source.isArray()) {
            throw new InspectionSupport.InspectionException("UNSUPPORTED_FORMAT", "View children must be an array");
        }
        for (int i = 0; i < source.size(); i++) {
            if (budget.count >= budget.maximum || depth >= budget.maxDepth) {
                budget.truncated = true;
                row.put("childrenTruncated", true);
                break;
            }
            if (!source.get(i).isObject()) {
                throw new InspectionSupport.InspectionException("UNSUPPORTED_FORMAT", "View contains a non-object child component");
            }
            children.add(component(source.get(i), pointer + "/children/" + i, depth + 1, properties, scripts, budget, context));
        }
        return row;
    }

    private JsonNode detail(JsonNode node, boolean scripts) {
        JsonNode copy = node.deepCopy();
        prepareDetail(copy, scripts, 0);
        return copy;
    }

    private void prepareDetail(JsonNode node, boolean scripts, int depth) {
        if (depth > 64) { throw new IllegalArgumentException("Resource detail exceeds maximum nesting depth"); }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            if (!scripts && object.has("script")) {
                object.remove("script");
                object.put("scriptOmitted", true);
            }
            object.elements().forEachRemaining(child -> prepareDetail(child, scripts, depth + 1));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(child -> prepareDetail(child, scripts, depth + 1));
        }
    }

    private ToolExecutionResult pipeline(JsonNode args, ToolCallContext context) {
        String project = InspectionSupport.required(args, "project");
        String path = InspectionSupport.required(args, "path");
        String type = args.path("resourceType").asText("");
        Resource resource;
        if (!type.isEmpty()) {
            if (!type.toLowerCase(Locale.ROOT).contains("pipeline")) {
                throw new IllegalArgumentException("resourceType must identify an alarm pipeline");
            }
            resource = InspectionSupport.readResource(context, project, NOTIFICATION, type, path);
        } else {
            var inventory = InspectionSupport.inventory(context, args,
                candidate -> matches(candidate) && candidate.getPath().toString().equals(path));
            if (inventory.truncated()) {
                return ToolExecutionResult.error("RESOURCE_BUDGET_EXCEEDED", "Supply resourceType from pipelines.list to avoid an incomplete lookup");
            }
            if (inventory.entries().isEmpty()) { return ToolExecutionResult.error("NOT_FOUND", "Authorized pipeline not found"); }
            if (inventory.entries().size() > 1) { throw new IllegalArgumentException("Supply resourceType to select the pipeline resource"); }
            resource = inventory.entries().get(0).resource();
        }
        List<String> keys = new ArrayList<>(resource.getDataKeys());
        keys.sort(String::compareTo);
        ObjectNode out = InspectionSupport.identity(context, project, resource);
        ObjectNode configurations = out.putObject("configuration");
        int skipped = 0;
        int examined = 0;
        long bytesExamined = 0;
        for (String key : keys) {
            if (++examined > 32) { skipped++; continue; }
            if (!key.endsWith(".json") && !key.equals("data.bin")) { skipped++; continue; }
            var bytes = resource.getData(key);
            if (bytes.isEmpty() || bytes.get().length() > 8_388_608 - bytesExamined) { skipped++; continue; }
            bytesExamined += bytes.get().length();
            if (key.equals("data.bin")) {
                configurations.set(key, NativePipelineReader.read(context.objectMapper(), bytes.get().getBytes()));
                continue;
            }
            try {
                JsonNode configuration = InspectionSupport.json(context, resource, key);
                // A filesystem manifest alone is not a pipeline definition.
                if (key.equals("resource.json") && configuration.has("files") && configuration.has("scope")) {
                    continue;
                }
                configurations.set(key, configuration);
            } catch (InspectionSupport.InspectionException e) { skipped++; }
        }
        if (configurations.isEmpty()) {
            out.put("code", "UNSUPPORTED_FORMAT");
            out.put("message", "No supported pipeline data; expected JSON or Ignition binary serialization v1/v2");
            out.set("dataKeys", context.objectMapper().valueToTree(keys));
            return ToolExecutionResult.error("Pipeline format is not supported", out);
        }
        out.put("skippedFiles", skipped);
        out.put("complete", skipped == 0);
        out.put("note", "Stored pipeline configuration only; does not describe running notification jobs");
        return InspectionSupport.result("Alarm pipeline configuration inspected", out);
    }

    private ToolExecutionResult search(JsonNode args, ToolCallContext context) {
        String query = InspectionSupport.required(args, name.endsWith("references") ? "target" : "query");
        if (query.length() > 512) { throw new IllegalArgumentException("Search text exceeds 512 characters"); }
        String module = args.path("moduleId").asText("");
        String type = args.path("resourceType").asText("");
        var inventory = InspectionSupport.inventory(context, args,
            path -> (module.isEmpty() || module.equals(path.getModuleId())) && (type.isEmpty() || type.equals(path.getType())));
        ObjectNode out = context.objectMapper().createObjectNode();
        ArrayNode matches = out.putArray("matches");
        SearchBudget budget = new SearchBudget(InspectionSupport.limit(args, "maxResults", 100, 500),
            query, args.path("caseSensitive").asBoolean(name.endsWith("references")));
        int skipped = 0;
        int files = 0;
        outer: for (var entry : inventory.entries()) {
            List<String> keys = new ArrayList<>(entry.resource().getDataKeys());
            keys.sort(String::compareTo);
            for (String key : keys) {
                if (files >= 2000 || budget.bytes >= 8_388_608 || budget.nodes >= 50000 || matches.size() >= budget.maximum) {
                    budget.truncated = true;
                    break outer;
                }
                files++;
                var bytes = entry.resource().getData(key);
                if (bytes.isEmpty()) { skipped++; continue; }
                if (bytes.get().length() > 8_388_608 - budget.bytes) { budget.truncated = true; skipped++; break outer; }
                budget.bytes += bytes.get().length();
                var text = InspectionSupport.text(bytes.get());
                if (text.isEmpty()) { skipped++; continue; }
                JsonNode parsed = null;
                if (key.endsWith(".json")) {
                    try { parsed = context.objectMapper().readTree(text.get()); } catch (Exception ignored) { /* Search as text. */ }
                }
                if (parsed != null) {
                    searchJson(parsed, "", 0, key, entry, matches, budget, context);
                } else {
                    String[] lines = text.get().split("\n", -1);
                    for (int line = 0; line < lines.length; line++) {
                        if (budget.nodes++ >= 50000 || matches.size() >= budget.maximum) { budget.truncated = true; break; }
                        if (budget.contains(lines[line])) {
                            ObjectNode match = match(context, entry, key, lines[line], budget);
                            match.put("line", line + 1);
                            matches.add(match);
                        }
                    }
                }
            }
        }
        out.put("count", matches.size());
        out.put("resourcesExamined", inventory.examined());
        out.put("filesExamined", files);
        out.put("skippedFiles", skipped);
        out.put("truncated", inventory.truncated() || budget.truncated);
        out.put("complete", !inventory.truncated() && !budget.truncated && skipped == 0);
        out.put("matchKind", "literal");
        out.put("limitations", "Literal source matches only; dynamic, relative, inherited and computed references may not match. Results are candidates, not a complete dependency graph.");
        return InspectionSupport.result("Project source search completed", out);
    }

    private void searchJson(JsonNode node, String pointer, int depth, String key, InspectionSupport.Entry entry,
                            ArrayNode matches, SearchBudget budget, ToolCallContext context) {
        if (depth > 64 || budget.nodes++ >= 50000 || matches.size() >= budget.maximum) { budget.truncated = true; return; }
        if (node.isValueNode()) {
            if (budget.contains(node.asText())) {
                ObjectNode row = match(context, entry, key, node.asText(), budget);
                row.put("jsonPointer", pointer);
                matches.add(row);
            }
        } else if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                if (matches.size() >= budget.maximum || budget.nodes >= 50000) { budget.truncated = true; break; }
                var field = fields.next();
                searchJson(field.getValue(), pointer + "/" + field.getKey().replace("~", "~0").replace("/", "~1"),
                    depth + 1, key, entry, matches, budget, context);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                if (matches.size() >= budget.maximum || budget.nodes >= 50000) { budget.truncated = true; break; }
                searchJson(node.get(i), pointer + "/" + i, depth + 1, key, entry, matches, budget, context);
            }
        }
    }

    private ObjectNode match(ToolCallContext context, InspectionSupport.Entry entry, String key, String value, SearchBudget budget) {
        ObjectNode row = InspectionSupport.identity(context, entry.project(), entry.resource());
        row.put("file", key);
        int offset = budget.normalized(value).indexOf(budget.query);
        int start = Math.max(0, offset - 80);
        row.put("excerpt", value.substring(start, Math.min(value.length(), start + 300)));
        return row;
    }

    private String description() {
        return switch (name) {
            case "ignition.perspective.views.list" -> "List authorized Perspective views with resource identities and inheritance provenance";
            case "ignition.perspective.views.read" -> "Inspect a bounded Perspective component tree, parameters, binding configs/transforms and event actions; scripts are optional";
            case "ignition.alarms.pipelines.list" -> "List authorized Alarm Notification pipeline project resources (not runtime alarm events)";
            case "ignition.alarms.pipelines.read" -> "Read stored JSON or native binary alarm pipeline configuration without executing scripts or notifications";
            case "ignition.projects.references" -> "Find literal reference candidates with source locations; cannot resolve dynamic references or prove a complete dependency graph";
            default -> "Search authorized project resource text and JSON values with bounded work and source locations (literal search, no regex)";
        };
    }

    private ObjectNode schema() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        for (String key : List.of("project", "path", "pathPrefix", "resourceType", "moduleId", "query", "target")) {
            properties.putObject(key).put("type", "string");
        }
        for (String key : List.of("maxResults", "maxComponents", "maxDepth", "maxResourcesExamined")) {
            properties.putObject(key).put("type", "integer").put("minimum", 1);
        }
        for (String key : List.of("includeScripts", "includeProperties", "caseSensitive")) {
            properties.putObject(key).put("type", "boolean");
        }
        ArrayNode required = schema.putArray("required");
        if (name.endsWith(".read")) { required.add("project").add("path"); }
        if (name.endsWith(".search")) { required.add("query"); }
        if (name.endsWith(".references")) { required.add("target"); }
        return schema;
    }

    private static final class ViewBudget {
        final int maximum;
        final int maxDepth;
        int count;
        boolean truncated;
        ViewBudget(int maximum, int maxDepth) { this.maximum = maximum; this.maxDepth = maxDepth; }
    }
    private static final class SearchBudget {
        final int maximum;
        final String query;
        final boolean caseSensitive;
        int nodes;
        long bytes;
        boolean truncated;
        SearchBudget(int maximum, String query, boolean caseSensitive) {
            this.maximum = maximum; this.caseSensitive = caseSensitive; this.query = normalized(query);
        }
        String normalized(String value) { return caseSensitive ? value : value.toLowerCase(Locale.ROOT); }
        boolean contains(String value) { return normalized(value).contains(query); }
    }
}
