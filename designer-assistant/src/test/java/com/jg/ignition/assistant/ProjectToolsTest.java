package com.jg.ignition.assistant;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jg.ignition.mcp.bridge.DesignerMcpRpc;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import static com.jg.ignition.assistant.AssistantJson.JSON;
import static org.junit.jupiter.api.Assertions.*;

class ProjectToolsTest {
    private static final String SCRIPT_TOOL = "ignition.scripts.project.write";
    private static final String CODE = "def value():\n    return 42\n";

    @Test
    void agentUsesGatewayCatalogAndAppliesTheReviewedScriptOnlyAfterApproval() throws Exception {
        var calls = new ArrayList<ObjectNode>();
        DesignerMcpRpc rpc = new DesignerMcpRpc() {
            public String moduleVersion() { return ProjectTools.VERSION; }

            public String tools() {
                return """
                    [{"name":"ignition.scripts.project.write","description":"Write script","mutating":true,
                    "inputSchema":{"properties":{"commit":{"type":"boolean"}}}}]
                    """;
            }

            public String call(String tool, String raw) throws Exception {
                assertEquals(SCRIPT_TOOL, tool);
                var arguments = (ObjectNode) JSON.readTree(raw);
                calls.add(arguments);
                boolean commit = arguments.get("commit").booleanValue();
                return JSON.createObjectNode().set("result", JSON.createObjectNode().put("isError", false)
                    .set("structuredContent", JSON.createObjectNode().put("project", "QA")
                        .put("path", "ignition/script-python/Fixture/Example").put("dryRun", !commit))).toString();
            }
        };
        AgentProject project = new AgentProject() {
            public String name() { return "QA"; }
            public String defaultProvider() { return "default"; }
            public ObjectNode inspect(String name, ObjectNode args) { throw new AssertionError(name); }
            public void checkDestination(UdtDraft draft) { throw new AssertionError(draft); }
            public ObjectNode createUdt(UdtDraft draft) { throw new AssertionError(draft); }
        };
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", request -> {
            try (request) {
                var messages = JSON.readTree(request.getRequestBody()).path("messages");
                assertTrue(messages.get(0).path("content").asText().contains(SCRIPT_TOOL));
                var response = JSON.createObjectNode();
                response.putObject("action").put("name", SCRIPT_TOOL).putObject("arguments")
                    .put("operation", "create").put("scriptType", "timer").put("path", "Fixture/Example").put("code", CODE);
                byte[] body = JSON.writeValueAsBytes(JSON.createObjectNode().set("choices", JSON.createArrayNode()
                    .add(JSON.createObjectNode().set("message", JSON.createObjectNode().put("content", response.toString())))));
                request.sendResponseHeaders(200, body.length);
                request.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            var settings = new ModelProvider.Settings(ModelProvider.Kind.OPENAI_COMPATIBLE,
                "http://127.0.0.1:" + server.getAddress().getPort(), "fixture", "", "", "", "");
            var agent = new AssistantAgent(project, new ProjectTools(rpc));
            var input = List.of(new ModelProvider.Message("user", "Create a Project Library script in Fixture/Example"));
            var turn = agent.run(settings, input, AssistantAgent.Mode.REVIEW, null, null, JSON.createObjectNode(), ignored -> {});
            assertNotNull(turn.pending(), turn.reply());
            assertEquals("Create project script", turn.pending().title());
            assertEquals(CODE, turn.pending().sources().get(0).text());
            assertEquals(1, calls.size());
            assertFalse(calls.get(0).get("commit").booleanValue());

            assertTrue(turn.pending().apply().call().projectChanged());
            assertEquals(calls.get(0).deepCopy().put("commit", true), calls.get(1));

            assertNotNull(agent.run(settings, input, AssistantAgent.Mode.AUTO, null, null, JSON.createObjectNode(), ignored -> {}).applied());
            assertEquals(List.of(false, true, false, true), calls.stream().map(c -> c.get("commit").booleanValue()).toList());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void bothModulesComeFromTheSameRelease() throws Exception {
        assertNotEquals("unknown", ProjectTools.VERSION);
        assertTrue(ProjectTools.VERSION.matches("\\d+\\.\\d+\\.\\d+"), ProjectTools.VERSION);

        var matching = new ProjectTools(rpc(() -> ProjectTools.VERSION));
        matching.checkModulePair();
        assertTrue(matching.catalog().isEmpty());

        var older = new ProjectTools(rpc(() -> "0.5.1"));
        var mismatch = assertThrows(java.io.IOException.class, older::catalog);
        assertTrue(mismatch.getMessage().contains("this Gateway has Ignition MCP 0.5.1"), mismatch.getMessage());

        // A Gateway module from before 0.6.0 has no moduleVersion method, so the RPC call fails.
        var missing = new ProjectTools(rpc(() -> { throw new IllegalStateException("No such RPC method"); }));
        var absent = assertThrows(java.io.IOException.class, missing::checkModulePair);
        assertTrue(absent.getMessage().contains("missing, not running, or older than " + ProjectTools.VERSION),
            absent.getMessage());
    }

    private interface VersionSource { String get() throws Exception; }

    private static DesignerMcpRpc rpc(VersionSource version) {
        return new DesignerMcpRpc() {
            public String moduleVersion() throws Exception { return version.get(); }
            public String tools() { return "[]"; }
            public String call(String tool, String arguments) { throw new AssertionError(tool); }
        };
    }

    @Test
    void nestedDefinitionsMustAllConfirmDryRun() throws Exception {
        var schema = JSON.readTree("{\"properties\":{\"commit\":{\"type\":\"boolean\"}}}");
        var tool = new ProjectTools.Tool(ProjectTools.DEFINITIONS, "Save definitions", schema, true);
        var args = (ObjectNode) JSON.readTree("""
            {"definitions":[{"commit":true,"definitions":[{"commit":true,"path":"[default]Test"}]}]}
            """);
        var preview = tool.changeArguments(args, false);
        assertTrue(args.at("/definitions/0/definitions/0/commit").booleanValue());
        assertFalse(preview.at("/definitions/0/definitions/0/commit").booleanValue());
        assertFalse(tool.confirmsDryRun(preview, JSON.readTree("""
            {"results":[{"isError":false,"structuredContent":{"results":[
                {"isError":false,"structuredContent":{"dryRun":false}}]}}]}
            """)));
    }
}
