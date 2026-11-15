package com.jg.ignition.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static com.jg.ignition.assistant.AssistantJson.JSON;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "DESIGNER_ASSISTANT_QA_CONFIG", matches = ".+")
class GatewayAssistantTest {
    @Test
    void reviewAndAutoUseChosenFoldersOnTheLocalGateway() throws Exception {
        JsonNode settings = JSON.readTree(Path.of(System.getenv("DESIGNER_ASSISTANT_QA_CONFIG")).toFile());
        try (var gateway = new GatewayProbe(settings)) {
            var agent = new AssistantAgent(gateway);
            var model = new ModelProvider.Settings(ModelProvider.Kind.OPENAI_COMPATIBLE,
                "http://127.0.0.1:8103/v1/chat/completions", "designer-qa", "", "", "", "");
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            String motor = "ReviewMotor_" + suffix;
            String pump = "AutoPump_" + suffix;
            String review = "[default]_types_/Packaging/Line 1/" + motor;
            String auto = "[default]_types_/Utilities/Cooling/" + pump;

            var inspection = agent.run(model, prompt("What is in the current project?"), AssistantAgent.Mode.REVIEW, ignored -> {});
            assertTrue(inspection.reply().contains(gateway.name()));
            assertTrue(inspection.reply().contains("Packaging"));
            assertTrue(inspection.reply().contains("Scripts"));
            assertTrue(inspection.reply().contains("Perspective"));

            var proposal = agent.run(model, prompt("Create " + motor + " UDT in Packaging/Line 1"),
                AssistantAgent.Mode.REVIEW, ignored -> {});
            assertNotNull(proposal.pending(), proposal.reply());
            assertEquals(review, proposal.pending().path());
            assertFalse(gateway.exists(review), "Review wrote before approval");
            assertEquals(review, agent.apply(proposal.pending()).draft().path());
            assertTrue(gateway.exists(review));

            var applied = agent.run(model, prompt("Create " + pump + " UDT in Utilities/Cooling"),
                AssistantAgent.Mode.AUTO, ignored -> {});
            assertNull(applied.pending());
            assertNotNull(applied.applied(), applied.reply());
            assertEquals(auto, applied.applied().draft().path());
            assertTrue(gateway.exists(auto));
            System.out.println("Live approval verified " + review + "; live auto-apply verified " + auto);
        }
    }

    private static List<ModelProvider.Message> prompt(String text) {
        return List.of(new ModelProvider.Message("user", text));
    }

    // Uses the existing signed Gateway module as an independent local acceptance-test transport.
    // The installed Designer Assistant uses NativeAgentProject and never reads this file or token.
    private static final class GatewayProbe implements AgentProject, AutoCloseable {
        private final URI endpoint;
        private final String token;
        private final String project;
        private String session;
        private int sequence;

        GatewayProbe(JsonNode configuration) throws Exception {
            URI gateway = URI.create(configuration.path("gateway").asText());
            assertEquals("127.0.0.1", gateway.getHost());
            assertEquals("http", gateway.getScheme());
            project = configuration.path("project").asText();
            assertTrue(project.startsWith("DesignerAssistantQA"));
            token = configuration.path("token").asText();
            endpoint = gateway.resolve("/data/ignition-mcp/mcp");
            ObjectNode init = JSON.createObjectNode().put("protocolVersion", "2025-06-18");
            init.putObject("capabilities");
            init.putObject("clientInfo").put("name", "Designer Assistant QA").put("version", "1");
            rpc("initialize", init, false);
            rpc("notifications/initialized", JSON.createObjectNode(), true);
        }

        @Override public String name() { return project; }
        @Override public String defaultProvider() { return "default"; }

        @Override public JsonNode inspect(String action, ObjectNode args) throws IOException, InterruptedException {
            return switch (action) {
                case "project_overview" -> JSON.createObjectNode().put("project", project).put("ignitionVersion", "8.3.9");
                case "list_project_resources" -> read("ignition.projects.resource.list", JSON.createObjectNode()
                    .put("project", project).put("resourceType", "scripts".equals(args.path("kind").asText()) ? "script-python" : "views"));
                case "browse_tags" -> fromBrowse("ignition.tags.browse", JSON.createObjectNode().put("path", "[default]Assistant"));
                case "browse_udts" -> {
                    String folder = args.path("folder").asText();
                    if (folder.isBlank()) {
                        var out = JSON.createObjectNode();
                        var items = out.putArray("items");
                        for (String root : List.of("Packaging", "Utilities")) {
                            var lookup = JSON.createObjectNode();
                            lookup.putArray("paths").add("[default]_types_/" + root);
                            read("ignition.tags.definition.read", lookup);
                            items.addObject().put("name", root);
                        }
                        yield out;
                    }
                    yield fromBrowse("ignition.tags.browse", JSON.createObjectNode().put("path", "[default]_types_/" + folder));
                }
                default -> throw new IOException("Unsupported QA inspection.");
            };
        }

        private ObjectNode fromBrowse(String name, ObjectNode args) throws IOException, InterruptedException {
            JsonNode result = read(name, args);
            return JSON.createObjectNode().set("items", result.path("nodes"));
        }

        @Override public void checkDestination(UdtDraft draft) throws IOException, InterruptedException {
            var lookup = JSON.createObjectNode();
            lookup.putArray("paths").add(draft.parentPath());
            if (!read("ignition.tags.definition.read", lookup).toString().contains("Folder")) {
                throw new IOException("QA folder unavailable.");
            }
            if (exists(draft.path())) {
                throw new IOException("Already exists.");
            }
        }

        @Override public ObjectNode createUdt(UdtDraft draft) throws IOException, InterruptedException {
            var args = JSON.createObjectNode().put("path", draft.path()).put("operation", "create")
                .put("tagObjectType", "UdtType").put("collisionPolicy", "Abort").put("commit", true);
            var children = args.putArray("children");
            draft.members().forEach(member -> children.addObject().put("name", member.name()).put("tagObjectType", "AtomicTag")
                .putObject("properties").put("dataType", member.dataType()).put("valueSource", "memory"));
            read("ignition.tags.definition.write", args);
            JsonNode actual = read("ignition.udts.read", JSON.createObjectNode().put("path", draft.path()));
            for (var member : draft.members()) {
                assertTrue(actual.toString().contains(member.name()) && actual.toString().contains(member.dataType()), actual::toPrettyString);
            }
            return draft.receipt();
        }

        boolean exists(String path) throws IOException, InterruptedException {
            return !tool("ignition.udts.read", JSON.createObjectNode().put("path", path)).path("isError").asBoolean();
        }

        private JsonNode read(String name, ObjectNode args) throws IOException, InterruptedException {
            var result = tool(name, args);
            if (result.path("isError").asBoolean()) {
                throw new IOException(result.path("content").path(0).path("text").asText("Gateway call failed."));
            }
            return result.path("structuredContent");
        }

        private JsonNode tool(String name, ObjectNode args) throws IOException, InterruptedException {
            return rpc("tools/call", JSON.createObjectNode().put("name", name).set("arguments", args), false);
        }

        private JsonNode rpc(String name, ObjectNode args, boolean notification) throws IOException, InterruptedException {
            var request = JSON.createObjectNode().put("jsonrpc", "2.0").put("method", name);
            if (!notification) {
                request.put("id", ++sequence);
            }
            request.set("params", args);
            var builder = HttpJson.post(endpoint, request, 15).header("X-Ignition-API-Token", token)
                .header("MCP-Protocol-Version", "2025-06-18").header("Accept", "application/json, text/event-stream");
            if (session != null) {
                builder.header("Mcp-Session-Id", session);
            }
            var response = HttpJson.send(builder.build(), 15);
            if (response.status() < 200 || response.status() >= 300) {
                throw new IOException("Test Gateway returned HTTP " + response.status());
            }
            session = response.headers().firstValue("Mcp-Session-Id").orElse(session);
            if (notification) {
                return null;
            }
            var data = response.json();
            if (data.has("error")) {
                throw new IOException(data.path("error").path("message").asText());
            }
            return data.path("result");
        }

        @Override public void close() throws Exception {
            if (session != null) {
                HttpJson.send(HttpRequest.newBuilder(endpoint).DELETE()
                    .header("X-Ignition-API-Token", token).header("Mcp-Session-Id", session)
                    .header("MCP-Protocol-Version", "2025-06-18").build(), 5);
            }
        }
    }
}
