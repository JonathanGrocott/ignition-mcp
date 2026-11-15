package com.jg.ignition.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.KeyStroke;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
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
        AssistantPanel dock = edt(() -> new AssistantPanel(project, settings));
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
            edt(() -> { dock.close(); return null; });
            model.stop(0);
        }
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

    private static void click(AssistantPanel dock, String title) throws Exception {
        edt(() -> {
            buttons(dock, title).filter(JButton::isEnabled).findFirst().orElseThrow().doClick();
            return null;
        });
    }

    private static Stream<JButton> buttons(AssistantPanel dock, String title) {
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
