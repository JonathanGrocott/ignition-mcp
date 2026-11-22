package com.jg.ignition.assistant;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jg.ignition.mcp.bridge.DesignerMcpRpc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static com.jg.ignition.assistant.AssistantJson.JSON;

final class ProjectTools {
    static final String EXPORT = "ignition.projects.resource.export";
    static final String PREVIEW = "ignition.projects.changes.preview";
    static final String APPLY = "ignition.projects.changes.apply";
    static final String STATUS = "ignition.projects.changes.status";
    static final String DEFINITIONS = "ignition.tags.definition.write";
    private static final int MAX_IMPORT_BYTES = 2_097_152;
    private static final int MAX_JSON_ATTACHMENT_BYTES = 4_194_304;
    static final String VERSION = loadVersion();
    private final DesignerMcpRpc rpc;
    private volatile boolean pairConfirmed;

    ProjectTools(DesignerMcpRpc rpc) {
        this.rpc = rpc;
    }

    /**
     * Confirms the Gateway runs the Ignition MCP module from the same release as this Designer Assistant.
     * A matching result is remembered; a failure is checked again on the next call.
     */
    void checkModulePair() throws IOException {
        if (pairConfirmed) {
            return;
        }
        String gatewayVersion;
        try {
            gatewayVersion = rpc.moduleVersion();
        } catch (Exception e) {
            throw new IOException("Designer Assistant " + VERSION + " needs Ignition MCP " + VERSION
                + " on this Gateway. The Ignition MCP module is missing, not running, or older than " + VERSION
                + ". Install both modules from the same release.", e);
        }
        if (!VERSION.equals(gatewayVersion)) {
            throw new IOException("Designer Assistant " + VERSION + " needs Ignition MCP " + VERSION
                + ", but this Gateway has Ignition MCP " + gatewayVersion + ". Install both modules from the same release.");
        }
        pairConfirmed = true;
    }

    Map<String, Tool> catalog() throws IOException, InterruptedException {
        checkModulePair();
        try {
            var catalog = new LinkedHashMap<String, Tool>();
            for (JsonNode entry : JSON.readTree(rpc.tools())) {
                String name = entry.path("name").asText();
                catalog.put(name, new Tool(name, entry.path("description").asText(), entry.path("inputSchema"),
                    entry.path("mutating").asBoolean()));
            }
            return catalog;
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Gateway project tools are unavailable. Check that the Gateway module is current and that this Designer user has an authorization profile.", e);
        }
    }

    Result call(String name, ObjectNode arguments) throws IOException, InterruptedException {
        try {
            JsonNode response = JSON.readTree(rpc.call(name, arguments.toString()));
            if (response.has("error")) {
                var error = response.path("error");
                return new Result(error.path("message").asText("The Gateway rejected this action."), error.path("data"), true);
            }
            var result = response.path("result");
            return new Result(result.path("content").path(0).path("text").asText("The Gateway returned no result."),
                result.path("structuredContent"), result.path("isError").asBoolean(true));
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("The Gateway could not confirm the action. Check the current state before retrying.", e);
        }
    }

    private static String loadVersion() {
        try (InputStream stream = ProjectTools.class.getResourceAsStream("/designer-assistant-version.properties")) {
            var properties = new java.util.Properties();
            if (stream != null) {
                properties.load(stream);
            }
            return properties.getProperty("moduleVersion", "unknown");
        } catch (IOException e) {
            return "unknown";
        }
    }

    static String instructions(Map<String, Tool> catalog) {
        if (catalog.isEmpty()) {
            return "\nThis Designer session has no additional Gateway actions available.";
        }
        var description = new StringBuilder("""

            Gateway actions available to this signed-in user (name, purpose, argument schema):
            """);
        catalog.values().forEach(tool -> description.append(tool.name()).append(" | ").append(tool.description())
            .append(" | ").append(tool.schema()).append('\n'));
        return description.toString();
    }

    record Tool(String name, String description, JsonNode schema, boolean mutating) {
        ObjectNode changeArguments(ObjectNode input, boolean commit) throws IOException {
            if (!mutating || !"boolean".equals(schema.path("properties").path("commit").path("type").asText())) {
                throw new IOException("The Gateway has no supported approval contract for this change.");
            }
            ObjectNode args = input.deepCopy().put("commit", commit);
            if (DEFINITIONS.equals(name)) {
                setDefinitionCommit(args, commit, 0);
            }
            return args;
        }

        boolean confirmsDryRun(ObjectNode arguments, JsonNode outcome) {
            return DEFINITIONS.equals(name) ? definitionDryRun(arguments, outcome) : outcome.path("dryRun").asBoolean();
        }

        private static void setDefinitionCommit(ObjectNode args, boolean commit, int depth) throws IOException {
            if (depth > 8) {
                throw new IOException("The tag definition batch is nested too deeply.");
            }
            args.put("commit", commit);
            if (args.has("definitions")) {
                var definitions = args.path("definitions");
                if (!definitions.isArray() || definitions.isEmpty()) {
                    throw new IOException("Provide at least one tag definition.");
                }
                for (JsonNode entry : definitions) {
                    if (!(entry instanceof ObjectNode definition)) {
                        throw new IOException("Each tag definition needs a path and configuration.");
                    }
                    setDefinitionCommit(definition, commit, depth + 1);
                }
            }
        }

        private static boolean definitionDryRun(JsonNode input, JsonNode outcome) {
            JsonNode definitions = input.path("definitions");
            if (!definitions.isArray()) {
                return outcome.path("dryRun").asBoolean();
            }
            JsonNode rows = outcome.path("results");
            if (!rows.isArray() || rows.size() != definitions.size()) {
                return false;
            }
            for (int i = 0; i < rows.size(); i++) {
                var row = rows.get(i);
                if (row.path("isError").asBoolean(true)
                        || !definitionDryRun(definitions.get(i), row.path("structuredContent"))) {
                    return false;
                }
            }
            return true;
        }
    }

    record Result(String message, JsonNode data, boolean error) {
        JsonNode forModel() {
            return JSON.createObjectNode().put("message", message).put("error", error).set("details", data);
        }
    }

    record Attachment(String name, JsonNode json, String zipBase64) {
        static Attachment read(Path file) throws IOException {
            String name = file.getFileName().toString();
            String extension = name.toLowerCase(Locale.ROOT);
            boolean zip = extension.endsWith(".zip");
            if (!zip && !extension.endsWith(".json")) {
                throw new IOException("Select a JSON file or an Ignition project export ZIP.");
            }
            int limit = zip ? MAX_IMPORT_BYTES : MAX_JSON_ATTACHMENT_BYTES;
            try (InputStream stream = Files.newInputStream(file)) {
                byte[] bytes = stream.readNBytes(limit + 1);
                if (bytes.length > limit) {
                    throw new IOException(zip ? "An Ignition ZIP attachment can be up to 2 MiB."
                        : "A JSON attachment can be up to 4 MiB.");
                }
                if (zip) {
                    return new Attachment(name, null, Base64.getEncoder().encodeToString(bytes));
                }
                try {
                    JsonNode contents = JSON.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
                    if (contents == null || contents.isMissingNode()) {
                        throw new IOException("The attached JSON file is empty.");
                    }
                    return new Attachment(name, contents, null);
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new IOException("The attachment is not valid JSON.", e);
                }
            }
        }

        ObjectNode inspect() throws IOException {
            if (json == null) {
                throw new IOException("Use the Ignition project ZIP inspector to read the attached archive.");
            }
            return JSON.createObjectNode().put("name", name).set("contents", json);
        }

        void insert(Tool tool, ObjectNode arguments) throws IOException {
            JsonNode properties = tool.schema().path("properties");
            if (json != null && json.isObject() && properties.has("bundle")) {
                if (JSON.writeValueAsBytes(json).length > MAX_IMPORT_BYTES) {
                    throw new IOException("The JSON can be inspected here. A project import bundle can be up to 2 MiB.");
                }
                arguments.remove("zipBase64");
                arguments.set("bundle", json);
            } else if (zipBase64 != null && properties.has("zipBase64")) {
                arguments.remove("bundle");
                arguments.put("zipBase64", zipBase64);
            } else {
                throw new IOException("This action cannot use the attached file type.");
            }
        }
    }

    record Export(String filename, JsonNode bundle) {}
}
