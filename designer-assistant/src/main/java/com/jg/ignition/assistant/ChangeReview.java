package com.jg.ignition.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

import static com.jg.ignition.assistant.AssistantJson.JSON;

record ChangeReview(String title, String target, List<String> details, List<Source> sources,
                    String approveLabel, Callable<AssistantAgent.Applied> apply) {
    private static final int MAX_DETAILS = 150;
    record Source(String title, String text) {}

    static ChangeReview gateway(String tool, ObjectNode args, JsonNode preview,
                                Callable<AssistantAgent.Applied> apply) {
        String title = describe(tool, args);
        String target = preview.path("targetProject").asText(preview.path("project").asText(
            args.path("path").asText(args.path("source").asText(""))));
        var details = new ArrayList<String>();
        var sources = new ArrayList<Source>();
        if (preview.path("changes").isArray()) {
            if (preview.path("changes").size() == 1) {
                var change = preview.path("changes").get(0);
                title = capitalize(change.path("action").asText()) + " " + resourceKind(change);
            }
            for (JsonNode change : preview.path("changes")) {
                String path = change.path("path").asText();
                details.add(capitalize(change.path("action").asText()) + " " + resourceKind(change) + " " + path);
                for (JsonNode file : change.path("fileChanges")) {
                    String filename = file.path("file").asText();
                    details.add("  " + filename + numberChange(file));
                    addResourceSource(sources, path, filename, file, "before", "Current");
                    addResourceSource(sources, path, filename, file, "after", "Proposed");
                }
            }
        } else if (ProjectTools.DEFINITIONS.equals(tool)) {
            describeDefinition(args, "", details, 0);
            describeDefinitionOutcomes(preview, details, 0);
        } else if ("ignition.tags.write".equals(tool)) {
            for (JsonNode write : args.path("writes")) {
                details.add(write.path("path").asText() + " → " + value(write.path("value")));
            }
        } else {
            for (String key : List.of("path", "scriptType", "source", "database", "type", "enabled", "readOnly",
                    "delay", "fixedDelay", "sharedThread", "threadType", "cronExpression", "notes", "description")) {
                if (args.has(key) && !target.equals(args.path(key).asText())) {
                    details.add(label(key) + "  " + value(args.path(key)));
                }
            }
            flatten(details, "Parameter", args.path("parameters"), 0);
            flatten(details, "Alarm event", args.path("eventIds"), 0);
            if ("ignition.namedqueries.execute".equals(tool)) {
                details.add("Runs the saved query against its configured database.");
            }
            if (args.path("code").isTextual()) {
                sources.add(new Source("Proposed Python source", args.path("code").asText()));
            }
            if (args.path("query").isTextual()) {
                sources.add(new Source("Proposed SQL", args.path("query").asText()));
            }
            for (JsonNode resource : preview.path("resources")) {
                details.add(resource.path("path").asText(resource.path("message").asText("Imported resource")));
            }
            JsonNode resources = args.path("bundle").path("resources");
            if (!resources.isArray()) {
                resources = args.path("resources");
            }
            for (JsonNode resource : resources) {
                String path = resource.path("path").asText();
                details.add(resourceKind(resource) + " " + path);
                resource.path("data").fields().forEachRemaining(file -> {
                    var data = file.getValue();
                    details.add("  " + file.getKey() + ("base64".equals(data.path("encoding").asText()) ? " (encoded)" : ""));
                    if (data.path("value").isTextual() && !"base64".equals(data.path("encoding").asText())
                            && (file.getKey().endsWith(".py") || file.getKey().endsWith(".sql"))) {
                        sources.add(new Source(path + " / " + file.getKey(), data.path("value").asText()));
                    }
                });
            }
        }
        if (target.isBlank() && details.isEmpty()) {
            details.add("Review the proposed operation before applying.");
        }
        if (details.size() > MAX_DETAILS) {
            int omitted = details.size() - MAX_DETAILS;
            details.subList(MAX_DETAILS, details.size()).clear();
            details.add(omitted + " more details in this batch.");
        }
        return new ChangeReview(title, target, List.copyOf(details), List.copyOf(sources), "Approve change", apply);
    }

    static String describe(String tool, JsonNode args) {
        String operation = args.path("operation").asText("upsert");
        String verb = switch (operation) {
            case "create" -> "Create";
            case "edit" -> "Edit";
            case "delete" -> "Delete";
            default -> "Save";
        };
        return switch (tool) {
            case "ignition.scripts.project.write" -> verb + " project script";
            case "ignition.scripts.project.delete" -> "Delete project script";
            case "ignition.scripts.project.import" -> "Import project scripts";
            case "ignition.namedqueries.write" -> verb + " Named Query";
            case "ignition.namedqueries.delete" -> "Delete Named Query";
            case "ignition.namedqueries.import" -> "Import Named Queries";
            case "ignition.namedqueries.execute" -> "Run Named Query";
            case "ignition.tags.write" -> "Write live tag values";
            case ProjectTools.DEFINITIONS -> verb + " tag and UDT definitions";
            case "ignition.alarms.acknowledge" -> "Acknowledge alarms";
            case ProjectTools.PREVIEW, ProjectTools.APPLY, "ignition.projects.changes.write" -> "Update project resources";
            default -> "Inspect " + tool.replaceFirst("^ignition\\.", "").replace('.', ' ');
        };
    }

    private static void describeDefinition(JsonNode def, String parent, List<String> details, int depth) {
        if (depth > 16 || details.size() > MAX_DETAILS) {
            return;
        }
        if (def.path("definitions").isArray()) {
            def.path("definitions").forEach(child -> describeDefinition(child, parent, details, depth + 1));
            return;
        }
        String local = def.path("path").asText(def.path("name").asText());
        String path = parent.isEmpty() ? local : parent + "/" + local;
        String kind = def.path("tagObjectType").asText();
        details.add(capitalize(def.path("operation").asText("save")) + " " + path
            + (kind.isBlank() ? "" : " (" + kind + ")"));
        if (def.has("provider")) {
            details.add("  Provider  " + value(def.path("provider")));
        }
        flatten(details, "  Property", def.path("properties"), 0);
        flatten(details, "  Binding", def.path("bindings"), 0);
        flatten(details, "  Remove property", def.path("removeProperties"), 0);
        def.path("children").forEach(child -> describeDefinition(child, path, details, depth + 1));
    }

    private static void describeDefinitionOutcomes(JsonNode preview, List<String> details, int depth) {
        if (depth > 8) {
            return;
        }
        if (preview.path("path").isTextual()) {
            details.add("Target  " + preview.path("path").asText());
        }
        for (JsonNode row : preview.path("results")) {
            describeDefinitionOutcomes(row.path("structuredContent"), details, depth + 1);
        }
    }

    private static void addResourceSource(List<Source> sources, String path, String filename, JsonNode file,
                                          String prefix, String title) {
        String text = file.path(prefix + "Text").asText("");
        if (text.isEmpty()) {
            return;
        }
        if (filename.equals("view.json")) {
            if (file.path(prefix + "TextTruncated").asBoolean()) {
                return;
            }
            try {
                var view = JSON.readTree(text);
                var components = new ArrayList<String>();
                describeComponents(view.path("root"), components, 0);
                if (!components.isEmpty()) {
                    sources.add(new Source(title + " components in " + path, String.join("\n", components)));
                }
            } catch (java.io.IOException ignored) {
                // The Gateway file comparison still identifies the changed file and size.
            }
        } else if (filename.endsWith(".py") || filename.endsWith(".sql")) {
            sources.add(new Source(title + " " + path + " / " + filename, text
                + (file.path(prefix + "TextTruncated").asBoolean() ? "\n[Excerpt from Gateway preview]" : "")));
        } else if (filename.endsWith(".json") && !file.path(prefix + "TextTruncated").asBoolean()) {
            try {
                var fields = new ArrayList<String>();
                flatten(fields, "", JSON.readTree(text), 0);
                if (!fields.isEmpty()) {
                    sources.add(new Source(title + " " + path + " / " + filename, String.join("\n", fields)));
                }
            } catch (java.io.IOException ignored) {
                // A typed view of this file is unavailable.
            }
        }
    }

    private static void describeComponents(JsonNode node, List<String> components, int level) {
        if (!node.isObject() || level > 12 || components.size() > MAX_DETAILS) {
            return;
        }
        String name = node.path("meta").path("name").asText();
        String type = node.path("type").asText();
        components.add("  ".repeat(level) + (name.isBlank() ? type : name + " (" + type + ")"));
        flatten(components, "  ".repeat(level + 1) + "Properties", node.path("props"), 0);
        flatten(components, "  ".repeat(level + 1) + "Bindings", node.path("propConfig"), 0);
        for (JsonNode child : node.path("children")) {
            describeComponents(child, components, level + 1);
        }
    }

    private static void flatten(List<String> lines, String key, JsonNode node, int depth) {
        if (node.isMissingNode() || lines.size() > MAX_DETAILS) {
            return;
        }
        if (node.isValueNode()) {
            lines.add(key.stripTrailing() + "  " + value(node));
        } else if (depth >= 8) {
            lines.add(key + " (" + node.size() + " nested values)");
        } else if (node.isObject()) {
            node.fields().forEachRemaining(field -> flatten(lines,
                key.isBlank() ? field.getKey() : key + " / " + field.getKey(), field.getValue(), depth + 1));
        } else {
            for (int i = 0; i < node.size(); i++) {
                flatten(lines, key + " " + (i + 1), node.get(i), depth + 1);
            }
        }
    }

    private static String value(JsonNode node) {
        if (node.isContainerNode()) {
            var lines = new ArrayList<String>();
            flatten(lines, "", node, 0);
            return String.join("; ", lines);
        }
        return node.isNull() ? "(null)" : node.asText();
    }

    private static String numberChange(JsonNode file) {
        String before = file.has("beforeBytes") ? file.path("beforeBytes").asText() : "absent";
        String after = file.has("afterBytes") ? file.path("afterBytes").asText() : "absent";
        return " (" + before + " → " + after + " bytes)";
    }

    static String queryResult(JsonNode data) {
        var result = data.path("result");
        if (result.isMissingNode()) {
            return "";
        }
        if (!result.path("rows").isArray()) {
            return "\nResult  " + value(result);
        }
        var text = new StringBuilder("\n").append(result.path("rowCount").asInt()).append(" rows");
        int rows = Math.min(25, result.path("rows").size());
        for (int i = 0; i < rows; i++) {
            text.append("\n").append(i + 1).append(". ").append(value(result.path("rows").get(i)));
        }
        if (rows < result.path("rowCount").asInt()) {
            text.append("\nShowing ").append(rows).append(" rows.");
        }
        return text.toString();
    }

    private static String resourceKind(JsonNode change) {
        return switch (change.path("resourceType").asText()) {
            case "views" -> "Perspective view";
            case "script-python" -> "Project Library script";
            case "named-query", "named-queries" -> "Named Query";
            case "" -> "resource";
            default -> change.path("resourceType").asText().replace('-', ' ');
        };
    }

    private static String label(String key) {
        return capitalize(key.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT));
    }

    private static String capitalize(String text) {
        return text.isBlank() ? text : text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }
}
