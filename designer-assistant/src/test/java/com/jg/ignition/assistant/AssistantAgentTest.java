package com.jg.ignition.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.KeyStroke;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;
import java.util.stream.Stream;

import static com.jg.ignition.assistant.AssistantJson.JSON;
import static org.junit.jupiter.api.Assertions.*;

class AssistantAgentTest {
    @Test
    void implicitReferenceUsesTheEditorAndSelectionAtSendTime() throws Exception {
        String viewPath = "Packaging/LineOverview";
        var selection = JSON.createObjectNode();
        var editor = selection.putObject("activeResource").put("kind", "perspective")
            .put("path", viewPath).put("resourceType", "views");
        selection.putObject("perspectiveSelection").put("kind", "components").put("count", 1)
            .putArray("components").addObject().put("name", "Speed").put("designerPath", "C.0");
        selection.putArray("projectBrowserSelection").addObject().put("path", "Utilities/OtherView");

        var readArguments = new CopyOnWriteArrayList<JsonNode>();
        var project = new TestProject() {
            @Override public ObjectNode designerContext() {
                assertTrue(SwingUtilities.isEventDispatchThread());
                return selection;
            }

            @Override public String ignitionVersion() { return "8.3.10"; }

            @Override public JsonNode inspect(String action, ObjectNode args) {
                readArguments.add(args.deepCopy());
                return JSON.createObjectNode().put("purpose", "Displays line speed");
            }
        };
        var snapshots = new CopyOnWriteArrayList<JsonNode>();
        var prompts = new CopyOnWriteArrayList<String>();
        var model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/", exchange -> {
            try (exchange) {
                var messages = JSON.readTree(exchange.getRequestBody()).path("messages");
                String instructions = messages.get(0).path("content").asText();
                prompts.add(instructions);
                String marker = "\nDesigner context for the current user message (JSON data): ";
                var snapshot = JSON.readTree(instructions.substring(instructions.indexOf(marker) + marker.length()));
                snapshots.add(snapshot);
                ObjectNode reply = JSON.createObjectNode();
                if (messages.get(messages.size() - 1).path("content").asText().contains("Displays line speed")) {
                    reply.put("reply", "The selected component displays line speed.");
                } else {
                    var active = snapshot.path("activeResource");
                    reply.putObject("action").put("name", "read_project_resource").putObject("arguments")
                        .put("kind", active.path("kind").asText()).put("path", active.path("path").asText())
                        .put("resourceType", active.path("resourceType").asText());
                }
                ObjectNode response = JSON.createObjectNode();
                response.putArray("choices").addObject().putObject("message").put("content", reply.toString());
                byte[] body = JSON.writeValueAsBytes(response);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        model.start();
        var settings = new AssistantSettings(new ModelProvider.Settings(ModelProvider.Kind.OPENAI_COMPATIBLE,
            "http://127.0.0.1:" + model.getAddress().getPort(), "qa", "", "", "", ""));
        var dock = edt(() -> new AssistantPanel(project, settings));
        try {
            edt(() -> {
                assertEquals("Perspective  ·  Packaging/LineOverview  ·  Speed", AssistantDock.contextLabel(selection));
                var input = composer(dock);
                input.setText("What does this component do?");
                useKey(input, "ENTER");
                editor.put("path", "Packaging/SwitchedView");
                return null;
            });
            waitFor(() -> hasText(dock, "The selected component displays line speed."));
            assertEquals(1, readArguments.size());
            assertEquals(viewPath, readArguments.get(0).path("path").asText());
            assertEquals("views", readArguments.get(0).path("resourceType").asText());
            assertEquals(2, snapshots.size());
            for (String prompt : prompts) {
                assertTrue(prompt.contains("\nIgnition Gateway version: 8.3.10\n"), prompt);
                assertFalse(prompt.contains("8.3.9"), "The prompt must not assume a fixed 8.3 patch.");
            }
            for (var snapshot : snapshots) {
                assertEquals(viewPath, snapshot.at("/activeResource/path").asText());
                assertEquals("Speed", snapshot.at("/perspectiveSelection/components/0/name").asText());
                assertEquals("Utilities/OtherView", snapshot.at("/projectBrowserSelection/0/path").asText());
            }
        } finally {
            edt(() -> { dock.close(); return null; });
            model.stop(0);
        }
    }

    @Test
    void agentUsesInspectionResultsAndOnlyWritesWhenApprovedOrInAutoMode() throws Exception {
        var project = new TestProject();
        var model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/", exchange -> {
            try (exchange) {
                var messages = JSON.readTree(exchange.getRequestBody()).path("messages");
                var latest = messages.get(messages.size() - 1).path("content").asText();
                var request = latest.startsWith("Designer tool result")
                    ? messages.get(messages.size() - 3).path("content").asText() : latest;
                ObjectNode reply = JSON.createObjectNode();
                if (latest.equals("Inspect the scripts")) {
                    reply.putObject("action").put("name", "list_project_resources").putObject("arguments").put("kind", "scripts");
                } else if (latest.contains("scripts") && latest.contains("Utilities/Runtime")) {
                    reply.put("reply", "The project has the script Utilities/Runtime.");
                } else if (latest.startsWith("Designer tool result")) {
                    var definition = reply.putObject("action").put("name", "create_udt").putObject("arguments");
                    definition.put("name", request.equals("Create Pump") ? "Pump" : "Motor")
                        .put("folder", request.equals("Create Pump") ? "Utilities/Cooling" : "Packaging/Line 1")
                        .putArray("members").addObject().put("name", "Faulted").put("dataType", "Boolean");
                } else {
                    reply.putObject("action").put("name", "browse_udts").putObject("arguments")
                        .put("folder", request.equals("Create Pump") ? "Utilities/Cooling" : "Packaging/Line 1");
                }
                ObjectNode response = JSON.createObjectNode();
                response.putArray("choices").addObject().putObject("message").put("content", reply.toString());
                byte[] body = JSON.writeValueAsBytes(response);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        model.start();
        var settings = new AssistantSettings(new ModelProvider.Settings(ModelProvider.Kind.OPENAI_COMPATIBLE,
            "http://127.0.0.1:" + model.getAddress().getPort(), "qa", "", "", "", ""));
        var workspace = edt(() -> new AssistantDock(project, settings, null));
        var tabs = edt(() -> components(workspace).filter(JTabbedPane.class::isInstance).map(JTabbedPane.class::cast)
            .findFirst().orElseThrow());
        var dock = edt(() -> (AssistantPanel) tabs.getSelectedComponent());
        try {
            edt(() -> {
                JTextArea input = composer(dock);
                input.setText("First line");
                input.setCaretPosition(input.getDocument().getLength());
                useKey(input, "shift ENTER");
                assertEquals("First line\n", input.getText());
                assertTrue(project.reads.isEmpty());
                input.setText("Inspect the scripts");
                useKey(input, "ENTER");
                return null;
            });
            waitFor(() -> hasText(dock, "The project has the script Utilities/Runtime."));
            assertEquals(List.of("list_project_resources"), project.reads);

            send(dock, "Create Pump");
            waitFor(() -> enabled(dock, "Approve creation"));
            assertTrue(hasText(dock, "[working]_types_/Utilities/Cooling/Pump"));
            assertTrue(hasText(dock, "Faulted   Boolean · Memory"));
            assertFalse(hasText(dock, "create_udt"));
            assertTrue(project.writes.isEmpty());
            edt(() -> { composer(dock).setText("Draft in Chat 1"); return null; });
            click(workspace, "New chat");
            var otherChat = edt(() -> (AssistantPanel) tabs.getSelectedComponent());
            assertNotSame(dock, otherChat);
            assertEquals(2, edt(tabs::getTabCount));
            assertEquals("", edt(() -> composer(otherChat).getText()));
            assertFalse(hasText(otherChat, "Utilities/Cooling/Pump"));
            assertFalse(enabled(otherChat, "Approve creation"));
            edt(() -> {
                composer(otherChat).setText("Inspect the scripts");
                buttons(otherChat, "Send").findFirst().orElseThrow().doClick();
                tabs.setSelectedComponent(dock);
                return null;
            });
            waitFor(() -> hasText(otherChat, "The project has the script Utilities/Runtime."));
            assertEquals("Draft in Chat 1", edt(() -> composer(dock).getText()));
            assertTrue(enabled(dock, "Approve creation"));
            assertEquals("Inspect the scripts", edt(() -> tabs.getTitleAt(1)));
            click(edt(() -> (Container) tabs.getTabComponentAt(1)), "Close Inspect the scripts");
            assertEquals(1, edt(tabs::getTabCount));
            assertSame(dock, edt(tabs::getSelectedComponent));
            assertEquals("Inspect the scripts", edt(() -> tabs.getTitleAt(0)));
            click(dock, "Dismiss");
            assertTrue(project.writes.isEmpty());

            send(dock, "Create Pump");
            waitFor(() -> enabled(dock, "Approve creation"));
            click(dock, "Approve creation");
            waitFor(() -> hasText(dock, "Created and verified [working]_types_/Utilities/Cooling/Pump"));
            assertEquals(List.of("[working]_types_/Utilities/Cooling/Pump"), project.writes);

            edt(() -> {
                components(dock).filter(JComboBox.class::isInstance).map(JComboBox.class::cast)
                    .findFirst().orElseThrow().setSelectedItem(AssistantAgent.Mode.AUTO);
                return null;
            });
            send(dock, "Create Motor");
            waitFor(() -> hasText(dock, "Created and verified [working]_types_/Packaging/Line 1/Motor"));
            assertFalse(enabled(dock, "Approve creation"));
            assertEquals(List.of("[working]_types_/Utilities/Cooling/Pump", "[working]_types_/Packaging/Line 1/Motor"),
                project.writes);
            assertTrue(project.reads.contains("browse_udts"));
        } finally {
            edt(() -> { workspace.close(); return null; });
            model.stop(0);
        }
    }

    @Test
    void dividerCanGiveTheComposerMoreHeight() throws Exception {
        edt(() -> {
            var dock = new AssistantPanel(new TestProject(), new AssistantSettings(new ModelProvider.Settings(
                ModelProvider.Kind.CODEX_CLI, "", "", "", "", "", "")));
            var split = components(dock).filter(JSplitPane.class::isInstance).map(JSplitPane.class::cast)
                .findFirst().orElseThrow();
            split.setSize(240, 400);
            split.doLayout();
            int originalHeight = split.getBottomComponent().getHeight();
            split.setDividerLocation(split.getTopComponent().getHeight() - 60);
            split.doLayout();
            assertEquals(originalHeight + 60, split.getBottomComponent().getHeight());
            assertTrue(split.getTopComponent().getHeight() >= split.getTopComponent().getMinimumSize().height);

            var graphics = new BufferedImage(240, 400, BufferedImage.TYPE_INT_RGB).createGraphics();
            try {
                split.paint(graphics);
            } finally {
                graphics.dispose();
            }
            split.setSize(240, 500);
            split.doLayout();
            assertEquals(originalHeight + 60, split.getBottomComponent().getHeight());
            dock.close();
            return null;
        });
    }

    @Test
    void transcriptGivesWrappedTextAndActionsRoomAtNarrowWidths() throws Exception {
        edt(() -> {
            var transcript = new JPanel(new TranscriptLayout());
            var message = new JTextArea("Review the proposed change to the memory tags and the existing configuration before applying.");
            message.setLineWrap(true);
            message.setWrapStyleWord(true);
            transcript.add(message);
            var actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            for (String label : List.of("Approve", "Dismiss", "Details")) {
                var action = new JButton(label);
                action.setPreferredSize(new Dimension(80, 24));
                actions.add(action);
            }
            transcript.add(actions);

            transcript.setSize(340, 360);
            transcript.doLayout();
            int wideTextHeight = message.getHeight();
            assertEquals(24, actions.getHeight());

            transcript.setSize(190, 360);
            transcript.doLayout();
            actions.doLayout();
            assertTrue(message.getHeight() > wideTextHeight);
            assertEquals(48, actions.getHeight());
            assertEquals(24, actions.getComponent(2).getY());
            return null;
        });
    }

    @Test
    void draftRequiresAnExplicitValidLocationAndUniqueSupportedMembers() throws Exception {
        ObjectNode args = (ObjectNode) JSON.readTree("""
            {"name":"Drive","folder":"Packaging/Line 1","members":[{"name":"Running","dataType":"Boolean"}]}
            """);
        assertEquals("[actual]_types_/Packaging/Line 1/Drive", UdtDraft.parse(args, "actual").path());
        assertThrows(IOException.class, () -> UdtDraft.parse(args.deepCopy().put("folder", "../Control"), "actual"));
        assertThrows(IOException.class, () -> UdtDraft.parse(args.deepCopy().put("provider", "System"), "actual"));
        assertThrows(IOException.class, () -> UdtDraft.parse(args.deepCopy().put("folder", 2), "actual"));
        assertThrows(IOException.class, () -> UdtDraft.parse(args.deepCopy().put("folder", "Utilities/"), "actual"));
        args.withArray("members").addObject().put("name", "running").put("dataType", "Boolean");
        assertThrows(IOException.class, () -> UdtDraft.parse(args, "actual"));
        assertThrows(IOException.class, () -> AssistantAgent.parse("""
            {"action":{"name":"ignition.tags.write","arguments":{}}}
            """));
    }

    private static class TestProject implements AgentProject {
        final List<String> writes = new CopyOnWriteArrayList<>();
        final List<String> reads = new CopyOnWriteArrayList<>();
        @Override public String name() { return "OpenProject"; }
        @Override public String defaultProvider() { return "working"; }
        @Override public JsonNode inspect(String action, ObjectNode args) {
            reads.add(action);
            return JSON.createObjectNode().put("kind", args.path("kind").asText())
                .put("resource", "Utilities/Runtime");
        }
        @Override public void checkDestination(UdtDraft draft) throws IOException {
            if (writes.contains(draft.path())) {
                throw new IOException("Already exists.");
            }
        }
        @Override public ObjectNode createUdt(UdtDraft draft) {
            writes.add(draft.path());
            return draft.receipt();
        }
    }

    private static void send(AssistantPanel dock, String input) throws Exception {
        edt(() -> {
            composer(dock).setText(input);
            return null;
        });
        click(dock, "Send");
    }

    private static JTextArea composer(AssistantPanel dock) {
        return components(dock).filter(JTextArea.class::isInstance).map(JTextArea.class::cast)
            .filter(area -> "Message for Designer Assistant".equals(area.getAccessibleContext().getAccessibleName()))
            .findFirst().orElseThrow();
    }

    private static void useKey(JTextArea input, String key) {
        Object binding = input.getInputMap().get(KeyStroke.getKeyStroke(key));
        input.getActionMap().get(binding).actionPerformed(new ActionEvent(input, ActionEvent.ACTION_PERFORMED, key));
    }

    private static boolean hasText(AssistantPanel dock, String value) throws Exception {
        return edt(() -> components(dock).filter(JTextArea.class::isInstance).map(JTextArea.class::cast)
            .anyMatch(area -> area.getText().contains(value)));
    }

    private static boolean enabled(AssistantPanel dock, String title) throws Exception {
        return edt(() -> buttons(dock, title).anyMatch(JButton::isEnabled));
    }

    private static void click(Container dock, String title) throws Exception {
        edt(() -> {
            buttons(dock, title).filter(JButton::isEnabled).findFirst().orElseThrow().doClick();
            return null;
        });
    }

    private static Stream<JButton> buttons(Container dock, String title) {
        return components(dock).filter(JButton.class::isInstance).map(JButton.class::cast)
            .filter(button -> button.isVisible() && title.equals(button.getAccessibleContext().getAccessibleName()));
    }

    private static Stream<Component> components(Component root) {
        var entries = root instanceof Container container
            ? Stream.of(container.getComponents()).flatMap(AssistantAgentTest::components) : Stream.<Component>empty();
        return Stream.concat(Stream.of(root), entries);
    }

    private static <T> T edt(Callable<T> task) throws Exception {
        var future = new FutureTask<>(task);
        SwingUtilities.invokeAndWait(future);
        return future.get();
    }

    private static void waitFor(Callable<Boolean> condition) throws Exception {
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < end) {
            if (condition.call()) {
                return;
            }
            Thread.sleep(40);
        }
        fail("The Designer dock did not reach the expected state.");
    }
}
