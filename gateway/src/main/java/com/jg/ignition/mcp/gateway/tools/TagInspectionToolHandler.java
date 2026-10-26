package com.jg.ignition.mcp.gateway.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inductiveautomation.ignition.common.browsing.BrowseFilter;
import com.inductiveautomation.ignition.common.tags.config.TagConfigurationModel;
import com.inductiveautomation.ignition.common.tags.config.types.TagObjectType;
import com.inductiveautomation.ignition.common.tags.model.TagPath;
import com.inductiveautomation.ignition.common.tags.paths.parser.TagPathParser;
import com.jg.ignition.mcp.common.PermissionRequirement;
import com.jg.ignition.mcp.common.ToolDefinition;
import com.jg.ignition.mcp.common.ToolExecutionResult;
import com.jg.ignition.mcp.gateway.ToolCallContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Budgeted discovery using one-level configuration reads and browses, never an unbounded recursive export. */
public final class TagInspectionToolHandler implements ToolHandler {
    private final String name;
    public TagInspectionToolHandler(String name) { this.name = name; }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(name, switch (name) {
            case "ignition.udts.list" -> "Discover UDT definitions below a provider's _types_ folder within tag, depth, browse and time budgets";
            case "ignition.udts.read" -> "Inspect a UDT definition and authorized members, including parameters, inherited and local properties";
            case "ignition.tags.opc.search" -> "Search authorized tag configurations for OPC server/item addresses; returns mappings, not PLC logic";
            default -> "Capture authorized tags and optional live values/quality with explicit tag, depth, browse and time budgets; not an atomic snapshot";
        }, PermissionRequirement.READ, schema(), false);
    }

    @Override
    public ToolExecutionResult execute(JsonNode arguments, ToolCallContext context) {
        try {
            JsonNode args = arguments == null ? context.objectMapper().createObjectNode() : arguments;
            if (!args.isObject()) { throw new IllegalArgumentException("arguments must be an object"); }
            boolean udtList = name.equals("ignition.udts.list");
            boolean udtRead = name.equals("ignition.udts.read");
            boolean opc = name.equals("ignition.tags.opc.search");
            boolean values = name.equals("ignition.tags.snapshot") && args.path("includeValues").asBoolean(true);
            String path = args.path("path").asText("").trim();
            if (path.isEmpty() && udtList) {
                String provider = args.path("provider").asText("default");
                if (provider.isBlank() || provider.contains("[") || provider.contains("]") || provider.contains("/")) {
                    throw new IllegalArgumentException("Invalid provider name");
                }
                path = "[" + provider + "]_types_";
            }
            if (path.isEmpty()) { throw new IllegalArgumentException("path is required"); }
            TagPath root = TagPathParser.parseSafe(path);
            if (root == null || root.getSource() == null || root.getSource().isBlank()) {
                throw new IllegalArgumentException("path must include a provider");
            }
            if (!allowed(context, root)) { return ToolExecutionResult.error("ALLOWLIST_BLOCKED", "Root tag path blocked by allowlist"); }
            var manager = context.gatewayContext().getTagManager();
            if (manager == null) { return ToolExecutionResult.error("SDK_UNAVAILABLE", "Tag manager is unavailable"); }
            var provider = manager.getTagProvider(root.getSource());
            if (provider == null) { return ToolExecutionResult.error("NOT_FOUND", "Tag provider not found"); }
            int maxTags = InspectionSupport.limit(args, "maxTags", 200, 1000);
            int maxBrowses = InspectionSupport.limit(args, "maxBrowses", 30, 100);
            int maxDepth = InspectionSupport.limit(args, "maxDepth", 5, 20);
            int duration = InspectionSupport.limit(args, "maxDurationMs", 10000, 30000);
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(duration);
            String query = args.path("query").asText("").toLowerCase(Locale.ROOT);
            if (query.length() > 512) { throw new IllegalArgumentException("query exceeds 512 characters"); }
            ObjectNode out = context.objectMapper().createObjectNode();
            out.put("path", root.toStringFull());
            out.put("startedAt", System.currentTimeMillis());
            ArrayNode rows = out.putArray(udtList || udtRead ? "definitions" : "tags");
            ArrayNode errors = out.putArray("errors");
            ArrayNode reasons = out.putArray("truncationReasons");
            Set<String> reasonSet = new HashSet<>();
            ArrayDeque<Pending> queue = new ArrayDeque<>();
            Set<String> seen = new HashSet<>();
            queue.add(new Pending(root, 0));
            List<TagPath> valuePaths = new ArrayList<>();
            List<ObjectNode> valueRows = new ArrayList<>();
            int examined = 0;
            int browses = 0;
            while (!queue.isEmpty()) {
                if (examined >= maxTags) { reasonSet.add("maxTags"); break; }
                if (System.nanoTime() >= deadline) { reasonSet.add("maxDurationMs"); break; }
                Pending current = queue.removeFirst();
                if (!seen.add(current.path.toStringFull()) || !allowed(context, current.path)) { continue; }
                examined++;
                List<TagConfigurationModel> configs;
                try {
                    configs = await(provider.getTagConfigsAsync(List.of(current.path), false, false), deadline);
                } catch (Exception e) {
                    errors.addObject().put("path", current.path.toStringFull()).put("code", e instanceof TimeoutException ? "TIMEOUT" : "CONFIG_READ_FAILED");
                    if (e instanceof TimeoutException) { reasonSet.add("maxDurationMs"); break; }
                    continue;
                }
                if (configs.isEmpty()) {
                    if (current.depth == 0) { return ToolExecutionResult.error("NOT_FOUND", "Root tag configuration not found"); }
                    errors.addObject().put("path", current.path.toStringFull()).put("code", "NOT_FOUND");
                    continue;
                }
                TagConfigurationModel config = configs.get(0);
                if (udtRead && current.depth == 0 && config.getType() != TagObjectType.UdtType) {
                    return ToolExecutionResult.error("VALIDATION_ERROR", "path must identify a UDT definition");
                }
                ObjectNode properties = TagDefinitionToolHandler.serializeBoundPropertySet(context, config.getTagProperties());
                boolean isOpc = properties.has("opcItemPath") || properties.has("opcServer");
                String mapping = (properties.path("opcItemPath").toString() + " " + properties.path("opcServer").toString()
                    + " " + current.path.toStringFull()).toLowerCase(Locale.ROOT);
                boolean include = udtRead || (udtList ? config.getType() == TagObjectType.UdtType : !opc || isOpc && mapping.contains(query));
                if (include) {
                    ObjectNode row = rows.addObject();
                    row.put("path", current.path.toStringFull());
                    row.put("name", config.getName());
                    row.put("type", config.getType() == null ? "Unknown" : config.getType().name());
                    row.put("inherited", config.isInherited());
                    row.put("udtMember", config.isUdtMember());
                    if (udtRead) {
                        row.set("properties", properties);
                        if (config.getLocalConfiguration() != null) {
                            row.set("localProperties", TagDefinitionToolHandler.serializeBoundPropertySet(context, config.getLocalConfiguration().getTagProperties()));
                        }
                    } else {
                        for (String key : List.of("dataType", "documentation", "valueSource", "opcServer", "opcItemPath", "typeId", "parameters")) {
                            if (properties.has(key)) { row.set(key, properties.get(key)); }
                        }
                    }
                    if (values && config.getType() == TagObjectType.AtomicTag) { valuePaths.add(current.path); valueRows.add(row); }
                }
                boolean children = config.getType() == TagObjectType.Folder || config.getType() == TagObjectType.Provider
                    || config.getType() == TagObjectType.UdtInstance || config.getType() == TagObjectType.UdtType;
                // UDT listing only needs to traverse folders, not members of definitions already found.
                if (!children || udtList && config.getType() == TagObjectType.UdtType) { continue; }
                if (current.depth >= maxDepth) { reasonSet.add("maxDepth"); continue; }
                if (browses >= maxBrowses) { reasonSet.add("maxBrowses"); continue; }
                if (examined + queue.size() >= maxTags) { reasonSet.add("maxTags"); continue; }
                try {
                    browses++;
                    int remaining = maxTags - examined - queue.size();
                    var result = await(manager.browseAsync(current.path, new BrowseFilter().setMaxResults(remaining)), deadline);
                    if (result.getResultQuality() != null && result.getResultQuality().isNotGood()) {
                        errors.addObject().put("path", current.path.toStringFull()).put("code", "BROWSE_BAD_QUALITY");
                        continue;
                    }
                    if (result.getContinuationPoint() != null && !result.getContinuationPoint().isEmpty()
                        || result.getTotalAvailableSize() > result.getReturnedSize()) { reasonSet.add("browseContinuation"); }
                    int discovered = 0;
                    for (var node : result.getResults()) {
                        if (discovered++ >= remaining) { reasonSet.add("maxTags"); break; }
                        TagPath child = node.getFullPath();
                        if (child == null || child.getSource() == null || child.getSource().isBlank()) {
                            child = current.path.getChildPath(node.getName());
                        }
                        if (allowed(context, child)) { queue.addLast(new Pending(child, current.depth + 1)); }
                    }
                } catch (Exception e) {
                    errors.addObject().put("path", current.path.toStringFull()).put("code", e instanceof TimeoutException ? "TIMEOUT" : "BROWSE_FAILED");
                    if (e instanceof TimeoutException) { reasonSet.add("maxDurationMs"); break; }
                }
            }
            if (!valuePaths.isEmpty()) {
                try {
                    var read = await(manager.readAsync(valuePaths), deadline);
                    for (int i = 0; i < valueRows.size(); i++) {
                        ObjectNode row = valueRows.get(i);
                        if (i >= read.size() || read.get(i) == null) {
                            errors.addObject().put("path", valuePaths.get(i).toStringFull()).put("code", "MISSING_READ_RESULT");
                            continue;
                        }
                        var value = read.get(i);
                        row.set("value", context.objectMapper().valueToTree(value.getValue()));
                        row.put("quality", value.getQuality() == null ? "Unknown" : value.getQuality().toString());
                        row.put("good", value.getQuality() != null && value.getQuality().isGood());
                        if (value.getTimestamp() != null) { row.put("timestamp", value.getTimestamp().getTime()); }
                    }
                } catch (Exception e) {
                    errors.addObject().put("code", e instanceof TimeoutException ? "TIMEOUT" : "VALUE_READ_FAILED");
                    if (e instanceof TimeoutException) { reasonSet.add("maxDurationMs"); }
                }
            }
            reasonSet.stream().sorted().forEach(reasons::add);
            out.put("count", rows.size());
            out.put("tagsExamined", examined);
            out.put("browses", browses);
            out.put("truncated", !reasonSet.isEmpty());
            out.put("complete", reasonSet.isEmpty() && errors.isEmpty());
            out.put("finishedAt", System.currentTimeMillis());
            out.put("note", values ? "Values are collected after browsing; this is not an atomic process snapshot" : "Configuration discovery only; OPC paths do not resolve PLC logic");
            return InspectionSupport.result("Tag inspection completed", out);
        } catch (Exception e) { return InspectionSupport.failure(e); }
    }

    private boolean allowed(ToolCallContext context, TagPath path) {
        return context.safetyPolicy().isTagReadAllowed(context.authContext().tokenName(), path.toStringFull());
    }

    private <T> T await(Future<T> future, long deadline) throws Exception {
        long remaining = deadline - System.nanoTime();
        try {
            if (remaining <= 0) { throw new TimeoutException(); }
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    private ObjectNode schema() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        ObjectNode out = mapper.createObjectNode().put("type", "object");
        ObjectNode properties = out.putObject("properties");
        for (String key : List.of("path", "provider", "query")) { properties.putObject(key).put("type", "string"); }
        for (String key : List.of("maxTags", "maxBrowses", "maxDepth", "maxDurationMs")) {
            properties.putObject(key).put("type", "integer").put("minimum", 1);
        }
        properties.putObject("includeValues").put("type", "boolean");
        if (!name.equals("ignition.udts.list")) { out.putArray("required").add("path"); }
        return out;
    }
    private record Pending(TagPath path, int depth) {}
}
