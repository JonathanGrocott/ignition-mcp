package com.jg.ignition.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Set;
import java.util.regex.Pattern;

import static com.jg.ignition.assistant.AssistantJson.JSON;

record McpTool(String name, String description, JsonNode schema) {
    private static final Pattern NAME = Pattern.compile("ignition\\.[a-z0-9_.-]{1,116}");
    private static final Set<String> READS = Set.of(
        "ignition.projects.list", "ignition.projects.resource.list", "ignition.projects.resource.read",
        "ignition.projects.resource.export", "ignition.projects.search", "ignition.projects.references",
        "ignition.projects.bundle.inspect", IgnitionMcpClient.PREVIEW, IgnitionMcpClient.STATUS,
        "ignition.scripts.project.list", "ignition.scripts.project.read",
        "ignition.namedqueries.list", "ignition.namedqueries.read",
        "ignition.perspective.views.list", "ignition.perspective.views.read",
        "ignition.tags.browse", "ignition.tags.read", "ignition.tags.definition.read",
        "ignition.tags.opc.search", "ignition.tags.snapshot", "ignition.udts.list", "ignition.udts.read",
        "ignition.historian.query", "ignition.alarms.list",
        "ignition.alarms.pipelines.list", "ignition.alarms.pipelines.read",
        "ignition.gateway.status", "ignition.gateway.modules", "ignition.gateway.connections",
        "ignition.gateway.logs", "ignition.gateway.diagnose"
    );
    private static final Set<String> DRY_RUNS = Set.of(
        "ignition.projects.changes.write", "ignition.tags.write", "ignition.tags.definition.write",
        "ignition.scripts.project.write", "ignition.scripts.project.delete", "ignition.scripts.project.import",
        "ignition.namedqueries.execute", "ignition.namedqueries.write", "ignition.namedqueries.delete",
        "ignition.namedqueries.import", "ignition.alarms.acknowledge"
    );

    enum Mode { READ, DRY_RUN, STORED_PLAN, UNKNOWN }

    static boolean validName(String value) { return NAME.matcher(value).matches(); }

    Mode mode() {
        if (READS.contains(name)) {
            return Mode.READ;
        }
        if (IgnitionMcpClient.APPLY.equals(name)) {
            return Mode.STORED_PLAN;
        }
        if (DRY_RUNS.contains(name) && schema.path("properties").path("commit").path("type").asText().equals("boolean")) {
            return Mode.DRY_RUN;
        }
        return Mode.UNKNOWN;
    }

    ObjectNode initialArguments(String project) {
        ObjectNode args = JSON.createObjectNode();
        for (String field : new String[]{"project", "targetProject"}) {
            if (schema.path("properties").has(field) && project != null && !project.isBlank()) {
                args.put(field, project);
            }
        }
        return args;
    }

    ObjectNode writeArguments(ObjectNode input, boolean commit) throws IOException {
        if (mode() != Mode.DRY_RUN && mode() != Mode.STORED_PLAN) {
            throw new IOException("This tool has no supported commit contract.");
        }
        ObjectNode args = input.deepCopy().put("commit", commit);
        if ("ignition.tags.definition.write".equals(name)) {
            setDefinitionCommit(args, commit, 0);
        }
        return args;
    }

    private static void setDefinitionCommit(ObjectNode args, boolean commit, int depth) throws IOException {
        // The Gateway allows per-definition overrides within a batch.
        if (depth > 8) {
            throw new IOException("The definition batch is nested too deeply.");
        }
        args.put("commit", commit);
        if (args.has("definitions")) {
            if (!args.path("definitions").isArray() || args.path("definitions").isEmpty()) {
                throw new IOException("Definitions must be a nonempty JSON array.");
            }
            for (JsonNode entry : args.path("definitions")) {
                if (!(entry instanceof ObjectNode definition)) {
                    throw new IOException("Each definition in the batch must be a JSON object.");
                }
                setDefinitionCommit(definition, commit, depth + 1);
            }
        }
    }

    boolean isDryRun(ObjectNode input, JsonNode result) {
        if ("ignition.tags.definition.write".equals(name) && input.path("definitions").isArray()) {
            return isDefinitionDryRun(input, result);
        }
        return result.path("dryRun").asBoolean(false);
    }

    private static boolean isDefinitionDryRun(JsonNode input, JsonNode result) {
        JsonNode definitions = input.path("definitions");
        if (!definitions.isArray()) {
            return result.path("dryRun").asBoolean(false);
        }
        JsonNode rows = result.path("results");
        if (!rows.isArray() || rows.size() != definitions.size()) {
            return false;
        }
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).path("isError").asBoolean(true)
                || !isDefinitionDryRun(definitions.get(i), rows.get(i).path("structuredContent"))) {
                return false;
            }
        }
        return true;
    }

    @Override public String toString() { return name; }
}
