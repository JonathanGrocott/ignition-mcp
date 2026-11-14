package com.jg.ignition.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.regex.Pattern;

import static com.jg.ignition.assistant.AssistantJson.JSON;

/** Translates prose into a bounded, self-contained 8.3.9 view. */
record PerspectiveDraft(String path, String viewJson) {
    static final String MODULE = "com.inductiveautomation.perspective";
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_. -]{0,63}");
    static final String SPEC = """
        Reply with exactly one JSON object with these fields:
        {"reply":"short reply to the Designer user","view":null}
        If the user requests a new Perspective view, set view to an object:
        {"title":"heading","subtitle":"optional subheading or empty string","sections":[
          {"heading":"section heading","body":"section text"}
        ]}
        Use 1–6 sections. Titles and headings max 80 characters, subtitle max 160,
        section text max 240. This creates a static flex layout with labels.
        Do not claim to create bindings, scripts, pages, controls or live data.
        If these are requested, explain that the draft uses plain text placeholders.
        The user chooses the view's resource path separately in Designer.
        """;

    static PerspectiveDraft create(String path, JsonNode description) throws IOException {
        requirePath(path);
        if (!description.isObject()) {
            throw new IOException("Expected a view description object.");
        }

        String title = text(description, "title", 80, false);
        String subtitle = text(description, "subtitle", 160, true);
        JsonNode sections = description.path("sections");
        if (!sections.isArray() || sections.isEmpty() || sections.size() > 6) {
            throw new IOException("A view needs between one and six sections.");
        }

        ObjectNode view = JSON.createObjectNode();
        view.putObject("custom");
        view.putObject("params");
        view.putObject("props").putObject("defaultSize").put("width", 900).put("height", 680);
        ObjectNode root = view.putObject("root").put("type", "ia.container.flex").put("version", 0);
        root.putObject("meta").put("name", "root");
        ObjectNode rootProps = root.putObject("props").put("direction", "column");
        rootProps.putObject("style").put("padding", "24px").put("gap", "8px").put("overflow", "auto")
            .put("backgroundColor", "#f4f6f9");
        ArrayNode children = root.putArray("children");
        label(children, "Title", title, "26px", true);
        if (!subtitle.isEmpty()) {
            label(children, "Subtitle", subtitle, "15px", false);
        }
        for (int i = 0; i < sections.size(); i++) {
            JsonNode section = sections.get(i);
            label(children, "Heading" + (i + 1), text(section, "heading", 80, false), "18px", true);
            label(children, "Body" + (i + 1), text(section, "body", 240, false), "15px", false);
        }
        return new PerspectiveDraft(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(view));
    }

    private static void label(ArrayNode children, String name, String text, String size, boolean heading) {
        ObjectNode label = children.addObject().put("type", "ia.display.label").put("version", 0);
        label.putObject("meta").put("name", name);
        label.putObject("position").put("basis", heading ? "34px" : "42px").put("shrink", 0);
        label.putObject("props").put("text", text).putObject("style")
            .put("fontSize", size).put("fontWeight", heading ? "600" : "400")
            .put("color", "#172b44").put("whiteSpace", "pre-wrap");
    }

    static String text(JsonNode node, String key, int limit, boolean emptyOk) throws IOException {
        JsonNode value = node.path(key);
        if (!value.isTextual() || value.textValue().length() > limit
                || (!emptyOk && value.textValue().isBlank())
                || value.textValue().codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')) {
            throw new IOException("Invalid " + key + " in the assistant response.");
        }
        return value.textValue().strip();
    }

    private static void requirePath(String value) throws IOException {
        if (value.length() > 200) {
            throw new IOException("View path must be at most 200 characters.");
        }
        for (String segment : value.split("/", -1)) {
            if (!SEGMENT.matcher(segment).matches() || !segment.equals(segment.strip())) {
                throw new IOException("Enter a view path such as Assistant/PumpOverview.");
            }
        }
    }

    ObjectNode previewArguments(String project) {
        ObjectNode arguments = JSON.createObjectNode().put("targetProject", project);
        ObjectNode entry = arguments.putObject("bundle").put("format", "ignition-mcp.project-resource-bundle.v1")
            .putArray("resources").addObject();
        entry.put("moduleId", MODULE).put("resourceType", "views").put("path", path)
            .put("operation", "create").put("applicationScope", "G").put("version", 1)
            .put("restricted", false).put("overridable", true);
        entry.putObject("attributes");
        entry.putObject("data").putObject("view.json").put("encoding", "utf-8").put("value", viewJson);
        return arguments;
    }

    void verifyPreview(String project, JsonNode result) throws IOException {
        JsonNode changes = result.path("changes");
        JsonNode row = changes.path(0);
        if (!project.equals(result.path("targetProject").asText()) || !changes.isArray() || changes.size() != 1
                || !"create".equals(row.path("action").asText()) || !MODULE.equals(row.path("moduleId").asText())
                || !"views".equals(row.path("resourceType").asText()) || !path.equals(row.path("path").asText())) {
            throw new IOException("Gateway preview did not match the requested new view.");
        }
    }
}
