package com.jg.ignition.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.stream.Stream;

import static com.jg.ignition.assistant.AssistantJson.JSON;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "DESIGNER_ASSISTANT_QA_CONFIG", matches = ".+")
class DesignerGatewayTest {
    @Test
    void dockPreviewsAppliesAndReadsBackOnDisposable839Gateway() throws Exception {
        JsonNode config = JSON.readTree(Path.of(System.getenv("DESIGNER_ASSISTANT_QA_CONFIG")).toFile());
        String project = config.path("project").asText();
        URI gateway = URI.create(config.path("gateway").asText());
        assertEquals("http", gateway.getScheme());
        assertEquals("127.0.0.1", gateway.getHost());
        assertTrue(project.startsWith("DesignerAssistantQA"));

        String token = config.path("token").asText();
        String mcpPath = "/data/ignition-mcp/mcp";
        var client = new IgnitionMcpClient(gateway.resolve(mcpPath), token);
        List<McpTool> tools = client.catalog();
        assertTrue(tools.size() >= 42, "Expected the Ignition MCP 0.5.0 catalog");
        assertTrue(tools.stream().noneMatch(tool -> tool.mode() == McpTool.Mode.UNKNOWN),
            () -> "Unclassified: " + tools.stream().filter(tool -> tool.mode() == McpTool.Mode.UNKNOWN).toList());

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String script = "Assistant/Library_" + suffix;
        String query = "Assistant/Query_" + suffix;
        String udt = "[default]_types_/Assistant/Motor_" + suffix;
        String view = "Assistant/View_" + suffix;
        var scriptArgs = JSON.createObjectNode().put("targetProject", project);
        var scriptResource = scriptArgs.putObject("bundle").put("format", "ignition-mcp.project-resource-bundle.v1")
            .putArray("resources").addObject().put("moduleId", "ignition").put("resourceType", "script-python")
            .put("path", script).put("operation", "create").put("applicationScope", "A").put("version", 1)
            .put("restricted", false).put("overridable", true);
        scriptResource.putObject("attributes");
        scriptResource.putObject("data").putObject("code.py").put("encoding", "utf-8")
            .put("value", "def answer():\n    return 42\n");

        var queryArgs = JSON.createObjectNode().put("project", project).put("path", query)
            .put("operation", "create").put("type", "ScalarQuery").put("query", "SELECT 42")
            .put("database", "QA_Synthetic");
        var udtArgs = JSON.createObjectNode();
        udtArgs.putArray("definitions").addObject().put("operation", "create").put("path", udt)
            .put("tagObjectType", "UdtType").put("commit", true)
            .putArray("children").addObject().put("name", "Running").put("tagObjectType", "AtomicTag")
            .putObject("properties").put("dataType", "Boolean").put("valueSource", "memory");

        HttpServer model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                var messages = JSON.readTree(exchange.getRequestBody()).path("messages");
                assertTrue(messages.get(0).path("content").asText().contains("ignition.tags.definition.write"));
                String latest = messages.get(messages.size() - 1).path("content").asText();
                ObjectNode response = JSON.createObjectNode().put("reply", "Fixture proposal");
                switch (latest) {
                    case "Create script" -> propose(response, IgnitionMcpClient.PREVIEW, scriptArgs);
                    case "Create query" -> propose(response, "ignition.namedqueries.write", queryArgs);
                    case "Create UDT" -> propose(response, "ignition.tags.definition.write", udtArgs);
                    case "Inspect project" -> propose(response, "ignition.projects.resource.list",
                        JSON.createObjectNode().put("project", project));
                    case "Create view" -> response.putObject("view").put("title", "Pump overview").put("subtitle", "")
                        .putArray("sections").addObject().put("heading", "Status").put("body", "Ready");
                    default -> throw new IllegalArgumentException("Unexpected fixture prompt: " + latest);
                }
                respond(exchange, response);
            }
        });
        model.start();

        var settings = new AssistantSettings(new ModelProvider.Settings(ModelProvider.Kind.OPENAI_COMPATIBLE,
            "http://127.0.0.1:" + model.getAddress().getPort() + "/v1/chat/completions",
            "designer-fixture", "", "", "", ""), mcpPath, token);
        AssistantPanel panel = edt(() -> new AssistantPanel(() -> project, gateway::toString, path -> false, settings));
        try {
            var scriptRead = JSON.createObjectNode().put("project", project).put("scriptType", "python").put("path", script);
            var queryRead = JSON.createObjectNode().put("project", project).put("path", query).put("includeQuery", true);
            var udtRead = JSON.createObjectNode().put("path", udt);

            runChange(panel, "Create script", () -> { assertTrue(client.invoke("ignition.scripts.project.read", scriptRead).error()); return null; });
            assertTrue(success(client, "ignition.scripts.project.read", scriptRead).toString().contains("return 42"));

            runChange(panel, "Create query", () -> { assertTrue(client.invoke("ignition.namedqueries.read", queryRead).error()); return null; });
            assertTrue(success(client, "ignition.namedqueries.read", queryRead).toString().contains("SELECT 42"));

            runChange(panel, "Create UDT", () -> { assertTrue(client.invoke("ignition.udts.read", udtRead).error()); return null; });
            assertTrue(success(client, "ignition.udts.read", udtRead).toString().contains("Running"));

            send(panel, "Inspect project", "MCP tools");
            click(panel, "Run read");
            waitFor(() -> enabled(panel, "Use result in chat"));
            click(panel, "Use result in chat");
            waitFor(() -> edt(() -> tabs(panel).getSelectedIndex() == 0));
            click(panel, "New chat");

            send(panel, "Create view", "View preview");
            edt(() -> {
                components(panel).filter(JTextField.class::isInstance).map(JTextField.class::cast)
                    .filter(field -> field.getText().equals("Assistant/NewView")).findFirst().orElseThrow().setText(view);
                return null;
            });
            click(panel, "Preview view");
            waitFor(() -> enabled(panel, "Apply new view"));
            click(panel, "Apply new view");
            waitFor(() -> edt(() -> components(panel).filter(JLabel.class::isInstance).map(JLabel.class::cast)
                .anyMatch(label -> label.getText().startsWith("View created and verified."))));
            var viewRead = JSON.createObjectNode().put("project", project).put("path", view).put("includeProperties", true);
            assertTrue(success(client, "ignition.perspective.views.read", viewRead).toString().contains("Pump overview"));
            System.out.println("Verified dock workflow for " + script + ", " + query + ", " + udt + ", and " + view);
        } finally {
            edt(() -> { panel.close(); return null; });
            model.stop(0);
        }
    }

    private static void runChange(AssistantPanel panel, String prompt, Callable<Void> beforeApply) throws Exception {
        send(panel, prompt, "MCP tools");
        click(panel, "Preview call");
        waitFor(() -> enabled(panel, "Apply reviewed call"));
        beforeApply.call();
        click(panel, "Apply reviewed call");
        waitFor(() -> edt(() -> components(panel).filter(JLabel.class::isInstance).map(JLabel.class::cast)
            .anyMatch(label -> label.getText().startsWith("Gateway returned a receipt."))));
    }

    private static void send(AssistantPanel panel, String prompt, String expectedTab) throws Exception {
        edt(() -> {
            tabs(panel).setSelectedIndex(0);
            components(panel).filter(JTextArea.class::isInstance).map(JTextArea.class::cast)
                .filter(area -> "Message for Designer Assistant".equals(area.getAccessibleContext().getAccessibleName()))
                .findFirst().orElseThrow().setText(prompt);
            return null;
        });
        click(panel, "Send");
        waitFor(() -> edt(() -> tabs(panel).getTitleAt(tabs(panel).getSelectedIndex()).equals(expectedTab)
            && button(panel, "Send").isEnabled()));
    }

    private static boolean enabled(AssistantPanel panel, String label) throws Exception {
        return edt(() -> button(panel, label).isEnabled());
    }

    private static void click(AssistantPanel panel, String label) throws Exception {
        edt(() -> {
            JButton button = button(panel, label);
            assertTrue(button.isEnabled(), label);
            button.doClick();
            return null;
        });
    }

    private static JButton button(AssistantPanel panel, String label) {
        return components(panel).filter(JButton.class::isInstance).map(JButton.class::cast)
            .filter(button -> label.equals(button.getText())).findFirst().orElseThrow();
    }

    private static JTabbedPane tabs(AssistantPanel panel) {
        return components(panel).filter(JTabbedPane.class::isInstance).map(JTabbedPane.class::cast)
            .findFirst().orElseThrow();
    }

    private static Stream<Component> components(Component root) {
        Stream<Component> descendants = root instanceof Container container
            ? Stream.of(container.getComponents()).flatMap(DesignerGatewayTest::components) : Stream.empty();
        return Stream.concat(Stream.of(root), descendants);
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var future = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(future);
        return future.get();
    }

    private static void waitFor(Callable<Boolean> condition) throws Exception {
        long expiry = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < expiry) {
            if (condition.call()) {
                return;
            }
            Thread.sleep(80);
        }
        fail("Timed out waiting for the Designer dock.");
    }

    private static JsonNode success(IgnitionMcpClient client, String name, ObjectNode arguments) throws Exception {
        var reply = client.invoke(name, arguments);
        assertFalse(reply.error(), reply.message());
        return reply.data();
    }

    private static void propose(ObjectNode response, String name, ObjectNode arguments) {
        response.putObject("tool").put("name", name).set("arguments", arguments);
    }

    private static void respond(HttpExchange exchange, ObjectNode value) throws java.io.IOException {
        var response = JSON.createObjectNode();
        response.putArray("choices").addObject().putObject("message").put("content", value.toString());
        byte[] body = JSON.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
    }
}
